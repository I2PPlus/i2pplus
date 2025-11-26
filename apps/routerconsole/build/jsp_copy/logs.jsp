<%  response.setStatus(301);
    response.setHeader("Cache-Control", "private, max-age=2628000, immutable");
    response.setHeader("Location", "/routerlogs");
%>