[![CodeQL](https://github.com/vituperative/i2pplus/actions/workflows/codeql-analysis.yml/badge.svg)](https://github.com/vituperative/i2pplus/actions/workflows/codeql-analysis.yml)
[![Java CI](https://github.com/vituperative/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/vituperative/i2pplus/actions/workflows/ant.yml)
[![I2P+ Update zip](https://i2pplus.github.io/download.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](https://i2pplus.github.io/i2psnarkdownload.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](https://i2pplus.github.io/javadocsdownload.svg)](https://i2pplus.github.io/javadoc.zip)

# I2P+

[English](README.md) | [Русский](README-ru.md) | [中文](README-zh.md) | [हिन्दी](README-hi.md) | [བོད་ཡིག](README-bo.md) | [فارسی](README-fa.md)

これはI2PのJava実装のソフトフォークのソースコードです。

最新リリース：https://i2pplus.github.io/

## インストール

INSTALL.txtを参照するか、https://i2pplus.github.io/でインストール手順を確認してください。

## ドキュメント

https://geti2p.net/how

FAQ：https://geti2p.net/faq

API：http://docs.i2p-projekt.de/javadoc/
または 'ant javadoc' を実行し、build/javadoc/index.htmlから開始してください。

## 貢献方法 / I2Pの改造

[HACKING.md](docs/HACKING.md)とdocsディレクトリの他のドキュメントをご確認ください。

## ソースコードからパッケージをビルドする方法

ソースコントロールから開発ブランチを取得するには：https://gitlab.com/i2p.plus/I2P.Plus/

### 必要条件

- Java SDK（可能な限りOracle/SunまたはOpenJDK）1.8.0以上
  - Linux以外のオペレーティングシステムとJVM：https://trac.i2p2.de/wiki/javaを参照してください
  - 埋め込み用の特定のサブシステム（core、router、mstreaming、streaming、i2ptunnel）にはJava 1.6のみが必要です。
- Apache Ant 1.9.8以上
- GNU gettextパッケージからインストールされたxgettext、msgfmt、およびmsgmergeツール
  http://www.gnu.org/software/gettext/
- ビルド環境はUTF-8ロケールを使用する必要があります。

### Antビルドプロセス

x86システムでは、次のコマンドを実行します（これによりIzPack4を使用してビルドされます）：

    ant pkg

x86以外の場合は、次のいずれかを使用してください：

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

IzPack5でビルドする場合は、http://izpack.org/downloads/ からダウンロードし、
インストールしてから次のコマンドを実行してください：

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

既存のインストールに対して署名されていない更新をビルドするには、次のコマンドを実行します：

    ant updater

他のビルドオプションを確認するには、引数なしで 'ant' を実行してください。

### Docker
I2PをDockerで実行する方法の詳細については、[Docker.md](Docker.md)を参照してください。
## 連絡先情報

ヘルプが必要ですか？I2P IRCネットワークのIRCチャンネル＃saltRをご覧ください。

バグ報告：https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues または https://github.com/I2PPlus/i2pplus/issues

## ライセンス

I2P+はAGPL v.3の下でライセンスされています。

さまざまなサブコンポーネントのライセンスについては、LICENSE.txtを参照してください。