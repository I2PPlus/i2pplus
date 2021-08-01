<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<%
  /*
   *   Do not tag this file for translation - copy it to help_xx.jsp and translate inline.
   */
%>
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "ar";
    if (ctx.getProperty("routerconsole.lang") != null)
        lang = ctx.getProperty("routerconsole.lang");
%>
<html lang="<%=lang%>">
<head>
<title>I2P مساعدة لوحة التحكم</title>
<%@include file="css.jsi" %>
<script src="/js/ajax.js" type="text/javascript"></script>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>

<h1>I2P مساعدة لوحة التحكم</h1>

<div class="main" id="help" dir="rtl" lang="ar">

<div class="confignav">
<span class="tab"><a href="#sidebarhelp">Sidebar</a></span>
<span class="tab"><a href="#configurationhelp">Configuration</a></span>
<span class="tab"><a href="#reachabilityhelp">Reachability</a></span>
<span class="tab"><a href="#reseedhelp">Reseeding</a></span>
<span class="tab"><a href="#advancedsettings">Advanced Settings</a></span>
<span class="tab"><a href="#faq">FAQ</a></span>
<span class="tab"><a href="#legal">Legal</a></span>
<span class="tab"><a href="#changelog">Change Log</a></span>
</div>

<div id="volunteer">

<h2>مساعدة إضافية</h2>

<p>اذا رغبت في المساعدة أو ترجمة الوثائق، أو المساعدة في أشياء أخرى، انظر اسفله <a href="http://i2p-projekt.i2p/ar/get-involved">تطوع</a></p>

<p>المزيد من المساعدة هنا:</p>

<ul class="links">
<li><a href="http://i2p-projekt.i2p/ar/faq">ابئلة شائعة i2p-projekt.i2p</a></li>

<li>او بالدردشة على IRC.</li></ul>

</div>

<div id="sidebarhelp">

<h2>شريط المعلومات</h2><p>
يمكن للاحصائات أن
<a href="configstats.jsp">تتغير</a> لكي تظهر على شكل
<a href="graphs.jsp">رسم بياني</a> للمزيد من التحاليل
</p><h3>عام</h3><ul>
<li><b>:هوية</b>
الحروف الأولى (24 bits) من 44-حرف (256-) Base64 hash.
The full hash is shown on your <a href="netdb.jsp?r=.">صفحة معلومات الموجه</a>.
هذا لا يكشف عن عنوان  IP الخاص بك لأحد.</li>
<li><b>الاصدار</b>
اصدار I2P المستعمل</li>
<li><b>الآن</b>
الوقت الحالي (UTC)والانحراف الممكن. يحتاج I2P الى ساعة مضبوطة. اذا كان انحراف الساعة اكثر من بضع ثواني، قم بتصحيح الخلل.</li>
<li><b>إمكانية الوصول</b>
امكانية الاتصال الخارجي بالموجه
المزيد من التفاصيل في  <a href="confignet#help">صفحة الاعدادات</a>.</li>
</ul><h3>النظائر</h3><ul>
<li><b>مفعل</b>
هذا هو عدد النظائر التي تم إرسال أو تلقيها رسالة  في الدقائق القليلة الماضية.
قد يكون هذا النطاق 8-10 الى عدة مئات، اعتمادا على عرض النطاق الترددي الإجمالي ،
تقاسم عرض النطاق الترددي ، وحركة المرور المولدة محليا.
والرقم الثاني هو عدد من نظرائه ينظر في آخر ساعة أو نحو ذلك.
لا تشعر بالقلق إذا كانت هذه الأرقام تختلف على نطاق واسع.
<a href="configstats.jsp#router.activePeers">[تفعيل الرسم البياني]</a>.</li>
<li><b>سريع</b>
هذا هو عدد النظائر التي تستعملها لانشاء أنفاق جديدة. هي في نطاق 8-30. النظائر السريعة في
 <a href="profiles.jsp">صفحة البروفايل</a>.
 <a href="configstats.jsp#router.fastPeers">[تفعيل الرسم البياني]</a>.</li>
<li><b>قدرة عالية</b>
هذا هو عدد النظائر التي تستعملها لانشاء أنفاق الاكتشاف. هي في نطاق 8-75. النظائر السريعة. النظائر القدرة عالية تظهر هنا.
 <a href="profiles.jsp">صفحة البروفايل</a>.
 <a href="configstats.jsp#router.highCapacityPeers">[تفعيل الرسم البياني]</a>.</li>
