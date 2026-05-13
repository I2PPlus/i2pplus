<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true"%>
<%@page import="java.util.HashMap"%>
<%@page import="java.util.Map"%>
<% String i2pcontextId = request.getParameter("i2p.contextId");%>
<jsp:useBean class="net.i2p.router.web.CSSHelper" id="intl" scope="request" />
<jsp:setProperty name="intl" property="contextId" value="<%=i2pcontextId%>" />
<%
     net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
     String lang = request.getParameter("lang");
     if (lang == null) lang = ctx.getProperty("routerconsole.lang", "en");
     String theme = (String) request.getAttribute("theme");
     if (theme == null) theme = "dark";
     String themeCSS = "/themes/login/" + theme + "/login.css";
     Boolean routerStarting = (Boolean) request.getAttribute("routerStarting");
     if (routerStarting == null) routerStarting = false;
     Boolean setupMode = (Boolean) request.getAttribute("setupMode");
     if (setupMode == null) setupMode = false;
     String setupTitle = (String) request.getAttribute("setupTitle");
     if (setupTitle == null) setupTitle = "Set Up Console Access";
     String setupMessage = (String) request.getAttribute("setupMessage");
     if (setupMessage == null) setupMessage = "Create a username and password to access the router console &amp; webapps.";
     String success = (String) request.getAttribute("success");
     String safeLang = net.i2p.data.DataHelper.escapeHTML(lang);
 %>
