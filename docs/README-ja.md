[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="28" height="21" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="28" height="21" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="28" height="21" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="28" height="21" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="28" height="21" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="28" height="21" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="28" height="21" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="28" height="21" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="28" height="21" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="28" height="21" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="28" height="21" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="28" height="21" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="28" height="21" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="28" height="21" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="28" height="21" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="28" height="21" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="28" height="21" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="28" height="21" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="28" height="21" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="28" height="21" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="28" height="21" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="28" height="21" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="28" height="21" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="28" height="21" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="28" height="21" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="28" height="21" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="28" height="21" title="English">](README-en.md)

これはI2PのJava実装のソフトフォークのソースコードです。

最新リリース：https://i2pplus.github.io/

## インストール

[INSTALL.md](INSTALL.md)を参照するか、https://i2pplus.github.io/でインストール手順を確認してください。

### Windows インストーラーに関する注意

Java > 1.8 または代替ディストリビューション（AdoptOpenJDKなど）を使用すると、インストーラーのexeが "Java not found" または "invalid/corrupt" エラーで失敗する可能性があります。回避方法：exe から install.jar を展開し、コマンドラインから `java -jar install.jar` を実行します。

## ドキュメント

https://geti2p.net/how

FAQ：https://geti2p.net/faq

API：https://i2pplus.github.io/javadoc/
または 'ant javadoc' を実行し、build/javadoc/index.htmlから開始してください。

## 貢献方法 / I2Pの改造

[HACKING.md](HACKING.md)とdocsディレクトリの他のドキュメントをご確認ください。

## ソースコードからパッケージをビルドする方法

ソースコントロールから開発ブランチを取得するには：https://github.com/I2PPlus/i2pplus/

### 必要条件

- Java SDK（可能な限りOracle/SunまたはOpenJDK）1.8.0以上
- Apache Ant 1.9.8以上
- GNU gettextパッケージからインストールされたxgettext、msgfmt、およびmsgmergeツール
  http://www.gnu.org/software/gettext/
- ビルド環境はUTF-8ロケールを使用する必要があります。
- Debianパッケージビルド用: `dpkg-deb`と`fakeroot`パッケージ（パッケージマネージャーで）

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

## See also

### ドキュメント

- [docs/README.md](docs/README.md) - 完全的ドキュメント索引
- [docs/INSTALL.md](docs/INSTALL.md) - インストールガイド
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - ヘッドレス（コンソールモード）インストール
- [docs/HACKING.md](docs/HACKING.md) - 開発者ガイドとビルドシステム
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - ソースツリー構造
- [docs/DEBUGGING.md](docs/DEBUGGING.md) - JDWPおよびその他のツールによるランタイムデバッグ
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - nftablesによるI2Pセッション禁止の管理
- [docs/LICENSES.md](docs/LICENSES.md) - サードパーティライセンス
- [docs/history.txt](docs/history.txt) - 完全な変更履歴

### サブプロジェクト

- [apps/README.md](apps/README.md) - アプリケーション概要
- [apps/addressbook/README.md](apps/addressbook/README.md) - アドレス帳アプリケーション
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - デスクトップGUIアプリケーション
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - I2PコントロールAPI
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - I2PSnark BitTorrentクライアント
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - I2Pトンネルアプリケーション
- [apps/imagegen/README.md](apps/imagegen/README.md) - 画像生成ツール
- [apps/jetty/README.md](apps/jetty/README.md) - Jetty HTTPサーバー
- [apps/jrobin/README.md](apps/jrobin/README.md) - JRobin監視ライブラリ
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - 最小ストリーミングライブラリ
- [apps/pack200/README.md](apps/pack200/README.md) - Pack200圧縮
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - プロキシスクリプト
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - ルーターコンソール
- [apps/sam/README.md](apps/sam/README.md) - シンプル匿名メッセージング
- [apps/streaming/README.md](apps/streaming/README.md) - ストリーミングライブラリ
- [apps/susidns/README.md](apps/susidns/README.md) - DNSサーバー
- [apps/susimail/README.md](apps/susimail/README.md) - I2Pメールクライアント
- [apps/systray/README.md](apps/systray/README.md) - システムトレイアプリケーション
- [core/README.md](core/README.md) - コアライブラリのドキュメント
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - 暗号化用ネイティブJNIライブラリ(GMP)

### その他

- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - nftablesによるI2Pセッション禁止の管理
- [installer/resources/README.md](installer/resources/README.md) - バンドルされたインストーラーリソース
- [tools/scripts/README.md](tools/scripts/README.md) - 開発・管理用ユーティリティスクリプト
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - 検証・テストスクリプト



Linux 用 AppImage をビルドする場合：
```bash
ant buildAppImage
```

詳細は [tools/appimage/README.md](tools/appimage/README.md) を参照してください。


Debian/Ubuntu 用の自己完結型 Debian パッケージをビルドする場合（外部 Jetty/Tomcat 依存関係なし）：
```bash
ant buildDeb
```

これは、バンドルされた Jetty と Tomcat ライブラリを含む自己完結型の `.deb` パッケージを生成し、外部依存関係は不要です。


- [docker/README.md](docker/README.md) - DockerでのI2P+の実行





## 連絡先情報
ヘルプが必要ですか？I2P IRCネットワークのIRCチャンネル＃saltRをご覧ください。

## 連絡先情報


## 連絡先情報
バグ報告：https://github.com/I2PPlus/i2pplus/issues

## 連絡先情報


## 連絡先情報


## 連絡先情報
## ライセンス
I2P+はAGPL v.3の下でライセンスされています。

## 連絡先情報
## ライセンス


## 連絡先情報
## ライセンス
さまざまなサブコンポーネントのライセンスについては、[README.md](docs/LICENSES.md)を参照してください。

## 連絡先情報
## ライセンス
