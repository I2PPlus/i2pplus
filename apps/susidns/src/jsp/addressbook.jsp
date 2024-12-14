<%
/*
 * Created on Sep 02, 2005
 *
 *  This file is part of susidns project, see http://susi.i2p/
 *
 *  Copyright (C) 2005 <susi23@mail.i2p>
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 * $Revision: 1.3 $
 */
%>
<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" import="net.i2p.servlet.RequestWrapper" import="java.util.regex.Pattern" import="java.util.regex.Matcher" buffer="128kb"%>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<%@include file="headers.jsi"%>
<jsp:useBean id="base" class="i2p.susi.dns.BaseBean" scope="session" />
<jsp:useBean id="book" class="i2p.susi.dns.NamingServiceBean" scope="session" />
<jsp:useBean id="intl" class="i2p.susi.dns.Messages" scope="application" />
<jsp:useBean id="version" class="i2p.susi.dns.VersionBean" scope="application" />
<jsp:setProperty name="book" property="*" />
<jsp:setProperty name="book" property="resetDeletionMarks" value="1"/>
<c:forEach items="${paramValues.checked}" var="checked">
<jsp:setProperty name="book" property="markedForDeletion" value="${checked}"/>
</c:forEach>
<%
    String importMessages = null;
    String query = request.getQueryString();
    RequestWrapper bookRequest = new RequestWrapper(request);
    String here = bookRequest.getParameter("book");
    // This is what does the form processing.
    // We need to do this before any notEmpty test and
    // before loadBookMessages() which displays the entry count.
    // Messages will be displayed below.
    String formMessages = book.getMessages();
    String susiNonce = book.getSerial(); // have to only do this once per page
    boolean isFiltered = book.isHasFilter();
    if (intl._t("Import").equals(request.getParameter("action"))) {
        RequestWrapper wrequest = new RequestWrapper(request);
        importMessages = book.importFile(wrequest);
    }
%>
<!DOCTYPE HTML>
<html>
<head>
<script src=/js/setupIframe.js></script>
<meta charset=utf-8>
<title>${book.book} <%=intl._t("address book")%> - susidns</title>
<link rel=preload href="<%=book.getTheme()%>../images/images.css?<%=net.i2p.CoreVersion.VERSION%>" as="style">
<link rel=preload href="<%=book.getTheme()%>images/images.css?<%=net.i2p.CoreVersion.VERSION%>" as="style">
<link rel=stylesheet href="<%=book.getTheme()%>susidns.css?<%=net.i2p.CoreVersion.VERSION%>">
<link rel="icon shortcut" href=/themes/console/images/addressbook.svg type=image/svg+xml>
<%  if (base.useSoraFont()) { %><link href="<%=base.getTheme()%>../../fonts/Sora.css" rel=stylesheet><% } else { %>
<link href="<%=base.getTheme()%>../../fonts/OpenSans.css" rel=stylesheet><% } %>
<link rel=stylesheet href="<%=book.getTheme()%>override.css">
<script src="/js/iframeResizer/iframeResizer.contentWindow.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script src="/js/iframeResizer/updatedEvent.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script src="/js/resetScroll.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script src="/js/scrollTo.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script src="/js/clickToClose.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script nonce="<%=cspNonce%>">window.jdenticon_config = { padding: 0, saturation: {color: 1, grayscale: 0} };</script>
<script nonce="<%=cspNonce%>" src="/js/jdenticon.js"></script>
</head>
<body id=bk class="<%=book.getThemeName()%>" style=display:none;pointer-events:none>
<div<% if (book.getBook().equals("published")) { %> id=published<% } %> id=page>
<div id=navi class="${book.getBook()}">
<a class="abook router<%=(here.contains("router") ? " selected" : "")%>" href="/susidns/addressbook?book=router&amp;filter=none"><%=intl._t("Router")%></a>&nbsp;
<a class="abook master<%=(here.contains("master") ? " selected" : "")%>" href="/susidns/addressbook?book=master&amp;filter=none"><%=intl._t("Master")%></a>&nbsp;
<a class="abook private<%=(here.contains("private") ? " selected" : "")%>" href="/susidns/addressbook?book=private&amp;filter=none"><%=intl._t("Private")%></a>&nbsp;
<a class="abook published<%=(here.contains("published") ? " selected" : "")%>" href="/susidns/addressbook?book=published&amp;filter=none"><%=intl._t("Published")%></a>&nbsp;
<a id=subs href="subscriptions"><%=intl._t("Subscriptions")%></a>&nbsp;
<a id=configlink href="config"><%=intl._t("Configuration")%></a>&nbsp;
<a id=overview href="index"><%=intl._t("Help")%></a>
</div>
<main>
<hr>
<form action="/susidns/export" id=exportlist method=GET target=_blank hidden></form>
<div class=headline id=addressbook>
<h3><%=intl._t("Book")%>: <%=intl._t(book.getBook())%>${book.loadBookMessages}<c:if test="${book.isEmpty}">&nbsp;<span class=results>(<%=intl._t("No entries")%>)</span></c:if>
<span id=export>
<a href=#add id=addNewDest class=fakebutton title="<%=intl._t("Add new destination")%>" style=display:none!important hidden></a><a href=#import id=importFromFile class=fakebutton title="<%=intl._t("Import from hosts.txt file")%>" style=display:none!important hidden></a>
<c:if test="${book.isEmpty}"><input form="exportlist" type=submit class=export id=exporthosts <c:if test="${book.isEmpty}">disabled</c:if>></c:if>
<c:if test="${book.notEmpty}">
<%
    if (book.getEntries().length > 0) { /* Don't show if no results. Can't figure out how to do this with c:if */
%>
<input form="exportlist" type=hidden name="book" value="${book.book}">
<c:if test="${book.search} != null && ${book.search}.length() > 0"><input form="exportlist" type=hidden name="search" value="${book.search}"></c:if>
<c:if test="${book.hasFilter}"><input form="exportlist" type=hidden name="filter" value="${book.filter}"></c:if>
<%
        if (book.isHasFilter() || book.getSearch() != null) {
%>
<input form="exportlist" type=submit class=export id=exporthosts value="<%=intl._t("Export in hosts.txt format")%>" name="export" title="<%=intl._t("Export results in hosts.txt format")%>">
<%
        } else {
%>
<input form="exportlist" type=submit class=export id=exporthosts value="<%=intl._t("Export in hosts.txt format")%>" name="export" title="<%=intl._t("Export book in hosts.txt format")%>">
<%
        }
%>
<%
    } else { /* book.getEntries().length() > 0 */
%>
<input form="exportlist" type=submit class=export id=exporthosts disabled>
<%
    }
