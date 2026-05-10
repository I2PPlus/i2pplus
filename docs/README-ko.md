[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="28" height="21" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="28" height="21" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="28" height="21" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="28" height="21" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="28" height="21" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="28" height="21" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="28" height="21" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="28" height="21" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="28" height="21" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="28" height="21" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="28" height="21" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="28" height="21" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="28" height="21" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="28" height="21" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="28" height="21" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="28" height="21" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="28" height="21" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="28" height="21" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="28" height="21" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="28" height="21" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="28" height="21" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="28" height="21" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="28" height="21" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="28" height="21" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="28" height="21" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="28" height="21" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="28" height="21" title="English">](README-en.md)

이것은 I2P의 Java 구현의 소프트웨어 포크(soft-fork)의 소스 코드입니다.

최신 릴리스: https://i2pplus.github.io/

## 설치

설치 지침은 [INSTALL.md](INSTALL.md) 또는 https://i2pplus.github.io/를 참조하세요.

### Windows 인스톨러 참고

Java > 1.8 또는 대체 배포판(AdoptOpenJDK 등)을 사용할 때 인스톨러 exe가 "Java not found" 또는 "invalid/corrupt" 오류로 실패할 수 있습니다. 해결 방법: exe에서 install.jar을 추출하고 명령줄에서 `java -jar install.jar`을 실행하세요.

## 문서

https://geti2p.net/how

FAQ: https://geti2p.net/faq

API: https://i2pplus.github.io/javadoc/
또는 'ant javadoc'을 실행한 후 build/javadoc/index.html에서 시작하세요

## 기여 방법 / I2P에서 개발

[HACKING.md](docs/HACKING.md)와 docs 디렉토리의 다른 문서를 확인하세요.

## 소스에서 패키지 빌드

소스 제어에서 개발 브랜치를 가져오려면: https://github.com/I2PPlus/i2pplus/

### 전제 조건

- Java SDK(가능하면 Oracle/Sun 또는 OpenJDK) 1.8.0 이상
- Apache Ant 1.9.8 이상
- GNU gettext 패키지에서 설치된 xgettext, msgfmt, msgmerge 도구
  http://www.gnu.org/software/gettext/
- 빌드 환경은 UTF-8 로케일을 사용해야 합니다.
- Debian 패키지 빌드용: `dpkg-deb` 및 `fakeroot` 패키지 (패키지 관리자를 통해)

### Ant 빌드 프로세스

x86 시스템에서 다음을 실행합니다(IzPack4로 빌드됨):

    ant pkg

비 x86 시스템에서는 다음 중 하나를 대신 사용하세요:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

IzPack5로 빌드하려면 다음에서 다운로드: http://izpack.org/downloads/
설치한 후 다음 명령을 실행하세요:

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

기존 설치를 위한 서명되지 않은 업데이트를 빌드하려면 다음을 실행하세요:

    ant updater

전체 설치 프로그램 빌드에 문제가 있는 경우(Java14 이상은 pack200 관련 izpack 빌드 오류를 생성할 수 있음),
추출하여 그 자리에서 실행할 수 있는 전체 설치 zip을 빌드할 수 있습니다:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

다른 빌드 옵션을 보려면 인자 없이 'ant'를 실행하세요.

Jetty/Tomcat 외부 종속성 없이 Debian/Ubuntu용 독립 실행형 Debian 패키지를 생성하려면:
```bash
ant buildDeb
```

이렇게 하면 외부 종속성 없이 번들된 Jetty 및 Tomcat 라이브러리가 포함된 독립 실행형 `.deb` 패키지가 생성됩니다.


Linux용 AppImage를 빌드하려면:
```bash
ant buildAppImage
```

세부사항은 [tools/appimage/README.md](tools/appimage/README.md)를 참조하세요.


Docker에서 I2P를 실행하는 방법에 대한 자세한 내용은 [docker/README.md](docker/README.md)를 참조하세요


## 연락처 정보

도움이 필요하세요? I2P IRC 네트워크의 #saltR IRC 채널을 방문하세요

버그 보고: https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues 또는 https://github.com/I2PPlus/i2pplus/issues

## 라이선스

I2P+는 AGPL v.3으로 라이선스됩니다.

various 하위 구성 요소 라이선스는 다음을 참조하세요: [README.md](docs/LICENSES.md)

## 참고하세요

### 문서
- [docs/README.md](docs/README.md) - 전체 문서 색인
- [docs/INSTALL.md](docs/INSTALL.md) - 설치 가이드
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - 헤드리스(콘솔 모드) 설치
- [docs/HACKING.md](docs/HACKING.md) - 개발자 가이드 및 빌드 시스템
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - 소스 트리 레이아웃
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - nftables로 I2P 세션 금지 관리
- [docs/LICENSES.md](docs/LICENSES.md) - 서드파티 라이선스
- [docs/history.txt](docs/history.txt) - 전체 변경 로그

### 하위 프로젝트
- [apps/README.md](apps/README.md) - 애플리케이션 개요
- [apps/addressbook/README.md](apps/addressbook/README.md) - 주소록 애플리케이션
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - 데스크톱 GUI 애플리케이션
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - I2P 제어 API
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - I2PSnark BitTorrent 클라이언트
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - I2P 터널 애플리케이션
- [apps/imagegen/README.md](apps/imagegen/README.md) - 이미지 생성 도구
- [apps/jetty/README.md](apps/jetty/README.md) - Jetty HTTP 서버
- [apps/jrobin/README.md](apps/jrobin/README.md) - JRobin 모니터링 라이브러리
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - 최소 스트리밍 라이브러리
- [apps/pack200/README.md](apps/pack200/README.md) - Pack200 압축
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - 프록시 스크립트
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - 라우터 콘솔
- [apps/sam/README.md](apps/sam/README.md) - 단순 익명 메시징
- [apps/streaming/README.md](apps/streaming/README.md) - 스트리밍 라이브러리
- [apps/susidns/README.md](apps/susidns/README.md) - DNS 서버
- [apps/susimail/README.md](apps/susimail/README.md) - I2P 이메일 클라이언트
- [apps/systray/README.md](apps/systray/README.md) - 시스템 트레이 애플리케이션
- [core/README.md](core/README.md) - 코어 라이브러리 문서
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - 암호화용 네이티브 JNI 라이브러리(GMP)

### Misc
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - nftables로 I2P 세션 금지 관리
- [installer/resources/README.md](installer/resources/README.md) - 번들된 인스톨러 리소스
- [tools/scripts/README.md](tools/scripts/README.md) - 개발 및 관리용 유틸리티 스크립트
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - 검증 및 테스트 스크립트

- [docker/README.md](docker/README.md) - Docker에서 I2P+ 실행

