<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb" %>
<jsp:useBean class="net.i2p.router.web.CSSHelper" id="tester" scope="request"/>
<%
  String i2pcontextId1 = null;
  try {i2pcontextId1 = (String) session.getAttribute("i2p.contextId");}
  catch (IllegalStateException ise) {}
%>
<jsp:setProperty name="tester" property="contextId" value="<%=i2pcontextId1%>"/>
<%
  boolean testIFrame = tester.allowIFrame(request.getHeader("User-Agent"));
  boolean embedApp = tester.embedApps();
  if (!testIFrame || !embedApp) {
    response.setStatus(307);
    response.setHeader("Location", "/susimail/");
    response.getOutputStream().close(); // force commitment
    return;
  } else {
%>
<!DOCTYPE HTML>
<%
  net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
  String lang = "en";
  if (ctx.getProperty("routerconsole.lang") != null) {lang = ctx.getProperty("routerconsole.lang");}
%>
<%@include file="head.jsi" %>
<%=intl.title("webmail")%>
</head>
<body class=embed>
<script nonce=<%=cspNonce%>>progressx.show(theme);progressx.progress(0.1);</script>
<%@include file="sidebar.jsi" %>
<h1 class=mail><%=intl._t("Webmail")%> <a href="/susimail/" target=_blank title="<%=intl._t("Open in new tab")%>"><span id=newtab><img src="<%=intl.getTheme(request.getHeader("User-Agent"))%>../images/newtab.svg" alt="<%=intl._t("Open in new tab")%>"></span></a></h1>
<div class=main id=webmail>
<noscript><p class=infohelp id=jsRequired style=margin:10px>Javascript is required to view <a href="/susimail/" target=_blank rel=noreferrer>the webmail client</a> in embedded mode.</p></noscript>
<iframe id=susimailframe class=embed src="/susimail/" title="I2P+ <%=intl._t("webmail")%>" width=100% frameborder=0 border=0 scrolling=no name="susimailframe" allowtransparency=true style=display:none;pointer-events:none>
<%=intl._t("Your browser does not support iFrames.")%>&nbsp;<a href="/susimail/"><%=intl._t("Click here to continue.")%></a>
</iframe>
</div>
<script src="/js/iframeResizer/iframeResizer.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script src="/js/iframeResizer/initResizer.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script nonce=<%=cspNonce%>>document.addEventListener("updated", function() {initResizer("susimailframe");});</script>
<style>body.modal{overflow:hidden;height:100vh}#susimailframe{display:block!important;pointer-events:auto!important}#webmail::before{width:100%;animation:fade .3s linear .7s both}</style>
</body>
</html>
<% } %>