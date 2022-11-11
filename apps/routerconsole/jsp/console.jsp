<%
/*
 * USE CAUTION WHEN EDITING
 * Trailing whitespace OR NEWLINE on the last line will cause
 * IllegalStateExceptions !!!
 *
 * Do not tag this file for translation.
 */
    //
    //  Redirect from /console to /help/newusers
    //
    response.setStatus(301);
    response.setHeader("Cache-Control","no-cache");
    response.setHeader("Location","/help/newusers");
    // force commitment
    response.getOutputStream().close();
%>