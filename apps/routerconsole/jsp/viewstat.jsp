<%@ page import="net.i2p.I2PAppContext, net.i2p.router.web.GraphGenerator, net.i2p.router.web.GraphGroups, net.i2p.stat.Rate, net.i2p.stat.RateStat, net.i2p.data.DataHelper, java.util.Set, java.util.HashSet, java.util.Collections, java.util.LinkedHashSet, net.i2p.router.web.GraphListener" buffer="64kb" trimDirectiveWhitespaces="true" %>
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
    if (stat == null || stat.isEmpty()
            || stat.contains("\n") || stat.contains("\r")
            || stat.contains("\0") || stat.contains("/")
            || stat.contains("\\") || stat.contains("\"")) {
        response.sendError(403, "Invalid stat parameter"); return;
    }

    boolean fakeBw = "bw.combined".equals(stat);
    // A group id is not a stat, so resolve it against the registry before looking for a
    // Rate. Only groups the user has actually switched on may be drawn.
    boolean isGroup = false;
    if (!fakeBw && GraphGroups.groupIds().contains(stat)) {
        if (!ctx.getBooleanProperty(GraphGroups.PROP_COMBINE)) {
            response.sendError(403, "Graph combining is not enabled"); return;
        }
        isGroup = true;
    }
    // The enabled set comes from the generator, which only holds listeners for stats the
    // user switched on. Deriving it here rather than trusting a request parameter means the
    // graph page and this endpoint can never disagree about what is enabled.
    Set<String> enabledStats = new HashSet<>();
    for (GraphListener lsnr : graphGen.getListeners()) {
        enabledStats.add(lsnr.getRate().getRateStat().getName());
    }
    RateStat rateStat = isGroup ? null : ctx.statManager().getRate(stat);
    Rate rate = null;

    int width = -1, height = -1, periodCount = -1, end = 0;
    try { width = Integer.parseInt(request.getParameter("width")); } catch (Exception ignored) {}
    try { height = Integer.parseInt(request.getParameter("height")); } catch (Exception ignored) {}
    try { periodCount = Integer.parseInt(request.getParameter("periodCount")); } catch (Exception ignored) {}
    try { end = Integer.parseInt(request.getParameter("end")); } catch (Exception ignored) {}

    long period = -1;
    if (fakeBw || isGroup) { period = 60000L; }
    else {
        try { period = Long.parseLong(request.getParameter("period")); }
        catch (Exception ignored) { /* ignored */ }
    }

    boolean hideLegend = Boolean.parseBoolean(request.getParameter("hideLegend"));
    boolean hideRestarts = Boolean.parseBoolean(request.getParameter("hideRestarts"));
    boolean hideGrid = Boolean.parseBoolean(request.getParameter("hideGrid"));
    boolean hideTitle = Boolean.parseBoolean(request.getParameter("hideTitle"));
    boolean showEvents = Boolean.parseBoolean(request.getParameter("showEvents"));
    boolean showCredit = Boolean.parseBoolean(request.getParameter("showCredit"));

    String format = request.getParameter("format");
    boolean rendered = false;

    if (fakeBw || isGroup || (rateStat != null && period > 0)) {
        if (!fakeBw && !isGroup) rate = rateStat.getRate(period);
        if (fakeBw || isGroup || rate != null) {
            response.setHeader("X-Content-Type-Options", "nosniff");
            java.io.OutputStream stream = response.getOutputStream();
            try {
                if ("xml".equalsIgnoreCase(format) && !fakeBw && !isGroup) {
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
                    response.setHeader("X-Content-Type-Options", "nosniff");
                    if (isGroup) {
                        rendered = graphGen.renderGroupedGraph(stream, stat, enabledStats, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount, end, showCredit, !hideRestarts);
                    } else {
                        rendered = fakeBw
                            ? graphGen.renderCombinedGraph(stream, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount, end, showCredit, !hideRestarts)
                            : graphGen.renderGraph(rate, stream, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount, end, showCredit, !hideRestarts);
                    }
                }
            } finally {
                if (rendered) stream.close();
            }
        }
    }

    if (!rendered) {
        String msg = "The stat '" + DataHelper.stripHTML(stat) + "' is not available - enable it for graphing.";
        response.sendError(400, msg);
    }
%>