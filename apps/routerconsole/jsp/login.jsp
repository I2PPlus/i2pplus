<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true"%>
<% String i2pcontextId = request.getParameter("i2p.contextId"); %>
<jsp:useBean class="net.i2p.router.web.CSSHelper" id="intl" scope="request" />
<jsp:setProperty name="intl" property="contextId" value="<%=i2pcontextId%>" />
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = ctx.getProperty("routerconsole.lang", "en");
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
    if (setupMessage == null) setupMessage = "Create a username and password to access the router console.";
    String success = (String) request.getAttribute("success");
%>
<!DOCTYPE html>
<html lang="<%=lang%>">
<head>
<meta charset=utf-8>
<meta name=viewport content="width=device-width, initial-scale=1">
<title><%= setupMode ? intl._t("Setup") : intl._t("Login") %> - <%= intl._t("I2P+ Router Console") %></title>
<link rel="icon" href="/themes/console/<%=theme%>/images/favicon.svg">
<link rel="stylesheet" href="/themes/login/shared.css">
<link rel="stylesheet" href="<%=themeCSS%>">
<% if (routerStarting) { %>
<meta http-equiv="refresh" content="3">
<% } %>
</head>
<body>
<div class=login-container>
<h1><%= intl._t("I2P+ Router Console") %></h1>
<% if (routerStarting) { %>
<div class="notice">
<p><%= intl._t("Router is starting up...") %></p>
<p class="small"><%= intl._t("This page will refresh automatically") %></p>
</div>
<% } else { %>
<% if (request.getAttribute("error") != null) { %>
<div class=error><%= request.getAttribute("error") %></div>
<% } %>
<% if (success != null) { %>
<div class="success notice"><%= success %></div>
<% } %>
<% if (setupMode) { %>
<div class=notice><%= intl._t(setupMessage) %></div>
<% } %>
<form method=post action="<%= request.getContextPath() %>/login">
<%
String redirectParam = request.getParameter("redirect");
if (redirectParam != null && !redirectParam.isEmpty()) {
%>
<input type=hidden name=redirect value="<%= redirectParam %>">
<% } %>
<%
String csrfToken = (String) request.getAttribute("csrfToken");
if (csrfToken != null) {
%>
<input type=hidden name=csrfToken value="<%= csrfToken %>">
<% } %>
<div class=form-group>
<label for="username"><%= intl._t("Username") %></label>
<input type=text id=username name=username required autocomplete="username" maxlength=64>
</div>
<div class=form-group>
<label for="password"><%= intl._t("Password") %></label>
<input type=password id=password name=password required <% if (setupMode) { %> autocomplete="new-password" <% } else { %> autocomplete="current-password" <% } %> maxlength=128>
</div>
<% if (setupMode) { %>
<div class=form-group>
<label for="confirmPassword"><%= intl._t("Confirm Password") %></label>
<input type=password id=confirmPassword name=confirmPassword required autocomplete="new-password" maxlength=128>
</div>
<% } else { %>
<div class=form-group>
<label for="duration"><%= intl._t("Session Duration") %></label>
<select id=duration name=duration>
<option value=15m><%= intl._t("15 minutes") %></option>
<option value=1h selected><%= intl._t("1 hour") %></option>
<option value=4h><%= intl._t("4 hours") %></option>
<option value=8h><%= intl._t("8 hours") %></option>
<option value=1d><%= intl._t("1 day") %></option>
<option value=forever><%= intl._t("Forever") %></option>
</select>
</div>
<% } %>
<button type=submit><%= setupMode ? intl._t("Set Password") : intl._t("Login") %></button>
</form>
<% } %>
</div>
</body>
</html>