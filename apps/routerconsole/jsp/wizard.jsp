<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<jsp:useBean class="net.i2p.router.web.helpers.WizardHelper" id="wizhelper" scope="session" />
<%
    // note that for the helper we use a session scope, not a request scope,
    // so that we can access the NDT test results.
    // The MLabHelper singleton will prevent multiple simultaneous tests, even across sessions.

    // page ID
    final int LAST_PAGE = 5;
    String pg = request.getParameter("page");
    int ipg;
    if (pg == null) {
        ipg = 1;
    } else {
        try {
            ipg = Integer.parseInt(pg);
            if (request.getParameter("prev") != null) {
                // previous button handling
                if (ipg == 6)
                    ipg = 3;
                else
                    ipg -= 2;
            }
            if (ipg <= 0 || ipg > LAST_PAGE) {
                ipg = 1;
            } else if (ipg == 3 && request.getParameter("skipbw") != null) {
                ipg++;  // skip bw test
            }
        } catch (NumberFormatException nfe) {
            ipg = 1;
        }
    }

    // detect completion
    boolean done = request.getParameter("done") != null || request.getParameter("skip") != null;
    if (done) {
        // tell wizard helper we're done
        String i2pcontextId = request.getParameter("i2p.contextId");
        try {
            if (i2pcontextId != null) {
                session.setAttribute("i2p.contextId", i2pcontextId);
            } else {
                i2pcontextId = (String) session.getAttribute("i2p.contextId");
            }
        } catch (IllegalStateException ise) {}
        wizhelper.setContextId(i2pcontextId);
        wizhelper.complete();

        // redirect to /home
        response.setStatus(307);
        response.setHeader("Cache-Control","no-cache");
        String req = request.getRequestURL().toString();
        int slash = req.indexOf("/welcome");
        if (slash >= 0)
            req = req.substring(0, slash) + "/home";
        else // shouldn't happen
            req = "http://127.0.0.1:7657/home";
        response.setHeader("Location", req);
        // force commitment
        response.getOutputStream().close();
        return;
    }
%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null)
        lang = ctx.getProperty("routerconsole.lang");
%>
<html lang="<%=lang%>">
<head>
<%@include file="css.jsi" %>
<%=intl.title("New Install Wizard")%>
<%
    wizhelper.setContextId(i2pcontextId);
%>
</head>
<%
    if (ipg == 3) {
%>
<body id="wizardpage">
<%
    } else {
%>
<body id="wizardpage">
<%
    }
%>
<div class="routersummaryouter" style="width: 200px; float: left; margin-right: 20px">
<div class="routersummary" id="sidebar">
<div id="sb_logo" style="height:36px;pointer-events:none">
<img src="<%=intl.getTheme(request.getHeader("User-Agent"))%>images/i2plogo.png" alt="<%=intl._t("I2P Router Console").replace("I2P", "I2P+")%>" title="<%=intl._t("I2P Router Console").replace("I2P", "I2P+")%>" width="200">
</div>
<hr>
<h3>Setup Wizard</h3>
<hr>
<form action="" method="POST">
<input type="submit" class="cancel" name="skip" value="<%=intl._t("Skip Setup")%>">
</form>
</div>
</div>
<%
    if (ipg > 0 && ipg < 5 || ipg == LAST_PAGE) {
        // language selection
%>
<h1>New Install Wizard <span id="pagecount" style="float: right"><%=ipg%>/<%=LAST_PAGE%></span></h1>
<%
    } else {
%>
<h1>Unknown Wizard page <span id="pagecount" style="float: right"><%=ipg%>/<%=LAST_PAGE%></span></h1>
<%
    }
%>
<div class="main" id="setupwizard">
<div id="wizard">
<jsp:useBean class="net.i2p.router.web.helpers.WizardHandler" id="formhandler" scope="request" />
<%
    // Bind the session-scope Helper to the request-scope Handler
    formhandler.setWizardHelper(wizhelper);