%>
</span>
</h3>
</div>
<% /* need this whether book is empty or not to display the form messages */ %>
<div id=messages class=canClose><%=formMessages%>
<%
   if (importMessages != null) {
%>
<%=importMessages%>
<%
   }
%>
</div>
<div id=search>
<form method=GET action="/susidns/addressbook?book=${book.book}">
<input id=bookname type=hidden name="book" value="${book.book}">
<input type=hidden name="begin" value="0">
<input type=hidden name="end" value="99">
<input type=hidden name="filter" value="${book.filter}">
<div id=booksearch>
<%
    if (book.getSearch() == null) {
%>
<input class=search type=text name="search" value="" size=20>
<%
    } else {
%>
<input class=search type=text name="search" value="${book.search}" size=20>
<%
    }
%>
<input class=search type=submit name="submitsearch" value="<%=intl._t("Search")%>">
</div>
</form>
</div>
<div id=filter>
<%
    String[][] filters = {
        {"a", "A"}, {"b", "B"}, {"c", "C"}, {"d", "D"},
        {"e", "E"}, {"f", "F"}, {"g", "G"}, {"h", "H"},
        {"i", "I"}, {"j", "J"}, {"k", "K"}, {"l", "L"},
        {"m", "M"}, {"n", "N"}, {"o", "O"}, {"p", "P"},
        {"q", "Q"}, {"r", "R"}, {"s", "S"}, {"t", "T"},
        {"u", "U"}, {"v", "V"}, {"w", "W"}, {"x", "X"},
        {"y", "Y"}, {"z", "Z"}, {"0-9", "0-9"}, {"xn--", intl._t("other")},
        {"none", intl._t("all")}
    };

    for (String[] filter : filters) {
        String filterValue = filter[0];
        String displayText = filter[1];
        boolean notActive = query != null && !query.matches(".*[?&]filter=" + filterValue + "(&|$).*");
        boolean showAll = query == null || query.contains("none") || !query.contains("filter");

        if (notActive) {
%>
<a href="/susidns/addressbook?book=${book.book}&amp;filter=<%= filterValue %>&amp;begin=0"><%= displayText %></a>
<%
        } else {
%>
<span id="activefilter"><%= displayText %></span>
<%
        }
    }
