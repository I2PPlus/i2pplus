<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb" %>
<jsp:useBean class="net.i2p.router.web.CSSHelper" id="tester" scope="request" />
<%
   String i2pcontextId1 = null;
   try {i2pcontextId1 = (String) session.getAttribute("i2p.contextId");}
   catch (IllegalStateException ise) {}
%>
<jsp:setProperty name="tester" property="contextId" value="<%=i2pcontextId1%>" />
<%
    // CSSHelper is also pulled in by head.jsi below...
    boolean testIFrame = tester.allowIFrame(request.getHeader("User-Agent"));
    if (!testIFrame) {
        response.setStatus(307);
        response.setHeader("Location", "/i2ptunnel/");
        // force commitment
        response.getOutputStream().close();
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
<%=intl.title("Tunnel Manager")%>
<style>iframe{display:none;pointer-events:none}</style>
</head>
<body>
<script nonce=<%=cspNonce%>>progressx.show(theme);progressx.progress(0.1);</script>
<%@include file="sidebar.jsi" %>
<h1 class=conf><%=intl._t("Tunnel Manager")%> <a href="/i2ptunnel/" target=_blank title="<%=intl._t("Open in new tab")%>"><span id=newtab><img src="<%=intl.getTheme(request.getHeader("User-Agent"))%>../images/newtab.svg" alt="<%=intl._t("Open in new tab")%>"></span></a></h1>
<div class=main id=tunnelmgr>
<noscript><p class=infohelp id=jsRequired style=margin:10px>Javascript is required to view <a href="/i2ptunnel/" target=_blank rel=noreferrer>the Tunnel Manager</a> in embedded mode.</p></noscript>
<iframe id=i2ptunnelframe class=embed src="/i2ptunnel/" title="I2P+ <%=intl._t("Tunnel Manager")%>" frameborder=0 border=0 width=100% scrolling=no name="i2ptunnelframe" allowtransparency=true>
<%=intl._t("Your browser does not support iFrames.")%>&nbsp;<a href="/i2ptunnel/"><%=intl._t("Click here to continue.")%></a>
</iframe>
</div>
<script src="/js/iframeResizer/iframeResizer.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script src="/js/iframeResizer/initResizer.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script nonce=<%=cspNonce%>>document.addEventListener("updated", function() {initResizer("i2ptunnelframe");});</script>
<style>#i2ptunnelframe{display:block;pointer-events:auto}#tunnelmgr::before{width:100%;animation:fade .3s linear .7s both}</style>
</body>
</html><%
    }
%>