%>
<%@include file="formhandler.jsi" %>
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>">
<input type="hidden" name="action" value="blah" >
<input type="hidden" name="page" value="<%=(ipg + 1)%>">
<%
    if (ipg == 1) {
        // language selection
%>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigUIHelper" id="uihelper" scope="request" />
<jsp:setProperty name="uihelper" property="contextId" value="<%=i2pcontextId%>" />
<%-- needed for CSS: --%><div id="config_ui">
<%-- needed for lang setting in css.jsi: --%><input type="hidden" name="consoleNonce" value="<%=net.i2p.router.web.CSSHelper.getNonce()%>">
<p class="infohelp" id="flags">
Select the language to be used for the router console and web applications (<a href="/webmail" target="_blank" rel="noreferrer">webmail</a>, <a href="/torrents" target="_blank" rel="noreferrer">torrents</a>, <a href="/tunnelmgr" target="_blank" rel="noreferrer">tunnel manager</a> etc). If you wish to change the language in future, change the router console theme, or configure a password to access the console, you may do so on the <a href="/configui" target="_blank" rel="noreferrer">User Interface configuration page</a>.
</p>
<h3 id="langheading"><%=uihelper._t("Router Console &amp; WebApps Display Language")%></h3>
<div id="langsettings">
<jsp:getProperty name="uihelper" property="langSettings" />
</div></div>
<%
    } else if (ipg == 2) {
        // Overview of bandwidth test
%>
<p class="infohelp" id="bandwidth">
<%=intl._t("Your I2P+ router by default contributes to the network by routing internal, end-to-end encrypted traffic for other routers, known as <i>participation</i>. Bandwidth participation improves the anonymity level of all users on the network and maximizes your download speed.")%>&nbsp;<wbr><%=intl._t("For the best experience, it's recommended to configure your bandwidth settings to match the values provided by your ISP. If you are unsure of your bandwidth, you can run a test to determine your download and upload speeds.")%>
</p>
<h3><%=intl._t("Bandwidth Test")%></h3>
<p>
<%=intl._t("I2P will now test your internet connection to identify the optimal speed settings.").replace("I2P", "I2P+")%>&nbsp;<wbr>
<%=intl._t("This is done using the third-party M-Lab service.")%>
</p>
<p>
<%=intl._t("Please review the M-Lab privacy policies linked below.")%>&nbsp;<wbr>
<%=intl._t("If you do not wish to run the M-Lab bandwidth test, you may skip it by clicking the button below.")%>
</p>
<p>
<a href="https://www.measurementlab.net/privacy/" target="_blank" rel="noreferrer"><%=intl._t("M-Lab Privacy Policy")%></a>
<br><a href="https://github.com/m-lab/mlab-ns/blob/master/MLAB-NS_PRIVACY_POLICY.md" target="_blank" rel="noreferrer"><%=intl._t("M-Lab Name Server Privacy Policy")%></a>
</p>
<%
    } else if (ipg == 3) {
        // Bandwidth test in progress (w/ AJAX)
%>
<p class="infohelp" id="bandwidth">The bandwidth test is now running and will take around 60 seconds to complete, after which you should be forwarded to the results page.</p>
<div id="bandwidthTestRunning"><%=intl._t("Bandwidth Test in progress")%>...</div>
<noscript>
<div id="xhr">
<p class="infohelp" id="bandwidth">
<%=intl._t("Javascript is disabled - wait 60 seconds for the bandwidth test to complete and then click Next")%>
</p>
</div>
</noscript>
<div id="xhr2">
</div>
<%
    } else if (ipg == 4) {
        // Bandwidth test results
        // and/or manual bw entry?
%>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigNetHelper" id="nethelper" scope="request" />
<jsp:setProperty name="nethelper" property="contextId" value="<%=i2pcontextId%>" />
<%
        if (request.getParameter("skipbw") == null) {
            // don't display this if we skipped the test
%>
<h3><%=intl._t("Bandwidth Test Results")%></h3>
<table class="configtable">
<%
            if (!wizhelper.isNDTComplete()) {
%>
<tr><td width="20%" nowrap><b><%=intl._t("Test running?")%></b></td><td>
<%              if (wizhelper.isNDTRunning()) {
%>
<span class="yes">
<%              } else {
%>
<span class="no">
<%
                }
%>
<%=wizhelper.isNDTRunning()%></span></td></tr>
<%
            }
%>
<tr><td nowrap><b><%=intl._t("Test complete?")%></b></td><td>
<%          if (wizhelper.isNDTComplete()) {
%>
<span class="yes">
<%          } else {
%>
<span class="no">
<%
            }
%>
<%=wizhelper.isNDTComplete()%></span></td></tr>
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
<tr><td nowrap><b><%=intl._t("Share of Bandwidth for I2P").replace("I2P", "I2P+")%></b></td><td><%=Math.round(net.i2p.router.web.helpers.WizardHelper.BW_SCALE * 100)%>%</td></tr>
<%
            } // successful
%>
</table>
<%
        } // skipbw
