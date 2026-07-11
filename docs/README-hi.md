[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="28" height="21" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="28" height="21" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="28" height="21" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="28" height="21" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="28" height="21" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="28" height="21" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="28" height="21" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="28" height="21" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="28" height="21" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="28" height="21" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="28" height="21" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="28" height="21" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="28" height="21" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="28" height="21" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="28" height="21" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="28" height="21" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="28" height="21" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="28" height="21" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="28" height="21" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="28" height="21" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="28" height="21" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="28" height="21" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="28" height="21" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="28" height="21" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="28" height="21" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="28" height="21" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="28" height="21" title="English">](README-en.md)

यह Java कार्यान्वयन के I2P के सॉफ्ट-फर्क का स्रोत कोड है।

नवीनतम रिलीज़: [https://i2pplus.github.io/](https://i2pplus.github.io/)

## स्थापना

स्थापना निर्देशों के लिए [INSTALL.md](INSTALL.md) या [https://i2pplus.github.io/](https://i2pplus.github.io/) देखें।

### Windows installer नोट

Java > 1.8 या वैकल्पिक वितरण (AdoptOpenJDK, आदि) के साथ, installer exe "Java not found" या "invalid/corrupt" त्रुटियों के साथ विफल हो सकता है। समाधान: exe से install.jar निकालें और कमांड लाइन से `java -jar install.jar` चलाएं।

## प्रलेखन

[https://geti2p.net/how](https://geti2p.net/how)

अक्सर पूछे जाने वाले प्रश्न: [https://geti2p.net/faq](https://geti2p.net/faq)

API: [https://i2pplus.github.io/javadoc/](https://i2pplus.github.io/javadoc/) या 'ant javadoc' चलाएँ फिर build/javadoc/index.html पर शुरू करें।

## योगदान कैसे करें / I2P पर हैक करें

कृपया [HACKING.md](HACKING.md) और docs डायरेक्टरी में अन्य दस्तावेज़ों की जांच करें।


- [docs/DEBUGGING.md](docs/DEBUGGING.md) - JDWP और अन्य उपकरणों के साथ रनटाइम डिबगिंग
## स्रोत से पैकेज बनाना

स्रोत नियंत्रण से विकास शाखा प्राप्त करने के लिए: [https://github.com/I2PPlus/i2pplus/](https://github.com/I2PPlus/i2pplus/)

### पूर्वापेक्षाएँ

*   Java SDK (प्राथमिकता Oracle/Sun या OpenJDK) 1.8.0 या उच्चतर
*   Apache Ant 1.9.8 या उच्चतर
*   GNU gettext पैकेज से स्थापित xgettext, msgfmt, और msgmerge उपकरण [http://www.gnu.org/software/gettext/](http://www.gnu.org/software/gettext/)
*   निर्माण वातावरण को UTF-8 स्थानीय सेटिंग का उपयोग करना चाहिए।
*   Debian पैकेज बिल्ड के लिए: `dpkg-deb` और `fakeroot` पैकेज (आपके पैकेज मैनेजर के माध्यम से)

### एंटी निर्माण प्रक्रिया

x86 सिस्टम पर निम्नलिखित चलाएँ (यह IzPack4 का उपयोग करके बनाएगा):

`ant pkg`

गैर-x86 पर, इसके बजाय निम्नलिखित में से एक का उपयोग करें:

`ant installer-linux ant installer-freebsd ant installer-osx ant installer-windows`

यदि आप IzPack5 के साथ निर्माण करना चाहते हैं, तो इस लिंक से डाउनलोड करें: [http://izpack.org/downloads/](http://izpack.org/downloads/) और फिर इसे स्थापित करें, और फिर निम्नलिखित कमांड(s) चलाएँ:

`ant installer5-linux ant installer5-freebsd ant installer5-osx ant installer5-windows`

मौजूदा स्थापना के लिए एक अस्वीकृत अपडेट बनाने के लिए, चलाएँ:

`ant updater`

अन्य निर्माण विकल्प देखने के लिए बिना तर्क के 'ant' चलाएँ।

Debian/Ubuntu के लिए बिना Jetty/Tomcat बाहरी निर्भरता के एक स्वतंत्र Debian पैकेज बनाने के लिए:
```bash
ant buildDeb
```

यह एक स्वतंत्र `.deb` पैकेज बनाता है जिसमें किसी बाहरी निर्भरता के बिना बंडल किए गए Jetty और Tomcat लाइब्रेरी शामिल हैं।

### डॉकर

Linux के लिए AppImage बनाने के लिए:
```bash
ant buildAppImage
```

विवरण के लिए [tools/appimage/README.md](tools/appimage/README.md) देखें।


I2P को डॉकर में चलाने के अधिक जानकारी के लिए, देखें [Docker.md](../docker/Docker.md)

## संपर्क जानकारी

क्या आपको मदद चाहिए? I2P IRC नेटवर्क पर IRC चैनल #saltR देखें।

बग रिपोर्ट: [https://github.com/I2PPlus/i2pplus/issues](https://github.com/I2PPlus/i2pplus/issues)

## अनुमतियाँ

I2P+ AGPL v.3 के अंतर्गत लाइसेंसित है।

विभिन्न उप-घटक लाइसेंसों के लिए, देखें: [README.md](docs/LICENSES.md)