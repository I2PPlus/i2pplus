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

    // http://www.crazysquirrel.com/computing/general/form-encoding.jspx
    if (request.getCharacterEncoding() == null)
        request.setCharacterEncoding("UTF-8");
    response.setHeader("X-Frame-Options", "SAMEORIGIN");
    response.setHeader("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; script-src 'self'; form-action 'self'; frame-ancestors 'self'; object-src 'none'; media-src 'none'; require-trusted-types-for 'script'; base-uri 'self'");
    response.setHeader("X-XSS-Protection", "1; mode=block");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("Accept-Ranges", "none");
%>
<%@ page pageEncoding="UTF-8" contentType="text/html" trimDirectiveWhitespaces="true" import="net.i2p.servlet.RequestWrapper"%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<jsp:useBean id="version" class="i2p.susi.dns.VersionBean" scope="application" />
<jsp:useBean id="book" class="i2p.susi.dns.NamingServiceBean" scope="session" />
<jsp:useBean id="intl" class="i2p.susi.dns.Messages" scope="application" />
<%
   String importMessages = null;
   if (intl._t("Import").equals(request.getParameter("action"))) {
       RequestWrapper wrequest = new RequestWrapper(request);
       importMessages = book.importFile(wrequest);
   }
   boolean isFiltered = book.isHasFilter();
%>
<jsp:setProperty name="book" property="*" />
<jsp:setProperty name="book" property="resetDeletionMarks" value="1"/>
<c:forEach items="${paramValues.checked}" var="checked">
<jsp:setProperty name="book" property="markedForDeletion" value="${checked}"/>
</c:forEach>
<!DOCTYPE HTML>
<html>
<head>
<meta charset="utf-8">
<title>${book.book} <%=intl._t("address book")%> - susidns</title>
<link rel="preload" href="<%=book.getTheme()%>../images/images.css?<%=net.i2p.CoreVersion.VERSION%>" as="style">
<link rel="preload" href="<%=book.getTheme()%>images/images.css?<%=net.i2p.CoreVersion.VERSION%>" as="style">
<link rel="stylesheet" type="text/css" href="<%=book.getTheme()%>susidns.css?<%=net.i2p.CoreVersion.VERSION%>">
<link rel="stylesheet" type="text/css" href="<%=book.getTheme()%>override.css?<%=net.i2p.CoreVersion.VERSION%>">
<script type="text/javascript" src="/js/iframeResizer/iframeResizer.contentWindow.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script src="/js/resetScroll.js?<%=net.i2p.CoreVersion.VERSION%>" type="text/javascript"></script>
<script src="/js/scrollTo.js?<%=net.i2p.CoreVersion.VERSION%>" type="text/javascript"></script>
<script src="/js/closeMessage.js?<%=net.i2p.CoreVersion.VERSION%>" type="text/javascript"></script>
<%
    String cspNonce = Integer.toHexString(net.i2p.util.RandomSource.getInstance().nextInt());
    String query = request.getQueryString();
    RequestWrapper bookRequest = new RequestWrapper(request);
    String here = bookRequest.getParameter("book");
