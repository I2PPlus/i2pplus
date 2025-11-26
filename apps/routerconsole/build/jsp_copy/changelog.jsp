<%  /* No newline or spaces at end of file, otherwise errors! */
    response.setStatus(301);
    response.setHeader("Cache-Control","immutable");
    response.setHeader("Location","/help/changelog");
    response.getOutputStream().close();
%>