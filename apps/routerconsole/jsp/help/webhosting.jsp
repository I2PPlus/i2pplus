<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML>
<html>
<head>
<title>Web Hosting - I2P+</title>
<%@include file="../css.jsi" %>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="../summary.jsi" %>
<h1 class="hlp">Web hosting on I2P</h1>
<div class="main" id="help">
<div class="confignav">
<span class="tab"><a href="/help/configuration">Configuration</a></span>
<span class="tab"><a href="/help/advancedsettings">Advanced Settings</a></span>
<span class="tab"><a href="/help/ui">User Interface</a></span>
<span class="tab"><a href="/help/reseed">Reseeding</a></span>
<span class="tab"><a href="/help/tunnelfilter">Tunnel Filtering</a></span>
<span class="tab"><a href="/help/faq">FAQ</a></span>
<span class="tab"><a href="/help/newusers">New User Guide</a></span>
<span class="tab2">Web Hosting</span>
<span class="tab"><a href="/help/hostnameregistration">Hostname Registration</a></span>
<span class="tab"><a href="/help/troubleshoot">Troubleshoot</a></span>
<span class="tab"><a href="/help/glossary">Glossary</a></span>
<span class="tab"><a href="/help/legal">Legal</a></span>
<span class="tab"><a href="/help/changelog">Change Log</a></span>
</div>
<div id="webhosting">

<h2>Introduction to Hosting Websites on I2P</h2>

<p>By default, I2P is supplied with a webserver to host your own anonymized website (traditionally referred to as an <i>eepsite</i>). Both the site and the I2P Router Console are running on a streamlined version of <a href="https://en.wikipedia.org/wiki/Jetty_(web_server)" target="_blank" rel="noreferrer" class="sitelink external">Jetty</a>, customized for I2P.</p>

<p>To serve your own static content, you only need to edit or replace the files in the webserver's root directory, and the site will be available on the I2P network after you've followed the instructions below. Additionally, cgi and python scripts can be run from the <code>cgi-bin</code> directory immediately above <code>docroot</code> if you have <a href="https://www.perl.org/about.html" target="_blank" rel="noreferrer" class="sitelink external">perl</a> and <a href="https://www.python.org/" target="_blank" rel="noreferrer" class="sitelink external">python</a> installed.</p>

<p>Directory listings (sometimes referred to as virtual directories) are enabled by default, so you can host files from a sub-directory of <code>docroot</code> without providing a page with links to the files. You can also serve content that exists outside of <code>docroot</code> by using a <a href="https://en.wikipedia.org/wiki/Symbolic_link" target="_blank" rel="noreferrer" class="sitelink external">symbolic link</a> to the desired directory.</p>

<p>You can override the <a href="http://127.0.0.1:7658/help/images/">default appearance</a> of the directory listing by supplying an edited <a href="http://127.0.0.1:7658/.resources/jetty-dir.css">jetty-dir.css</a> file to set a global appearance. Note that this file will not be displayed in the webserver's directory listing. Files and directories prefixed with . and files named favicon.ico or files ending with ~ or .bak or .old will also be hidden from view (though still accessible). Note: To create hidden files with a . prefix on Windows, add a trailing . to the file or directory e.g. <code>.hidden.</code>; Windows explorer will then save the file with the correct name, removing the trailing dot in the process.</p>

<p>To disable directory listings, find the entry for <code>org.eclipse.jetty.servlet.Default.dirAllowed</code> in the <code>base-context.xml</code> file, located in the <code>eepsite/contexts</code> folder (just above docroot folder), and change 'true' to 'false' immediately underneath. To apply the change immediately, stop and restart the <i>I2P Webserver</i> client on the <a href="http://127.0.0.1:7657/configclients">Client Configuration page</a>. You can also drop an empty <code>index.html</code> file in any exposed directory to suppress directory listings for that location.</p>

<table id="rootdir">
<tr><th colspan="2">Location of the I2P webserver root directory</th></tr>
<tr><td>Windows</td><td><code>%APPDATA%\I2P\eepsite\docroot\</code></td></tr>
<tr><td>Apple OS X</td><td><code>/Users/(user)/Library/Application Support/i2p</code></td></tr>
<tr><td>Linux</td><td><code>~/.i2p/eepsite/docroot/</code> (Standard Java installation)<br>
<code>/var/lib/i2p/i2p-config/eepsite/docroot/</code> (Package/repository installation)</td></tr>
</table>

<p>On the I2P network, remotely hosted services can be accessed using a <a href="https://en.wikipedia.org/wiki/Base32" target="_blank" rel="noreferrer" class="sitelink external">Base32</a> address ending in ".b32.i2p", a <i>destination</i> represented as a long <a href="https://en.wikipedia.org/wiki/Base64" class="sitelink external">Base64</a> string, or more usually by using an .i2p domain. A destination is I2P's equivalent to an IP address, and is shown on the <a href="http://127.0.0.1:7657/i2ptunnel/edit?tunnel=3">Tunnel Manager Configuration page</a>. You can share your b32.i2p address to allow others to access to your website until you've registered your own .i2p domain.</p>

<h3>Setting up &amp; announcing your website</h3>

<p>Your webserver is running by default at <a href="http://127.0.0.1:7658/">http://127.0.0.1:7658/</a>, but is not accessible by others until you start the <i>I2P Webserver</i> tunnel. After starting the tunnel, your site will be difficult for other people to find. You could just tell people the destination or the Base32 address, but thankfully I2P has an address book to store domain names, and various services where you can <a href="/help/hostnameregistration">register a new .i2p domain</a> for free.</p>