<li><b>المندمجة جيدا</b>
هذا هو عدد النظائر المستعملة في الاتصال بقاعدة البيانات. النظائر المندمجة جيدا موجودة في أسفل
<a href="profiles.jsp">صفحة البروفايل</a>.</li>
<li><b>المعروفة</b>
هذا هو عدد الموجهات المعروفة.
والظاهرة في صفحة <a href="netdb.jsp">قاعدة البيانات</a>
هي مابين 100 الى 1000 او أكثر.
هذا العدد ليس حجم الاجمالي للشبكة،
يمكنه ان بتغيير حسب سرعة الاتصال.
</li>
</ul>
<h3>سرعة الاتصال الداخلي/خارجي</h3>
<p>السرعة ب بايت في الثانية
غير السرعة في <a href="confignet#help">صفحة الاعدادات</a>.
السرعة <a href="graphs.jsp">مرسومة</a> </p>
<h3>الوجهات الداخلية</h3>
<p>الاتصالات الداخلية  البرامج المحلية المتصلة عبر الموجه <a href="i2ptunnel/index.jsp">I2PTunnel</a></p>
<p>او برامج خارجية متصلة SAM, BOB, او مباشرة بـ I2CP.</p>
<h3>الأنفاق الداخلة/خارجة</h3>
<p>الأنفاق الحالية موجودة في <a href="tunnels.jsp">صفحة الأنفاق</a>.</p><ul>
<li><b>تصفح</b>
الأنفاق المستخدمة من طرف الموجه تستعمل في الاتصال مع النظائر، انشاء انفاق جديدة.
</li>
<li><b>المستخدمين</b>
الأنفاق المستخدمة من طرف الموجه </li>
<li><b>المشاركة</b>
الأنفاق المنشئة من طرف موجهات أخرى عبر موجهك.
هذا ينبني على درجة استخدام الشبكة، مقدار المشاركة...
يمكنك تغيير درجة المشاركة بـ <a href="confignet#help">صفحة الاعدادات</a>.
You may also limit the total number by setting <tt>router.maxParticipatingTunnels=nnn</tt> on
the <a href="configadvanced.jsp">صفحة الاعدادات المتقدمة</a>. <a href="configstats.jsp#tunnel.participatingTunnels">[تفعيل الرسم البياني]</a>.</li>
<li><b>نسبة المشاركة</b>
عدد الانفاق المشاركة، مقسوما على عدد اجمالي الانفاق.
عدد أكبر من 1.00 يعني انك تساهم في الشبكة بعدد اكبر مما تستهلك.</li>
</ul>

<h3>ازدحام</h3>
<p>بعض مشرات ازدحام الموجه</p>
<ul>

<li><b>Job lag:</b> How long jobs are waiting before execution. The job queue is listed on the <a href="jobs.jsp">jobs page</a>. Unfortunately, there are several other job queues in the router that may be congested, and their status is not available in the router console. The job lag should generally be zero. If it is consistently higher than 500ms, your computer is very slow, or the router has serious problems. <a href="configstats.jsp#jobQueue.jobLag">[تفعيل الرسم البياني]</a>.</li>

<li><b>Message delay:</b> How long an outbound message waits in the queue. This should generally be a few hundred milliseconds or less. If it is consistently higher than 1000ms, your computer is very slow, or you should adjust your bandwidth limits, or your (bittorrent?) clients may be sending too much data and should have their transmit bandwidth limit reduced. <a href="configstats.jsp#transport.sendProcessingTime">[تفعيل الرسم البياني]</a> (transport.sendProcessingTime).</li>

<li><b>Tunnel lag:</b> This is the round trip time for a tunnel test, which sends a single message out a client tunnel and in an exploratory tunnel, or vice versa. It should usually be less than 5 seconds. If it is consistently higher than that, your computer is very slow, or you should adjust your bandwidth limits, or there are network problems. <a href="configstats.jsp#tunnel.testSuccessTime">[تفعيل الرسم البياني]</a> (tunnel.testSuccessTime).</li>

<li><b>Backlog:</b> This is the number of pending requests from other routers to build a participating tunnel through your router. It should usually be close to zero. If it is consistently high, your computer is too slow, and you should reduce your share bandwidth limits.</li>

<li><b>Accepting/Rejecting:</b> Your router's status on accepting or rejecting requests from other routers to build a participating tunnel through your router. Your router may accept all requests, accept or reject a percentage of requests, or reject all requests for a number of reasons, to control the bandwidth and CPU demands and maintain capacity for local clients.</li>
</ul>

</div>

<% /* untranslated */ %>
<div id="configurationhelp"><%@include file="help-configuration.jsi" %></div>
<div id="reachabilityhelp"><%@include file="help-reachability.jsi" %></div>
<div id="reseedhelp"><%@include file="help-reseed.jsi" %></div> <% /* untranslated */ %>
<div id="advancedsettings"><%@include file="help-advancedsettings.jsi" %></div> <% /* untranslated */ %>
<div id="faq"><%@include file="help-faq.jsi" %></div> <% /* untranslated */ %>
<div id="legal"><%@include file="help-legal.jsi" %></div> <% /* untranslated */ %>

<div id="changelog">
<h2>Change Log</h2>
 <jsp:useBean class="net.i2p.router.web.ContentHelper" id="contenthelper" scope="request" />
 <% java.io.File fpath = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getBaseDir(), "history.txt"); %>
 <jsp:setProperty name="contenthelper" property="page" value="<%=fpath.getAbsolutePath()%>" />
 <jsp:setProperty name="contenthelper" property="maxLines" value="512" />
 <jsp:setProperty name="contenthelper" property="startAtBeginning" value="true" />
 <jsp:getProperty name="contenthelper" property="textContent" />

 <p id="fullhistory"><a href="/history.txt">View the full change log</a></p>
</div>

</div><%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body></html>