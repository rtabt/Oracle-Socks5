# Oracle-Socks5

# 一、环境搭建

快速搭建环境，直接使用阿里云进行搭建，购买云服务器ECS

![image-20250307134625386](https://github.com/rtabt/Oracle-Socks5/blob/main/image/image-20250307134625386.png)

搜索oracle，寻找使用人数较多并且免费的镜像

![image-20250307134713134](https://github.com/rtabt/Oracle-Socks5/blob/main/image/image-20250307134713134.png)

随后分配ipv4公网地址，配置账号密码，安装镜像即可，配置云安全组开放策略，如果需要支付镜像费用，点击支付即可，显示0元

登录服务器，配置数据库，执行su - oracle切换oracle用户

![image-20250307135433818](https://github.com/rtabt/Oracle-Socks5/blob/main/image/image-20250307135433818.png)

执行sqlplus / as sysdba 进入数据库，因为我们这里使用dba权限进行相关操作，所以修改sys用户的密码

ALTER USER SYS IDENTIFIED BY Xxx123456;

![image-20250307140448419](https://github.com/rtabt/Oracle-Socks5/blob/main/image/image-20250307140448419.png)

尝试使用数据库管理软件如Navicat等使用SYSDBA权限登录即可，登录成功后就可以尝试使用工具开启Socks5进行测试

# 二、工具使用

![image-20250307201508912](https://github.com/rtabt/Oracle-Socks5/blob/main/image/image-20250307201508912.png)

点击启动代理

![image-20250307201700681](https://github.com/rtabt/Oracle-Socks5/blob/main/image/image-20250307201700681.png)

测试代理是否可用

![image-20250307201935928](https://github.com/rtabt/Oracle-Socks5/blob/main/image/image-20250307201935928.png)

# 三、 经过测试的版本

Oracle  11g 、12c、19c

# 四、适用场景

在苛刻条件下，内网中遇到Oracle数据库拥有数据库管理员权限，命令执行遭到拦截，但是这台服务器又通向其他网段，直接使用此工具可以基于Oracle数据库启动一个socks5代理，通过代理继续横向其他网段

# 五、免责声明

该工具只授权于企业内部进行问题排查，请勿用于非法用途，请遵守网络安全法，否则后果作者概不负责
由于传播、利用此工具所提供的信息而造成的任何直接或者间接的后果及损失，均由工具使用者本人负责，作者不为此承担任何责任。
