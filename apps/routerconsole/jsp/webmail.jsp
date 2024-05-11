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
    if (!testIFrame || !embedApp) {
        response.setStatus(307);
        response.setHeader("Location", "/susimail/");
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
<%=intl.title("webmail")%>
<script src="/js/iframeResizer/iframeResizer.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
</head>
<body class=embed>
<script nonce=<%=cspNonce%>>progressx.show("<%=theme%>");progressx.progress(0.1);</script>
<%@include file="summary.jsi" %>
<h1 class=mail><%=intl._t("Webmail")%> <a href="/susimail/" target=_blank title="<%=intl._t("Open in new tab")%>"><span id=newtab><img src="<%=intl.getTheme(request.getHeader("User-Agent"))%>images/newtab.png" alt="<%=intl._t("Open in new tab")%>"></span></a></h1>
<div class=main id=webmail>
<style>iframe{display:none;pointer-events:none}</style>
<noscript>
<style>iframe{display:none}</style>
<p class=infohelp id=jsRequired style=margin:10px>Javascript is required to view <a href="/susimail/" target=_blank rel=noreferrer>the webmail client</a> in embedded mode.</p>
</noscript>
<iframe id=susimailframe class=embed src="/susimail/" title="I2P+ <%=intl._t("webmail")%>" width=100% frameborder=0 border=0 scrolling=no name="susimailframe" allowtransparency=true>
<%=intl._t("Your browser does not support iFrames.")%>
&nbsp;<a href="/susimail/"><%=intl._t("Click here to continue.")%></a>
</iframe>
<script nonce=<%=cspNonce%>>
  document.addEventListener('DOMContentLoaded', function(event) {
    var iframes = iFrameResize({interval: 0, heightCalculationMethod: 'taggedElement', warningTimeout: 0}, '#susimailframe');
    progressx.hide();
  });
</script>
</div>
<style>iframe{display:block;pointer-events:auto}#webmail::before{width:100%;animation:fade .3s linear .7s both}</style>
<script src=/js/iframeTop.js></script>
</body>
</html><%
    }
%>