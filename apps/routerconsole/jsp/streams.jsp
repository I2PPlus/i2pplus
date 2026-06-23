<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="256kb"%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%=intl.title("streaming connections")%>
<link rel=stylesheet href=/themes/console/tunnels.css>
</head>
<body id=routerstreams>
<%@include file="sidebar.jsi"%>
<h1 class=netwrk><%=intl._t("Streaming Connections")%></h1>
<jsp:useBean class="net.i2p.router.web.helpers.StreamHelper" id="streamHelper" scope="request"/>
<jsp:setProperty name="streamHelper" property="contextId" value="<%=i2pcontextId%>"/>
<div class=main id=streams>
<div class=confignav>
<span class=tab2 title="<%=intl._t("Active I2P streaming connections")%>"><%=intl._t("Streams")%></span>
<span class=tab><a href=/tunnels><%=intl._t("Local Tunnels")%></a></span>
<span class=tab><a href=/transit><%=intl._t("Transit")%> (<%=intl._t("Most Recent")%>)</a></span>
</div>
<div id=streamsContainer>
<% streamHelper.storeWriter(out);%>
<jsp:getProperty name="streamHelper" property="streamSummary"/>
</div>
</div>
</body>
</html>
