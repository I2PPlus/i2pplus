<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb"%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%=intl.title("router auto-tuning")%>
</head>
<body id=autotuning>
<%@include file="sidebar.jsi"%><h1 class=sched><%=intl._t("Auto-Tuning")%></h1>
<jsp:useBean class="net.i2p.router.web.TuningFormHandler" id="formhandler" scope="request"/>
<%@include file="formhandler.jsi"%>
<jsp:useBean class="net.i2p.router.web.helpers.TuningHelper" id="tuninghelper" scope="request"/>
<jsp:setProperty name="tuninghelper" property="contextId" value="<%=i2pcontextId%>"/>
<jsp:setProperty name="tuninghelper" property="nonce" value="<%=pageNonce%>"/>
<% tuninghelper.storeWriter(out); %>
<iframe name=processForm id=processForm hidden></iframe>
<jsp:getProperty name="tuninghelper" property="tuning"/>
<script src=/js/toggleElements.js></script>
<script src=/js/tuning.js type=module></script>
</body>
</html>
