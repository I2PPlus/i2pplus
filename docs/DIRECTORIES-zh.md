# 简短的目录说明

此列表应为任何新开发人员提供一个找到他们希望修改的代码的位置。

也适合记忆力不好的老开发人员😀

目录| 描述
-------  |  ---------
`apps` | 该目录包含i2p附带的应用程序和客户端。
`apps/addressbook` | 一些用于地址簿的headless代码。
`apps/apparmor` | AppArmor的规则集。
`apps/BOB` | BOB服务的代码。
`apps/desktopgui` | 新系统托盘应用程序
`apps/i2psnark` |  i2psnark的代码，Webconsole中的torrent客户端组件。
`apps/i2ptunnel` | Web控制台中的“隐身服务管理器”及其GUI的代码。
`apps/imagegen` | 图像生成器Webapp
`apps/jetty` | Jetty Web服务器代码。
`apps/jrobin` | 控制台的图形包
`apps/ministreaming` | 流媒体（类似TCP的套接字）接口。
`apps/routerconsole` | 路由器控制台代码。
`apps/routerconsole/java/src/net/i2p/router/news` | 新闻提要子系统
`apps/routerconsole/java/src/net/i2p/router/update` | 自动更新子系统
`apps/routerconsole/java/src/net/i2p/router/web` 控制台的Java代码，包括插件支持
`apps/routerconsole/jsp` | 控制台的jsp
`apps/sam` | SAM服务。
`apps/streaming` | 流媒体传输（类似于TCP的套接字）实现。
`apps/susidns` | Web控制台中地址簿组件的代码。
`apps/susimail` | Web控制台中邮件客户端组件的代码。
`apps/systray` | 旧系统托盘应用程序（现已删除）和一些相关实用程序
`installer` | 此目录包含安装程序的代码。
`installer/lib/izpack` | 安装程序库
`installer/lib/jbigi` | jbigi 和 jcpuid DLL
`installer/lib/launch4j` | Windows jar-to-exe 二进制文件
`installer/lib/wrapper` | Wrapper 二进制文件和库
`installer/resources` | 用于i2p打包的静态文件。
`core/java` | 路由器和应用程序使用的通用核心代码。
`core/java/src/net/i2p/app` | 应用界面代码。
`core/java/src/net/i2p/client` | 低级客户端I2CP接口（I2PClient，I2PSession等）。
`core/java/src/net/i2p/client/impl` | 客户端I2CP实现
`core/java/src/net/i2p/client/naming` | 地址簿接口和基本实现
`core/java/src/net/i2p/crypto` | 该目录包含大多数加密算法代码。
`core/java/src/net/i2p/data` | 通用数据结构和与数据相关的实用程序
`core/java/src/net/i2p/internal` | 内部无socket的I2CP连接
`core/java/src/net/i2p/kademlia` | 路由器和i2psnark使用的基本Kademlia实现
`core/java/src/net/i2p/socks` | SOCKS客户端实现。
`core/java/src/net/i2p/stat` | 统计子系统。
`core/java/src/net/i2p/time` | 内部时间表示
`core/java/src/net/i2p/update` | 统计代码的一部分
`core/java/src/net/i2p/util` | 实用程序代码，例如Log，FileUtil，EepGet，HexDump等。
`router/java` | 该目录包含I2P路由器代码。
`router/java/src/net/i2p/data/i2np` | i2np I2NP（I2P的内部协议）代码。
`router/java/src/net/i2p/data/router` | 路由器数据结构，如RouterInfo
`router/java/src/net/i2p/router/client` | 路由器端I2CP实现
`router/java/src/net/i2p/router/crypto` | 核心代码库中不需要的I2P加密算法
`router/java/src/net/i2p/router/dummy` | 一些用于测试的子系统的虚拟实现
`router/java/src/net/i2p/router/message` | 大蒜路由消息创建和解析
`router/java/src/net/i2p/router/networkdb/kademlia` | DHT（kademlia）代码。
`router/java/src/net/i2p/router/networkdb/reseed` | 补种代码。
`router/java/src/net/i2p/router/peermanager` | 对等端配置文件跟踪和存储
`router/java/src/net/i2p/router/startup` | 与启动顺序有关的代码。
`router/java/src/net/i2p/router/transport` | 传输实现代码 (NTCP, SSU).
`router/java/src/net/i2p/router/transport/crypto` | 传输加密算法 (DH)
`router/java/src/net/i2p/router/tasks` | 小型路由器Helper，定期运行
`router/java/src/net/i2p/router/tunnel` | 隧道实现代码。
`router/java/src/net/i2p/router/util` | 路由器实用程序