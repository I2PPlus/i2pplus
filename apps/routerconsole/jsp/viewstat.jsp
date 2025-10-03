<%@ page import="net.i2p.I2PAppContext, net.i2p.router.web.GraphGenerator, net.i2p.stat.Rate, net.i2p.stat.RateStat, net.i2p.data.DataHelper" buffer="64kb" trimDirectiveWhitespaces="true" %>
<%
    /*
    * USE CAUTION WHEN EDITING
    * Trailing whitespace OR NEWLINE on the last line will cause IllegalStateExceptions !!!
    *
    * Do not tag this file for translation.
    */
    I2PAppContext ctx = I2PAppContext.getGlobalContext();
    GraphGenerator graphGen = GraphGenerator.instance(ctx);
    if (graphGen == null) { response.sendError(403, "Graphs disabled"); return; }

    String stat = request.getParameter("stat");
    if (stat == null || stat.contains("\n") || stat.contains("\r")) { response.sendError(403, "Invalid stat parameter"); return; }

    boolean fakeBw = "bw.combined".equals(stat);
    RateStat rateStat = ctx.statManager().getRate(stat);
    Rate rate = null;

    int width = -1, height = -1, periodCount = -1, end = 0;
    try { width = Integer.parseInt(request.getParameter("width")); } catch (Exception ignored) {}
    try { height = Integer.parseInt(request.getParameter("height")); } catch (Exception ignored) {}
    try { periodCount = Integer.parseInt(request.getParameter("periodCount")); } catch (Exception ignored) {}
    try { end = Integer.parseInt(request.getParameter("end")); } catch (Exception ignored) {}

    long period = -1;
    if (fakeBw) { period = 60000L; }
    else {
        try { period = Long.parseLong(request.getParameter("period")); }
        catch (Exception ignored) {}
    }

    boolean hideLegend = Boolean.parseBoolean(request.getParameter("hideLegend"));
    boolean hideGrid = Boolean.parseBoolean(request.getParameter("hideGrid"));
    boolean hideTitle = Boolean.parseBoolean(request.getParameter("hideTitle"));
    boolean showEvents = Boolean.parseBoolean(request.getParameter("showEvents"));
    boolean showCredit = Boolean.parseBoolean(request.getParameter("showCredit"));

    String format = request.getParameter("format");
    boolean rendered = false;

    if (fakeBw || (rateStat != null && period > 0)) {
        if (!fakeBw) rate = rateStat.getRate(period);
        if (fakeBw || rate != null) {
            response.setHeader("X-Content-Type-Options", "nosniff");
            java.io.OutputStream stream = response.getOutputStream();
            try {
                if ("xml".equalsIgnoreCase(format) && !fakeBw) {
                    response.setContentType("text/xml; charset=utf-8");
                    response.setHeader("Content-Disposition", "attachment; filename=\"" + stat + ".xml\"");
                    rendered = graphGen.getXML(rate, stream);
                } else {
                    response.setContentType("image/svg+xml");
                    response.setCharacterEncoding("UTF-8");
                    response.setHeader("Content-Disposition", "inline; filename=\"" + stat + ".svg\"");
                    response.addHeader("Cache-Control", "private, no-cache, max-age=14400");
                    response.setHeader("Accept-Ranges", "none");
                    response.setHeader("Connection", "Close");
                    rendered = fakeBw
                        ? graphGen.renderRatePng(stream, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount, end, showCredit)
                        : graphGen.renderPng(rate, stream, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount, end, showCredit);
                }
            } finally {
                if (rendered) stream.close();
            }
        }
    }

    if (!rendered) {
        String msg = (stat != null)
            ? "The stat '" + DataHelper.stripHTML(stat) + "' is not available - enable it for graphing."
            : "No stat specified";
        response.sendError(400, msg);
    }
%>