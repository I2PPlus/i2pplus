<%@page pageEncoding="UTF-8"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@ page import="java.io.File" %>
<%@ page import="java.io.IOException" %>
<%@ page import="java.nio.file.Files" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%
/*
 * USE CAUTION WHEN EDITING
 * Trailing whitespace OR NEWLINE on the last line will cause
 * IllegalStateExceptions !!!
 *
 */
String slash = File.separator;
String geomapPath = "docs" + slash + "themes" + slash + "geomap" + slash + "geomap.svg";
java.io.File base = net.i2p.I2PAppContext.getGlobalContext().getBaseDir();
java.io.File file = new java.io.File(base, geomapPath);
try {out.print(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));}
catch (IOException e) {}
%>