%>
</head>
<body id="bk">
<style type="text/css">body{opacity:0}</style>
<div<% if (book.getBook().equals("published")) { %> id="published"<% } %> class="page">
<div id="navi" class="${book.getBook()}">
<a class="abook router<%=(here.contains("router") ? " selected" : "")%>" href="addressbook?book=router&amp;filter=none"><%=intl._t("Router")%></a>&nbsp;
<a class="abook master<%=(here.contains("master") ? " selected" : "")%>" href="addressbook?book=master&amp;filter=none"><%=intl._t("Master")%></a>&nbsp;
<a class="abook private<%=(here.contains("private") ? " selected" : "")%>" href="addressbook?book=private&amp;filter=none"><%=intl._t("Private")%></a>&nbsp;
<a class="abook published<%=(here.contains("published") ? " selected" : "")%>" href="addressbook?book=published&amp;filter=none"><%=intl._t("Published")%></a>&nbsp;
<a id="subs" href="subscriptions"><%=intl._t("Subscriptions")%></a>&nbsp;
<a id="configlink" href="config"><%=intl._t("Configuration")%></a>&nbsp;
<a id="overview" href="index"><%=intl._t("Help")%></a>
</div>
<hr>
<form action="export" id="exportlist" method="GET" target="_blank" hidden></form>
<div class="headline" id="addressbook">
<h3><%=intl._t("Book")%>: <%=intl._t(book.getBook())%>${book.loadBookMessages}<c:if test="${book.isEmpty}">&nbsp;<span class="results">(<%=intl._t("No entries")%>)</span></c:if>
<c:if test="${book.isEmpty}"><span id="export"><input form="exportlist" type="submit" class="export" id="exporthosts" <c:if test="${book.isEmpty}">disabled</c:if>></span></c:if>
<c:if test="${book.notEmpty}">
<%
    if (book.getEntries().length > 0) { /* Don't show if no results. Can't figure out how to do this with c:if */
%>
<span id="export">
<input form="exportlist" type="hidden" name="book" value="${book.book}">
<c:if test="${book.search} != null && ${book.search}.length() > 0"><input form="exportlist" type="hidden" name="search" value="${book.search}"></c:if>
<c:if test="${book.hasFilter}"><input form="exportlist" type="hidden" name="filter" value="${book.filter}"></c:if>
<%
        if (book.isHasFilter() || book.getSearch() != null) {
%>
<input form="exportlist" type="submit" class="export" id="exporthosts" value="<%=intl._t("Export in hosts.txt format")%>" name="export" title="<%=intl._t("Export results in hosts.txt format")%>">
<%
        } else {
%>
<input form="exportlist" type="submit" class="export" id="exporthosts" value="<%=intl._t("Export in hosts.txt format")%>" name="export" title="<%=intl._t("Export book in hosts.txt format")%>">
<%
        }
%>
</span>
<%
    } else { /* book.getEntries().length() > 0 */
%>
<span id="export"><input form="exportlist" type="submit" class="export" id="exporthosts" disabled></span>
<%
    }
%>
</h3>
</div>
<div id="messages">${book.messages}
<%
   if (importMessages != null) {
%>
<%=importMessages%>
<%
   }
%>
</div>
<div id="search">
<form method="GET" action="addressbook?book=${book.book}">
<input type="hidden" name="book" value="${book.book}">
<input type="hidden" name="begin" value="0">
<input type="hidden" name="end" value="99">
<input type="hidden" name="filter" value="${book.filter}">
<div id="booksearch">
<%
    if (book.getSearch() == null) {
%>
<input class="search" type="text" name="search" value="" size="20">
<%
    } else {
%>
<input class="search" type="text" name="search" value="${book.search}" size="20">
<%
    }