%>
<h3><%=intl._t("Bandwidth Configuration")%></h3>
<table id="bandwidthconfig" class="configtable">
<tr>
<td class="infohelp" colspan="2">
<%=intl._t("I2P will work best if you configure your rates to match the speed of your internet connection.").replace("I2P", "I2P+")%>
&nbsp;<wbr><%=intl._t("Note: Your contribution to the network (network share) is determined by the allocation of upstream bandwidth (upload speed).")%>
&nbsp;<wbr><%=intl._t("The maximum data transfer values indicate the theoretical maximum, and in practice will normally be much lower.")%>
</td>
</tr>
<%-- display burst, set standard, handler will fix up --%>
<tr>
<td>
<div class="optionsingle bw_in">
<span class="bw_title"><%=intl._t("Download Speed")%></span>
<input style="text-align: right; width: 5em" name="inboundrate" type="text" size="5" maxlength="5" value="<jsp:getProperty name="nethelper" property="inboundBurstRate" />">
<%=intl._t("KBps In")%>
</div>
</td>
<td>
<jsp:getProperty name="nethelper" property="inboundBurstRateBits" />
</td>
<%--
<!-- let's keep this simple...
bursting up to
<input name="inboundburstrate" type="text" size="5" value="<jsp:getProperty name="nethelper" property="inboundBurstRate" />"> KBps for
<jsp:getProperty name="nethelper" property="inboundBurstFactorBox" /><br>
-->
--%>
</tr>
<tr>
<%-- display burst, set standard, handler will fix up --%>
<td>
<div class="optionsingle bw_out">
<span class="bw_title"><%=intl._t("Upload Speed")%></span>
<input style="text-align: right; width: 5em" name="outboundrate" type="text" size="5" maxlength="5" value="<jsp:getProperty name="nethelper" property="outboundBurstRate" />">
<%=intl._t("KBps Out")%>
</div>
</td>
<td>
<jsp:getProperty name="nethelper" property="outboundBurstRateBits" />
</td>
<%--
<!-- let's keep this simple...
 bursting up to
