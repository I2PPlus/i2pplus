<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="1024kb"%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%
    String showParam = request.getParameter("show");
    String lsParam = request.getParameter("ls");
    String rParam = request.getParameter("r");
    String lParam = request.getParameter("l");
    String cParam = request.getParameter("c");
    String pageTitle;
    if ("all".equals(showParam)) pageTitle = intl._t("All Routers");
    else if ("all_debug".equals(showParam)) pageTitle = intl._t("All Routers [Advanced]");
    else if ("sybils".equals(showParam)) pageTitle = intl._t("Sybil Analysis");
    else if ("lookup".equals(showParam)) pageTitle = intl._t("NetDb Search");
    else if ("local".equals(showParam)) pageTitle = intl._t("Local Router");
    else if ("ls_remote".equals(showParam)) pageTitle = intl._t("LeaseSets") + " [" + intl._t("Remote") + "]";
    else if ("ls_debug".equals(showParam)) pageTitle = intl._t("LeaseSets") + " [" + intl._t("Remote") + " - " + intl._t("Debug") + "]";
    else if ("ls_local".equals(showParam)) pageTitle = intl._t("LeaseSets");
    else if ("client".equals(showParam)) pageTitle = intl._t("All Routers (Client NetDb)");
    else if ("client_debug".equals(showParam)) pageTitle = intl._t("All Routers (Client NetDb) [Advanced]");
    else if (lsParam != null) pageTitle = intl._t("LeaseSet Lookup");
    else if (".".equals(rParam)) pageTitle = intl._t("Local Router");
    else if (rParam != null) pageTitle = intl._t("Router Lookup");
    else if (lParam != null) pageTitle = intl._t("LeaseSets");
    else if (cParam != null) pageTitle = intl._t("Routers");
    else pageTitle = intl._t("Network Database");
%>
<%=intl.title(pageTitle)%>
<script nonce=<%=cspNonce%>>
const translate_encType = "<%=intl._t("Encryption type")%>";
const translate_encTypes = "<%=intl._t("Encryption types")%>";
const translate_sigType = "<%=intl._t("Signature type")%>";
const translate_sigTypes = "<%=intl._t("Signature types")%>";
const translate_localPrivate = "<%=intl._t("Locally hosted private service")%>";
const translate_localPublic = "<%=intl._t("Locally hosted public service")%>";
const translate_requestedLS = "<%=intl._t("Requested client leaseset")%>";
</script>
<% String fParam = request.getParameter("f");
    if ("1".equals(fParam) || "2".equals(fParam)) {
        int currentPage = 1;
        try { currentPage = Integer.parseInt(request.getParameter("pg")); } catch (Exception ignored) { /* Use default currentPage = 1 */ }
        int nextPage = currentPage + 1;

        StringBuilder nextPageQuery = new StringBuilder();
        boolean first = true;
        for (java.util.Map.Entry<String, String[]> e : request.getParameterMap().entrySet()) {
            if (e.getKey().equals("pg")) continue;
            for (String val : e.getValue()) {
                if (!first) nextPageQuery.append("&");
                nextPageQuery.append(e.getKey()).append("=").append(java.net.URLEncoder.encode(val, "UTF-8"));
                first = false;
            }
        }
        if (!first) nextPageQuery.append("&");
        nextPageQuery.append("pg=").append(nextPage);
%>
<link rel=prefetch href="<%=request.getRequestURI() + "?" + nextPageQuery.toString() %>">
<% } %>
</head>
<body>
<%@include file="sidebar.jsi"%>
<jsp:useBean id="formhandler" class="net.i2p.router.web.helpers.NetDbHelper" scope="request"/>
<jsp:setProperty name="formhandler" property="full" value="<%=request.getParameter(\"f\")%>"/>
<jsp:setProperty name="formhandler" property="show" value="<%=request.getParameter(\"show\")%>"/>
<jsp:setProperty name="formhandler" property="router" value="<%=request.getParameter(\"r\")%>"/>
<jsp:setProperty name="formhandler" property="lease" value="<%=request.getParameter(\"l\")%>"/>
<jsp:setProperty name="formhandler" property="version" value="<%=request.getParameter(\"v\")%>"/>
<%  // Country setter with fallback
    String cc = request.getParameter("cc");
    if (cc != null && !cc.isEmpty()) {
%>
<jsp:setProperty name="formhandler" property="country" value="<%=cc%>"/>
<%  } else {
        String c = request.getParameter("c");
        if (c != null) {
%>
<jsp:setProperty name="formhandler" property="country" value="<%=c%>"/>
<%      }
    }
