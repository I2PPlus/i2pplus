<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<%
    String now = String.valueOf(net.i2p.I2PAppContext.getGlobalContext().clock().now());
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
</head>
<body>
<script nonce="<%=cspNonce%>" type=text/javascript>progressx.show();progressx.progress(0.5);</script>
<%@include file="summary.jsi" %>
<h1 class=snark><%=intl._t("Torrent Manager")%> <a href="/i2psnark/" target=_blank title="<%=intl._t("Open in new tab")%>"><span id=newtab><img src="<%=intl.getTheme(request.getHeader("User-Agent"))%>images/newtab.png" alt="<%=intl._t("Open in new tab")%>"></span></a></h1>
<div class=main id=torrents>
<style>iframe{display:none;pointer-events:none}</style>
<noscript>
<style type=text/css>iframe {display: none}</style>
<p class=infohelp id=jsRequired style=margin:10px>Javascript is required to view <a href="/i2psnark" target=_blank rel=noreferrer>I2PSnark</a> in embedded mode.</p>
</noscript>
<iframe src="/i2psnark/configure" title="I2P+ <%=intl._t("Torrent Manager")%>" frameborder=0 border=0 width=100% scrolling=no name="i2psnarkframe" id=i2psnarkframe allowtransparency=true allow=fullscreen allowfullscreen=true webkitallowfullscreen=true mozallowfullscreen=true>
<%=intl._t("Your browser does not support iFrames.")%>
&nbsp;<a href="/i2psnark/"><%=intl._t("Click here to continue.")%></a>
</iframe>
<script charset=utf-8 type=text/javascript src="/js/iframeResizer/iframeResizer.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script charset=utf-8 type=text/javascript src="/js/iframedClassInject.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script nonce="<%=cspNonce%>" type=text/javascript>
  function setupFrame() {
    f = document.getElementById("i2psnarkframe");
    f.addEventListener("load", function() {
      injectClass(f);
    }, true);
  }
  document.addEventListener('DOMContentLoaded', function(event) {
    var iframes = iFrameResize({interval: 0, heightCalculationMethod: 'taggedElement', warningTimeout: 0}, '#i2psnarkframe');
    progressx.hide();
  });
</script>
</div>
<style type=text/css>iframe{display:block;pointer-events:auto}#torrents::before{width:100%;animation:fade .3s linear .7s both}</style>
</body>
</html>