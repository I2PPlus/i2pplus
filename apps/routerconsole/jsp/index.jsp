<%
/*
 * USE CAUTION WHEN EDITING
 * Trailing whitespace OR NEWLINE on the last line will cause
 * IllegalStateExceptions !!!
 *
 * Do not tag this file for translation.
 */
    response.setStatus(307);
    response.setHeader("Cache-Control","no-cache");
    response.setHeader("Location", "/home");
    // force commitment
    response.getOutputStream().close();
%>