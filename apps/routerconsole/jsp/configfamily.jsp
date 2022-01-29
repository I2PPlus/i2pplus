<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML>
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null)
        lang = ctx.getProperty("routerconsole.lang");
%>
<html lang="<%=lang%>">
<head>
<%@include file="css.jsi" %>
<%=intl.title("config router family")%>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigFamilyHelper" id="familyHelper" scope="request" />
<jsp:setProperty name="familyHelper" property="contextId" value="<%=i2pcontextId%>" />
<h1 class="conf"><%=intl._t("Router Family")%></h1>
<div class="main" id="config_family">
<%@include file="confignav.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigFamilyHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
<% if (!familyHelper.isAdvanced()) { %>
<p class="infohelp"><%=intl._t("Routers in the same family share a family key.")%>&nbsp;
<%=intl._t("To join an existing family, import the private key you exported from a router in the family.")%>&nbsp;
<%=intl._t("To start a new family, enter a family name.")%>
</p>
<% }  // !isAdvanced %>
<%
   String family = familyHelper.getFamily();
   if (family.length() <= 0) {
       // no family yet
%>
<form action="" method="POST" enctype="multipart/form-data" accept-charset="UTF-8">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<table class="configtable" id="joinfamily">
<tr><th colspan="2"><%=intl._t("Join Existing Router Family")%></th></tr>
<tr><td colspan="2" class="infohelp joinfamily"><%=intl._t("Import the secret family key that you exported from an existing router in the family.")%></td></tr>
<tr>
<td colspan="2"><b class="suboption"><%=intl._t("Select secret key file")%></b><br>
<div class="optionsingle">
<table>
<tr>
<td>
<input name="file" type="file" value="" />
</td>
<td>
<input type="submit" name="action" class="download" value="<%=intl._t("Join Family")%>" />
</td>
</tr>
</table>
</div>
</td>
</tr>
</table>
</form>
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<table class="configtable" id="newfamily">
<tr><th colspan="2"><%=intl._t("Create New Router Family")%></th></tr>
<tr><td colspan="2" class="infohelp newfamily"><%=intl._t("You may create a new router family here. Once you have created the family, you may export the family key file and share with other routers in your nominated family.")%>&nbsp;
<b><%=intl._t("Note: To avoid deanonymization, do not name your family with personally-attributable information e.g. your IRC nickname; your family name is associated with your router's IP address.")%></b></td></tr>
<tr>
<td colspan="2"><b class="suboption"><%=intl._t("Enter new family name")%></b><br>
<div class="optionsingle"  id="createNewFamily">
<table>
<tr>
<td>
<input name="family" type="text" size="30" value="" />
</td>
<td>
<input type="submit" name="action" class="accept" value="<%=intl._t("Create Family")%>" />
</td>
</tr>
</table>
</div>
</td>
</tr>
</table>
</form>
<%
   } else {
       // family is configured
       String keypw = familyHelper.getKeyPW();
       if (keypw.length() > 0) {
           // family is active
%>
<form action="/exportfamily" method="GET">
<table class="configtable" id="exportfamily">
<tr><th><%=intl._t("Export Family Key")%></th></tr>
<tr><td><%=intl._t("Export the secret family key to be imported into other routers you control.")%></td></tr>
<tr>
<td class="optionsave">
<input type="submit" name="action" class="go" value="<%=intl._t("Export Family Key")%>" />
</td>
</tr>
</table>
</form>
<%
       } else {
           // family is not active
%>
<p class="infohelp needrestart"><b><%=intl._t("Restart required to activate family {0}.", '"' + family + '"')%>&nbsp;
<%=intl._t("After restarting, you may export the family key.")%></b></p>
<%
       }
%>
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<table class="configtable" id="leavefamily">
<tr><th colspan="2"><%=intl._t("Leave Router Family")%></th></tr>
<tr><td><%=intl._t("No longer be a member of the family {0}.", '"' + family + '"')%></td>
<td class="optionsave">
<input type="submit" name="action" class="delete" value="<%=intl._t("Leave Family")%>" />
</td>
</tr>
</table>
</form>
<%
   }
%>
</div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>