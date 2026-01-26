# I2P+

[English](README.md) | [Русский](README-ru.md) | [中文](README-zh.md) | [བོད་ཡིག](README-bo.md) | [فارسی](README-fa.md)

这是JAVA版本的i2p软fork源代码。

最新版本：https://i2pplus.github.io/

## 安装

有关安装说明，请参见INSTALL.txt或https://i2pplus.github.io/。

## 文档

https://geti2p.net/how

常见问题解答：https://geti2p.net/faq

API：http://docs.i2p-projekt.de/javadoc/
或运行“ ant javadoc”，然后从build/javadoc/index.html开始浏览

## 如何在I2P上做贡献/破解

请在docs目录中查看[HACKING.md](docs/HACKING.md)和其他文档。

## 从源代码构建软件包

要从源代码管理中获取开发分支，请访问：https://gitlab.com/i2p.plus/I2P.Plus/

### 准备

-Java SDK（最好是Oracle / Sun或OpenJDK）1.7.0或更高版本
  -非Linux操作系统和JVM：请参阅https://trac.i2p2.de/wiki/java
  -某些嵌入式子系统（核心，路由器，mstreaming，流传输，i2ptunnel）
    只需要Java 1.6
-Apache Ant 1.7.0或更高版本
-从GNU gettext包安装的xgettext，msgfmt和msgmerge工具
  http://www.gnu.org/software/gettext/
-构建环境必须使用UTF-8语言环境。

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

## 联系信息

需要帮忙？请参阅I2P IRC网络上的IRC频道#saltR

错误报告：https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues或https://github.com/I2PPlus/i2pplus/issues

## 许可证

I2P+已根据AGPL v.3许可。

有关各子组件许可证，请参阅：LICENSE.txt
