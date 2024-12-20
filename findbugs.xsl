<?xml version="1.0" encoding="UTF-8"?>
<!--
  A simple XSLT stylesheet to transform FindBugs XML results annotated with messages into HTML.
  Authors: David Hovemeyer, Chris Nappin (summary table), dr|z3d (Dark theme and layout mods)
-->
<xsl:stylesheet version="1.0" xmlns="http://www.w3.org/1999/xhtml" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:output method="html" indent="yes" omit-xml-declaration="yes" standalone="yes" encoding="UTF-8" />

  <xsl:variable name="literalNbsp">&amp;nbsp;</xsl:variable>

  <!--xsl:key name="bug-category-key" match="/BugCollection/BugInstance" use="@category"/-->

  <xsl:variable name="bugTableHeader">
    <tr class="tableheader">
      <th align="left">Code</th>
      <th align="left">Warning</th>
    </tr>
  </xsl:variable>

  <xsl:template match="/">
    <html>
      <head>
        <title>I2P+ | FindBugs Report</title>
        <style type="text/css">
          a{color:#f60;text-decoration:none}
          a:hover{color:#f90}
          a:link{font-weight:600}
          body{margin:0 30px;padding:0 30px 5px;background:#111;color:#ddd;font-family:Open Sans,Noto Sans,Roboto Sans,sans-serif;font-size:10pt;border-left:1px solid #666;border-right:1px solid #666}
          h1{font-size:200%}
          h2{font-size:140%;margin-bottom:0}
          h2 + p{margin-top:2px}
          h2 a[name]{font-size:80%;color:#bb0}
          hr{margin:10px 0 5px;height:1px;border:none;background:#444}
          html{background:#000}
          p[style] a{font-size:110%}
          pre{padding:8px;border-radius:4px;background:#222}
          pre,code{color:#3a3}
          table{padding:1px;width:100%;border:1px solid #555;border-collapse:collapse;background:#111}
          table + h2 a[name],h2 a[name="Warnings_BAD_PRACTICE"],h2#summarytitle{padding-bottom:6px;display:inline-block;font-size:14pt;color:#ddd}
          td,th{padding-left:8px;padding-right:8px}
          th{background:linear-gradient(to bottom,#333,#111);border-bottom:1px solid #555}
          tr[onclick]{cursor:pointer}
          .detailrow0{background:#222}
          .detailrow1{background:#333}
          .header{padding-bottom:8px !important}
          .priority-1{background:#900}
          .priority-1,.priority-2,.priority-3,.priority-4{font-weight:bold;color:#fff}
          .priority-2{background:#730}
          .priority-3{background:#373}
          .priority-4{background:blue}
          .smaller{font-size:80%}
          .tableheader{background:#444}
          .tablerow0{background:#222}
          .tablerow0:hover,.tablerow1:hover{background:#252}
          .tablerow1{background:#333}
          .tabletitle{margin:15px 0 -1px;padding:4px 8px 2px;display:inline-block;border:1px solid #555;background:linear-gradient(to bottom,#222 50%,#000 50%)}
          .tabletitle a,.header{display:inline-block;line-height:1;font-size:12pt !important;text-shadow:0 1px 1px #000}
          .warningtable td{padding:0 15px;border-bottom:1px solid rgba(16,16,16,.1)}
          .warningtable th{padding:4px 15px}
          .warningtable th:first-child,.warningtable td:first-child{width:1%;white-space:nowrap;text-align:center;border-right:1px solid #444}
          .warningtable tr:last-child td{border-bottom:none}
          #analyzed{margin:0;padding:8px 8px 8px 28px;border:1px solid #555;background:#222;columns:auto 200px}
          #bugdetails{margin-bottom:20px;padding:0 20px;border:1px solid #555}
          #bugdetails h2{padding-top:10px;border-top:1px solid #444}
          #bugdetails h2:first-child{padding-top:0;border-top:none}
          #defects{text-align:right}
          #metrics{margin-top:-1px}
          #navbar{margin:0 -50px;padding:6px 4px 7px;position:sticky;top:0;text-align:center;border:1px solid #666;border-top:none;border-radius:0 0 2px 2px;box-shadow:0 3px 3px rgba(0,0,0,.3);background:linear-gradient(to bottom,#222,#000)}
          #navbar a{font-weight:700}
          #navbar span{margin:1px -1px;padding:2px 6px 5px;display:inline-block;line-height:1;font-size:105%;text-transform:capitalize;text-shadow:0 1px 1px #000;border:1px solid #222;background:linear-gradient(to bottom,#333 50%,#000 50%)}
          #summary + table td a{display:inline-block;width:100%}
          #version{margin-top:-16px}
          @media (max-width:1200px){body{font-size:9pt}}
        </style>
        <script type="text/javascript">
          function toggleRow(elid) {
            if (document.getElementById) {
              element = document.getElementById(elid);
              if (!element) {return;}
              if (element.style.display == 'none') {element.style.display = 'block';}
              else {element.style.display = 'none';}
            }
          }
        </script>
      </head>

      <xsl:variable name="unique-catkey" select="/BugCollection/BugCategory/@category" />
      <!--xsl:variable name="unique-catkey" select="/BugCollection/BugInstance[generate-id() = generate-id(key('bug-category-key',@category))]/@category"/-->

      <body>

        <div id="navbar">
          <span>
            <a href="#summary">Summary</a>
          </span>
          <xsl:for-each select="$unique-catkey">
            <xsl:sort select="." order="ascending" />
              <xsl:variable name="catkey" select="." />
              <xsl:variable name="catdesc" select="/BugCollection/BugCategory[@category=$catkey]/Description" />
              <span>
              <a href="#Warnings_{$catkey}">
                <xsl:value-of select="$catdesc" />
              </a>
            </span>
          </xsl:for-each>
          <span>
            <a href="#Details">Details</a>
          </span>
        </div>

        <xsl:apply-templates select="/BugCollection/Project" />

        <h2 class="tabletitle header" id="summary">Summary</h2>
        <table width="500" cellpadding="5" cellspacing="2">
          <tr class="tableheader">
            <th align="left">Warning Type</th>
            <th align="right">Number</th>
          </tr>

          <xsl:for-each select="$unique-catkey">
            <xsl:sort select="." order="ascending" />
            <xsl:variable name="catkey" select="." />
            <xsl:variable name="catdesc" select="/BugCollection/BugCategory[@category=$catkey]/Description" />
            <xsl:variable name="styleclass">
              <xsl:choose>
                <xsl:when test="position() mod 2 = 1">tablerow0</xsl:when>
                <xsl:otherwise>tablerow1</xsl:otherwise>
              </xsl:choose>
            </xsl:variable>

            <tr class="{$styleclass}">
              <td>
                <a href="#Warnings_{$catkey}"><xsl:value-of select="$catdesc" /> Warnings </a>
              </td>
              <td align="right">
                <xsl:value-of select="count(/BugCollection/BugInstance[(@category=$catkey) and not(@last)])" />
              </td>
            </tr>
          </xsl:for-each>

          <xsl:variable name="styleclass">
            <xsl:choose>
              <xsl:when test="count($unique-catkey) mod 2 = 0">tablerow0</xsl:when>
              <xsl:otherwise>tablerow1</xsl:otherwise>
            </xsl:choose>
          </xsl:variable>
          <tr class="{$styleclass}">
            <td>
              <b>Total</b>
            </td>
            <td align="right">
              <b>
                <xsl:value-of select="count(/BugCollection/BugInstance[not(@last)])" />
              </b>
            </td>
          </tr>
        </table>

        <xsl:apply-templates select="/BugCollection/FindBugsSummary" />

        <xsl:for-each select="$unique-catkey">
          <xsl:sort select="." order="ascending" />
          <xsl:variable name="catkey" select="." />
          <xsl:variable name="catdesc" select="/BugCollection/BugCategory[@category=$catkey]/Description" />

          <xsl:call-template name="generateWarningTable">
            <xsl:with-param name="warningSet" select="/BugCollection/BugInstance[(@category=$catkey) and not(@last)]" />
            <xsl:with-param name="sectionTitle"><xsl:value-of select="$catdesc" /> Warnings </xsl:with-param>
            <xsl:with-param name="sectionId">Warnings_<xsl:value-of select="$catkey" /></xsl:with-param>
          </xsl:call-template>
        </xsl:for-each>

        <h2 class="tabletitle">
          <a name="Details">Details</a>
        </h2>
        <div id="bugdetails">
          <xsl:apply-templates select="/BugCollection/BugPattern">
            <xsl:sort select="@abbrev" />
            <xsl:sort select="ShortDescription" />
          </xsl:apply-templates>
        </div>
      </body>
    </html>
  </xsl:template>

  <xsl:template match="Project">
    <h1> Project: <xsl:choose>
        <xsl:when test="string-length(/BugCollection/Project/@projectName)>0">
          <xsl:value-of select="/BugCollection/Project/@projectName" />
        </xsl:when>
        <xsl:otherwise>
          <xsl:value-of select="/BugCollection/Project/@filename" />
        </xsl:otherwise>
      </xsl:choose>
    </h1>
    <p class="smaller" id="version"> FindBugs version: <xsl:value-of select="/BugCollection/@version" />
    </p>
    <hr />
    <xsl:variable name="kloc" select="@total_size div 1000.0" />
    <xsl:variable name="format" select="'#######0.00'" />

    <h2 class="tabletitle header">Analyzed packages</h2>
    <ul id="analyzed">
      <xsl:for-each select="./Jar">
        <li>
          <xsl:value-of select="text()" />
        </li>
      </xsl:for-each>
    </ul>
  </xsl:template>

  <xsl:template match="BugInstance[not(@last)]">
    <xsl:variable name="warningId">
      <xsl:value-of select="generate-id()" />
    </xsl:variable>

    <tr class="tablerow{position() mod 2}" onclick="toggleRow('{$warningId}');">

      <td>
        <xsl:attribute name="class"> priority-<xsl:value-of select="@priority" />
        </xsl:attribute>
        <xsl:value-of select="@abbrev" />
      </td>

      <td>
        <xsl:value-of select="LongMessage" />
      </td>

    </tr>

    <!-- Add bug annotation elements: Class, Method, Field, SourceLine, Field -->
    <tr class="detailrow{position() mod 2}">
      <td />
      <td>
        <p id="{$warningId}" style="display: none;">
          <b>Bug type:</b>
          <a href="#{@type}">
            <xsl:value-of select="@type" />
          </a>
          <xsl:for-each select="./*/Message">
            <br />
            <xsl:value-of select="text()" disable-output-escaping="no" />
          </xsl:for-each>
        </p>
      </td>
    </tr>
  </xsl:template>

  <xsl:template match="BugPattern">
    <h2>
      <a name="{@type}">
        <xsl:value-of select="@type" /> : <xsl:value-of select="ShortDescription" />
      </a>
    </h2>
    <xsl:value-of select="Details" disable-output-escaping="yes" />
  </xsl:template>

  <xsl:template name="generateWarningTable">
    <xsl:param name="warningSet" />
    <xsl:param name="sectionTitle" />
    <xsl:param name="sectionId" />

    <h2 class="tabletitle">
      <a name="{$sectionId}">
        <xsl:value-of select="$sectionTitle" />
      </a>
    </h2>
    <table class="warningtable" width="100%" cellspacing="0">
      <xsl:copy-of select="$bugTableHeader" />
      <xsl:apply-templates select="$warningSet">
        <xsl:sort select="@abbrev" />
        <xsl:sort select="Class/@classname" />
      </xsl:apply-templates>
    </table>
  </xsl:template>

  <xsl:template match="FindBugsSummary">
    <xsl:variable name="kloc" select="@total_size div 1000.0" />
    <xsl:variable name="format" select="'#######0.00'" />

    <table id="metrics" width="500" cellpadding="5" cellspacing="2">
      <tr class="tableheader">
        <th align="left">Metric</th>
        <th align="right">Total</th>
        <th align="right">Density*</th>
      </tr>
      <tr class="tablerow0">
        <td>High Priority Warnings</td>
        <td align="right">
          <xsl:value-of select="@priority_1" />
        </td>
        <td align="right">
          <xsl:choose>
            <xsl:when test="number($kloc) &gt; 0.0 and number(@priority_1) &gt; 0.0">
              <xsl:value-of select="format-number(@priority_1 div $kloc, $format)" />
            </xsl:when>
            <xsl:otherwise>
              <xsl:value-of select="format-number(0.0, $format)" />
            </xsl:otherwise>
          </xsl:choose>
        </td>
      </tr>
      <tr class="tablerow1">
        <td>Medium Priority Warnings</td>
        <td align="right">
          <xsl:value-of select="@priority_2" />
        </td>
        <td align="right">
          <xsl:choose>
            <xsl:when test="number($kloc) &gt; 0.0 and number(@priority_2) &gt; 0.0">
              <xsl:value-of select="format-number(@priority_2 div $kloc, $format)" />
            </xsl:when>
            <xsl:otherwise>
              <xsl:value-of select="format-number(0.0, $format)" />
            </xsl:otherwise>
          </xsl:choose>
        </td>
      </tr>

      <xsl:choose>
        <xsl:when test="@priority_3">
          <tr class="tablerow1">
            <td>Low Priority Warnings</td>
            <td align="right">
              <xsl:value-of select="@priority_3" />
            </td>
            <td align="right">
              <xsl:choose>
                <xsl:when test="number($kloc) &gt; 0.0 and number(@priority_3) &gt; 0.0">
                  <xsl:value-of select="format-number(@priority_3 div $kloc, $format)" />
                </xsl:when>
                <xsl:otherwise>
                  <xsl:value-of select="format-number(0.0, $format)" />
                </xsl:otherwise>
              </xsl:choose>
            </td>
          </tr>
          <xsl:variable name="totalClass" select="tablerow0" />
        </xsl:when>
        <xsl:otherwise>
          <xsl:variable name="totalClass" select="tablerow1" />
        </xsl:otherwise>
      </xsl:choose>

      <tr>
        <td>
          <b>Total Warnings</b>
          <i class="smaller">*Defects per Thousand lines of non-commented source statements</i>
        </td>
        <td align="right">
          <b>
            <xsl:value-of select="@total_bugs" />
          </b>
        </td>
        <xsl:choose>
          <xsl:when test="number($kloc) &gt; 0.0">
            <td align="right">
              <b>
                <xsl:value-of select="format-number(@total_bugs div $kloc, $format)" />
              </b>
            </td>
          </xsl:when>
          <xsl:otherwise>
            <td align="right">
              <b>
                <xsl:value-of select="format-number(0.0, $format)" />
              </b>
            </td>
          </xsl:otherwise>
        </xsl:choose>
      </tr>
    </table>

  </xsl:template>

</xsl:stylesheet><!-- vim:set ts=4: -->