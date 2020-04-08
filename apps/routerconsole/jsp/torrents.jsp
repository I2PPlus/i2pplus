<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<jsp:useBean class="net.i2p.router.web.CSSHelper" id="tester" scope="request" />
<%
   String i2pcontextId1 = null;
   try {
       i2pcontextId1 = (String) session.getAttribute("i2p.contextId");
   } catch (IllegalStateException ise) {}
%>
<jsp:setProperty name="tester" property="contextId" value="<%=i2pcontextId1%>" />
<%
    // CSSHelper is also pulled in by css.jsi below...
    boolean testIFrame = tester.allowIFrame(request.getHeader("User-Agent"));
    boolean embedApp = tester.embedApps();
    if (!testIFrame || !embedApp) {
        response.setStatus(307);
        response.setHeader("Location", "/i2psnark/");
        // force commitment
        response.getOutputStream().close();
        return;
    } else {
%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<link rel="preload" href="/i2psnark/" as="document">
<%@include file="css.jsi" %>
<%@include file="csp-unsafe.jsi" %>
<%=intl.title("torrents")%>
<script nonce="<%=cspNonce%>" type="text/javascript">
function setupFrame() {
      f = document.getElementById("i2psnarkframe");
      injectClass(f);
      iframeResizer(f);
}
</script>
<script type="text/javascript" src="/js/iframedClassInject.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script type="text/javascript" src="/js/iframeResizer/iframeResizer.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<%@include file="summaryajax.jsi" %>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<h1 class="snark"><%=intl._t("Torrent Manager")%> <span class="newtab"><a href="/i2psnark/" target="_blank" title="<%=intl._t("Open in new tab")%>"><img src="<%=intl.getTheme(request.getHeader("User-Agent"))%>images/newtab.png" /></a></span></h1>
<div class="main" id="torrents">
<style>iframe {width: 1px; min-width: 100%;}</style>
<noscript><style type="text/css">iframe {display: none}</style><p class="infohelp" style="margin: 10px;">Javascript is required to view <a href="/i2psnark" target="_blank">I2PSnark</a> in embedded mode.</p></noscript>
<iframe src="/i2psnark/" frameborder="0" border="0" width="100%" scrolling="no" name="i2psnarkframe" id="i2psnarkframe" allowtransparency="true" allow="fullscreen" onload="setupFrame()" allowfullscreen="true" webkitallowfullscreen="true" mozallowfullscreen="true">
<%=intl._t("Your browser does not support iFrames.")%>
&nbsp;<a href="/i2psnark/"><%=intl._t("Click here to continue.")%></a>
</iframe>
</div>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.hide();</script>
</body>
</html>
<%
    }
%>
