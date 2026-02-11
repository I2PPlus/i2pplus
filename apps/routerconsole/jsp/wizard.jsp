<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb"%>
<jsp:useBean class="net.i2p.router.web.helpers.WizardHelper" id="wizhelper" scope="session"/>
<%
    // Note that for the helper we use a session scope, not a request scope, so that we can access the NDT test results.
    // The MLabHelper singleton will prevent multiple simultaneous tests, even across sessions.

    // Page constants with descriptive names
    final int PAGE_LANGUAGE = 1;
    final int PAGE_BANDWIDTH_OVERVIEW = 2;
    final int PAGE_BANDWIDTH_TEST = 3;
    final int PAGE_BANDWIDTH_CONFIG = 4;
    final int PAGE_BROWSER_SETUP = 5;
    final int PAGE_WELCOME = 6;
    final int LAST_PAGE = PAGE_WELCOME;

    // Other constants
    final int AJAX_POLL_INTERVAL = 75000; // 75 seconds
    final int MIN_SHARE_BANDWIDTH = 12; // KBps

    String pg = request.getParameter("page");
    int ipg;
    if (pg == null) {ipg = PAGE_LANGUAGE;}
    else {
        try {
            ipg = Integer.parseInt(pg);
            if (request.getParameter("prev") != null) {
                // previous button handling - skip bandwidth test pages when going back from browser setup
                if (ipg == PAGE_BROWSER_SETUP + 1) {ipg = PAGE_BANDWIDTH_TEST;}
                else {ipg -= 2;}
            }
            if (ipg <= 0 || ipg > LAST_PAGE) {ipg = PAGE_LANGUAGE;}
            else if (ipg == PAGE_BANDWIDTH_TEST && request.getParameter("skipbw") != null) {ipg++;} // skip bw test
        } catch (NumberFormatException nfe) {ipg = PAGE_LANGUAGE;}
    }

    // detect completion
    boolean done = request.getParameter("done") != null || request.getParameter("skip") != null;
    if (done) {
        // tell wizard helper we're done
        String i2pcontextId = request.getParameter("i2p.contextId");
        try {
            if (i2pcontextId == null) {
                i2pcontextId = (String) session.getAttribute("i2p.contextId");
            } else {
                session.setAttribute("i2p.contextId", i2pcontextId);
            }
        } catch (IllegalStateException ise) {
            // Session invalidated - continue with null contextId
        }
        wizhelper.setContextId(i2pcontextId);
        wizhelper.complete();
        response.setStatus(307); // redirect to /home
        response.setHeader("Cache-Control","no-cache");
        String req = request.getRequestURL().toString();
        int slash = req.indexOf("/welcome");
        if (slash >= 0) {
            req = new StringBuilder(req.substring(0, slash)).append("/home").toString();
        } else {
            req = "http://127.0.0.1:7657/home"; // shouldn't happen
        }
        response.setHeader("Location", req);
        response.getOutputStream().close(); // force commitment
        return;
    }
%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%=intl.title("New Install Wizard")%>
<%  wizhelper.setContextId(i2pcontextId); %>
</head>
<%  if (ipg == PAGE_BANDWIDTH_TEST) { %>
<body id=wizardpage>
<%  } else { %>
<body id=wizardpage class=bandwidthtester>
<%  } %>
<div id=sb_wrap class="" style=width:200px;float:left;margin-right:20px>
<div class=sb id=sidebar>
<div id=sb_logo style=height:36px;pointer-events:none>
<img src="<%=intl.getTheme(request.getHeader("User-Agent"))%>images/i2plogo.png" alt="<%=intl._t("I2P+ Router Console")%>" title="<%=intl._t("I2P+ Router Console")%>" width="200">
</div>
<hr>
<h3>Setup Wizard</h3>
<hr>
<form method=POST>
<input type=submit class=cancel name="skip" value="<%=intl._t("Skip Setup")%>">
</form>
</div>
</div>
<%  if (ipg >= PAGE_LANGUAGE && ipg <= PAGE_BROWSER_SETUP) { /* valid pages */%>
<h1>New Install Wizard <span id=pagecount style=float:right><%=ipg%>/<%=LAST_PAGE%></span></h1>
<%  } else { %>
<h1>Unknown Wizard page <span id=pagecount style=float:right><%=ipg%>/<%=LAST_PAGE%></span></h1>
<%  } %>
<div class=main id=setupwizard>
<div id=wizard>
<jsp:useBean class="net.i2p.router.web.helpers.WizardHandler" id="formhandler" scope="request"/>
<%
    // Bind the session-scope Helper to the request-scope Handler
    formhandler.setWizardHelper(wizhelper);
