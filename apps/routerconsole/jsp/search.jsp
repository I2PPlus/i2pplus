<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<%@ page buffer="32kb" %>
<%
   // http://www.crazysquirrel.com/computing/general/form-encoding.jspx
   if (request.getCharacterEncoding() == null)
       request.setCharacterEncoding("UTF-8");
   String i2pcontextId = null;
   try {
       i2pcontextId = (String) session.getAttribute("i2p.contextId");
   } catch (IllegalStateException ise) {}
%>
<jsp:useBean class="net.i2p.router.web.helpers.SearchHelper" id="searchhelper" scope="request" />
<jsp:setProperty name="searchhelper" property="contextId" value="<%=i2pcontextId%>" />
<jsp:setProperty name="searchhelper" property="engine" value="<%=request.getParameter(\"engine\")%>" />
<jsp:setProperty name="searchhelper" property="query" value="<%=request.getParameter(\"query\")%>" />
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null)
        lang = ctx.getProperty("routerconsole.lang");
%>
<!DOCTYPE html><html lang="<%=lang%>">
<head>
<style>
html,body{margin:0;padding:0;height:100%;overflow:hidden;background:#041804}
#sitesearch{position:relative}
#sitesearch table{width:100%;height:100%;position:absolute;top:0;left:0;right:0;bottom:0}
#sitesearch td{text-align:center;color:#911;font-size:18pt;text-shadow:2px 2px 2px #000}
#sitesearch td#inprogress{background:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='160' height='20' viewBox='0 0 128 16'%3E%3Cpath fill='%23fff' d='M0 0h128v16H0z'/%3E%3ClinearGradient id='A' gradientTransform='rotate(90)'%3E%3Cstop offset='0%25' stop-color='%23fff' stop-opacity='.8'/%3E%3Cstop offset='100%25' stop-color='%23fff' stop-opacity='0'/%3E%3C/linearGradient%3E%3Cpath d='M-.8-.8h129.6v17.6H-.8V-.8z' fill='%23d6edda'/%3E%3Cg transform='matrix(1 0 0 -1 0 16)'%3E%3Cpath d='M-31.935 16l16-16H-.1l-16 16h-15.835zm32 0l16-16H31.9l-16 16H.065zm32 0l16-16H63.9l-16 16H32.065zm32 0l16-16H95.9l-16 16H64.065zm32 0l16-16H127.9l-16 16H96.065z' fill='%230c9224'/%3E%3CanimateTransform attributeName='transform' type='translate' from='32 0' to='0 0' dur='1800ms' repeatCount='indefinite'/%3E%3C/g%3E%3Cpath d='M.8.8v14.4H0V0h128v.8H.8z' fill='%23a1d5aa'/%3E%3Cpath d='M127.2 16H0v-.8h127.2V0h.8v16h-.8z' fill='%23aad9b3'/%3E%3Cpath d='M.8.8h126.4v8.8H.8V.8z' fill='url(%23A)' fill-rule='evenodd'/%3E%3C/svg%3E") no-repeat center center}
</style>
</head>
<body id=sitesearch>
<table><tr>
<%
    String url = searchhelper.getURL();
    if (url != null) {
        response.setStatus(303);
        response.setHeader("Location", url);
%>
<td id=inprogress>
<%
    } else {
        response.setStatus(403);
        String query = request.getParameter("query");
        if (query == null || query.trim().length() <= 0) {
%>
<td>No search string specified!
<%
        } else if (request.getParameter("engine") == null) {
%>
<td>No search engine specified!
<%
        } else {
%>
<td>No search engines found!
<%
        }
    }
%>
</td></tr></table>
</body>
</html>