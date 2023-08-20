<%
/*
 * USE CAUTION WHEN EDITING
 * Trailing whitespace OR NEWLINE on the last line will cause
 * IllegalStateExceptions !!!
 */

    // Redirect from /tunnelsparticipatingfastest to /transitfast
    response.setStatus(301);
    response.setHeader("Cache-Control","immutable");
    response.setHeader("Location","/transitfast");
    // force commitment
    response.getOutputStream().close();
%>