%>
</div>
</c:if>
<c:if test="${book.notEmpty}">
<form method=POST action="/susidns/addressbook">
<input type=hidden name="book" value="${book.book}">
<input type=hidden name="serial" value="<%=susiNonce%>">
<input type=hidden name="begin" value="0">
<input type=hidden name="end" value="99">
<input type=hidden name=action value="<%=intl._t("Delete Selected")%>">
<div id=book>
<table class=book id=host_list>
<tr class=head>
<%
    if (book.getEntries().length > 0) { /* Don't show if no results. Can't figure out how to do this with c:if */
%>
<th class=info><%=intl._t("Info")%></th><th class=names><%=intl._t("Hostname")%></th><th class=b32link><%=intl._t("Link (b32)")%></th><th class=helper>Helper</th><th class=destinations><%=intl._t("Destination")%> (b64)</th>
<c:if test="${book.validBook}"><th class=checkbox title="<%=intl._t("Select hosts for deletion from addressbook")%>"></th></c:if>
</tr>
<!-- limit iterator, or "Form too large" may result on submit, and is a huge web page if we don't -->
<c:forEach items="${book.entries}" var="addr" begin="${book.resultBegin}" end="${book.resultEnd}">
<tr class=lazy>
<td class=info>
<%
        boolean haveImagegen = book.haveImagegen();
        if (haveImagegen) {
%>
<a href="details?h=${addr.name}&amp;book=${book.book}" title="<%=intl._t("More information on this entry")%>"><svg width="24" height="24" class=identicon data-jdenticon-value="${addr.b32}" xmlns="http://www.w3.org/2000/svg"></svg><noscript><img src="/imagegen/id?s=24&amp;c=${addr.b32}" loading=lazy><style>.identicon{display:none!important}</style></noscript></a>
<%
        }  else { // haveImagegen
%>
<a href="details?h=${addr.name}&amp;book=${book.book}" title="<%=intl._t("More information on this entry")%>"><img width=20 height=20 src="/themes/console/images/info.svg"></a>
<%
        }
%>
</td>
<td class=names><a href="http://${addr.name}/" target=_blank>${addr.displayName}</a></td>
<td class=b32link><span class=addrhlpr><a href="http://${addr.b32}/" target=_blank rel=noreferrer title="<%=intl._t("Base 32 address")%>">b32</a></span></td>
<td class=helper><a href="http://${addr.name}/?i2paddresshelper=${addr.destination}" target=_blank rel=noreferrer title="<%=intl._t("Helper link to share host address with option to add to addressbook")%>">link</a></td>
<td class=destinations><div class="destaddress resetScrollLeft" name="dest_${addr.name}" width=200px tabindex=0>${addr.destination}</div></td>
<c:if test="${book.validBook}"><td class=checkbox><input type=checkbox class=optbox name="checked" value="${addr.name}" title="<%=intl._t("Mark for deletion")%>"></td></c:if>
</tr>
</c:forEach>
<%
    } /* book..getEntries().length() > 0 */
%>
</table>
</div>
<%
    if (book.getEntries().length > 0) { /* Don't show if no results. Can't figure out how to do this with c:if */
%>
<c:if test="${book.validBook}">
<div id=buttons>
<p class=buttons>
<input class=cancel type=reset value="<%=intl._t("Cancel")%>">
<input class=delete type=submit name=action value="<%=intl._t("Delete Selected")%>">
</p>
</div>
</c:if>
<%
    } /* book..getEntries().length() > 0 */
%>
</form>
</c:if>
<%
    /* book.notEmpty */
%>
<c:if test="${book.isEmpty}"></h3></div><div id=empty></div></c:if>
<form id=addDestForm method=POST action="/susidns/addressbook?book=${book.book}">
<input type=hidden name="book" value="${book.book}">
<input type=hidden name="serial" value="<%=susiNonce%>">
<input type=hidden name="begin" value="0">
<input type=hidden name="end" value="99">
<div id=add>
<h3><%=intl._t("Add new destination")%></h3>
<table>
<tr><td><b><%=intl._t("Hostname")%></b></td><td><input type=text name="hostname" value="${book.hostname}" size=30 required placeholder="<%=intl._t("e.g. newdomain.i2p")%>"></td></tr>
<tr><td><b><%=intl._t("B64 or B32")%></b></td><td><input type=text name="destination" value="${book.destination}" size="50" required placeholder="<%=intl._t("Full destination or b32 address")%>"></td></tr>
</table>
<p class=buttons>
<input class=cancel type=reset value="<%=intl._t("Cancel")%>">
<c:if test="${book.notEmpty}">
<input class="accept scrollToNav" type=submit name=action value="<%=intl._t("Replace")%>">
<%
    if (!book.getBook().equals("published")) {
%>
<input class="add scrollToNav" type=submit name=action value="<%=intl._t("Add Alternate")%>">
<%
    }
%>
</c:if><% /* book.notEmpty */ %>
<input class="add scrollToNav" type=submit name=action value="<%=intl._t("Add")%>">
</p>
</div>
</form>
<%
    if (!book.getBook().equals("published")) {
%>
<form id=importHostsForm method=POST action="/susidns/addressbook?book=${book.book}" enctype="multipart/form-data" accept-charset=utf-8>
<input type=hidden name="book" value="${book.book}">
<input type=hidden name="serial" value="<%=susiNonce%>">
<input type=hidden name="begin" value="0">
<input type=hidden name="end" value="99">
<div id=import>
<h3><%=intl._t("Import from hosts.txt file")%></h3>
<table><tr><td><b><%=intl._t("Select file")%></b></td><td><input name="file" type="file" accept=".txt" value=""></td></tr></table>
<p class=buttons>
<input class=cancel type=reset value="<%=intl._t("Cancel")%>">
<input class="download scrollToNav" type=submit name=action value="<%=intl._t("Import")%>">
</p>
</div>
</form>
<%
    }
%>
<c:if test="${book.isEmpty}"></div></c:if>
</main>
</div>
<span data-iframe-height></span>
<style>body{display:block!important;pointer-events:auto!important}</style>
<script src=/js/lazyload.js></script>
<script src=/susidns/js/togglePanels.js></script>
</body>
</html>