<ul>
<li>Choose a name for your website (<i>something</i>.i2p), using lower-case. Enter the new name for your website on the <a href="http://127.0.0.1:7657/i2ptunnel/edit?tunnel=3">Tunnel Manager Configuration page</a> where it says <i>Website name</i>, replacing the default <i>mysite.i2p</i> placeholder. If you want your website to be available when I2P starts, check the <i>Auto Start</i> box and click <i>Save</i> at the bottom of the page.</li>
<li>Click the <i>Start</i> button for your webserver tunnel on the <a href="http://127.0.0.1:7657/i2ptunnel/">main Tunnel Manager page</a>. You should now see it listed under <i>Service Tunnels</i> on the <a href="http://127.0.0.1:7657/">Router Console</a> sidebar. A green star displayed next to the tunnel name (<i>I2P Webserver</i> by default) indicates that your website is active on the I2P network.</li>
<li>Highlight and copy the entire <i>Local destination</i> on the <a href="http://127.0.0.1:7657/i2ptunnel/edit?tunnel=3">Tunnel Manager Configuration page</a>.</li>
<li>Enter the name and paste the destination into your <a href="http://127.0.0.1:7657/susidns/addressbook?book=router&amp;filter=none">address book</a>. Click <i>Add</i> to save the new entry.</li>
<li>In the web browser you have configured for I2P usage, browse to your website name (<i>something</i>.i2p) and you should be returned to this page.</li>
</ul>

<p>Before you tell the world about your new website, you should add some content. Go to the server's root directory <a href="#rootdir">listed above</a> and replace the <i>index.html</i> redirect page with your own content. If you need a template for a basic site, feel free to adapt <a href="pagetemplate.html">this page</a>. If you're returned to this page after editing the content, try clearing your browser's web cache.</p>

<h3>Content Optimization</h3>

<p>In order to provide the best experience for visitors to your site, there are various additional steps you may wish to consider once your site is up and running:</p>

<ul>
<li><b>Optimize your images</b><br>
If you want your site to feel responsive, image compression is essential when hosting large images. Using a service like <a href="https://tinypng.com/" target="blank" class="sitelink external">tinypng.com</a> to compress your png and jpeg images will often result in much smaller files with minimal loss of quality. In some situations the webp format can best jpegs, generating smaller files with approximately the same or better visual fidelity, and has wide browser support. Also consider using svgs instead of bitmaps for icons and illustrations - they're often smaller than the equivalent bitmap file (run them through a service like <a href="https://vecta.io/nano" target="_blank" class="sitelink external">Nano</a> to remove unneeded cruft), scale without loss of quality, and can be gzipped by your webserver.</li>

<li><b>Use separate files for multi-use assets</b><br>
Wherever possible, assets that are used repeatedly on your site should be loaded from an external file and not inlined. If you're using the same CSS on several pages, create a separate css file and load that in the <code>&lt;head&gt;</code> section of your pages. This will make styling updates to your site easier to manage, and will also allow the file to be cached in the visitor's browser, resulting in less bandwidth demands on your webserver and faster page loads for your visitors.
</li>

<li><b>Consider using a backend cache</b><br>
If you're running a dynamic site handling a large number of concurrent users, content caching can often improve the time taken to deliver pages. For content that rapidly changes, a <a href="https://blog.stackpath.com/glossary-micro-caching/" target="_blank" class="sitelink external">micro-caching strategy</a> may still yield some benefits. The <a href="https://www.nginx.com/" target="_blank" class="sitelink external">Nginx webserver</a> provides a competent caching sub-system, or for a more heavy-weight solution, have a look at <a href="https://varnish-cache.org/" target="_blank" class="sitelink external">Varnish</a>.</li>
</ul>

<h3>Using an alternative webserver</h3>

<p>To configure an alternative webserver for use on I2P, you can either use the existing webserver tunnel and <a href="http://127.0.0.1:7657/configclients">disable the default webserver</a> from running, or create a new HTTP Server tunnel in the <a href="http://127.0.0.1:7657/i2ptunnelmgr">Tunnel Manager</a>. Ensure that the webserver's <i>listening port</i> is also configured in the Tunnel Manager settings. For example, if your webserver is listening by default on address 127.0.0.1 port 80, you'd also need to ensure that the <i>Target port</i> in the Tunnel Manager settings page for the service is also configured to port 80.</p>

<p>To preserve anonymity, make sure that the webserver is not publicly available outside of the I2P network, which is normally achieved by configuring the webserver to listen on localhost (127.0.0.1) rather than all interfaces (0.0.0.0).</p>

<p>Be aware that a poorly configured webserver or web appplication can leak information such as your real IP address or server details that may reduce your anonymity or assist a hacker. If using an alternative platform, be sure to secure it before putting it online. If in doubt, consult online guides about web server and web application hardening, for example:

<ul id="guides">
<li><a href="https://geekflare.com/apache-web-server-hardening-security/" target="_blank" rel="noreferrer" class="sitelink external">Apache Web Server Security Guide</a></li>
<li><a href="https://geekflare.com/nginx-webserver-security-hardening-guide/" target="_blank" rel="noreferrer" class="sitelink external">Nginx Web Server Security Guide</a></li>
<li><a href="https://www.wordfence.com/learn/how-to-harden-wordpress-sites/" target="_blank" rel="noreferrer" class="sitelink external">How to Harden Your WordPress Site</a></li>
</ul>

<p><b>Note:</b> On some Apache installations, the <a href="https://httpd.apache.org/docs/2.4/mod/mod_status.html" target="blank" class="sitelink external">mod_status</a> and <a href="https://httpd.apache.org/docs/2.4/mod/mod_info.html" target="blank" class="sitelink external">mod_info</a> modules are enabled by default. It is important to disable these, or otherwise protect access to the urls, to avoid compromising the anonymity and security of your server.</p>

</div>
</div>
<%@include file="../summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>