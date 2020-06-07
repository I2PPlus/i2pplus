<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<%@include file="css.jsi" %>
<%@include file="csp-unsafe.jsi" %>
<%=intl.title("peer profiles")%>
<!-- tablesort.js https://github.com/tristen/tablesort/ -->
<script nonce="<%=cspNonce%>" src="/js/tablesort/tablesort.js" type="text/javascript"></script>
<script nonce="<%=cspNonce%>" src="/js/tablesort/tablesort.number.js" type="text/javascript"></script>
<link href="/themes/console/tablesort.css" rel="stylesheet" type="text/css">
<!-- end tablesort.js -->
<%@include file="summaryajax.jsi" %>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<h1 class="netwrk"><%=intl._t("Peer Profiles")%></h1>
<div class="main" id="profiles"><div class="wideload">
 <jsp:useBean class="net.i2p.router.web.helpers.ProfilesHelper" id="profilesHelper" scope="request" />
 <jsp:setProperty name="profilesHelper" property="contextId" value="<%=i2pcontextId%>" />
<%
    profilesHelper.storeWriter(out);
    if (allowIFrame)
        profilesHelper.allowGraphical();
%>
 <jsp:setProperty name="profilesHelper" property="full" value="<%=request.getParameter(\"f\")%>" />
 <jsp:getProperty name="profilesHelper" property="summary" />
</div>
</div>
<script nonce="<%=cspNonce%>" type="text/javascript">
  var ff = document.getElementById("floodfills");
  if (ff) {
    new Tablesort(ff);
  }
</script>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.hide();</script>
</body>
</html>
