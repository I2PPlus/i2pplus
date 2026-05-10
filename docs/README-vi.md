[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="28" height="21" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="28" height="21" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="28" height="21" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="28" height="21" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="28" height="21" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="28" height="21" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="28" height="21" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="28" height="21" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="28" height="21" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="28" height="21" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="28" height="21" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="28" height="21" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="28" height="21" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="28" height="21" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="28" height="21" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="28" height="21" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="28" height="21" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="28" height="21" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="28" height="21" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="28" height="21" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="28" height="21" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="28" height="21" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="28" height="21" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="28" height="21" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="28" height="21" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="28" height="21" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="28" height="21" title="English">](README-en.md)

Đây là mã nguồn cho bản fork mềm (soft-fork) của phiên bản Java implementation của I2P.

Phiên bản mới nhất: https://i2pplus.github.io/

## Cài đặt

Xem [INSTALL.md](docs/INSTALL.md) hoặc https://i2pplus.github.io/ để biết hướng dẫn cài đặt.

### Lưu ý về trình cài đặt Windows

Với Java > 1.8 hoặc các bản phân phối thay thế (AdoptOpenJDK, v.v.), tệp exe của trình cài đặt có thể thất bại với lỗi "Java not found" hoặc "invalid/corrupt". Giải pháp thay thế: giải nén install.jar từ exe và chạy `java -jar install.jar` từ dòng lệnh.

## Tài liệu

https://geti2p.net/how

Câu hỏi thường gặp: https://geti2p.net/faq

API: https://i2pplus.github.io/javadoc/
hoặc chạy 'ant javadoc' rồi bắt đầu tại build/javadoc/index.html

## Cách đóng góp / Hack trên I2P

Vui lòng xem [HACKING.md](docs/HACKING.md) và các tài liệu khác trong thư mục docs.

## Xây dựng gói cài đặt từ mã nguồn

Để lấy nhánh phát triển từ source control: https://github.com/I2PPlus/i2pplus/

### Yêu cầu trước khi cài đặt

- Java SDK (ưu tiên Oracle/Sun hoặc OpenJDK) 1.8.0 hoặc cao hơn
  - Một số subsystem cho embedded (core, router, mstreaming, streaming, i2ptunnel)
- Apache Ant 1.9.8 hoặc cao hơn
- Cài đặt các công cụ xgettext, msgfmt, và msgmerge từ GNU gettext package
  http://www.gnu.org/software/gettext/
- Môi trường build phải sử dụng UTF-8 locale.
- Cho việc đóng gói Debian: các gói `dpkg-deb` và `fakeroot` (qua trình quản lý gói của bạn)

### Quá trình build Ant

Trên hệ x86, chạy lệnh sau (sẽ build sử dụng IzPack4):

    ant pkg

Trên hệ không phải x86, sử dụng một trong các lệnh sau:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

Nếu bạn muốn build với IzPack5, tải về từ: http://izpack.org/downloads/ rồi cài đặt, sau đó chạy các lệnh sau:

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

Để build một bản cập nhật chưa được ký cho cài đặt hiện tại, chạy:

    ant updater

Nếu bạn gặp vấn đề khi build full installer (Java14 trở lên có thể gây ra lỗi build cho izpack liên quan đến pack200),
bạn có thể build file nén cài đặt đầy đủ có thể giải nén và chạy tại chỗ:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

Chạy 'ant' không có đối số để xem các tùy chọn build khác.

Để tạo gói Debian độc lập cho Debian/Ubuntu mà không có phụ thuộc bên ngoài vào Jetty/Tomcat:
```bash
ant buildDeb
```

Điều này tạo ra một gói `.deb` độc lập bao gồm các thư viện Jetty và Tomcat được đóng gói mà không có phụ thuộc bên ngoài.


Để xây dựng AppImage cho Linux:
```bash
ant buildAppImage
```

Xem [tools/appimage/README.md](tools/appimage/README.md) để biết chi tiết.


Để biết thêm thông tin về cách chạy I2P trong Docker, xem [docker/README.md](docker/README.md)


## Thông tin liên hệ

Cần giúp đỡ? Truy cập kênh IRC #saltR trên mạng I2P IRC

Báo lỗi: https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues hoặc https://github.com/I2PPlus/i2pplus/issues

## Giấy phép

I2P+ được cấp phép theo AGPL v.3.

Để xem giấy phép của các thành phần phụ, xem: [README.md](docs/LICENSES.md)

## Xem thêm

### Tài liệu

- [docs/README.md](docs/README.md) - Chỉ mục tài liệu đầy đủ
- [docs/INSTALL.md](docs/INSTALL.md) - Hướng dẫn cài đặt
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - Cài đặt headless (console mode)
- [docs/HACKING.md](docs/HACKING.md) - Hướng dẫn phát triển và hệ thống build
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - Bố trí cây mã nguồn và nơi tìm thứ
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Quản lý lệnh cấm session I2P với nftables
- [docs/LICENSES.md](docs/LICENSES.md) - Giấy phép third-party
- [docs/history.txt](docs/history.txt) - Lịch sử thay đổi đầy đủ

### Sub-projects

- [apps/README.md](apps/README.md) - Tổng quan ứng dụng
- [apps/addressbook/README.md](apps/addressbook/README.md) - Ứng dụng addressbook
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - Ứng dụng Desktop GUI
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - I2P Control API
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - I2PSnark BitTorrent client
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - Ứng dụng I2P Tunnel
- [apps/imagegen/README.md](apps/imagegen/README.md) - Công cụ tạo hình ảnh
- [apps/jetty/README.md](apps/jetty/README.md) - Jetty HTTP server
- [apps/jrobin/README.md](apps/jrobin/README.md) - Thư viện giám sát JRobin
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - Thư viện streaming tối thiểu
- [apps/pack200/README.md](apps/pack200/README.md) - Nén Pack200
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - Proxy scripts
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - Router console
- [apps/sam/README.md](apps/sam/README.md) - Simple Anonymous Messaging
- [apps/streaming/README.md](apps/streaming/README.md) - Thư viện streaming
- [apps/susidns/README.md](apps/susidns/README.md) - DNS server
- [apps/susimail/README.md](apps/susimail/README.md) - I2P email client
- [apps/systray/README.md](apps/systray/README.md) - Ứng dụng system tray
- [core/README.md](core/README.md) - Tài liệu thư viện core
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - Thư viện JNI native cho mật mã học (GMP)

### MISC

- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Quản lý lệnh cấm session I2P với nftables
- [installer/resources/README.md](installer/resources/README.md) - Tài nguyên installer bundled
- [tools/scripts/README.md](tools/scripts/README.md) - Script tiện ích cho phát triển và quản trị
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - Script xác thực và kiểm tra



- [docker/README.md](docker/README.md) - Chạy I2P+ trong Docker

