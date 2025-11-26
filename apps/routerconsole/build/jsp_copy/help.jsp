<%
/*
 * USE CAUTION WHEN EDITING
 * Trailing whitespace OR NEWLINE on the last line will cause
 * IllegalStateExceptions !!!
 */

    // Redirect from /help to /help/
    response.setStatus(301);
    response.setHeader("Cache-Control","immutable");
    response.setHeader("Location","/help/");
    // force commitment
    response.getOutputStream().close();
%>