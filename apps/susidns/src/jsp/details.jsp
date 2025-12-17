<%
/*
 * This file is part of SusiDNS project for I2P
 * Copyright (C) 2005 <susi23@mail.i2p>
 * License: GPL2 or later
 */
%>
<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb" %>
<%@include file="headers.jsi"%>
<jsp:useBean id="base" class="i2p.susi.dns.BaseBean" scope="session"/>
<jsp:useBean id="book" class="i2p.susi.dns.NamingServiceBean" scope="session"/>
<jsp:useBean id="intl" class="i2p.susi.dns.Messages" scope="application"/>
<jsp:useBean id="version" class="i2p.susi.dns.VersionBean" scope="application"/>
<jsp:setProperty name="book" property="*"/>
<jsp:setProperty name="book" property="resetDeletionMarks" value="1"/>
<%
    book.storeMethod(request.getMethod());
    boolean overrideCssActive = base.isOverrideCssActive();
    String theme = base.getTheme().replace("/themes/susidns/", "").replace("/", "");
    theme = "\"" + theme + "\"";
%>
<!DOCTYPE HTML>
<html>
<head>
<script src=/js/setupIframe.js></script>
<meta charset=utf-8>
<meta name=viewport content="width=device-width, initial-scale=1">
<title>${book.book} <%=intl._t("addressbook")%> - susidns</title>
<link rel=stylesheet href="<%=book.getTheme()%>susidns.css?<%=net.i2p.CoreVersion.VERSION%>">
<%  if (base.useSoraFont()) { %><link href="<%=base.getTheme()%>../../fonts/Sora.css" rel=stylesheet><% } else { %>
<link href="<%=base.getTheme()%>../../fonts/OpenSans.css" rel=stylesheet><% } %>
<% if (overrideCssActive) { %><link rel=stylesheet href="<%=base.getTheme()%>override.css"><% } %>
<%  String query = request.getQueryString(); %>
<script nonce="<%=cspNonce%>">const theme = <%=theme%>;</script>
</head>
<body id=dtls style=display:none;pointer-events:none>
<div id=page>
<div id=navi>
<a class="abook router<%=(query.contains("book=router") ? " details selected" : "")%>" href="addressbook?book=router&amp;filter=none"><%=intl._t("Router")%></a>&nbsp;
<a class="abook master<%=(query.contains("book=master") ? " details selected" : "")%>" href="addressbook?book=master&amp;filter=none"><%=intl._t("Master")%></a>&nbsp;
<a class="abook private<%=(query.contains("book=private") ? " details selected" : "")%>" href="addressbook?book=private&amp;filter=none"><%=intl._t("Private")%></a>&nbsp;
<a class="abook published<%=(query.contains("book=published") ? " details selected" : "")%>" href="addressbook?book=published&amp;filter=none"><%=intl._t("Published")%></a>&nbsp;
<a id=subs href="subscriptions"><%=intl._t("Subscriptions")%></a>&nbsp;
<a id=blacklist href="blacklist"><%=intl._t("Blacklist")%></a>&nbsp;
<a id=configlink href="config"><%=intl._t("Configuration")%></a>&nbsp;
<a id=overview href="index"><%=intl._t("Help")%></a>
</div>
<hr>
<%  String detail = request.getParameter("h");
    if (detail == null) { %>
<p>No host specified</p>
<%  } else {
        // process save notes form
        book.saveNotes();
        detail = net.i2p.data.DataHelper.stripHTML(detail);
        java.util.List<i2p.susi.dns.AddressBean> addrs = book.getLookupAll();
        if (addrs == null) { %>
<p>Not found: <%=detail%></p>
<%      } else {
            boolean haveImagegen = book.haveImagegen();
            // use one nonce for all
            String nonce = book.getSerial();
            boolean showNotes = !book.getBook().equals("published");
            for (i2p.susi.dns.AddressBean addr : addrs) {
                String b32 = addr.getB32(); %>
<jsp:setProperty name="book" property="trClass"	value="0"/>
<div class=headline>
<h3><%=intl._t("Details")%>: <%=addr.getName()%></h3>
</div>
<div id=book>
<%              if (showNotes) { %>
<form method=POST action="details">
<input type=hidden name="book" value="${book.book}">
<input type=hidden name="serial" value="<%=nonce%>">
<input type=hidden name="h" value="<%=detail%>">
<input type=hidden name="destination" value="<%=addr.getDestination()%>">
<%              }  // showNotes %>
<table class=book id=host_details>
<tr>
<td><%=intl._t("Hostname")%></td>
<td><a href="http://<%=addr.getName()%>/" target=_blank rel=noreferrer><%=addr.getDisplayName()%></a>
&nbsp;<b><%=intl._t("Book")%></b>&nbsp;<%=intl._t(book.getBook())%>&nbsp;<b><%=intl._t("Address Helper")%></b>&nbsp;<a href="http://<%=addr.getName()%>/?i2paddresshelper=<%=addr.getDestination()%>" target=_blank rel=noreferrer><%=intl._t("link")%></a></td>
</tr>
<tr>
<%              if (addr.isIDN()) { %>
<td><%=intl._t("Encoded Name")%></td>
<td><a href="http://<%=addr.getName()%>/" target=_blank rel=noreferrer><%=addr.getName()%></a></td>
</tr>
<tr>
<%              } %>
<td><%=intl._t("Base 32 Address")%></td>
<td><a href="http://<%=b32%>/" target=_blank rel=noreferrer><%=b32%></a></td>
</tr>
<tr>
<td><%=intl._t("Base 64 Hash")%></td>
<td><%=addr.getB64()%></td>
</tr>
<tr>
<td><%=intl._t("Public Key")%></td>
<td><%=addr.getEncType()%>&nbsp;<wbr><b><%=intl._t("Signing Key")%></b>&nbsp;<%=addr.getSigType()%>&nbsp;<wbr><b><%=intl._t("Certificate")%></b>&nbsp;<%=addr.getCert()%>
&nbsp;<wbr><b><%=intl._t("Validated")%></b>&nbsp;<%=addr.isValidated() ? intl._t("yes") : intl._t("no")%></td>
</tr>
<tr>
<td><%=intl._t("Source")%></td>
<td><%=addr.getSource()%></td>
</tr>
<tr>
<td><%=intl._t("Added Date")%></td>
<td><%=addr.getAdded()%>&nbsp;
<%              String lastmod = addr.getModded();
                if (lastmod.length() > 0) { %>
<%=addr.getAdded()%>&nbsp;<b><%=intl._t("Last Modified")%></b>&nbsp;<span id=lastMod><%=lastmod%></span>
<%              } %>
</td>
</tr>
<tr>
<td><%=intl._t("Destination")%></td>
<td class=destinations><div class=destaddress tabindex=0><%=addr.getDestination()%></div></td>
</tr>
<%              if (showNotes) { %>
<tr class="list${book.trClass}" id=hostNotes>
<td><%=intl._t("Notes")%></td>
<td><textarea id=addNotes name="nofilter_notes" rows=3 style=height:6em cols=70 placeholder="<%=intl._t("Add notes about domain")%>"><%=addr.getNotes()%></textarea>
<input class=accept type=submit name=action value="<%=intl._t("Save Notes")%>"></td>
</tr>
<%              }  // showNotes %>
</table>
<%              if (showNotes) { %>
</form>
<%              }  // showNotes %>
<div id=buttons>
<form method=POST action="addressbook">
<p class=buttons>
<input type=hidden name="book" value="${book.book}">
<input type=hidden name="serial" value="<%=nonce%>">
<input type=hidden name="begin" value="0">
<input type=hidden name="end" value="99">
<input type=hidden name="checked" value="<%=detail%>">
<input type=hidden name="destination" value="<%=addr.getDestination()%>">
<input class=delete type=submit name=action value="<%=intl._t("Delete Entry")%>">
</p>
</form>
</div><%-- buttons --%>
<%              if (haveImagegen) { %>
<div id=visualid>
<h3><%=intl._t("Visual Identification for")%>&nbsp;<span id=idAddress><%=addr.getName()%></span></h3>
<table>
<tr>
<td><svg class=identicon width="256" height="256" data-jdenticon-value="<%=addr.getB32()%>" xmlns="http://www.w3.org/2000/svg"></svg><noscript><img src="/imagegen/id?s=256&amp;c=<%=addr.getB64().replace("=", "%3d")%>" width=256 height=256></noscript></td>
<td><img src="/imagegen/qr?s=256&amp;t=<%=addr.getName()%>&amp;c=http%3a%2f%2f<%=addr.getName()%>%2f%3fi2paddresshelper%3d<%=addr.getDestination()%>"></td>
</tr>
<tr>
<td colspan=2><a class=fakebutton href="/imagegen" title="<%=intl._t("Create your own identification images")%>" target=_blank rel=noreferrer><%=intl._t("Launch Image Generator")%></a></td>
</tr>
</table>
</div><%-- visualid --%>
<%              }  // haveImagegen %>
<hr>
<%          }  // foreach addr
        }  // addrs == null
    }  // detail == null %>
</div><%-- book --%>
</div><%-- page --%>
<span data-iframe-height></span>
<style>body{display:block!important;pointer-events:auto!important}</style>
<script src="/js/iframeResizer/iframeResizer.contentWindow.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script src="/js/iframeResizer/updatedEvent.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script src="/js/textareaResize.js"></script>
<script nonce="<%=cspNonce%>">
window.jdenticon_config = { padding: 0, saturation: {color: 1, grayscale: 0} };
document.addEventListener("DOMContentLoaded", function() {
  const myTextarea = document.getElementById("addNotes");
  addResizeListener(addNotes);
});
</script>
<script src="/js/jdenticon.js"></script>
</body>
</html>