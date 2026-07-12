[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="28" height="21" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="28" height="21" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="28" height="21" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="28" height="21" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="28" height="21" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="28" height="21" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="28" height="21" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="28" height="21" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="28" height="21" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="28" height="21" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="28" height="21" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="28" height="21" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="28" height="21" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="28" height="21" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="28" height="21" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="28" height="21" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="28" height="21" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="28" height="21" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="28" height="21" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="28" height="21" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="28" height="21" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="28" height="21" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="28" height="21" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="28" height="21" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="28" height="21" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="28" height="21" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="28" height="21" title="English">](README-en.md)

这是JAVA版本的i2p软fork源代码。

最新版本：https://i2pplus.github.io/

## 安装

有关安装说明，请参见[INSTALL.md](INSTALL.md)或https://i2pplus.github.io/。

### Windows installer note

当使用 Java > 1.8 或其他发行版（如 AdoptOpenJDK 等）时，installer exe 可能会出现"Java not found"或"invalid/corrupt"错误。解决方法：从 exe 中解压 install.jar 并从命令行运行 `java -jar install.jar`。

## 文档

https://geti2p.net/how

常见问题解答：https://geti2p.net/faq

API：https://i2pplus.github.io/javadoc/
或运行“ ant javadoc”，然后从build/javadoc/index.html开始浏览

## 如何在I2P上做贡献/破解

请在docs目录中查看[HACKING.md](HACKING.md)和其他文档。

## 从源代码构建软件包

要从源代码管理中获取开发分支，请访问：https://github.com/I2PPlus/i2pplus/

### 准备

- Java SDK 1.8.0或更高版本
- Apache Ant 1.9.8或更高版本
-从GNU gettext包安装的xgettext，msgfmt和msgmerge工具
  http://www.gnu.org/software/gettext/
-构建环境必须使用UTF-8语言环境。
-构建Debian软件包需要：`dpkg-deb`和`fakeroot`软件包（通过您的软件包管理器）

### Ant构建过程

在x86系统上，请执行以下操作（将会使用IzPack4构建）：

    ant pkg

在非x86上，请使用以下选项之一：

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

如果要使用IzPack5进行构建，请从http://izpack.org/downloads/下载进行安装，然后执行以下操作：

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

不带任何参数运行“ ant”以查看其他构建选项。


构建 Linux 的 AppImage：
```bash
ant buildAppImage
```

详情请参阅 [tools/appimage/README.md](tools/appimage/README.md)。

构建 Debian/Ubuntu 的自包含 Debian 包，无需外部 Jetty/Tomcat 依赖：
```bash
ant buildDeb
```

这会创建一个包含捆绑 Jetty 和 Tomcat 库的自包含 `.deb` 包，无需系统依赖。


## 联系信息

需要帮忙？请参阅I2P IRC网络上的IRC频道#saltR

错误报告：https://github.com/I2PPlus/i2pplus/issues

## 许可证

I2P+已根据AGPL v.3许可。

有关各子组件许可证，请参阅：[README.md](docs/LICENSES.md)

## 相关链接

### 文档

- [docs/README.md](docs/README.md) - 完整文档索引
- [docs/INSTALL.md](docs/INSTALL.md) - 安装指南
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - 无GUI安装（控制台模式）
- [docs/HACKING.md](docs/HACKING.md) - 开发者指南和构建系统
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - 源代码树布局
- [docs/DEBUGGING.md](docs/DEBUGGING.md) - 使用JDWP和其他工具进行运行时调试
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - 使用nftables管理I2P会话禁令
- [docs/THEMING.md](docs/THEMING.md) - Console and webapp theming system
- [docs/LICENSES.md](docs/LICENSES.md) - 第三方许可证
- [docs/history.txt](docs/history.txt) - 完整更改日志

### 子项目

- [apps/README.md](apps/README.md) - 应用程序概述
- [apps/addressbook/README.md](apps/addressbook/README.md) - 地址簿应用
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - 桌面GUI应用
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - I2P控制API
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - I2PSnark BitTorrent客户端
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - I2P隧道应用
- [apps/imagegen/README.md](apps/imagegen/README.md) - 图像生成工具
- [apps/jetty/README.md](apps/jetty/README.md) - Jetty HTTP服务器
- [apps/jrobin/README.md](apps/jrobin/README.md) - JRobin监控库
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - 最小流库
- [apps/pack200/README.md](apps/pack200/README.md) - Pack200压缩
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - 代理脚本
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - 路由器控制台
- [apps/sam/README.md](apps/sam/README.md) - 简单匿名消息
- [apps/streaming/README.md](apps/streaming/README.md) - 流库
- [apps/susidns/README.md](apps/susidns/README.md) - DNS服务器
- [apps/susimail/README.md](apps/susimail/README.md) - I2P邮件客户端
- [apps/systray/README.md](apps/systray/README.md) - 系统托盘应用
- [core/README.md](core/README.md) - 核心库文档
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - 本地JNI加密库(GMP)

### 杂项

- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - 使用nftables管理I2P会话禁令
- [installer/resources/README.md](installer/resources/README.md) - 安装程序资源
- [tools/scripts/README.md](tools/scripts/README.md) - 开发和管理实用脚本
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - 验证和测试脚本
