<%@ page import="net.i2p.I2PAppContext, net.i2p.router.web.GraphGenerator, net.i2p.stat.Rate, net.i2p.stat.RateStat, net.i2p.data.DataHelper, net.i2p.util.Log" buffer="64kb" trimDirectiveWhitespaces="true" %>
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

    Log log = ctx.logManager().getLog(GraphGenerator.class);

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
        catch (Exception ignored) { /* ignored */ }
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
                    if (log.shouldDebug()) {log.debug("Rendering " + (fakeBw ? "combined" : "single") + " graph: stat=" + stat + " w=" + width + " h=" + height + " pc=" + periodCount + " p=" + period + " end=" + end);}
                    rendered = fakeBw
                        ? graphGen.renderRateSvg(stream, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount, end, showCredit)
                        : graphGen.renderSvg(rate, stream, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount, end, showCredit);
                    if (log.shouldDebug()) {log.debug("Render complete: stat=" + stat + " rendered=" + rendered);}
                }
            } catch (Exception e) {
                log.error("Error rendering graph: stat=" + stat + " w=" + width + " h=" + height + " pc=" + periodCount + " p=" + period, e);
            } finally {
                if (rendered) stream.close();
            }
        } else {
            if (log.shouldWarn()) {log.warn("No rate for period: stat=" + stat + " period=" + period + " rateStat=" + rateStat);}
        }
    } else {
        if (log.shouldWarn()) {log.warn("Graph not available: stat=" + stat + " rateStat=" + rateStat + " fakeBw=" + fakeBw + " period=" + period + " w=" + width + " h=" + height + " pc=" + periodCount);}
    }

    if (!rendered) {
        String msg = "The stat '" + DataHelper.stripHTML(stat) + "' is not available - enable it for graphing.";
        if (log.shouldWarn()) {log.warn("Graph failed: " + msg);}
        response.sendError(400, msg);
    }
%>