%>
<%@include file="formhandler.jsi"%>
<form method=POST>
<input type=hidden name=nonce value="<%=pageNonce%>">
<input type=hidden name=action value=blah >
<input type=hidden name="page" value="<%=(ipg + 1)%>">
<%
    if (ipg == PAGE_LANGUAGE) {
        // language selection
%>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigUIHelper" id="uihelper" scope="request"/>
<jsp:setProperty name="uihelper" property="contextId" value="<%=i2pcontextId%>"/>
<%-- needed for CSS: --%><div id=config_ui>
<%-- needed for lang setting in head.jsi: --%><input type=hidden name=consoleNonce value="<%=net.i2p.router.web.CSSHelper.getNonce()%>">
<p class=infohelp id=flags>
<%
    StringBuilder langHelpText = new StringBuilder();
    langHelpText.append(intl._t("Select the language to be used for the router console and web applications ("))
               .append("<a href=/webmail target=_blank rel=noreferrer>webmail</a>, ")
               .append("<a href=/torrents target=_blank rel=noreferrer>torrents</a>, ")
               .append("<a href=/i2ptunnelmgr target=_blank rel=noreferrer>tunnel manager</a> etc). ")
               .append("If you wish to change the language in future, change the router console theme, or configure a password to access the console, you may do so on the ")
               .append("<a href=/configui target=_blank rel=noreferrer>User Interface configuration page</a>.");
    out.print(langHelpText.toString());
%>
</p>
<h3 id=langheading><%=uihelper._t("Router Console &amp; WebApps Display Language")%></h3>
<div id=langsettings>
<jsp:getProperty name="uihelper" property="langSettings"/>
</div></div>
<%
    } else if (ipg == PAGE_BANDWIDTH_OVERVIEW) {
        // Overview of bandwidth test
%>
<p class=infohelp id=bandwidth>
<%
    StringBuilder bandwidthHelpText = new StringBuilder();
    bandwidthHelpText.append(intl._t("Your I2P+ router by default contributes to the network by routing internal, end-to-end encrypted traffic for other routers, known as <i>participation</i>. Bandwidth participation improves the anonymity level of all users on the network and maximizes your download speed."))
                    .append("&nbsp;<wbr>")
                    .append(intl._t("For the best experience, it's recommended to configure your bandwidth settings to match the values provided by your ISP. If you are unsure of your bandwidth, you can run a test to determine your download and upload speeds."));
    out.print(bandwidthHelpText.toString());
%>
</p>
<h3><%=intl._t("Bandwidth Test")%></h3>
<p>
<%
    StringBuilder testInfoText = new StringBuilder();
    testInfoText.append(intl._t("I2P+ will now test your internet connection to identify the optimal speed settings."))
               .append("&nbsp;<wbr>")
               .append(intl._t("This is done using the third-party M-Lab service."));
    out.print(testInfoText.toString());
%>
</p>
<p>
<%
    StringBuilder privacyText = new StringBuilder();
    privacyText.append(intl._t("Please review the M-Lab privacy policies linked below."))
              .append("&nbsp;<wbr>")
              .append(intl._t("If you do not wish to run the M-Lab bandwidth test, you may skip it by clicking the button below."));
    out.print(privacyText.toString());
%>
</p>
<p>
<%
    StringBuilder privacyLinks = new StringBuilder();
    privacyLinks.append("<a href=\"https://www.measurementlab.net/privacy/\" target=_blank rel=noreferrer>")
               .append(intl._t("M-Lab Privacy Policy"))
               .append("</a>")
               .append("<br><a href=\"https://github.com/m-lab/mlab-ns/blob/master/MLAB-NS_PRIVACY_POLICY.md\" target=_blank rel=noreferrer>")
               .append(intl._t("M-Lab Name Server Privacy Policy"))
               .append("</a>");
    out.print(privacyLinks.toString());
%>
</p>
<%
    } else if (ipg == PAGE_BANDWIDTH_TEST) {
        // Bandwidth test in progress (w/ AJAX)
%>
<p class=infohelp id=bandwidth><%=intl._t("The bandwidth test is now running and will take around 60 seconds to complete, after which you should be forwarded to the results page.")%></p>
<div id=bandwidthTestRunning><%=intl._t("Bandwidth Test in progress")%>...</div>
<noscript>
<div id=xhrwizard>
<p class=infohelp id=bandwidth>
<%=intl._t("Javascript is disabled - wait 60 seconds for the bandwidth test to complete and then click Next")%>
</p>
</div>
</noscript>
<div id=xhrwizard2>
</div>
<%
    } else if (ipg == PAGE_BANDWIDTH_CONFIG) {
        // Bandwidth test results
        // and/or manual bw entry?
%>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigNetHelper" id="nethelper" scope="request"/>
<jsp:setProperty name="nethelper" property="contextId" value="<%=i2pcontextId%>"/>
<%
        if (request.getParameter("skipbw") == null) {
            // don't display this if we skipped the test
%>
<h3><%=intl._t("Bandwidth Test Results")%></h3>
<table class=configtable>
<%
            boolean ndtComplete = wizhelper.isNDTComplete();
            boolean ndtRunning = wizhelper.isNDTRunning();
            
            if (!ndtComplete) {
%>
<tr><td width="20%" nowrap><b><%=intl._t("Test running?")%></b></td><td>
<span class=<%=ndtRunning ? "yes" : "no"%>><%=ndtRunning%></span></td></tr>
<%
            }
%>
<tr><td nowrap><b><%=intl._t("Test complete?")%></b></td><td>
<span class=<%=ndtComplete ? "yes" : "no"%>><%=ndtComplete%></span></td></tr>
<tr><td nowrap><b><%=intl._t("Test server location")%></b></td><td><%=wizhelper.getServerLocation()%></td></tr>
<!--
<tr><td nowrap><b><%=intl._t("Completion status")%></b></td><td><%=wizhelper.getCompletionStatus()%></td></tr>
<tr><td nowrap><b><%=intl._t("Details")%></b></td><td><%=wizhelper.getDetailStatus()%></td></tr>
-->
<%
            if (wizhelper.isNDTSuccessful()) {
                // don't display this if test failed
%>
<tr><td nowrap><b><%=intl._t("Downstream Bandwidth")%></b></td><td><%=net.i2p.data.DataHelper.formatSize2Decimal(wizhelper.getDownBandwidth())%>Bps</td></tr>
<tr><td nowrap><b><%=intl._t("Upstream Bandwidth")%></b></td><td><%=net.i2p.data.DataHelper.formatSize2Decimal(wizhelper.getUpBandwidth())%>Bps</td></tr>
<tr><td nowrap><b><%=intl._t("Share of Bandwidth for I2P+")%></b></td><td><%=Math.round(net.i2p.router.web.helpers.WizardHelper.BW_SCALE * 100)%>%</td></tr>
<%
            } // successful
%>
</table>
<%
        } // skipbw
%>
<h3><%=intl._t("Bandwidth Configuration")%></h3>
<table id=bandwidthconfig class=configtable>
<tr>
<td class=infohelp colspan=2>
<%
    StringBuilder bandwidthConfigHelp = new StringBuilder();
    bandwidthConfigHelp.append(intl._t("I2P will work best if you configure your rates to match the speed of your internet connection.").replace("I2P", "I2P+"))
                      .append("&nbsp;<wbr>")
                      .append(intl._t("Note: Your contribution to the network (network share) is determined by the allocation of upstream bandwidth (upload speed)."))
                      .append("&nbsp;<wbr>")
                      .append(intl._t("The maximum data transfer values indicate the theoretical maximum, and in practice will normally be much lower."));
    out.print(bandwidthConfigHelp.toString());
%>
</td>
</tr>
<%-- display burst, set standard, handler will fix up --%>
<tr>
<td>
<div class="optionsingle bw_in">
<span class=bw_title><%=intl._t("Download Speed")%></span>
<input name="inboundrate" type=text size=5 maxlength=5 value="<jsp:getProperty name="nethelper" property="inboundBurstRate"/>">
<%=intl._t("KBps In")%>
</div>
</td>
<td>
<jsp:getProperty name="nethelper" property="inboundBurstRateBits"/>
</td>
<%--
<!-- let's keep this simple...
bursting up to
<input name="inboundburstrate" type=text size=5 value="<jsp:getProperty name="nethelper" property="inboundBurstRate"/>"> KBps for
<jsp:getProperty name="nethelper" property="inboundBurstFactorBox"/><br>
-->
--%>
</tr>
<tr>
<%-- display burst, set standard, handler will fix up --%>
<td>
<div class="optionsingle bw_out">
<span class=bw_title><%=intl._t("Upload Speed")%></span>
<input name="outboundrate" type=text size=5 maxlength=5 value="<jsp:getProperty name="nethelper" property="outboundBurstRate"/>">
<%=intl._t("KBps Out")%>
</div>
</td>
<td>
<jsp:getProperty name="nethelper" property="outboundBurstRateBits"/>
</td>
<%--
<!-- let's keep this simple...
 bursting up to
<input name="outboundburstrate" type=text size=2 value="<jsp:getProperty name="nethelper" property="outboundBurstRate"/>"> KBps for
<jsp:getProperty name="nethelper" property="outboundBurstFactorBox"/><br>
<i>KBps = kilobytes per second = 1024 bytes per second = 8192 bits per second.<br>
A negative rate sets the default.</i><br>
-->
--%>
</tr>
<tr>
<td>
<div class="optionsingle bw_share">
<span class=bw_title><%=intl._t("Network Share")%></span><jsp:getProperty name="nethelper" property="sharePercentageBox"/>
<%=intl._t("Share")%>
</div>
</td>
<td>
<jsp:getProperty name="nethelper" property="shareRateBits"/>
</td>
</tr>
<tr>
<td class=infohelp colspan=2>
<%
    int share = Math.round(nethelper.getShareBandwidth() * 1.024f);
    StringBuilder shareMessage = new StringBuilder();

    if (share < MIN_SHARE_BANDWIDTH) {
        shareMessage.append("<b>")
                   .append(intl._t("NOTE"))
                   .append("</b>: ")
                   .append(intl._t("You have configured I2P+ to share only {0} KBps.", "<b id=\"sharebps\">" + share + "</b>&nbsp;<wbr>"))
                   .append("\n")
                   .append(intl._t("I2P+ requires at least 12KBps to enable sharing. "))
                   .append(intl._t("Please enable sharing (participating in tunnels) by configuring more bandwidth. "))
                   .append(intl._t("It improves your anonymity by creating cover traffic, and helps the network. "));
    } else {
        shareMessage.append(intl._t("You have configured I2P+ to share {0} KBps.", "<b id=\"sharebps\">" + share + "</b>&nbsp;<wbr>"))
                   .append("\n")
                   .append(intl._t("The higher the share bandwidth the more you improve your anonymity and help the network."));
    }
    out.print(shareMessage.toString());
%>
</td>
</tr>
</table>
<%
    } else if (ipg == PAGE_BROWSER_SETUP) {
        // Browser setup
%>
<p class=infohelp id=webbrowser>
<%
    StringBuilder browserProxyHelp = new StringBuilder();
    browserProxyHelp.append(intl._t("I2P+ requires a web browser configured to use the resident HTTP proxy in order to browse websites on the I2P network."));
    out.print(browserProxyHelp.toString());
%>
</p>
<h3><%=intl._t("Browser Setup")%></h3>
<p>
<%
    StringBuilder browserSetupHelp = new StringBuilder();
    browserSetupHelp.append(intl._t("In order to access websites hosted on the I2P network, and optionally use the default outproxy to connect to websites on clearnet, you will need to configure your browser to use the I2P+ <b>HTTP</b> proxy, by default running on <code>127.0.0.1 port 4444</code>. "))
                   .append("For more help, see ")
                   .append("<a href=https://geti2p.net/htproxyports target=_blank rel=noreferrer>the configuration guide</a>, ")
                   .append("or ")
                   .append("<a href=/help/configuration#browserconfig>the mini-tutorial for Firefox</a>.");
    out.print(browserSetupHelp.toString());
%>
</p>
<%
    } else if (ipg == LAST_PAGE) {
        // Done
%>
<h3><%=intl._t("Welcome to I2P+!")%></h3>
<p class=infohelp>
<%=intl._t("When you start I2P+, it may take a few minutes to bootstrap (integrate) your router into the network and find additional peers, so please be patient.")%>
</p>
<p>
<%=intl._t("Once green stars are indicated next to your Service Tunnels, there is a wide variety of things you can do with I2P+.")%>
</p>
<%
    } else {
%>
<p><%=intl._t("Unknown Wizard page")%></p>
<%
    }
