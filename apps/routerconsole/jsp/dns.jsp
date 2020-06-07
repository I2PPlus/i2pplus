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
    if (!testIFrame) {
        response.setStatus(307);
        response.setHeader("Location", "/susidns/addressbook?book=router&amp;filter=none");
        // force commitment
        response.getOutputStream().close();
        return;
    } else {
%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<link rel="preload" href="/js/iframeResizer/iframeResizer.contentWindow.js?<%=net.i2p.CoreVersion.VERSION%>" as="script">
<link rel="preload" href="/susidns/addressbook?book=router&amp;filter=none" as="fetch" crossorigin>
<%@include file="css.jsi" %>
<%=intl.title("addressbook")%>

<script type="text/javascript" src="/js/iframeResizer/iframeResizer.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script type="text/javascript" src="/js/iframedClassInject.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">
  function setupFrame() {
    f = document.getElementById("susidnsframe");
    f.addEventListener("load", function() {
      injectClass(f);
    }, true);
  }
</script>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<h1 class="addbook"><%=intl._t("Addressbook")%> <span class="newtab"><a href="/susidns/addressbook?book=router&amp;filter=none" target="_blank" title="<%=intl._t("Open in new tab")%>"><img src="<%=intl.getTheme(request.getHeader("User-Agent"))%>images/newtab.png" /></a></span></h1>
<div class="main" id="dns">
<style>iframe {width: 1px; min-width: 100%;}</style>
<noscript><style type="text/css">iframe {display: none}</style><p class="infohelp" style="margin: 10px;">Javascript is required to view <a href="/susidns/addressbook?book=router&amp;filter=none" target="_blank">the Addressbook</a> in embedded mode.</p></noscript>
<iframe src="/susidns/addressbook?book=router&amp;filter=none" width="100%" scrolling="no" frameborder="0" border="0" name="susidnsframe" id="susidnsframe" allowtransparency="true">
<%=intl._t("Your browser does not support iFrames.")%>
&nbsp;<a href="/susidns/addressbook?book=router&amp;filter=none"><%=intl._t("Click here to continue.")%></a>
</iframe>
<script nonce="<%=cspNonce%>" type="text/javascript">
  document.addEventListener('DOMContentLoaded', function(event) {
    var iframes = iFrameResize({log: false, interval: 0, heightCalculationMethod: 'taggedElement', warningTimeout: 0}, '#susidnsframe')
  });
</script>
</div>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.hide();</script>
</body>
</html>
<%
    }
%>