<!DOCTYPE html>
<html lang="<%=safeLang%>">
<head>
<meta charset=utf-8>
<meta name=viewport content="width=device-width, initial-scale=1">
<title><%=setupMode ? intl._t("Setup") : intl._t("Login")%> - <%=intl._t("I2P+ Router Console")%></title>
<link rel=icon href="/themes/console/<%=theme%>/images/favicon.svg">
<link rel=stylesheet href="/themes/login/shared.css">
<link rel=stylesheet href="<%=themeCSS%>">
<script src=/themes/login/login.js></script>
<% if (routerStarting) {%>
<meta http-equiv=refresh content=3>
<% }%>
</head>
<% if (!routerStarting) {%>
<%
    java.util.Map langToCountry = new java.util.HashMap();
    langToCountry.put("bo", "xt");
    langToCountry.put("uk", "ua");
    langToCountry.put("ar", "lang_ar");
    langToCountry.put("az", "az");
    langToCountry.put("cs", "cz");
    langToCountry.put("zh", "cn");
    langToCountry.put("da", "dk");
    langToCountry.put("de", "de");
    langToCountry.put("et", "ee");
    langToCountry.put("en", "us");
    langToCountry.put("es", "es");
    langToCountry.put("fi", "fi");
    langToCountry.put("fr", "fr");
    langToCountry.put("el", "gr");
    langToCountry.put("hi", "in");
    langToCountry.put("hu", "hu");
    langToCountry.put("in", "id");
    langToCountry.put("it", "it");
    langToCountry.put("ja", "jp");
    langToCountry.put("ko", "kr");
    langToCountry.put("nl", "nl");
    langToCountry.put("nb", "no");
    langToCountry.put("fa", "ir");
    langToCountry.put("pl", "pl");
    langToCountry.put("pt", "pt");
    langToCountry.put("ro", "ro");
    langToCountry.put("ru", "ru");
    langToCountry.put("sl", "sk");
    langToCountry.put("sv", "se");
    langToCountry.put("bo", "xt");
    langToCountry.put("tr", "tr");
    langToCountry.put("vi", "vn");
    String currentCountry = langToCountry.containsKey(lang) ? (String)langToCountry.get(lang) : "us";
%>
<div id=topbar>
<div id=topbar-right>
<span id=themeselect class=dropdown data-open>
<button class=dropbtn title="<%=intl._t("Select theme")%>"><img src="/themes/console/<%=theme%>/images/thumbnail.png" alt="<%=theme%>"></button>
<div class=dropdown-content>
<a href="#" data-param="theme" data-value="classic" title="<%=intl._t("Classic")%>"><img src="/themes/console/classic/images/thumbnail.png"></a>
<a href="#" data-param="theme" data-value="dark" title="<%=intl._t("Dark")%>"><img src="/themes/console/dark/images/thumbnail.png"></a>
<a href="#" data-param="theme" data-value="light" title="<%=intl._t("Light")%>"><img src="/themes/console/light/images/thumbnail.png"></a>
<a href="#" data-param="theme" data-value="midnight" title="<%=intl._t("Midnight")%>"><img src="/themes/console/midnight/images/thumbnail.png"></a>
</div>
</span>
<span id=langselect class=dropdown data-open>
<button class=dropbtn title="<%=intl._t("Select display language")%>"><img src="/flags.jsp?c=<%=currentCountry%>" alt="<%=safeLang%>"></button>
<div class=dropdown-content>
<a href="#" data-param="lang" data-value="ar" title="Arabic"><img src="/flags.jsp?c=lang_ar"></a>
<a href="#" data-param="lang" data-value="az" title="Azerbaijani"><img src="/flags.jsp?c=az"></a>
<a href="#" data-param="lang" data-value="cs" title="Cestina"><img src="/flags.jsp?c=cz"></a>
<a href="#" data-param="lang" data-value="zh" title="Chinese"><img src="/flags.jsp?c=cn"></a>
<a href="#" data-param="lang" data-value="da" title="Dansk"><img src="/flags.jsp?c=dk"></a>
<a href="#" data-param="lang" data-value="de" title="Deutsch"><img src="/flags.jsp?c=de"></a>
<a href="#" data-param="lang" data-value="et" title="Eesti"><img src="/flags.jsp?c=ee"></a>
<a href="#" data-param="lang" data-value="en" title="English"><img src="/flags.jsp?c=us"></a>
<a href="#" data-param="lang" data-value="es" title="Espanol"><img src="/flags.jsp?c=es"></a>
<a href="#" data-param="lang" data-value="fi" title="Finnish"><img src="/flags.jsp?c=fi"></a>
<a href="#" data-param="lang" data-value="fr" title="Francais"><img src="/flags.jsp?c=fr"></a>
<a href="#" data-param="lang" data-value="el" title="Greek"><img src="/flags.jsp?c=gr"></a>
<a href="#" data-param="lang" data-value="hi" title="Hindi"><img src="/flags.jsp?c=in"></a>
<a href="#" data-param="lang" data-value="hu" title="Hungarian"><img src="/flags.jsp?c=hu"></a>
<a href="#" data-param="lang" data-value="in" title="Indonesian"><img src="/flags.jsp?c=id"></a>
<a href="#" data-param="lang" data-value="it" title="Italiano"><img src="/flags.jsp?c=it"></a>
<a href="#" data-param="lang" data-value="ja" title="Japanese"><img src="/flags.jsp?c=jp"></a>
<a href="#" data-param="lang" data-value="ko" title="Korean"><img src="/flags.jsp?c=kr"></a>
<a href="#" data-param="lang" data-value="nl" title="Nederlands"><img src="/flags.jsp?c=nl"></a>
<a href="#" data-param="lang" data-value="nb" title="Norsk"><img src="/flags.jsp?c=no"></a>
<a href="#" data-param="lang" data-value="fa" title="Persian"><img src="/flags.jsp?c=ir"></a>
<a href="#" data-param="lang" data-value="pl" title="Polski"><img src="/flags.jsp?c=pl"></a>
<a href="#" data-param="lang" data-value="pt" title="Portugues"><img src="/flags.jsp?c=pt"></a>
<a href="#" data-param="lang" data-value="ro" title="Romana"><img src="/flags.jsp?c=ro"></a>
<a href="#" data-param="lang" data-value="ru" title="Russian"><img src="/flags.jsp?c=ru"></a>
<a href="#" data-param="lang" data-value="sl" title="Slovencina"><img src="/flags.jsp?c=sk"></a>
<a href="#" data-param="lang" data-value="sv" title="Svenska"><img src="/flags.jsp?c=se"></a>
<a href="#" data-param="lang" data-value="bo" title="Tibetan"><img src="/flags.jsp?c=xt"></a>
<a href="#" data-param="lang" data-value="tr" title="Turkce"><img src="/flags.jsp?c=tr"></a>
<a href="#" data-param="lang" data-value="uk" title="Ukrainian"><img src="/flags.jsp?c=ua"></a>
<a href="#" data-param="lang" data-value="vi" title="Vietnamese"><img src="/flags.jsp?c=vn"></a>
</div>
</span>
</div>
</div>
<% }%>
<body>
<div class=login-container>
<h1><%=intl._t("I2P+ Router Console")%></h1>
<% if (routerStarting) {%>
<div class="notice startup">
<p><%=intl._t("Router is starting up...")%></p>
<p><%=intl._t("This page will refresh automatically")%></p>
</div>
<% } else {%>
<% if (request.getAttribute("error") != null) {%>
<div class=error><%=net.i2p.data.DataHelper.escapeHTML((String)request.getAttribute("error"))%></div>
<% }%>
<% if (success != null) {%>
<div class="success notice"><%=net.i2p.data.DataHelper.escapeHTML(success)%></div>
<% }%>
<% if (setupMode) {%>
<div class=notice><%=intl._t(setupMessage)%></div>
<% }%>
<form method=post action="<%=request.getContextPath()%>/login">
<%
String redirectParam = request.getParameter("redirect");
if (redirectParam != null && !redirectParam.isEmpty()) {
%>
<input type=hidden name=redirect value="<%=net.i2p.data.DataHelper.escapeHTML(redirectParam)%>">
<% }%>
<%
String csrfToken = (String) request.getAttribute("I2P+CSRFTOKEN");
if (csrfToken != null) {
%>
<input type=hidden name=I2P+CSRFTOKEN value="<%=csrfToken%>">
<% }%>
<div class=form-group>
<label for="username"><%=intl._t("Username")%></label>
<input type=text id=username name=username required autocomplete="username" maxlength=64>
</div>
<div class=form-group>
<label for="password"><%=intl._t("Password")%></label>
<input type=password id=password name=password required minlength=8 <% if (setupMode) {%> autocomplete="new-password" <% } else {%> autocomplete="current-password" <% }%> maxlength=128>
</div>
<% if (setupMode) {%>
<div class=form-group>
<label for="confirmPassword"><%=intl._t("Confirm Password")%></label>
<input type=password id=confirmPassword name=confirmPassword required minlength=8 autocomplete="new-password" maxlength=128>
</div>
<% } else {%>
<div class=form-group>
<label for="duration"><%=intl._t("Session Duration")%></label>
<select id=duration name=duration>
<option value=15m><%=intl._t("15 minutes")%></option>
<option value=1h selected><%=intl._t("1 hour")%></option>
<option value=4h><%=intl._t("4 hours")%></option>
<option value=8h><%=intl._t("8 hours")%></option>
<option value=1d><%=intl._t("1 day")%></option>
<option value=forever><%=intl._t("Forever")%></option>
</select>
</div>
<% }%>
<button type=submit><%=setupMode ? intl._t("Set Password") : intl._t("Login")%></button>
</form>
<% }%>
</div>
<div id=footer hidden>
<a href="https://i2pplus.github.io/" target=_blank><img src="/themes/console/images/plus.svg"></a></a>
<a href="https://github.com/I2PPlus/i2pplus" target=_blank><img src="/themes/login/<%=theme%>/github.svg"></a>
<a href="https://github.com/I2PPlus/i2pplus/issues" target=_blank><img src="/themes/console/images/bug.svg"></a>
</div>
<noscript><style>#topbar{display:none!important}</style></noscript>
</body>
</html>