<input name="outboundburstrate" type="text" size="2" value="<jsp:getProperty name="nethelper" property="outboundBurstRate" />"> KBps for
<jsp:getProperty name="nethelper" property="outboundBurstFactorBox" /><br>
<i>KBps = kilobytes per second = 1024 bytes per second = 8192 bits per second.<br>
A negative rate sets the default.</i><br>
-->
--%>
</tr>
<tr>
<td>
<div class="optionsingle bw_share">
<span class="bw_title"><%=intl._t("Network Share")%></span><jsp:getProperty name="nethelper" property="sharePercentageBox" />
<%=intl._t("Share")%>
</div>
</td>
<td>
<jsp:getProperty name="nethelper" property="shareRateBits" />
</td>
</tr>
<tr>
<td class="infohelp" colspan="2">
<% int share = Math.round(nethelper.getShareBandwidth() * 1.024f);
    if (share < 12) {
        out.print("<b>");
        out.print(intl._t("NOTE"));
        out.print("</b>: ");
        out.print(intl._t("You have configured I2P to share only {0} KBps.", "<b id=\"sharebps\">" + share + "</b>&nbsp;<wbr>").replace("I2P", "I2P+"));
        out.print("\n");
        out.print(intl._t("I2P requires at least 12KBps to enable sharing. ").replace("I2P", "I2P+"));
        out.print(intl._t("Please enable sharing (participating in tunnels) by configuring more bandwidth. "));
        out.print(intl._t("It improves your anonymity by creating cover traffic, and helps the network. "));
    } else {
        out.print(intl._t("You have configured I2P to share {0} KBps.", "<b id=\"sharebps\">" + share + "</b>&nbsp;<wbr>").replace("I2P", "I2P+"));
        out.print("\n");
        out.print(intl._t("The higher the share bandwidth the more you improve your anonymity and help the network."));
    }
%>
</td>
</tr>
</table>
<%
    } else if (ipg == 5) {
        // Browser setup
%>
<p class="infohelp" id="webbrowser">I2P+ requires a web browser configured to use the resident HTTP proxy in order to browse websites on the I2P network.</p>
<h3><%=intl._t("Browser Setup")%></h3>
<p>In order to access websites hosted on the I2P network, and optionally use the default outproxy to connect to websites on clearnet, you will need to configure your browser to use the I2P+ <b>HTTP</b> proxy, by default running on <code>127.0.0.1 port 4444</code>. For more help, see <a href="https://geti2p.net/htproxyports" target="_blank" rel="noreferrer">the configuration guide</a>, or <a href="/help/configuration#browserconfig">the mini-tutorial for Firefox</a>.</p>
<%
    } else if (ipg == LAST_PAGE) {
        // Done
%>
<h3><%=intl._t("Welcome to I2P!").replace("I2P", "I2P+")%></h3>
<p class="infohelp">
<%=intl._t("When you start I2P, it may take a few minutes to bootstrap (integrate) your router into the network and find additional peers, so please be patient.").replace("I2P", "I2P+")%>
</p>
<p>
<%=intl._t("Once green stars are indicated next to your Local Tunnels, there is a wide variety of things you can do with I2P.").replace("I2P", "I2P+").replace("Local", "Service")%>
</p>
<%
    } else {
%>
<p>Unknown Wizard page</p>
<%
    }
%>
<div class="wizardbuttons">
<table class="configtable">
<tr>
<td class="optionsave">
<%
    if (ipg != 1) {
%>
<input type="submit" class="back" name="prev" value="<%=intl._t("Previous")%>">
<%
    }
    if (ipg != LAST_PAGE) {
%>
<%
        if (ipg == 2) {
%>
<input type="submit" class="cancel" name="skipbw" value="<%=intl._t("Skip Bandwidth Test")%>">
<%
        } else if (ipg == 3) {
%>
<input type="submit" class="cancel" name="cancelbw" value="<%=intl._t("Cancel Bandwidth Test")%>">
<%
        }
%>
<input type="submit" class="go" name="next" value="<%=intl._t("Next")%>">
<%
    } else {
%>
<input type="submit" class="accept" name="done" value="<%=intl._t("Finished")%>">
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
    if (ipg == 3) {
%>
<script nonce="<%=cspNonce%>" type="text/javascript">
  setInterval(function() {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', '/wizard?page=4&' + new Date().getTime(), true);
    xhr.responseType = "text";
    xhr.onreadystatechange = function () {
      if (xhr.readyState==4 && xhr.status==200) {
        document.getElementById("wizardpage").innerHTML = xhr.responseText;
      }
    }
    xhr.send();
    window.addEventListener("pageshow", progressx.hide());
  }, 75000);
  window.addEventListener("pageshow", progressx.hide());
</script>
<%
    }
%>
</body>
</html>