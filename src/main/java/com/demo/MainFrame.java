package com.demo;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class MainFrame extends JFrame {
    private static final Color PRIMARY_COLOR = new Color(42, 92, 170);
    private static final Color BACKGROUND_COLOR = new Color(245, 247, 250);
    private static final Color SUCCESS_COLOR = new Color(76, 175, 80);
    private static final Color ERROR_COLOR = new Color(211, 47, 47);
    private static final Color WARNING_COLOR = new Color(255, 152, 0);

    // UI组件
    private final JTextField dbHostField = createStyledTextField("localhost");
    private final JTextField dbPortField = createStyledTextField("1521");
    private final JTextField dbServiceField = createStyledTextField("ORCL");
    private final JTextField dbUserField = createStyledTextField("system");
    private final JPasswordField dbPasswordField = createStyledPasswordField("password");
    private final JCheckBox sysdbaCheckBox = new JCheckBox("SYSDBA模式");
    private final JComboBox<String> serviceTypeCombo = new JComboBox<>(new String[]{"SID", "服务名"});

    private final JTextField proxyPortField = createStyledTextField("1080");
    private final JLabel connectionStatusLabel = new JLabel("连接状态: 未连接");
    private final JLabel versionLabel = new JLabel("数据库版本: 未知");
    private final JLabel privilegeLabel = new JLabel("权限状态: 未验证");
    private final JLabel socksInfoLabel = new JLabel("代理操作: 等待指令");
    private final JTextArea logArea = new JTextArea();

    private final JButton loginButton = new JButton("验证连接");
    private final JButton startButton = new JButton("启动代理");
    private final JButton stopButton = new JButton("停止代理");

    private SocksInjector injector;
    private volatile boolean isConnected = false;

    public MainFrame() {
        super("Oracle SOCKS5 代理工具    云川攻防实验室 <)）请勿用于非法用途、内部测试使用、请勿用于生产环境 ");
        initializeFrame();
        buildMainPanel();
        bindEventHandlers();
        enhanceComponents();
    }

    private void initializeFrame() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);
        setResizable(true);
        getContentPane().setBackground(BACKGROUND_COLOR);
    }

    private void buildMainPanel() {
        JPanel mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBackground(BACKGROUND_COLOR);
        mainPanel.setBorder(new EmptyBorder(20, 20, 20, 20));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 10, 8, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        gbc.gridx = 0;
        gbc.gridy = 0;
        mainPanel.add(createDatabasePanel(), gbc);

        gbc.gridx = 1;
        mainPanel.add(createProxyPanel(), gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 2;
        mainPanel.add(createStatusPanel(), gbc);

        gbc.gridy = 2;
        mainPanel.add(createButtonPanel(), gbc);

        gbc.gridy = 3;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        mainPanel.add(createLogPanel(), gbc);

        add(mainPanel);
    }

    private JPanel createDatabasePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(BACKGROUND_COLOR);
        panel.setBorder(createTitledBorder("数据库配置"));

        GridBagConstraints gbc = createGbc();
        addFormField(panel, gbc, "主机地址:", dbHostField, 0);
        addFormField(panel, gbc, "端口:", dbPortField, 1);
        addFormField(panel, gbc, "服务名/SID:", dbServiceField, 2);
        addFormField(panel, gbc, "用户名:", dbUserField, 3);
        addFormField(panel, gbc, "密码:", dbPasswordField, 4);
        addFormField(panel, gbc, "", sysdbaCheckBox, 5);
        addFormField(panel, gbc, "连接类型:", serviceTypeCombo, 6);

        return panel;
    }

    private JPanel createProxyPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(BACKGROUND_COLOR);
        panel.setBorder(createTitledBorder("代理配置"));

        GridBagConstraints gbc = createGbc();
        gbc.gridwidth = 2;
        panel.add(new JLabel("代理端口:"), gbc);
        gbc.gridx = 2;
        panel.add(proxyPortField, gbc);

        return panel;
    }

    private JPanel createStatusPanel() {
        JPanel panel = new JPanel(new GridLayout(4, 1, 0, 8));
        panel.setBackground(BACKGROUND_COLOR);
        panel.setBorder(createTitledBorder("连接状态"));
        panel.add(connectionStatusLabel);
        panel.add(versionLabel);
        panel.add(privilegeLabel);
        panel.add(socksInfoLabel);
        return panel;
    }

    private JPanel createButtonPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 10));
        panel.setBackground(BACKGROUND_COLOR);
        styleButton(loginButton, PRIMARY_COLOR);
        styleButton(startButton, SUCCESS_COLOR);
        styleButton(stopButton, ERROR_COLOR);

        // 初始状态设置
        stopButton.setEnabled(true);  // 始终启用停止按钮
        startButton.setEnabled(false);
        proxyPortField.setEnabled(false);

        panel.add(loginButton);
        panel.add(startButton);
        panel.add(stopButton);
        return panel;
    }

    private JPanel createLogPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BACKGROUND_COLOR);
        panel.setBorder(createTitledBorder("操作日志"));

        logArea.setEditable(false);
        logArea.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        logArea.setBackground(new Color(250, 250, 250));
        logArea.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(230, 230, 230)),
                new EmptyBorder(10, 15, 10, 15)
        ));

        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    private void bindEventHandlers() {
        loginButton.addActionListener(e -> handleLogin());
        startButton.addActionListener(e -> handleStartProxy());
        stopButton.addActionListener(e -> handleStopProxy());
    }

    private void handleLogin() {
        loginButton.setEnabled(false);
        updateStatus("正在验证...", Color.BLACK);
        clearLog();

        new SwingWorker<Boolean, String>() {
            private String dbVersion;
            private boolean hasPrivilege;

            @Override
            protected Boolean doInBackground() {
                try {
                    validateConnectionInputs();
                    injector = new SocksInjector(
                            dbHostField.getText().trim(),
                            dbPortField.getText().trim(),
                            dbServiceField.getText().trim(),
                            dbUserField.getText().trim(),
                            new String(dbPasswordField.getPassword()),
                            sysdbaCheckBox.isSelected(),
                            serviceTypeCombo.getSelectedItem().equals("服务名")
                    );

                    dbVersion = injector.getOracleVersion();
                    hasPrivilege = injector.checkDBAPrivilege();
                    publish("连接验证成功");
                    return true;
                } catch (Exception ex) {
                    publish("错误: " + ex.getMessage());
                    return false;
                }
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) {
                    appendLog(msg);
                }
                versionLabel.setText("数据库版本: " + dbVersion);
                privilegeLabel.setText("权限状态: " + (hasPrivilege ? "DBA权限" : "无权限"));
            }

            @Override
            protected void done() {
                try {
                    if (get()) {
                        isConnected = true;
                        updateStatus("已连接", SUCCESS_COLOR);
                        startButton.setEnabled(true);
                        proxyPortField.setEnabled(true);
                        showMessage("数据库连接成功！", "成功", JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        updateStatus("连接失败", ERROR_COLOR);
                    }
                } catch (Exception ex) {
                    handleError("连接异常", ex);
                } finally {
                    updateButtonStates();
                }
            }
        }.execute();
    }

    private void handleStartProxy() {
        startButton.setEnabled(false);
        updateStatus("正在启动代理...", WARNING_COLOR);
        appendLog("正在初始化代理服务");

        new SwingWorker<Boolean, String>() {
            @Override
            protected Boolean doInBackground() {
                try {
                    int proxyPort = Integer.parseInt(proxyPortField.getText().trim());
                    injector.inject(proxyPort);
                    publish("代理端口 " + proxyPort + " 启动请求已发送");
                    return true;
                } catch (Exception ex) {
                    publish("启动失败: " + ex.getMessage());
                    return false;
                }
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) {
                    appendLog(msg);
                }
            }

            @Override
            protected void done() {
                try {
                    get(); // 仅检查异常
                    updateSocksInfo("启动请求已发送", SUCCESS_COLOR);
                } catch (Exception ex) {
                    handleError("启动异常", ex);
                } finally {
                    updateButtonStates();
                }
            }
        }.execute();
    }

    private void handleStopProxy() {
        appendLog("正在发送停止代理指令...");
        updateSocksInfo("停止请求已发送", WARNING_COLOR);

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                try {
                    if (injector != null) {
                        injector.stop();
                        appendLog("停止指令已执行");
                    } else {
                        appendLog("代理实例不存在");
                    }
                } catch (Exception ex) {
                    appendLog("停止时发生错误: " + ex.getMessage());
                }
                return null;
            }

            @Override
            protected void done() {
                updateButtonStates();
            }
        }.execute();
    }

    // UI样式方法
    private JTextField createStyledTextField(String defaultValue) {
        JTextField field = new JTextField(defaultValue, 20);
        field.setBorder(createInputBorder());
        field.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        return field;
    }

    private JPasswordField createStyledPasswordField(String defaultPassword) {
        JPasswordField field = new JPasswordField(defaultPassword, 20);
        field.setBorder(createInputBorder());
        field.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        return field;
    }

    private Border createInputBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(204, 204, 204)),
                new EmptyBorder(8, 12, 8, 12)
        );
    }

    private Border createTitledBorder(String title) {
        return BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(PRIMARY_COLOR),
                title,
                TitledBorder.LEADING,
                TitledBorder.DEFAULT_POSITION,
                new Font("微软雅黑", Font.BOLD, 16),
                PRIMARY_COLOR
        );
    }

    private GridBagConstraints createGbc() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 10, 8, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        return gbc;
    }

    private void addFormField(JPanel panel, GridBagConstraints gbc, String label, JComponent field, int row) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        panel.add(new JLabel(label), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(field, gbc);
    }

    private void styleButton(JButton button, Color bgColor) {
        button.setFont(new Font("微软雅黑", Font.BOLD, 14));
        button.setForeground(Color.WHITE);
        button.setBackground(bgColor);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(bgColor.darker()),
                new EmptyBorder(12, 25, 12, 25)
        ));
        button.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) {
                if (button.isEnabled()) {
                    button.setBackground(bgColor.brighter());
                }
            }
            public void mouseExited(MouseEvent e) {
                if (button.isEnabled()) {
                    button.setBackground(bgColor);
                }
            }
        });
    }

    private void enhanceComponents() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        Font chineseFont = new Font("微软雅黑", Font.PLAIN, 14);
        setComponentFont(chineseFont,
                dbHostField, dbPortField, dbServiceField, dbUserField, dbPasswordField,
                sysdbaCheckBox, serviceTypeCombo, proxyPortField, connectionStatusLabel,
                versionLabel, privilegeLabel, socksInfoLabel, logArea, loginButton,
                startButton, stopButton
        );
    }

    private void setComponentFont(Font font, Component... components) {
        for (Component comp : components) {
            if (comp != null) {
                comp.setFont(font);
            }
        }
    }

    private void updateButtonStates() {
        SwingUtilities.invokeLater(() -> {
            startButton.setEnabled(isConnected);
            stopButton.setEnabled(true); // 始终启用停止按钮
            proxyPortField.setEnabled(isConnected);
            loginButton.setEnabled(!isConnected);
        });
    }

    private void appendLog(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
        SwingUtilities.invokeLater(() -> {
            try {
                logArea.append(timestamp + " " + message + "\n");
                logArea.setCaretPosition(logArea.getDocument().getLength());
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void clearLog() {
        SwingUtilities.invokeLater(() -> logArea.setText(""));
    }

    private void validateConnectionInputs() throws IllegalArgumentException {
        if (dbHostField.getText().trim().isEmpty() ||
                dbPortField.getText().trim().isEmpty() ||
                dbServiceField.getText().trim().isEmpty() ||
                dbUserField.getText().trim().isEmpty()) {
            throw new IllegalArgumentException("所有数据库字段必须填写");
        }

        if (serviceTypeCombo.getSelectedItem().equals("SID") &&
                dbServiceField.getText().trim().length() > 8) {
            throw new IllegalArgumentException("SID最大长度为8个字符");
        }
    }

    private void showMessage(String message, String title, int messageType) {
        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(this, message, title, messageType)
        );
    }

    private void handleError(String title, Exception ex) {
        String message = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
        appendLog("错误: " + message);
        updateStatus(title, ERROR_COLOR);
        showMessage(message, "错误", JOptionPane.ERROR_MESSAGE);
    }

    private void updateStatus(String message, Color color) {
        SwingUtilities.invokeLater(() -> {
            connectionStatusLabel.setText("连接状态: " + message);
            connectionStatusLabel.setForeground(color);
        });
    }

    private void updateSocksInfo(String message, Color color) {
        SwingUtilities.invokeLater(() -> {
            socksInfoLabel.setText("代理操作: " + message);
            socksInfoLabel.setForeground(color);
        });
    }

    public static void main(String[] args) {
        System.setProperty("file.encoding", "UTF-8");

        EventQueue.invokeLater(() -> {
            try {
                UIManager.put("swing.boldMetal", Boolean.FALSE);
                new MainFrame().setVisible(true);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null,
                        "程序初始化失败: " + e.getMessage(),
                        "严重错误",
                        JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}