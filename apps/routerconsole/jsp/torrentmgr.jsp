<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb" %>
<%
    String now = String.valueOf(net.i2p.I2PAppContext.getGlobalContext().clock().now());
%>
<!DOCTYPE HTML>
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null) {lang = ctx.getProperty("routerconsole.lang");}
%>
<%@include file="head.jsi" %>
<%=intl.title("torrents")%>
<style>iframe{display:none;pointer-events:none}</style>
<link rel=stylesheet href=/i2psnark/.res/fullscreen.css>
</head>
<body>
<script nonce=<%=cspNonce%>>progressx.show(theme);progressx.progress(0.1);</script>
<%@include file="sidebar.jsi" %>
<h1 class=snark><%=intl._t("Torrent Manager")%> <a href="/i2psnark/" target=_blank title="<%=intl._t("Open in new tab")%>"><span id=newtab><img src="<%=intl.getTheme(request.getHeader("User-Agent"))%>../images/newtab.svg" alt="<%=intl._t("Open in new tab")%>"></span></a></h1>
<div class=main id=torrents>
<noscript><p class=infohelp id=jsRequired style=margin:10px>Javascript is required to view <a href="/i2psnark" target=_blank rel=noreferrer>I2PSnark</a> in embedded mode.</p></noscript>
<iframe id=i2psnarkframe class=embed src="/i2psnark/configure" title="I2P+ <%=intl._t("Torrent Manager")%>" frameborder=0 border=0 width=100% scrolling=no name="i2psnarkframe" allowtransparency=true allow=fullscreen allowfullscreen=true>
<%=intl._t("Your browser does not support iFrames.")%>&nbsp;<a href="/i2psnark/"><%=intl._t("Click here to continue.")%></a>
</iframe>
</div>
<script src="/js/iframeResizer/iframeResizer.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script src="/js/iframeResizer/initResizer.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script nonce=<%=cspNonce%>>document.addEventListener("updated", function() {initResizer("i2psnarkframe");});</script>
<style>iframe{display:block;pointer-events:auto}#torrents::before{width:100%;animation:fade .3s linear .7s both}</style>
</body>
</html>