<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="256kb"%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%=intl.title("peer profiles")%>
<link href=/themes/console/tablesort.css rel=stylesheet>
</head>
<body>
<%@include file="sidebar.jsi"%>
<jsp:useBean class="net.i2p.router.web.helpers.ProfilesHelper" id="profilesHelper" scope="request"/>
<jsp:setProperty name="profilesHelper" property="contextId" value="<%=i2pcontextId%>"/>
<jsp:setProperty name="profilesHelper" property="full" value="<%=request.getParameter(\"f\")%>"/>
<%  String req = request.getParameter("f");
    if (req == null) {
%>
<h1 class=netwrk><%=intl._t("Peer Profiles")%></h1>
<%  } else if (req.equals("4")) { %>
<h1 class=netwrk><%=intl._t("Session Banned Peers")%></h1>
<% } else if (req.equals("3")) { %>
<h1 class=netwrk><%=intl._t("Banned Peers")%></h1>
<%  } else if (req.equals("2")) { %>
<h1 class=netwrk><%=intl._t("Peer Profiles")%> &ndash; <%=intl._t("Floodfills")%></h1>
<%  } else if (req.equals("1")) { %>
<h1 class=netwrk><%=intl._t("Peer Profiles")%> &ndash; <%=intl._t("Fast / High Capacity")%></h1>
<%  } %>
<div class=main id=profiles>
<div class=wideload style=height:5px;opacity:0>
<%  profilesHelper.storeWriter(out);
    if (allowIFrame) {profilesHelper.allowGraphical();}
%>
<jsp:getProperty name="profilesHelper" property="summary"/>
</div>
</div>
<script src=/js/tablesort/tablesort.js></script>
<script src=/js/tablesort/tablesort.number.js></script>
<script src=/js/lazyload.js></script>
<script src=/js/profiles.js type=module></script>
<style>.wideload{height:unset!important;opacity:1!important}#profiles::before{display:none}</style>
</body>
</html>