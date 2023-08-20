<%
/*
 * USE CAUTION WHEN EDITING
 * Trailing whitespace OR NEWLINE on the last line will cause
 * IllegalStateExceptions !!!
 */

    // Redirect from /i2ptunnelmgr to /tunnelmanager
    response.setStatus(301);
    response.setHeader("Cache-Control","immutable");
    response.setHeader("Location","/tunnelmanager");
    // force commitment
    response.getOutputStream().close();
%>