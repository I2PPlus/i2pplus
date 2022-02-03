<%@include file="headers.jsi"%>
<%@include file="headers-unsafe.jsi"%>
<%@page pageEncoding="UTF-8"%>
<%@page contentType="text/html" import="java.io.File,java.io.IOException,net.i2p.crypto.KeyStoreUtil,net.i2p.data.DataHelper,net.i2p.jetty.JettyXmlConfigurationParser"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page%>
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<%
  /* right now using EditBean instead of IndexBean for getSpoofedHost() */
  /* but might want to POST to it anyway ??? */
%>
<jsp:useBean class="net.i2p.i2ptunnel.web.EditBean" id="editBean" scope="request" />
<jsp:useBean class="net.i2p.i2ptunnel.ui.Messages" id="intl" scope="request" />
<%
   String tun = request.getParameter("tunnel");
   int curTunnel = -1;
   if (tun != null) {
     try {
       curTunnel = Integer.parseInt(tun);
     } catch (NumberFormatException nfe) {
       curTunnel = -1;
     }
   }
%>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en" id="tman">
<head>
<title><%=intl._t("Tunnel Manager")%> - <%=intl._t("SSL Helper")%></title>
<meta charset="utf-8">
<link rel="icon" href="<%=editBean.getTheme()%>images/favicon.svg">
<script type="text/javascript" src="/js/iframeResizer/iframeResizer.contentWindow.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<link rel="icon" href="<%=editBean.getTheme()%>images/favicon.svg">
<link href="<%=editBean.getTheme()%>i2ptunnel.css?<%=net.i2p.CoreVersion.VERSION%>" rel="stylesheet" type="text/css">
<link href="<%=editBean.getTheme()%>../images/images.css?<%=net.i2p.CoreVersion.VERSION%>" rel="stylesheet" type="text/css">
<link href="<%=editBean.getTheme()%>images/images.css?<%=net.i2p.CoreVersion.VERSION%>" rel="stylesheet" type="text/css">
<link href="<%=editBean.getTheme()%>../images/i2ptunnel.css?<%=net.i2p.CoreVersion.VERSION%>" rel="stylesheet" type="text/css">
<link href="<%=editBean.getTheme()%>override.css?<%=net.i2p.CoreVersion.VERSION%>" rel="stylesheet" type="text/css">
<style type="text/css">input.default {width: 1px; height: 1px; visibility: hidden;}</style>
</head>
<body id="tunnelSSL">
<style type="text/css">body{opacity:0}</style>
<%
  net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
  if (!ctx.isRouterContext()) { %>
<p>Unsupported in app context</p>
<%
  } else if (curTunnel < 0) { %>
<p>Tunnel not found</p>
<%
  } else if (editBean.isClient(curTunnel)) { %>
<p>Not supported for client tunnels</p>
<%
  } else if (editBean.isInitialized()) { %>
<%
    String tunnelTypeName;
    String tunnelType;
    boolean valid = false;
    tunnelTypeName = editBean.getTunnelType(curTunnel);
    tunnelType = editBean.getInternalType(curTunnel);
%>
<%
    // set a bunch of variables for the current configuration
    String b64 = editBean.getDestinationBase64(curTunnel);
    String b32 = editBean.getDestHashBase32(curTunnel);
    // todo
    String altb32 = editBean.getAltDestHashBase32(curTunnel);
    String name = editBean.getSpoofedHost(curTunnel);
    // non-null, default 127.0.0.1
    String targetHost = editBean.getTargetHost(curTunnel);
    if (targetHost.indexOf(':') >= 0)
        targetHost = '[' + targetHost + ']';
    // non-null, default ""
    String targetPort = editBean.getTargetPort(curTunnel);
    int intPort = 0;
    try {
        intPort = Integer.parseInt(targetPort);
    } catch (NumberFormatException nfe) {}
    String clientTgt = targetHost + ':' + targetPort;
    boolean sslToTarget = editBean.isSSLEnabled(curTunnel);
    String targetLink = clientTgt;
    boolean shouldLinkify = true;
    if (shouldLinkify) {
        String url = "://" + clientTgt + "\">" + clientTgt + "</a>";
        if (sslToTarget)
            targetLink = "<a target=\"_top\" href=\"https" + url;
        else
            targetLink = "<a target=\"_top\" href=\"http" + url;
    }
    net.i2p.util.PortMapper pm = ctx.portMapper();
    int jettyPort = pm.getPort(net.i2p.util.PortMapper.SVC_EEPSITE);
    int jettySSLPort = pm.getPort(net.i2p.util.PortMapper.SVC_HTTPS_EEPSITE);

    if (name == null || name.equals(""))
        name = editBean.getTunnelName(curTunnel);
    if (!"new".equals(tunnelType)) {
        // build tables for vhost and targets
        java.util.TreeSet<Integer> ports = new java.util.TreeSet<Integer>();
        java.util.Map<Integer, String> tgts = new java.util.HashMap<Integer, String>(4);
        java.util.Map<Integer, String> spoofs = new java.util.HashMap<Integer, String>(4);
        String custom = editBean.getCustomOptions(curTunnel);
        String[] opts = DataHelper.split(custom, "[, ]");
        for (int i = 0; i < opts.length; i++) {
            String opt = opts[i];
            boolean isTgt = false;
            if (opt.startsWith("targetForPort.")) {
                opt = opt.substring("targetForPort.".length());
                isTgt = true;
            } else if (opt.startsWith("spoofedHost.")) {
                opt = opt.substring("spoofedHost.".length());
            } else {
                 continue;
            }
            int eq = opt.indexOf('=');
            if (eq <= 0)
                 continue;
            int port;
            try {
                port = Integer.parseInt(opt.substring(0, eq));
            } catch (NumberFormatException nfe) {
                 continue;
            }
            String tgt = opt.substring(eq + 1);
            Integer iport = Integer.valueOf(port);
            ports.add(iport);
            if (isTgt)
                tgts.put(iport, tgt);
            else
                spoofs.put(iport, tgt);
        }

        // POST handling
        String action = request.getParameter("action");
        if (action != null) {
            StringBuilder msgs = new StringBuilder();
            String nonce = request.getParameter("nonce");
            String newpw = request.getParameter("nofilter_keyPassword");
            String kspw = request.getParameter("nofilter_obfKeyStorePassword");
            String appNum = request.getParameter("clientAppNumber");
            String ksPath = request.getParameter("nofilter_ksPath");
            String jettySSLConfigPath = request.getParameter("nofilter_jettySSLFile");
            String host = request.getParameter("jettySSLHost");
            String port = request.getParameter("jettySSLPort");
            if (newpw != null) {
                newpw = newpw.trim();
                if (newpw.length() <= 0)
                    newpw = null;
            }
            if (kspw != null) {
                kspw = JettyXmlConfigurationParser.deobfuscate(kspw);
            } else {
                kspw = KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD;
            }
            if (!net.i2p.i2ptunnel.web.IndexBean.haveNonce(nonce)) {
                msgs.append(intl._t("Invalid form submission, probably because you used the 'back' or 'reload' button on your browser. Please resubmit."))
                    .append('\n')
                    .append(intl._t("If the problem persists, verify that you have cookies enabled in your browser."))
                    .append('\n');
            } else if (!action.equals("Generate") && !action.equals("Enable") && !action.equals("Disable")) {
                msgs.append("Unknown form action\n");
            } else if (action.equals("Generate") && newpw == null) {
                msgs.append("Password required\n");
            } else if (appNum == null || ksPath == null || jettySSLConfigPath == null || host == null || port == null) {
                msgs.append("Missing parameters\n");
            } else if (b32.length() <= 0) {
                msgs.append("No destination set - start tunnel first\n");
            } else if (name == null || !name.endsWith(".i2p")) {
                msgs.append("No hostname set - go back and configure\n");
            } else if (intPort <= 0) {
                msgs.append("No target port set - go back and configure\n");
            } else {
                boolean ok = true;

                if (action.equals("Generate")) {
                    // generate self-signed cert
                    java.util.Set<String> altNames = new java.util.HashSet<String>(4);
                    altNames.add(b32);
                    altNames.add(name);
                    if (!name.startsWith("www."))
                        altNames.add("www." + name);
                    if (altb32 != null && altb32.length() > 0)
                        altNames.add(altb32);
                    for (String s : spoofs.values()) {
                         // only add if apparently local, don't expose IP if routed externally
                         if (s.startsWith("127.") || s.startsWith("192.168.") || s.startsWith("10."))
                             altNames.add(s);
                    }
                    File ks = new File(ksPath);
                    if (ks.exists()) {
                        // old ks if any must be moved or deleted, as any keys
                        // under different alias for a different chain will confuse jetty
                        File ksb = new File(ksPath + ".bkup");
                        if (ksb.exists())
                            ksb = new File(ksPath + '-' + System.currentTimeMillis() + ".bkup");
                        boolean rok = net.i2p.util.FileUtil.rename(ks, ksb);
                        if (!rok)
                            ks.delete();
                    }
                    try {
                        boolean haveEC = net.i2p.crypto.SigType.ECDSA_SHA256_P256.isAvailable();
                        String alg = haveEC ? "EC" : "RSA";
                        int sz = haveEC ? 256 : 2048;
                        Object[] rv = KeyStoreUtil.createKeysAndCRL(ks, kspw, "eepsite", name, altNames, b32,
                                                                    3652, alg, sz, newpw);
                        msgs.append("Created self-signed certificate\n");
                        // save cert
                        java.security.cert.X509Certificate cert = (java.security.cert.X509Certificate) rv[2];
                        File f = new net.i2p.util.SecureFile(ctx.getConfigDir(), "certificates");
                        if (!f.exists())
                            f.mkdir();
                        f = new net.i2p.util.SecureFile(f, "eepsite");
                        if (!f.exists())
                            f.mkdir();
                        f = new net.i2p.util.SecureFile(f, b32 + ".crt");
                        if (f.exists()) {
                            File fb = new File(f.getParentFile(), b32 + ".crt-" + System.currentTimeMillis() + ".bkup");
                            net.i2p.util.FileUtil.copy(f, fb, false, true);
                        }
                        ok = net.i2p.crypto.CertUtil.saveCert(cert, f);
                        if (ok)
                            msgs.append("Self-signed certificate stored\n");
                        else
                            msgs.append("Self-signed certificate store failed\n");
                    } catch (IOException ioe) {
                        ioe.printStackTrace();
                        msgs.append("Self-signed certificate store failed ").append(DataHelper.escapeHTML(ioe.toString())).append('\n');
                        ok = false;
                    } catch (java.security.GeneralSecurityException gse) {
                        gse.printStackTrace();
                        msgs.append("Self-signed certificate store failed ").append(DataHelper.escapeHTML(gse.toString())).append('\n');
                        ok = false;
                    }

                    // rewrite jetty-ssl.xml
                    if (ok) {
                        String obf = JettyXmlConfigurationParser.obfuscate(newpw);
                        String obfkspw = JettyXmlConfigurationParser.obfuscate(kspw);
                        File f = new File(jettySSLConfigPath);
                        try {
                            org.eclipse.jetty.xml.XmlParser.Node root;
                            root = JettyXmlConfigurationParser.parse(f);
                            JettyXmlConfigurationParser.setValue(root, "KeyStorePath", ksPath);
                            JettyXmlConfigurationParser.setValue(root, "TrustStorePath", ksPath);
                            JettyXmlConfigurationParser.setValue(root, "KeyStorePassword", obfkspw);
                            JettyXmlConfigurationParser.setValue(root, "TrustStorePassword", obfkspw);
                            JettyXmlConfigurationParser.setValue(root, "KeyManagerPassword", obf);
                            File fb = new File(jettySSLConfigPath + ".bkup");
                            if (fb.exists())
                                fb = new File(jettySSLConfigPath + '-' + System.currentTimeMillis() + ".bkup");
                            ok = net.i2p.util.FileUtil.copy(f, fb, false, true);
                            if (ok) {
                                java.io.Writer w = null;
                                try {
                                    w = new java.io.OutputStreamWriter(new net.i2p.util.SecureFileOutputStream(f), "UTF-8");
                                    w.write("<?xml version=\"1.0\"?>\n" +
                                            "<!DOCTYPE Configure PUBLIC \"-//Jetty//Configure//EN\" \"http://www.eclipse.org/jetty/configure.dtd\">\n\n" +
                                            "<!-- Modified by SSL Wizard -->\n\n");
                                    JettyXmlConfigurationParser.write(root, w);
                                    msgs.append("Jetty configuration updated\n");
                                } catch (IOException ioe) {
                                    ioe.printStackTrace();
                                    ok = false;
                                } finally {
                                    if (w != null) try { w.close(); } catch (IOException ioe2) {}
                                }
                            } else {
                                msgs.append("Jetty configuration backup failed");
                            }
                        } catch (org.xml.sax.SAXException saxe) {
                            saxe.printStackTrace();
                            msgs.append("Jetty configuration parse failed ").append(DataHelper.escapeHTML(saxe.toString())).append('\n');
                            ok = false;
                        }
                    }
                }  // action == Generate

                // rewrite clients.config
                boolean isSSLEnabled = Boolean.parseBoolean(request.getParameter("isSSLEnabled"));
                boolean addssl = ok && !isSSLEnabled && !action.equals("Disable");
                boolean delssl = ok && isSSLEnabled && action.equals("Disable");
                if (addssl || delssl) {
                    String configfile = request.getParameter("clientConfigFile");
                    File f;
                    if (configfile == null || configfile.equals("clients.config")) {
                        f = new File(ctx.getConfigDir(), "clients.config");
                    } else if (configfile.contains("/") || configfile.contains("\\")) {
                        throw new IllegalArgumentException();
                    } else {
                        f = new File(ctx.getConfigDir(), "clients.config.d");
                        f = new File(f, configfile);
                    }
                    java.util.Properties p = new net.i2p.util.OrderedProperties();
                    try {
                        DataHelper.loadProps(p, f);
                        String k = "clientApp." + appNum + ".args";
                        String v = p.getProperty(k);
                        if (v == null) {
                            ok = false;
                        } else if (addssl) {
                            // TODO use net.i2p.i2ptunnel.web.SSLHelper.parseArgs(v) instead?
                            if (!v.contains(jettySSLConfigPath)) {
                                v += " \"" + jettySSLConfigPath + '"';
                                p.setProperty(k, v);
                                DataHelper.storeProps(p, f);
                                msgs.append("Jetty SSL enabled\n");
                            }
                        } else {
                            // action = disable
                            boolean save = false;
                            java.util.List<String> argList = net.i2p.i2ptunnel.web.SSLHelper.parseArgs(v);
                            if (argList.remove(jettySSLConfigPath)) {
                                StringBuilder buf = new StringBuilder(v.length());
                                for (String arg : argList) {
                                     buf.append(arg).append(' ');
                                }
                                v = buf.toString().trim();
                                p.setProperty(k, v);
                                DataHelper.storeProps(p, f);
                                msgs.append("Jetty SSL disabled\n");
                            }
                        }
                    } catch (IOException ioe) {
                        ioe.printStackTrace();
                        ok = false;
                    }
                }

                // stop and restart jetty
                // this only works if running already, else it isn't registered
                net.i2p.app.ClientAppManager cmgr = ctx.clientAppManager();
                net.i2p.app.ClientApp jstart = cmgr.getRegisteredApp("Jetty");
                if (ok && jstart != null) {
                    String fullname = jstart.getDisplayName();
                    if (fullname.contains(jettySSLConfigPath) ||
                        fullname.contains(jettySSLConfigPath.replace("jetty-ssl.xml", "jetty.xml"))) {
                        // ok, this is probably the right ClientApp
                        net.i2p.app.ClientAppState state = jstart.getState();
                        if (state == net.i2p.app.ClientAppState.RUNNING) {
                            try {
                                // app becomes untracked,
                                // see workaround in RouterAppManager
                                jstart.shutdown(null);
                                for (int i = 0; i < 20; i++) {
                                    state = jstart.getState();
                                    if (state == net.i2p.app.ClientAppState.STOPPED) {
                                        if (i < 4) {
                                            try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
                                        }
                                        msgs.append("Jetty server stopped\n");
                                        break;
                                    }
                                    try { Thread.sleep(250); } catch (InterruptedException ie) { break; }
                                }
                                if (state != net.i2p.app.ClientAppState.STOPPED)
                                    msgs.append("Jetty server stop failed\n");
                            } catch (Throwable t) {
                                msgs.append("Jetty server stop failed: " + t + '\n');
                            }
                        }
                        if (state == net.i2p.app.ClientAppState.STOPPED) {
                            try {
                                jstart.startup();
                                for (int i = 0; i < 20; i++) {
                                    state = jstart.getState();
                                    if (state == net.i2p.app.ClientAppState.RUNNING) {
                                        msgs.append("Jetty server restarted\n");
                                        break;
                                    }
                                    try { Thread.sleep(250); } catch (InterruptedException ie) { break; }
                                }
                                if (state != net.i2p.app.ClientAppState.RUNNING)
                                    msgs.append("Jetty server start failed\n");
                            } catch (Throwable t) {
                                msgs.append("Jetty server start failed: " + t + '\n');
                                ok = false;
                            }
                        }
                    } else {
                        //msgs.append("Unable to restart Jetty server\n");
                        // no embedded urls here!
                        msgs.append("You must start the Jetty server on the Client Configuration page.\n");
                    }
                } else if (ok) {
                    //msgs.append("Unable to restart Jetty server\n");
                    msgs.append("You must start the Jetty server on the Client Configuration page.\n");
                }

                // rewrite i2ptunnel.config
                Integer i443 = Integer.valueOf(443);
                boolean addtgt = ok && !tgts.containsKey(i443) && !action.equals("Disable");
                boolean deltgt = ok && tgts.containsKey(i443) && action.equals("Disable");
                if (addtgt || deltgt) {
                    if (addtgt) {
                        // update table for display
                        tgts.put(i443, host + ':' + port);
                        ports.add(i443);
                        // add ssl config
                        custom += " targetForPort.443=" + host + ':' + port;
                    } else {
                        // update table for display
                        tgts.remove(i443);
                        ports.remove(i443);
                        // remove ssl config
                        StringBuilder newCust = new StringBuilder(custom.length());
                        for (int i = 0; i < opts.length; i++) {
                             String opt = opts[i];
                             if (opt.startsWith("targetForPort.443="))
                                 continue;
                             newCust.append(opt).append(' ');
                        }
                        custom = newCust.toString().trim();
                    }
                    editBean.setNofilter_customOptions(custom);
                    // copy over existing settings
                    // we only set the applicable server settings
                    editBean.setTunnel(tun);
                    editBean.setType(tunnelType);
                    editBean.setName(editBean.getTunnelName(curTunnel));
                    editBean.setTargetHost(editBean.getTargetHost(curTunnel));
                    editBean.setTargetPort(editBean.getTargetPort(curTunnel));
                    editBean.setSpoofedHost(editBean.getSpoofedHost(curTunnel));
                    editBean.setPrivKeyFile(editBean.getPrivateKeyFile(curTunnel));
                    editBean.setAltPrivKeyFile(editBean.getAltPrivateKeyFile(curTunnel));
                    editBean.setNofilter_description(editBean.getTunnelDescription(curTunnel));
                    editBean.setTunnelDepth(Integer.toString(editBean.getTunnelDepth(curTunnel, 3)));
                    editBean.setTunnelQuantity(Integer.toString(editBean.getTunnelQuantity(curTunnel, 2)));
                    editBean.setTunnelBackupQuantity(Integer.toString(editBean.getTunnelBackupQuantity(curTunnel, 0)));
                    editBean.setTunnelVariance(Integer.toString(editBean.getTunnelVariance(curTunnel, 0)));
                    editBean.setTunnelDepthOut(Integer.toString(editBean.getTunnelDepthOut(curTunnel, 3)));
                    editBean.setTunnelQuantityOut(Integer.toString(editBean.getTunnelQuantityOut(curTunnel, 2)));
                    editBean.setTunnelBackupQuantityOut(Integer.toString(editBean.getTunnelBackupQuantityOut(curTunnel, 0)));
                    editBean.setTunnelVarianceOut(Integer.toString(editBean.getTunnelVarianceOut(curTunnel, 0)));
                    editBean.setReduceCount(Integer.toString(editBean.getReduceCount(curTunnel)));
                    editBean.setReduceTime(Integer.toString(editBean.getReduceTime(curTunnel)));
                    editBean.setCert(Integer.toString(editBean.getCert(curTunnel)));
                    editBean.setLimitMinute(Integer.toString(editBean.getLimitMinute(curTunnel)));
                    editBean.setLimitHour(Integer.toString(editBean.getLimitHour(curTunnel)));
                    editBean.setLimitDay(Integer.toString(editBean.getLimitDay(curTunnel)));
                    editBean.setTotalMinute(Integer.toString(editBean.getTotalMinute(curTunnel)));
                    editBean.setTotalHour(Integer.toString(editBean.getTotalHour(curTunnel)));
                    editBean.setTotalDay(Integer.toString(editBean.getTotalDay(curTunnel)));
                    editBean.setMaxStreams(Integer.toString(editBean.getMaxStreams(curTunnel)));
                    editBean.setPostMax(Integer.toString(editBean.getPostMax(curTunnel)));
                    editBean.setPostTotalMax(Integer.toString(editBean.getPostTotalMax(curTunnel)));
                    editBean.setPostCheckTime(Integer.toString(editBean.getPostCheckTime(curTunnel)));
                    editBean.setPostBanTime(Integer.toString(editBean.getPostBanTime(curTunnel)));
                    editBean.setPostTotalBanTime(Integer.toString(editBean.getPostTotalBanTime(curTunnel)));
                    editBean.setUserAgents(editBean.getUserAgents(curTunnel));
                    editBean.setEncryptKey(editBean.getEncryptKey(curTunnel));
                    editBean.setAccessMode(editBean.getAccessMode(curTunnel));
                    editBean.setAccessList(editBean.getAccessList(curTunnel));
                    editBean.setKey1(editBean.getKey1(curTunnel));
                    editBean.setKey2(editBean.getKey2(curTunnel));
                    editBean.setKey3(editBean.getKey3(curTunnel));
                    editBean.setKey4(editBean.getKey4(curTunnel));
                    if (editBean.getMultihome(curTunnel))
                        editBean.setMultihome("");
                    if (editBean.getReduce(curTunnel))
                        editBean.setReduce("");
                    if (editBean.getEncrypt(curTunnel))
                        editBean.setEncrypt("");
                    if (editBean.getUniqueLocal(curTunnel))
                        editBean.setUniqueLocal("");
                    if (editBean.isRejectInproxy(curTunnel))
                        editBean.setRejectInproxy("");
                    if (editBean.isRejectReferer(curTunnel))
                        editBean.setRejectReferer("");
                    if (editBean.isRejectUserAgents(curTunnel))
                        editBean.setRejectUserAgents("");
                    if (editBean.startAutomatically(curTunnel))
                        editBean.setStartOnLoad("");
                    editBean.setNonce(nonce);
                    editBean.setAction("Save changes");
                    String msg = editBean.getMessages();
                    msgs.append(msg);
                }
            }
%>
<div class="panel" id="messages">
<h2><%=intl._t("Status Messages")%></h2>
<table id="statusMessagesTable">
<tr>
<td id="tunnelMessages">
<textarea id="statusMessages" rows="4" cols="60" readonly="readonly"><%=msgs%></textarea>
</td>
</tr>
</table>
</div>
<%
        } // action != null
%>
<div class="panel" id="ssl">
<h2><%=intl._t("SSL Wizard")%> (<%=editBean.getTunnelName(curTunnel)%>)</h2>
<form method="post" action="ssl" accept-charset="UTF-8">
<input type="hidden" name="tunnel" value="<%=curTunnel%>" />
<input type="hidden" name="nonce" value="<%=net.i2p.i2ptunnel.web.IndexBean.getNextNonce()%>" />
<input type="hidden" name="type" value="<%=tunnelType%>" />
<input type="submit" class="default" name="action" value="Save changes" />
<table>
<!--<tr><td colspan="4" class="infohelp"><%=intl._t("Experts only!")%></td></tr>-->
<%
      if (("httpserver".equals(tunnelType)) || ("httpbidirserver".equals(tunnelType))) {
%>
<tr><td colspan="4"><b><%=intl._t("Hostname")%>:</b> <%=editBean.getSpoofedHost(curTunnel)%></td></tr>
<%
       }
       if (b64 == null || b64.length() < 516) {
%>
<tr><td class="infohelp"><%=intl._t("Local destination is not available. Start the tunnel.")%></td></tr>
<%
       } else if (name == null || name.equals("") || name.contains(" ") || !name.endsWith(".i2p")) {
           if (("httpserver".equals(tunnelType)) || ("httpbidirserver".equals(tunnelType))) {
%>
<tr><td class="infohelp"><%=intl._t("To enable registration verification, edit tunnel and set name (or website name) to a valid host name ending in '.i2p'")%></td></tr>
<%
           } else {
%>
<tr><td class="infohelp"><%=intl._t("To enable registration verification, edit tunnel and set name to a valid host name ending in '.i2p'")%></td></tr>
<%
           }
       } else {
           valid = true;
%>
<tr><td colspan="4"><b><%=intl._t("Base32")%>:</b> <%=b32%></td></tr>
<%
    if (altb32 != null && altb32.length() > 0) {
%>
<tr><td><b><%=intl._t("Alt Base32")%>:</b> <%=altb32%></td></tr>
<%
    }  // altb32
    final String CHECK = "&nbsp;&nbsp;&#x2714;";
%>
<!--<tr><th colspan="4"><%=intl._t("Incoming I2P Port Routing")%></th></tr>-->
<tr><th colspan="2"><%=intl._t("Virtual Host")%></th><!--<th><%=intl._t("Via SSL?")%></th>--><th><%=intl._t("Points at")%></th><th><%=intl._t("Preview")%></th></tr>
<!-- TODO: check if tunnel is running, else display "No preview" text -->
<tr><td colspan="2">http://<%=name%></td><!--<td><%=sslToTarget%></td>--><td><%=targetLink%></td><td><a class="control" title="<%=intl._t("Test HTTP server through I2P")%>" target="_blank" rel="noreferrer" href="http://<%=b32%>/"><%=intl._t("Preview")%></a></td></tr>
<%
    // output vhost and targets
    for (Integer port : ports) {
        boolean ssl = sslToTarget;
        boolean sslPort = false;
        String spoof = spoofs.get(port);
        if (spoof == null)
            spoof = name;
        // can't spoof for HTTPS
        if (port.intValue() == 443) {
            spoof = b32;
            if (altb32 != null && altb32.length() > 0)
                spoof += "<br />" + altb32;
            ssl = true;
            sslPort = true;
        }
        String tgt = tgts.get(port);
        if (tgt != null) {
            if (shouldLinkify) {
                String url = "://" + tgt + "\">" + tgt + "</a>";
                if (ssl)
                    tgt = "<a target=\"_blank\" href=\"https" + url;
                else
                    tgt = "<a target=\"_blank\" href=\"http" + url;
            }
        } else {
            tgt = targetLink;
        }
        String portTgt = sslPort ? "https" : "http";
%>
<!--<tr><td><a target="_blank" rel="noreferrer" href="<%=portTgt%>://<%=b32%>:<%=port%>/"><%=port%></a></td><td><%=spoof%></td><td><%=ssl%></td><td><%=tgt%></td></tr>-->
<!--TODO: logic to determine if destination is available-->
<tr><td colspan="2">https://<%=spoof%></td><!--<td><%=ssl%></td>--><td><%=tgt%></td><td>
<a class="control" title="<%=intl._t("Test HTTPS server through I2P")%>" target="_blank" rel="noreferrer" href="<%=portTgt%>://<%=b32%>:<%=port%>/"><%=intl._t("Preview")%></a></td></tr>
<%
    }
%>
<%--
<tr><th colspan="4"><%=intl._t("Add Port Routing")%></th></tr>
<tr><td><input type="text" size="6" maxlength="5" id="i2pPort" name="i2pPort" title="<%=intl._t("Specify the port the server is running on")%>" value="" class="freetext port" placeholder="<%=intl._t("required")%>" /></td>
<td><input type="text" size="20" id="websiteName" name="spoofedHost" title="<%=intl._t("Website Hostname e.g. mysite.i2p")%>" value="<%=name%>" class="freetext" /></td>
<td><input value="1" type="checkbox" name="useSSL" class="optbox slider" /></td>
<td><input type="text" size="20" name="targetHost" title="<%=intl._t("Hostname or IP address of the target server")%>" value="<%=targetHost%>" class="freetext host" /> :
<input type="text" size="6" maxlength="5" id="targetPort" name="targetPort" title="<%=intl._t("Specify the port the server is running on")%>" value="" class="freetext port" placeholder="<%=intl._t("required")%>" />
</td></tr>
--%>
<tr><th><%=intl._t("Server")%></th><th colspan="2"><%=intl._t("Configuration")%></th><th><!--<%=intl._t("SSL Activation")%>--></th></tr>
<%
    // Now try to find the Jetty server in clients.config
    File configDir = ctx.getConfigDir();
    File clientsConfig = new File(configDir, "clients.config");
    boolean isSingleFile = clientsConfig.exists();
    File[] configFiles;
    if (!isSingleFile) {
        File clientsConfigD = new File(configDir, "clients.config.d");
        configFiles = clientsConfigD.listFiles(new net.i2p.util.FileSuffixFilter(".config"));
    } else {
        configFiles = null;
    }
    java.util.Properties clientProps = new java.util.Properties();
    try {
        boolean foundClientConfig = false;
        int i = -1;
        int fileNum = 0;
        while (true) {
            if (isSingleFile) {
                // next config in the file
                i++;
                if (i == 0)
                    DataHelper.loadProps(clientProps, clientsConfig);
            } else {
                if (configFiles == null)
                    break;
                if (fileNum >= configFiles.length)
                    break;
                // load the next file
                clientProps.clear();
                clientsConfig = configFiles[fileNum++];
                DataHelper.loadProps(clientProps, clientsConfig);
                // only look at client 0 in file
                i = 0;
            }
            String prop = "clientApp." + i + ".main";
            String cls = clientProps.getProperty(prop);
            if (cls == null) {
                if (isSingleFile)
                    break;
                continue;
            }
            if (!cls.equals("net.i2p.jetty.JettyStart"))
                continue;
            prop = "clientApp." + i + ".args";
            String clArgs = clientProps.getProperty(prop);
            if (clArgs == null)
                continue;
            prop = "clientApp." + i + ".name";
            String clName = clientProps.getProperty(prop);
            if (clName == null)
                clName = intl._t("I2P webserver (eepsite)").replace("webserver", "Web Server");
            prop = "clientApp." + i + ".startOnLoad";
            String clStart = clientProps.getProperty(prop);
            boolean start = true;
            if (clStart != null)
                start = Boolean.parseBoolean(clStart);
            // sample args
            // clientApp.3.args="/home/xxx/.i2p/eepsite/jetty.xml" "/home/xxx/.i2p/eepsite/jetty-ssl.xml" "/home/xxx/.i2p/eepsite/jetty-rewrite.xml"
            boolean ssl = clArgs.contains("jetty-ssl.xml");

            boolean jettySSLFileInArgs = false;
            boolean jettySSLFileExists = false;
            boolean jettySSLFileValid = false;
            boolean jettySSLFilePWSet = false;
            File jettyFile = null, jettySSLFile = null;
            String ksPW = null, kmPW = null, tsPW = null;
            String ksPath = null, tsPath = null;
            String host = null, port = null;
            String sslHost = null, sslPort = null;
            String error = "";
            java.util.List<String> argList = net.i2p.i2ptunnel.web.SSLHelper.parseArgs(clArgs);
            for (String arg : argList) {
                if (arg.endsWith("jetty.xml")) {
                    jettyFile = new File(arg);
                    if (!jettyFile.isAbsolute())
                        jettyFile = new File(ctx.getConfigDir(), arg);
                } else if (arg.endsWith("jetty-ssl.xml")) {
                    jettySSLFile = new File(arg);
                    if (!jettySSLFile.isAbsolute())
                        jettySSLFile = new File(ctx.getConfigDir(), arg);
                    jettySSLFileInArgs = true;
                }
            }  // for arg in argList
            if (jettyFile == null || !jettyFile.exists())
                continue;
            try {
                org.eclipse.jetty.xml.XmlParser.Node root;
                root = JettyXmlConfigurationParser.parse(jettyFile);
                host = JettyXmlConfigurationParser.getValue(root, "host");
                port = JettyXmlConfigurationParser.getValue(root, "port");
                // now check if host/port match the tunnel
                if (!targetPort.equals(port))
                    continue;
                if (!targetHost.equals(host) && !"0.0.0.0".equals(host) && !"::".equals(host) &&
                    !((targetHost.equals("127.0.0.1") && "localhost".equals(host)) ||
                      (targetHost.equals("localhost") && "127.0.0.1".equals(host))))
                    continue;
            } catch (org.xml.sax.SAXException saxe) {
                saxe.printStackTrace();
                error = DataHelper.escapeHTML(saxe.getMessage());
                continue;
            }
            if (jettySSLFile == null && !argList.isEmpty()) {
                String arg = argList.get(0);
                File f = new File(arg);
                if (!f.isAbsolute())
                    f = new File(ctx.getConfigDir(), arg);
                File p = f.getParentFile();
                if (p != null)
                    jettySSLFile = new File(p, "jetty-ssl.xml");
            }
            boolean ksDflt = false;
            boolean kmDflt = false;
            boolean tsDflt = false;
            boolean ksExists = false;
            if (jettySSLFile.exists()) {
                jettySSLFileExists = true;
                try {
                    org.eclipse.jetty.xml.XmlParser.Node root;
                    root = JettyXmlConfigurationParser.parse(jettySSLFile);
                    ksPW = JettyXmlConfigurationParser.getValue(root, "KeyStorePassword");
                    kmPW = JettyXmlConfigurationParser.getValue(root, "KeyManagerPassword");
                    tsPW = JettyXmlConfigurationParser.getValue(root, "TrustStorePassword");
                    ksPath = JettyXmlConfigurationParser.getValue(root, "KeyStorePath");
                    tsPath = JettyXmlConfigurationParser.getValue(root, "TrustStorePath");
                    sslHost = JettyXmlConfigurationParser.getValue(root, "host");
                    sslPort = JettyXmlConfigurationParser.getValue(root, "port");
                    // we can't proceed unless they are there
                    // tsPW may be null
                    File ksFile = null;
                    boolean tsIsKs = true;
                    boolean ksArgs = ksPW != null && kmPW != null && ksPath != null && sslHost != null && sslPort != null;
                    /** 2015+ installs */
                    final String DEFAULT_KSPW_1 = KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD;
                    final String DEFAULT_KMPW_1 = "myKeyPassword";
                    /** earlier */
                    final String DEFAULT_KSPW_2 = "OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4";
                    final String DEFAULT_KMPW_2 = "OBF:1u2u1wml1z7s1z7a1wnl1u2g";
                    if (ksArgs) {
                        jettySSLFileValid = true;
                        ksDflt = ksPW.equals(DEFAULT_KSPW_1) || ksPW.equals(DEFAULT_KSPW_2);
                        kmDflt = kmPW.equals(DEFAULT_KMPW_1) || kmPW.equals(DEFAULT_KMPW_2);
                        ksFile = new File(ksPath);
                        if (!ksFile.isAbsolute())
                            ksFile = new File(ctx.getConfigDir(), ksPath);
                        ksExists = ksFile.exists();
                        tsIsKs = tsPath == null || ksPath.equals(tsPath);
                    }
                    if (tsPW != null) {
                        tsDflt = tsPW.equals(DEFAULT_KSPW_1) || tsPW.equals(DEFAULT_KSPW_2);
                    }
                } catch (org.xml.sax.SAXException saxe) {
                    saxe.printStackTrace();
                    error = DataHelper.escapeHTML(saxe.getMessage());
                }
            }
            boolean canConfigure = jettySSLFileExists && jettySSLFileValid;
            boolean isEnabled = canConfigure && jettySSLFileInArgs && ksExists && ports.contains(Integer.valueOf(443));
            boolean isPWDefault = kmDflt || !ksExists;
            foundClientConfig = true;
            // now start the output for this client

%>
<tr><td><%=DataHelper.escapeHTML(clName)%></td><td colspan="2">
<%
            for (String arg : argList) {
%>
<%=DataHelper.escapeHTML(arg)%><br />
<%
            }
%>
</td><td>
</td>
<!--</td><td><%=start%></td><td><%=ssl%></td></tr>-->
<%
            if (!jettySSLFileExists) {
%>
</td><td></td></tr>
<tr class="configerror"><td colspan="4">Cannot configure, Jetty SSL configuration file does not exist: <code><%=jettySSLFile.toString()%></code></td></tr>
<%
            } else if (!jettySSLFileValid) {
%>
</td><td></td></tr>
<tr class="configerror"><td colspan="4">Cannot configure, Jetty SSL configuration file is too old or invalid: <code><%=jettySSLFile.toString()%></code></td></tr>
<%
                if (error.length() > 0) {
%>
</td></tr>
<tr class="configerror"><td colspan="4"><%=error%></td></tr>
<%
                }
            } else {
%>
<tr style="display: none;" hidden="hidden"><td colspan="4">
<input type="hidden" name="clientAppNumber" value="<%=i%>" />
<input type="hidden" name="clientConfigFile" value="<%=clientsConfig.getName()%>" />
<input type="hidden" name="isSSLEnabled" value="<%=isEnabled%>" />
<input type="hidden" name="nofilter_ksPath" value="<%=ksPath%>" />
<input type="hidden" name="nofilter_jettySSLFile" value="<%=jettySSLFile%>" />
<input type="hidden" name="jettySSLHost" value="<%=sslHost%>" />
<input type="hidden" name="jettySSLPort" value="<%=sslPort%>" />
<%
                if (ksPW != null) {
                    if (!ksPW.startsWith("OBF:"))
                        ksPW = JettyXmlConfigurationParser.obfuscate(ksPW);
%>
<input type="hidden" name="nofilter_obfKeyStorePassword" value="<%=ksPW%>" />
<%
                }
%>
</td></tr>
<%
                if (isEnabled && !isPWDefault) {
%>
<tr><td class="buttons" colspan="4">
<button id="controlSave" class="control" type="submit" name="action" value="Disable"><%=intl._t("Disable SSL")%></button></td></tr>
<%
                } else if (!isPWDefault) {
%>
<tr><td class="buttons" colspan="4">
<button id="controlSave" class="control" type="submit" name="action" value="Enable"><%=intl._t("Enable SSL")%></button></td></tr>
<%
                } else {
%>
<tr><td class="buttons" colspan="4"><b><%=intl._t("New Certificate Password")%>:</b>
<input type="password" name="nofilter_keyPassword" title="<%=intl._t("Password (required to encrypt the certificate)")%>" value="" class="freetext password" placeholder="<%=intl._t("required")%>" />
<%
                    if (isEnabled) {
%>
<button id="controlSave" class="control" type="submit" name="action" value="Generate"><%=intl._t("Generate new SSL certificate")%></button>
<%
                    } else {
%>
<button id="controlSave" class="control" type="submit" name="action" value="Generate"><%=intl._t("Generate SSL certificate and enable")%></button>
<%
                    }
                }
%>
</td></tr>
<%
                break;
            }  // canConfigure
        }  // while (for each client or client file)
        if (!foundClientConfig) {
%>
<tr class="configerror"><td colspan="4"><%=intl._t("Cannot configure, no Jetty server found in {0}client configurations{1} that matches this tunnel", "<a href=\"/configclients\">", "</a>")%><br><%=intl._t("Support for non-Jetty servers will be added in a future release")%></td></tr>
<%
        }
    } catch (IOException ioe) { ioe.printStackTrace(); }
%>
<tr><td colspan="4" class="buttons"><a class="control" href="list"><%=intl._t("Return to Tunnel Manager Index")%></a></td></tr>
</table>
</form>
<%
       }  // valid b64 and name
    }  // !"new".equals(tunnelType)
    if (!valid && curTunnel >= 0) {
%>
<table>
<tr><td class="buttons"><a class="control" href="edit?tunnel=<%=curTunnel%>"><%=intl._t("Return to Tunnel Editor")%></a></td></tr>
</table>
<%
    }  // !valid
%>
</div>
<%
  } else {
%>
<div id="notReady"><%=intl._t("Initializing Tunnel Manager{0}", "&hellip;")%><noscript><%=intl._t("Tunnels not initialized yet; please retry in a few moments.").replace("yet;", "yet&hellip;<br>")%></noscript></div>
<script nonce="<%=cspNonce%>" type="text/javascript">
  setInterval(function() {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', '/i2ptunnel/?' + new Date().getTime(), true);
    xhr.responseType = "text";
    xhr.onreadystatechange = function () {
      if (xhr.readyState==4 && xhr.status==200) {
        document.getElementById("page").innerHTML=xhr.responseText;
      }
    }
    xhr.send();
  }, 5000);
</script>
<%
  }  // isInitialized()
%>
<span data-iframe-height></span>
<style type="text/css">body {opacity: 1 !important;}</style>
</body>
</html>
