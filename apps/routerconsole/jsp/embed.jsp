<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<%
 String i2pcontextId = request.getParameter("i2p.contextId");
 try {
     if (i2pcontextId != null) {
         session.setAttribute("i2p.contextId", i2pcontextId);
     } else {
         i2pcontextId = (String) session.getAttribute("i2p.contextId");
     }
 } catch (IllegalStateException ise) {}
%>
<jsp:useBean class="net.i2p.router.web.CSSHelper" id="tester" scope="request" />
<jsp:setProperty name="tester" property="contextId" value="<%=i2pcontextId%>" />
<%
    boolean testIFrame = tester.allowIFrame(request.getHeader("User-Agent"));
    boolean embedApp = tester.embedApps();
    String theme = tester.getTheme(request.getHeader("User-Agent"));
    String url = request.getParameter("url");
    String name = request.getParameter("name");
    String norefresh = request.getParameter("norefresh");
    String appname;
    if (name == null || name == "")
        name = "embed page";
    if (norefresh != null)
        norefresh = "true";
    // CSSHelper is also pulled in by css_embed.jsi below...

    if (!testIFrame || !embedApp) {
        response.setStatus(307);
        response.setHeader("Location", url);
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
<%@include file="css_embed.jsi" %>
<%@include file="csp-unsafe.jsi" %>
<%=intl.title(name)%>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<%
        if (url == null) {
%>
<h1 class="webapp"><%=intl._t("Embed Page")%></h1>
<div class="main" id="embedpage">
<div class="confignav">
<span class="tab"><a href="/embed?url=imagegen&amp;name=Image%20Generator">ImageGen</a></span>
<span class="tab"><a href="/embed?url=/history.txt&amp;name=Changelog">Changelog</a></span>
<span class="tab"><a href="/embed?url=/router.log&amp;name=Router%20Log">Router Log</a></span>
<span class="tab"><a href="/embed?url=/wrapper.log&amp;name=Wrapper%20Log">Wrapper Log</a></span>
</div>
<p class="infohelp">To embed local pages in the console, set the <code>url</code> query parameter and optional <code>name</code> parameter, e.g. <a href="http://127.0.0.1:7657/embed?url=imagegen&amp;name=image generator">http://127.0.0.1:7657/embed?url=imagegen&name=image%20generator</a>, or use the form below. Only resources served from 127.0.0.1 or the address the console is running on are supported.</p>
<div id="embedurl">
<form action="/embed?url=" method="get">
<input type="hidden" name="norefresh">
<label>URL: <input type="text" name="url" size="30" required x-moz-errormessage="Please supply a locally hosted address or console path" placeholder="URL to embed" title="Locally hosted URL or console path"></label>
<span class="nowrap"><label>Name: <input type="text" name="name" size="15" value="embedded page" title="Optional title for embedded page"></label>
<input type="submit" value="Embed URL"></span>
</form>
</div>
<%
        } else {
%>
<h1 class="webapp"><%=intl._t(name)%> <a href="<%=url%>" target="_blank" title="<%=intl._t("Open in new tab")%>"><span id="newtab"><img src="<%=intl.getTheme(request.getHeader("User-Agent"))%>images/newtab.png" alt="<%=intl._t("Open in new tab")%>"></span></a></h1>
<%
            if (url.indexOf("imagegen") >= 0 && url.indexOf("css") == -1) {
                appname = "imagegen";
%>
<div class="main" id="<%=appname%>">
<script charset="utf-8" type="text/javascript" src="/js/iframeResizer/iframeResizer.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script charset="utf-8" type="text/javascript" src="/js/iframedClassInjectTheme.js"></script>
<script nonce="<%=cspNonce%>" type="text/javascript">
function setupFrame() {
      f = document.getElementById("<%=appname%>_frame");
      t = "<%=tester.getTheme(request.getHeader("User-Agent")).replaceAll("/", "").replaceAll("console","").replaceAll("themes","")%>";
      a = "<%=appname%>";
      injectClass(f);
}
</script>
<style>iframe {width: 1px; min-width: 100%;}</style>
<noscript><style type="text/css">iframe {width: 100%; height: 100%;}</style></noscript>
<iframe src="<%=url%>" title="<%=appname%>" width="100%" frameborder="0" border="0" scrolling="no" name="<%=appname%>_frame" id="<%=appname%>_frame" onload="setupFrame();" allowtransparency="true"></iframe>
<script nonce="<%=cspNonce%>" type="text/javascript">
var isOldIE = (navigator.userAgent.indexOf("MSIE") !== -1); // Detect IE10 and below
document.addEventListener('DOMContentLoaded', function(event) {
var iframes = iFrameResize({log: false, interval: 0, heightCalculationMethod: isOldIE ? 'max' : 'taggedElement'}, '#<%=appname%>_frame')
});
</script>
<%
        } else {
            if (url.contains("bote"))
                appname = "bote";
            else if (url.contains("BwSchedule"))
                appname = "bwscheduler";
            else if (url.contains("orchid"))
                appname = "orchid";
            else if (url.contains(".log") || url.contains(".css"))
                appname = "rawtext";
            else appname = "iframedapp";
%>
<div class="main embedded" id="<%=appname%>">
<script charset="utf-8" type="text/javascript" src="/js/iframedClassInjectTheme.js"></script>
<script charset="utf-8" type="text/javascript" src="/js/iframeResizer/iframeResizer.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script nonce="<%=cspNonce%>" type="text/javascript">
  function setupFrame() {
      f = document.getElementById("<%=appname%>_frame");
      u = "<%=url%>";
      n = "<%=name%>";
      t = "<%=tester.getTheme(request.getHeader("User-Agent")).replaceAll("/", "").replaceAll("console","").replaceAll("themes","")%>";
      a = "<%=appname%>";
      r = "<%=norefresh%>";
      injectClass(f);
      var cssLink = document.createElement("link");
      cssLink.href = "/themes/console/embed.css";
      cssLink.rel = "stylesheet";
      cssLink.type = "text/css";
      frames["<%=appname%>_frame"].document.body.appendChild(cssLink);
      var resizer = document.createElement("script");
      resizer.setAttribute("src", "/js/iframeResizer/iframeResizer.contentWindow.js");
      resizer.setAttribute("type", "text/javascript");
      frames["<%=appname%>_frame"].document.body.appendChild(resizer);
      endOfPage();
      doRefresh();
  }

  function scrollToEnd() {
      f = document.getElementById("<%=appname%>_frame");
      u = "<%=url%>";
      d = document.getElementById(a + "_frame").contentWindow;
  }
</script>
<style>iframe {width: 1px; min-width: 100%;}</style>
<noscript><style>iframe {width: 100%; height: 100%;}</style></noscript>
<iframe src="<%=url%>" title="<%=appname%>" width="100%" frameborder="0" border="0" scrolling="no" name="<%=appname%>_frame" id="<%=appname%>_frame" onload="setupFrame();scrollToEnd();" allowtransparency="true"></iframe>
<script nonce="<%=cspNonce%>" type="text/javascript">iFrameResize({log: false, interval: 0, heightCalculationMethod: 'taggedElement', warningTimeout: 0}, '#<%=appname%>_frame');</script>
<%
        }
%>
<%
    }
%>
</div>
<span id="endOfPage"></span>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>
<%
  }
%>