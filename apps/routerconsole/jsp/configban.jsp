<%@page contentType="text/html" pageEncoding="UTF-8" buffer="32kb"%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%=intl.title("config bans")%>
</head>
<body>
<%@include file="sidebar.jsi"%>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigBanHelper" id="banhelper" scope="request"/>
<jsp:setProperty name="banhelper" property="contextId" value="<%=i2pcontextId%>"/>
<h1 class=conf><%=intl._t("Ban Configuration")%></h1>
<div class=main id=config_bans>
<%@include file="confignav.jsi"%>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigBanHandler" id="formhandler" scope="request"/>
<%@include file="formhandler.jsi"%>
<form method=POST>
<input type=hidden name=nonce value="<%=pageNonce%>">
<input type=hidden name=action value=blah>
<h3 class=tabletitle><%=intl._t("Custom Router Ban Policies")%></h3>
<table id=banconfig class=configtable>
<tr><td><b class=suboption><%=intl._t("IP Blocklists")%></b><br>
<div class=optionlist>
<label title="<%=intl._t("Ban IP addresses from blocklist.txt. Disabling will purge all blocked IPs.")%>"><input type=checkbox class="optbox slider" name=enableBlocklist value=true <jsp:getProperty name="banhelper" property="blocklistChecked"/>><%=intl._t("Enable IP blocklist")%></label><br>
<label title="<%=intl._t("Ban Tor exit node IPs from blocklist_tor.txt. Disabling will purge all blocked IPs.")%>"><input type=checkbox class="optbox slider" name=enableTorBlocklist value=true <jsp:getProperty name="banhelper" property="torBlocklistChecked"/>><%=intl._t("Enable Tor exit node blocklist")%></label>
</div>
</td></tr>
<tr><td><b class=suboption><%=intl._t("Country Bans")%></b><br>
<div class=optionlist>
<label title="<%=intl._t("Ban routers from your own country until router restart")%>"><input type=checkbox class="optbox slider" name=enableBlockMyCountry value=true <jsp:getProperty name="banhelper" property="blockMyCountryChecked"/>><%=intl._t("Ban peers in my country")%></label><br>
<label title="<%=intl._t("Ban routers from specified countries until router restart")%>"><input type=checkbox class="optbox slider" name=enableCountryBan value=true <jsp:getProperty name="banhelper" property="countryBanChecked"/>><%=intl._t("Enable country-based bans")%></label><br>
<label id=ccodes><span class=nowrap><%=intl._t("Country codes")%>:</span> <input title="<%=intl._t("Add county codes, separated by spaces or commas e.g. cn,de")%>" name=customCountryCodes type=text size=30 value="<jsp:getProperty name="banhelper" property="customCountryCodes"/>"></label>
</div>
</td></tr>
<tr><td><b class=suboption><%=intl._t("Network Abuse")%></b><br>
<div class=optionlist>
<label title="<%=intl._t("Ban peers that send malformed packets")%>"><input type=checkbox class="optbox slider" name=enableBadPacketBan value=true <jsp:getProperty name="banhelper" property="badPacketBanChecked"/>><%=intl._t("Ban peers sending malformed packets")%></label><br>
<label title="<%=intl._t("Ban peers with corrupt connections during handshake")%>"><input type=checkbox class="optbox slider" name=enableCorruptConnectionBan value=true <jsp:getProperty name="banhelper" property="corruptConnectionBanChecked"/>><%=intl._t("Ban peers with corrupt connections")%></label><br>
<label title="<%=intl._t("Ban peers that rapidly change their ports")%>"><input type=checkbox class="optbox slider" name=enablePortHoppingBan value=true <jsp:getProperty name="banhelper" property="portHoppingBanChecked"/>><%=intl._t("Ban peers attempting port hopping")%></label><br>
<label title="<%=intl._t("Ban peers sending excessive unsolicited search replies")%>"><input type=checkbox class="optbox slider" name=enableDbSearchBan value=true <jsp:getProperty name="banhelper" property="dbSearchBanChecked"/>><%=intl._t("Ban peers sending fake/slow search replies")%></label>
</div>
<b class=suboption><%=intl._t("Abuse Thresholds")%></b><br>
<div class=optionlist>
<label><span class=nowrap><%=intl._t("Startup grace period")%>:</span> <input name=startupGrace type=number size=3 maxlength=3 value="<jsp:getProperty name="banhelper" property="startupGrace"/>"><%=intl._t("minutes")%></label><br>
<label><span class=nowrap><%=intl._t("Ban duration")%>:</span> <input name=badPacketDuration type=number size=5 maxlength=5 value="<jsp:getProperty name="banhelper" property="badPacketDuration"/>"><%=intl._t("minutes")%></label><br>
<label><span class=nowrap><%=intl._t("Max offenses")%>:</span> <input name=maxOffenses type=number size=3 maxlength=3 value="<jsp:getProperty name="banhelper" property="maxOffenses"/>"></label><br>
<label title="<%=intl._t("Period during which offenses are counted")%>"><span class=nowrap><%=intl._t("Offense window")%>:</span> <input name=offenseWindow type=number size=4 maxlength=4 value="<jsp:getProperty name="banhelper" property="offenseWindow"/>"><%=intl._t("minutes")%></label>
</div>
</td></tr>
<tr><td><b class=suboption><%=intl._t("Bans by Capability")%></b><br>
<div class=optionlist>
<label title="<%=intl._t("Ban XG tier routers - unlimited bandwidth but not hosting transit tunnels (probably botnet participant)")%>"><input type=checkbox class="optbox slider" name=enableXgBan value=true <jsp:getProperty name="banhelper" property="xgBanChecked"/>><%=intl._t("Ban XG routers")%></label><br>
<label title="<%=intl._t("Ban LU tier routers - low bandwidth and unreachable/firewalled")%>"><input type=checkbox class="optbox slider" name=enableLuBan value=true <jsp:getProperty name="banhelper" property="luBanChecked"/>><%=intl._t("Ban LU routers")%></label></br>
<label><span class=nowrap><%=intl._t("Additional Router Caps")%>:</span> <input title="<%=intl._t("Add caps groups, separated by spaces or commas e.g. LUf,PUG")%>" name=customCapabilityBans type=text size=30 value="<jsp:getProperty name="banhelper" property="customCapabilityBans"/>"></label>
</div>
</td></tr>
<tr><td class=optionsave>
<input style=float:left type=submit class=cancel name=action value="<%=intl._t("Clear all bans")%>">
<input type=submit class=cancel name=action value="<%=intl._t("Reset to defaults")%>">
<input type=submit class=accept name=save value="<%=intl._t("Save changes")%>">
</td></tr>
</table>
</form>
</div>
</body>
</html>