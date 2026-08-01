<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="64kb"%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%
    String fParam = request.getParameter("f");
    String showParam = request.getParameter("show");
    String tab = "all";
    if (showParam != null) {
        tab = showParam;
    } else if (fParam != null) {
        if ("1".equals(fParam)) tab = "fast";
        else if ("2".equals(fParam)) tab = "highcap";
        else if ("3".equals(fParam)) tab = "floodfill";
        else if ("4".equals(fParam)) tab = "banned";
    }
    String title;
    if ("fast".equals(tab)) title = intl._t("Fast Peers");
    else if ("highcap".equals(tab)) title = intl._t("High Capacity Peers");
    else if ("floodfill".equals(tab)) title = intl._t("Floodfill Peers");
    else if ("banned".equals(tab)) title = intl._t("Banned Peers");
    else title = intl._t("Recent Peer Profiles");
%>
<%=intl.title(title)%>
<link href=/themes/console/tablesort.css rel=stylesheet>
</head>
<body>
<%@include file="sidebar.jsi"%>
<jsp:useBean class="net.i2p.router.web.helpers.ProfilesHelper" id="profilesHelper" scope="request"/>
<jsp:setProperty name="profilesHelper" property="contextId" value="<%=i2pcontextId%>"/>
<jsp:setProperty name="profilesHelper" property="full" value="<%=fParam%>"/>
<jsp:setProperty name="profilesHelper" property="show" value="<%=showParam%>"/>
<h1 class=netwrk><%=title%></h1>
<div class=main id=profiles>
<div class=wideload style=height:5px;opacity:0>
<%  profilesHelper.storeWriter(out);
    if (allowIFrame) {profilesHelper.allowGraphical();}
%>
<jsp:getProperty name="profilesHelper" property="summary"/>
</div>
</div>
<script src=/js/tablesort/sortShared.js></script>
<script src=/js/tablesort/tablesort.js type=module></script>
<script src=/js/lazyload.js></script>
<script src=/js/profiles.js type=module></script>
<style>.wideload{height:unset!important;opacity:1!important}#profiles::before{display:none}</style>
</body>
</html>