%>
<input class="search" type="submit" name="submitsearch" value="<%=intl._t("Search")%>">
</div>
</form>
</div>
<div id="filter">
<%
    if (query != null && !query.contains("filter=a")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=a&amp;begin=0&amp;end=99">a</a>
<%
    } else {
%>
<span id="activefilter">A</span>
<%  }
    if (query != null && !query.contains("filter=b")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=b&amp;begin=0&amp;end=99">b</a>
<%
    } else {
%>
<span id="activefilter">B</span>
<%  }
    if (query != null && !query.contains("filter=c")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=c&amp;begin=0&amp;end=99">c</a>
<%
    } else {
%>
<span id="activefilter">C</span>
<%  }
    if (query != null && !query.contains("filter=d")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=d&amp;begin=0&amp;end=99">d</a>
<%
    } else {
%>
<span id="activefilter">D</span>
<%  }
    if (query != null && !query.contains("filter=e")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=e&amp;begin=0&amp;end=99">e</a>
<%
    } else {
%>
<span id="activefilter">E</span>
<%  }
    if (query != null && !query.contains("filter=f")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=f&amp;begin=0&amp;end=99">f</a>
<%
    } else {
%>
<span id="activefilter">F</span>
<%  }
    if (query != null && !query.contains("filter=g")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=g&amp;begin=0&amp;end=99">g</a>
<%
    } else {
%>
<span id="activefilter">G</span>
<%  }
    if (query != null && !query.contains("filter=h")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=h&amp;begin=0&amp;end=99">h</a>
<%
    } else {
%>
<span id="activefilter">H</span>
<%  }
    if (query != null && !query.contains("filter=i")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=i&amp;begin=0&amp;end=99">i</a>
<%
    } else {
%>
<span id="activefilter">I</span>
<%  }
    if (query != null && !query.contains("filter=j")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=j&amp;begin=0&amp;end=99">j</a>
<%
    } else {
%>
<span id="activefilter">J</span>
<%  }
    if (query != null && !query.contains("filter=k")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=k&amp;begin=0&amp;end=99">k</a>
<%
    } else {
%>
<span id="activefilter">K</span>
<%  }
    if (query != null && !query.contains("filter=l")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=l&amp;begin=0&amp;end=99">l</a>
<%
    } else {
%>
<span id="activefilter">L</span>
<%  }
    if (query != null && !query.contains("filter=m")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=m&amp;begin=0&amp;end=99">m</a>
<%
    } else {
%>
<span id="activefilter">M</span>
<%  }
    if (query != null && !query.contains("filter=n&")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=n&amp;begin=0&amp;end=99">n</a>
<%
    } else {
%>
<span id="activefilter">N</span>
<%  }
    if (query != null && !query.contains("filter=o")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=o&amp;begin=0&amp;end=99">o</a>
<%
    } else {
%>
<span id="activefilter">O</span>
<%  }
    if (query != null && !query.contains("filter=p")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=p&amp;begin=0&amp;end=99">p</a>
<%
    } else {
%>
<span id="activefilter">P</span>
<%  }
    if (query != null && !query.contains("filter=q")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=q&amp;begin=0&amp;end=99">q</a>
<%
    } else {
%>
<span id="activefilter">Q</span>
<%  }
    if (query != null && !query.contains("filter=r")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=r&amp;begin=0&amp;end=99">r</a>
<%
    } else {
%>
<span id="activefilter">R</span>
<%  }
    if (query != null && !query.contains("filter=s")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=s&amp;begin=0&amp;end=99">s</a>
<%
    } else {
%>
<span id="activefilter">S</span>
<%  }
    if (query != null && !query.contains("filter=t")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=t&amp;begin=0&amp;end=99">t</a>
<%
    } else {
%>
<span id="activefilter">T</span>
<%  }
    if (query != null && !query.contains("filter=u")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=u&amp;begin=0&amp;end=99">u</a>
<%
    } else {
%>
<span id="activefilter">U</span>
<%  }
    if (query != null && !query.contains("filter=v")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=v&amp;begin=0&amp;end=99">v</a>
<%
    } else {
%>
<span id="activefilter">V</span>
<%  }
    if (query != null && !query.contains("filter=w")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=w&amp;begin=0&amp;end=99">w</a>
<%
    } else {
%>
<span id="activefilter">W</span>
<%  }
    if (query != null && !query.contains("filter=x&")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=x&amp;begin=0&amp;end=99">x</a>
<%
    } else {
%>
<span id="activefilter">X</span>
<%  }
    if (query != null && !query.contains("filter=y")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=y&amp;begin=0&amp;end=99">y</a>
<%
    } else {
%>
<span id="activefilter">Y</span>
<%  }
    if (query != null && !query.contains("filter=z")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=z&amp;begin=0&amp;end=99">z</a>
<%
    } else {
%>
<span id="activefilter">Z</span>
<%  }
    if (query != null && !query.contains("filter=0-9")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=0-9&amp;begin=0&amp;end=99">0-9</a>
<%
    } else {
%>
<span id="activefilter">0-9</span>
<%  }
    if (query != null && !query.contains("filter=xn--&")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=xn--&amp;begin=0&amp;end=99"><%=intl._t("other")%></a>
<%
    } else {
%>
<span id="activefilter">Other</span>
<%  }
    if (query != null && !query.contains("filter=none")) {
%>
<a href="addressbook?book=${book.book}&amp;filter=none&amp;begin=0&amp;end=99"><%=intl._t("clear filter")%></a>
<%
    }
%>
</div>
</c:if>
<%
    String susiNonce = book.getSerial(); // have to only do this once per page