%>
<div class=wizardbuttons>
<table class=configtable>
<tr>
<td class=optionsave>
<%
    if (ipg != PAGE_LANGUAGE) {
%>
<input type=submit class=back name="prev" value="<%=intl._t("Previous")%>">
<%
    }
    if (ipg != LAST_PAGE) {
%>
<%
        if (ipg == PAGE_BANDWIDTH_OVERVIEW) {
%>
<input type=submit class=cancel name="skipbw" value="<%=intl._t("Skip Bandwidth Test")%>">
<%
        } else if (ipg == PAGE_BANDWIDTH_TEST) {
%>
<input type=submit class=cancel name="cancelbw" value="<%=intl._t("Cancel Bandwidth Test")%>">
<%
        }
%>
<input type=submit class=go name="next" value="<%=intl._t("Next")%>">
<%
    } else {
%>
<input type=submit class=accept name="done" value="<%=intl._t("Finished")%>">
<%
    }
%>
</td>
</tr>
</table>
</div>
</form>
</div>
</div>
<%
    if (ipg == PAGE_BANDWIDTH_TEST) {
%>
<script nonce=<%=cspNonce%>>
  let bwRefresh = setInterval(function() {
    var xhrwizard = new XMLHttpRequest();
    xhrwizard.open("GET", "/wizard?page=" + <%=PAGE_BANDWIDTH_CONFIG%>, true);
    xhrwizard.responseType = "text";
    xhrwizard.onload = function () {
      if (xhrwizard.status === 200) {
        document.getElementById("wizardpage").innerHTML = xhrwizard.responseText;
        clearInterval(bwRefresh);
      }
    }
    xhrwizard.onerror = function() {
      console.error("Bandwidth test status check failed");
    }
    xhrwizard.send();
  }, <%=AJAX_POLL_INTERVAL%>);
</script>
<%
    }
%>
</body>
</html>