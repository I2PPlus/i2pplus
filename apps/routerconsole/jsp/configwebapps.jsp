<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb" %>
<!DOCTYPE HTML>
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null) {lang = ctx.getProperty("routerconsole.lang");}
%>
<%@include file="head.jsi" %>
<%=intl.title("config webapps")%>
<style>button span.hide{display:none}input.default{width:1px;height:1px;visibility:hidden}</style>
</head>
<body>
<script nonce=<%=cspNonce%>>progressx.show(theme);progressx.progress(0.1);</script>
<%@include file="sidebar.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigClientsHelper" id="clientshelper" scope="request" />
<jsp:setProperty name="clientshelper" property="contextId" value="<%=i2pcontextId%>" />
<jsp:setProperty name="clientshelper" property="edit" value="<%=request.getParameter(\"edit\")%>" />
<h1 class=conf><%=intl._t("Web Apps")%></h1>
<div class=main id=config_webapps>
<%@include file="confignav.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigClientsHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
<div class=configure>
<h3 id=webappconfiguration><%=intl._t("WebApp Configuration")%>&nbsp;
<span class=h3navlinks>
<a href=configclients title="<%=intl._t("Client Configuration")%>"><%=intl._t("Clients")%></a>&nbsp;
<a href=configplugins title="<%=intl._t("Plugin Configuration")%>"><%=intl._t("Plugins")%></a>
</span>
</h3>
<p class=infohelp id=webappconfigtext>
<%=intl._t("The Java web applications listed below are started by the webConsole client and run in the same JVM as the router. They are usually web applications accessible through the router console. They may be complete applications (e.g. i2psnark), front-ends to another client or application which must be separately enabled (e.g. susidns, i2ptunnel), or have no web interface at all (e.g. addressbook).").replace(" They are usually web applications accessible through the router console. They may be complete applications (e.g. i2psnark), front-ends to another client or application which must be separately enabled (e.g. susidns, i2ptunnel), or have no web interface at all (e.g. addressbook).", "")%>
</p>
<iframe name=processForm id=processForm hidden></iframe>
<form id=form_webapps action="configwebapps" method=POST target=processForm>
<input type=hidden name="nonce" value="<%=pageNonce%>" >
<jsp:getProperty name="clientshelper" property="form2" />
<div class=formaction id=webappconfigactions>
<input type=submit class=cancel name=foo value="<%=intl._t("Cancel")%>" />
<input type=submit name=action class=accept value="<%=intl._t("Save WebApp Configuration")%>" />
</div>
</form>
</div>
</div>
<script src=/js/refreshOnClick.js></script>
<script nonce=<%=cspNonce%>>
  const webappsForm = document.getElementById("form_webapps");
  const processForm = document.getElementById("processForm");
  webappsForm.addEventListener("submit", progressx.show);
  processForm.addEventListener("load", progressx.hide);
  refreshOnClick("#webappconfig button.control", ".main");
</script>
</body>
</html>