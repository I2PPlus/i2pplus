<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb"%>
<jsp:useBean class="net.i2p.router.web.CSSHelper" id="tester" scope="request"/>
<%
   String i2pcontextId1 = null;
   try {i2pcontextId1 = (String) session.getAttribute("i2p.contextId");}
   catch (IllegalStateException ise) {}
%>
<jsp:setProperty name="tester" property="contextId" value="<%=i2pcontextId1%>"/>
<%
    // take /dns query params and pass to the iframe
    String requestURL;
    String query = request.getQueryString();
    if (query != null && query.contains("configure")) {requestURL = "/i2psnark/configure";}
    else {requestURL = "/i2psnark/";}

    // CSSHelper is also pulled in by head.jsi below...
    boolean testIFrame = tester.allowIFrame(request.getHeader("User-Agent"));
    boolean embedApp = tester.embedApps();
    String now = String.valueOf(net.i2p.I2PAppContext.getGlobalContext().clock().now());
    if (!testIFrame || !embedApp) {
        response.setStatus(301);
        response.setHeader("Location", requestURL);
        // force commitment
        response.getOutputStream().close();
        return;
    } else {
%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%=intl.title("torrents")%>
<style>iframe{display:none;pointer-events:none}</style>
<link rel=stylesheet href=/i2psnark/.res/fullscreen.css>
</head>
<body>
<%@include file="sidebar.jsi"%>
<h1 class=snark><%=intl._t("Torrent Manager")%> <a href="<%=requestURL%>" target=_blank title="<%=intl._t("Open in new tab")%>"><span id=newtab><img src="<%=intl.getTheme(request.getHeader("User-Agent"))%>../images/newtab.svg" alt="<%=intl._t("Open in new tab")%>"></span></a></h1>
<div class=main id=torrents>
<noscript><p class=infohelp id=jsRequired style=margin:10px>Javascript is required to view <a href="<%=requestURL%>" target=_blank rel=noreferrer>I2PSnark</a> in embedded mode.</p></noscript>
<iframe id=i2psnarkframe class=embed src="<%=requestURL%>" title="I2P+ <%=intl._t("Torrent Manager")%>" frameborder=0 border=0 width=100% scrolling=no name="i2psnarkframe" allowtransparency=true allow=fullscreen allowfullscreen=true>
<%=intl._t("Your browser does not support iFrames.")%> &nbsp;<a href="<%=requestURL%>"><%=intl._t("Click here to continue.")%></a>
</iframe>
</div>
<script src=/js/iframeResizer/initResizer.js></script>
<script src=/js/iframeResizer/iframeResizer.js type=module></script>
<script nonce=<%=cspNonce%>>document.addEventListener("updated", function() {initResizer("i2psnarkframe");});</script>
<style>iframe{display:block;pointer-events:auto}#torrents::before{width:100%;animation:fade .3s linear .7s both}</style>
</body>
</html>
<%  } %>