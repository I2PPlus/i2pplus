<%
    response.setStatus(301);
    response.setHeader("Cache-Control","immutable");
    response.setHeader("Location","/help/newusers");
    response.getOutputStream().close();
%>