%>
<c:if test="${book.notEmpty}">
<form method="POST" action="addressbook">
<input type="hidden" name="book" value="${book.book}">
<input type="hidden" name="serial" value="<%=susiNonce%>">
<input type="hidden" name="begin" value="0">
<input type="hidden" name="end" value="99">
<input type="hidden" name="action" value="<%=intl._t("Delete Selected")%>">
<div id="book">
<table class="book" id="host_list" cellspacing="0" cellpadding="5">
<tr class="head">
<%
    if (book.getEntries().length > 0) { /* Don't show if no results. Can't figure out how to do this with c:if */
%>
<th class="info"><%=intl._t("Info")%></th><th class="names"><%=intl._t("Hostname")%></th><th class="b32link"><%=intl._t("Link (b32)")%></th><th class="helper">Helper</th><th class="destinations"><%=intl._t("Destination")%> (b64)</th>
<c:if test="${book.validBook}"><th class="checkbox" title="<%=intl._t("Select hosts for deletion from addressbook")%>"></th></c:if>
</tr>
<!-- limit iterator, or "Form too large" may result on submit, and is a huge web page if we don't -->
<c:forEach items="${book.entries}" var="addr" begin="${book.resultBegin}" end="${book.resultEnd}">
<tr>
<td class="info">
<%
        boolean haveImagegen = book.haveImagegen();
        if (haveImagegen) {
%>
<a href="details?h=${addr.name}&amp;book=${book.book}" title="<%=intl._t("More information on this entry")%>"><img src="/imagegen/id?s=24&amp;c=${addr.b32}"></a>
<%
        }  else { // haveImagegen
%>
<a href="details?h=${addr.name}&amp;book=${book.book}" title="<%=intl._t("More information on this entry")%>"><img width=20 height=20 src="/themes/console/images/svg/info.svg"></a>
<%
        }
%>
</td>
<td class="names"><a href="http://${addr.name}/" target="_blank">${addr.displayName}</a></td>
<td class="b32link"><span class="addrhlpr"><a href="http://${addr.b32}/" target="_blank" rel="noreferrer" title="<%=intl._t("Base 32 address")%>">b32</a></span></td>
<td class="helper"><a href="http://${addr.name}/?i2paddresshelper=${addr.destination}" target="_blank" rel="noreferrer" title="<%=intl._t("Helper link to share host address with option to add to addressbook")%>">link</a></td>
<td class="destinations"><div class="destaddress resetScrollLeft" name="dest_${addr.name}" width="200px" tabindex="0">${addr.destination}</div></td>
<c:if test="${book.validBook}"><td class="checkbox"><input type="checkbox" class="optbox" name="checked" value="${addr.name}" title="<%=intl._t("Mark for deletion")%>"></td></c:if>
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
<div id="buttons">
<p class="buttons"><input class="cancel" type="reset" value="<%=intl._t("Cancel")%>"><input class="delete" type="submit" name="action" value="<%=intl._t("Delete Selected")%>"></p>
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
<c:if test="${book.isEmpty}"></h3></div><div id="empty"></div></c:if>
<form method="POST" action="addressbook?book=${book.book}">
<input type="hidden" name="book" value="${book.book}">
<input type="hidden" name="serial" value="<%=susiNonce%>">
<input type="hidden" name="begin" value="0">
<input type="hidden" name="end" value="99">
<div id="add">
<h3><%=intl._t("Add new destination")%></h3>
<table>
<tr><td><b><%=intl._t("Hostname")%></b></td><td><input type="text" name="hostname" value="${book.hostname}" size="30" required placeholder="e.g. newdomain.i2p"></td></tr>
<tr><td><b><%=intl._t("B64 or B32")%></b></td><td><input type="text" name="destination" value="${book.destination}" size="50" required placeholder="Full destination or b32 address"></td></tr>
</table>
<p class="buttons">
<input class="cancel" type="reset" value="<%=intl._t("Cancel")%>">
<input class="accept scrollToNav" type="submit" name="action" value="<%=intl._t("Replace")%>">
<%
    if (!book.getBook().equals("published")) {
%>
<input class="add scrollToNav" type="submit" name="action" value="<%=intl._t("Add Alternate")%>">
<%
    }
%>
<input class="add scrollToNav" type="submit" name="action" value="<%=intl._t("Add")%>">
</p>
</div>
</form>
<%
    if (!book.getBook().equals("published")) {
%>
<form method="POST" action="addressbook?book=${book.book}" enctype="multipart/form-data" accept-charset="UTF-8">
<input type="hidden" name="book" value="${book.book}">
<input type="hidden" name="serial" value="<%=susiNonce%>">
<input type="hidden" name="begin" value="0">
<input type="hidden" name="end" value="99">
<div id="import">
<h3><%=intl._t("Import from hosts.txt file")%></h3>
<table><tr><td><b><%=intl._t("Select file")%></b></td><td><input name="file" type="file" accept=".txt" value=""></td></tr></table>
<p class="buttons">
<input class="cancel" type="reset" value="<%=intl._t("Cancel")%>">
<input class="download scrollToNav" type="submit" name="action" value="<%=intl._t("Import")%>">
</p>
</div>
</form>
<%
    }
%>
<c:if test="${book.isEmpty}"></div></c:if>
<span data-iframe-height></span>
<style type="text/css">body{opacity:1 !important}</style>
</div>
</body>
</html>