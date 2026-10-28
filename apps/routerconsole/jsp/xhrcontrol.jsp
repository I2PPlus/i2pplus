<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true"%>
<% /*
   The router-control block on its own: the sidebar's restart/shutdown form and
   the status heading, with nothing else. The client posts the form here and
   re-fetches the block from here, so this is the whole document it has to parse
   and render. Rendering the full sidebar here instead costs 36 KB per exchange,
   loads two dozen icons the hidden frame's own CSP then blocks, and gives every
   extension in the browser a document to inject into.
   Note: this runs the restart/shutdown action, through the same path the sidebar
   does, and the same session nonce is required for it.
*/
   if (request.getCharacterEncoding() == null) {request.setCharacterEncoding("UTF-8");}
   String i2pcontextId = request.getParameter("i2p.contextId");
   try {
     if (i2pcontextId != null) {session.setAttribute("i2p.contextId", i2pcontextId);}
     else {i2pcontextId = (String) session.getAttribute("i2p.contextId");}
   } catch (IllegalStateException ise) {}
   response.setHeader("Cache-Control", "private, no-cache, max-age=3600");
   response.setHeader("Content-Security-Policy", "default-src 'none'");
   response.setHeader("X-XSS-Protection", "1; mode=block");
   response.setHeader("X-Content-Type-Options", "nosniff");
%>
<jsp:useBean class="net.i2p.router.web.CSSHelper" id="intl" scope="request"/>
<jsp:setProperty name="intl" property="contextId" value="<%=i2pcontextId%>"/>
<jsp:useBean class="net.i2p.router.web.helpers.SidebarHelper" id="helper" scope="request"/>
<jsp:setProperty name="helper" property="contextId" value="<%=i2pcontextId%>"/>
<jsp:setProperty name="helper" property="action" value="<%=request.getParameter(\"action\")%>"/>
<jsp:setProperty name="helper" property="consoleNonce" value="<%=request.getParameter(\"consoleNonce\")%>"/>
<%
  // The block's form posts to the page it was rendered for, so the no-scripting
  // path lands where the user was, as it does in the sidebar.
  String reqURI = request.getParameter("requestURI");
  if (reqURI == null || reqURI.isEmpty()) {reqURI = "/home";}
  helper.setRequestURI(reqURI);
  helper.storeSession(session);
%>
<!DOCTYPE HTML>
<html><head><meta charset=utf-8><title><%=intl._t("Router Console")%></title></head>
<body><%=helper.getRestartStatus()%></body></html>
