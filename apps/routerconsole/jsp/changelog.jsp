<%
/*
 * USE CAUTION WHEN EDITING
 * Trailing whitespace OR NEWLINE on the last line will cause
 * IllegalStateExceptions !!!
 */

    // Redirect from /changelog to /help/changelog
    response.setStatus(301);
    response.setHeader("Cache-Control","immutable");
    response.setHeader("Location","/help/changelog");
    // force commitment
    response.getOutputStream().close();
%>