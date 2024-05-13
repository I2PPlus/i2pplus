<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<jsp:useBean class="net.i2p.router.web.CSSHelper" id="tester" scope="request" />
<%
   String i2pcontextId1 = null;
   try {i2pcontextId1 = (String) session.getAttribute("i2p.contextId");}
   catch (IllegalStateException ise) {}
%>
<jsp:setProperty name="tester" property="contextId" value="<%=i2pcontextId1%>" />
<%
    // CSSHelper is also pulled in by css.jsi below...
    boolean testIFrame = tester.allowIFrame(request.getHeader("User-Agent"));
    boolean embedApp = tester.embedApps();
    String now = String.valueOf(net.i2p.I2PAppContext.getGlobalContext().clock().now());
    if (!testIFrame || !embedApp) {
        response.setStatus(307);
        response.setHeader("Location", "/i2psnark/");
        // force commitment
        response.getOutputStream().close();
        return;
    } else {
%>
<!DOCTYPE HTML>
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null)
        lang = ctx.getProperty("routerconsole.lang");
%>
<html lang="<%=lang%>">
<head>
<%@include file="css.jsi" %>
<%@include file="summaryajax.jsi" %>
<%=intl.title("torrents")%>
<script src="/js/iframeResizer/iframeResizer.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script src="/js/iframeResizer/initResizer.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
</head>
<body>
<script nonce=<%=cspNonce%>>progressx.show("<%=theme%>");progressx.progress(0.1);</script>
<%@include file="summary.jsi" %>
<h1 class=snark><%=intl._t("Torrent Manager")%> <a href="/i2psnark/" target=_blank title="<%=intl._t("Open in new tab")%>"><span id=newtab><img src="<%=intl.getTheme(request.getHeader("User-Agent"))%>images/newtab.png" alt="<%=intl._t("Open in new tab")%>"></span></a></h1>
<div class=main id=torrents>
<style>iframe{display:none;pointer-events:none}</style>
<noscript>
<style>iframe{display:none}</style>
<p class=infohelp id=jsRequired style=margin:10px>Javascript is required to view <a href="/i2psnark" target=_blank rel=noreferrer>I2PSnark</a> in embedded mode.</p>
</noscript>
<iframe id=i2psnarkframe class=embed src="/i2psnark/" title="I2P+ <%=intl._t("Torrent Manager")%>" frameborder=0 border=0 width=100% scrolling=no name="i2psnarkframe" allowtransparency=true allow=fullscreen allowfullscreen=true webkitallowfullscreen=true mozallowfullscreen=true>
<%=intl._t("Your browser does not support iFrames.")%>
&nbsp;<a href="/i2psnark/"><%=intl._t("Click here to continue.")%></a>
</iframe>
</div>
<script nonce=<%=cspNonce%>>
  document.addEventListener("DOMContentLoaded", function() {
    initResizer("i2psnarkframe");
    progressx.hide();
  });
  document.addEventListener("updated", function() {initResizer("i2psnarkframe");});
</script>
<style>iframe{display:block;pointer-events:auto}#torrents::before{width:100%;animation:fade .3s linear .7s both}</style>
</body>
</html>
<%  } %>