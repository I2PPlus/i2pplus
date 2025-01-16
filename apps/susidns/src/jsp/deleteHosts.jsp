<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="4kb" %>
<jsp:useBean id="base" class="i2p.susi.dns.BaseBean" scope="session" />
<jsp:useBean id="intl" class="i2p.susi.dns.Messages" scope="application" />
<jsp:useBean id="subs" class="i2p.susi.dns.SubscriptionsBean" scope="session" />
<jsp:useBean id="version" class="i2p.susi.dns.VersionBean" scope="application" />
<%@include file="headers.jsi"%>
<!DOCTYPE html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title><%=intl._t("delete hosts")%> - susidns</title>
<style>html{height:100vh;background:#686363}body,html{margin:0;display:flex;justify-content:center;align-items:center;font-family:Open Sans,Segoe UI,Noto Sans,sans-serif;font-weight:500}body{margin:1.5% 7.5%;padding:2% 4%;height:fit-content;color:#33211b;border:5px solid #b5a485;box-shadow:3px 3px 3px #0004,0 0 0 1px #201313 inset,0 0 0 2px #201313dd;background:#ffefb3}h1{margin:0;padding-bottom:8px;border-bottom:1px solid #201313}.messages{padding:8px 0 0;margin:0}#message.warning{padding:8px 0}</style>
<script nonce="<%=cspNonce%>">const nonce = "${base.getSerial()}";</script>
</head>
<body>
<div id=container><h1>Delete Hosts</h1><div id=message></div></div>
<script src=/susidns/js/deleteHosts.js type=module></script>
</body>
</html>