%>
<jsp:setProperty name="formhandler" property="caps" value="<%=request.getParameter(\"caps\")%>"/>
<jsp:setProperty name="formhandler" property="cost" value="<%=request.getParameter(\"cost\")%>"/>
<jsp:setProperty name="formhandler" property="date" value="<%=request.getParameter(\"date\")%>"/>
<jsp:setProperty name="formhandler" property="family" value="<%=request.getParameter(\"fam\")%>"/>
<jsp:setProperty name="formhandler" property="ipv6" value="<%=request.getParameter(\"ipv6\")%>"/>
<jsp:setProperty name="formhandler" property="ip" value="<%=request.getParameter(\"ip\")%>"/>
<jsp:setProperty name="formhandler" property="leaseset" value="<%=request.getParameter(\"ls\")%>"/>
<jsp:setProperty name="formhandler" property="limit" value="<%=request.getParameter(\"ps\")%>"/>
<jsp:setProperty name="formhandler" property="mode" value="<%=request.getParameter(\"m\")%>"/>
<jsp:setProperty name="formhandler" property="mtu" value="<%=request.getParameter(\"mtu\")%>"/>
<jsp:setProperty name="formhandler" property="page" value="<%=request.getParameter(\"pg\")%>"/>
<jsp:setProperty name="formhandler" property="port" value="<%=request.getParameter(\"port\")%>"/>
<jsp:setProperty name="formhandler" property="ssucaps" value="<%=request.getParameter(\"ssucaps\")%>"/>
<jsp:setProperty name="formhandler" property="sybil2" value="<%=request.getParameter(\"sybil2\")%>"/>
<jsp:setProperty name="formhandler" property="sybil" value="<%=request.getParameter(\"sybil\")%>"/>
<jsp:setProperty name="formhandler" property="transport" value="<%=request.getParameter(\"tr\")%>"/>
<jsp:setProperty name="formhandler" property="type" value="<%=request.getParameter(\"type\")%>"/>
<%  boolean delayLoad = false;
    String f = request.getParameter("f");
    String l = request.getParameter("l");
    String ls = request.getParameter("ls");
    String r = request.getParameter("r");
    String c = request.getParameter("c");
    String heading = intl._t("Network Database");

    if (showParam != null) {
        switch (showParam) {
            case "all": heading += " – " + intl._t("All Routers"); break;
            case "all_debug": heading += " – " + intl._t("All Routers") + " [" + intl._t("Advanced") + "]"; break;
            case "sybils": heading += " – " + intl._t("Sybil Analysis"); break;
            case "lookup": heading += " – " + intl._t("Advanced Lookup"); break;
            case "local": heading += " – " + intl._t("Local Router"); break;
            case "ls_remote": delayLoad = true; heading += " – " + intl._t("LeaseSets") + " [" + intl._t("Remote") + "]"; break;
            case "ls_debug": delayLoad = true; heading += " – " + intl._t("LeaseSets") + " [" + intl._t("Remote") + " - " + intl._t("Debug") + "]"; break;
            case "ls_local": delayLoad = true; heading += " – " + intl._t("LeaseSets"); break;
            case "client": heading += " – " + intl._t("All Routers (Client NetDb)"); break;
            case "client_debug": heading += " – " + intl._t("All Routers (Client NetDb)") + " [" + intl._t("Advanced") + "]"; break;
        }
    } else if (f == null && l == null && ls == null && r == null) {
%>
<link href=/themes/console/tablesort.css rel=stylesheet>
<script src=/js/tablesort/sortShared.js></script>
<script src=/js/tablesort/tablesort.js type=module></script>
<%  } else if (f != null) {
        switch (f) {
            case "1": heading += " – " + intl._t("All Routers") + " [" + intl._t("Advanced") + "]";
                break;
            case "2": heading += " – " + intl._t("All Routers");
                break;
            case "3": heading += " – " + intl._t("Sybil Analysis");
                break;
            case "4": heading += " – " + intl._t("Advanced Lookup"); break;
        }
    } else {
        if (".".equals(r)) heading += " – " + intl._t("Local Router");
        else if (r != null) heading += " – " + intl._t("Router Lookup");
        else if (ls != null) heading += " – " + intl._t("LeaseSet Lookup");
        else if (l != null) {
            delayLoad = true;
            heading += " – " + intl._t("LeaseSets");
            if ("1".equals(l)) heading += " [" + intl._t("Remote") + "]";
            else if ("2".equals(l)) heading += " [" + intl._t("Remote") + " - " + intl._t("Debug") + "]";
        } else if (c != null) heading += " – " + intl._t("Routers");
    }
%>
<h1 class=netwrk><%=heading%></h1>
<div class=main id=netdb>
<%  formhandler.storeWriter(out);
    if (allowIFrame) formhandler.allowGraphical();
%>
<%@include file="formhandler.jsi"%>
<% if (delayLoad) {%><div id=netdbwrap style=height:5px;opacity:0><% } %>
<% if ((r == null && ls != null) || l != null || ls != null) {%><div class=leasesets_container><% } %>
<%= formhandler.getNavBarHtml() %>
<% out.flush(); %>
<jsp:getProperty name="formhandler" property="floodfillNetDbSummary"/>
<% if ((r == null && ls != null) || l != null) {%></div><% } %>
</div>
<% if (delayLoad) {%></div>
<style>#netdbwrap{height:unset!important;opacity:1!important}#netdb::before{display:none}</style>
<noscript><style>body:not(.ready) .lazy{display:table!important}</style></noscript>
<% } %>
<style>#pagenav{display:block!important}</style>
<script src=/js/refreshElements.js type=module></script>
<script src=/js/lazyload.js></script>
<script src=/js/tablesort/sortShared.js></script>
<script src=/js/tablesort/tablesort.js></script>
<script src=/js/netdb.js type=module></script>
<script src=/js/netdbLookup.js></script>
<script src=/js/lsCompact.js type=module></script>
</body>
</html>
