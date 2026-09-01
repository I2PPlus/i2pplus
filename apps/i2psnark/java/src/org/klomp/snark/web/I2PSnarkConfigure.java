package org.klomp.snark.web;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.servlet.http.HttpServletRequest;

import org.klomp.snark.ClientID;
import org.klomp.snark.I2PSnarkUtil;
import org.klomp.snark.SnarkManager;
import org.klomp.snark.TorrentCreateFilter;
import org.klomp.snark.Tracker;

import net.i2p.CoreVersion;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Translate;

/**
 * Renders the three forms of the /configure page - main settings, torrent
 * creation filters, and tracker management - and applies submitted settings
 * back to SnarkManager. Extracted wholesale from I2PSnarkServlet; bodies are
 * verbatim moves qualified for servlet access via {@code srv}.
 */
class I2PSnarkConfigure {

    private final I2PSnarkServlet srv;

    I2PSnarkConfigure(I2PSnarkServlet srv) {this.srv = srv;}

    private static final int[] times = { 5, 15, 30, 60, 2*60, 5*60, 10*60, 30*60, 60*60, -1 };

    /**
     * Writes the HTML configuration form with all I2PSnark settings.
     *
     * @param out the PrintWriter to write the HTML output
     * @param req the HTTP request containing query parameters
     * @throws IOException if an I/O error occurs during writing
     */
    void writeConfigForm(PrintWriter out, HttpServletRequest req) throws IOException {
        StringBuilder buf = new StringBuilder(16*1024);
        String lang = Translate.getLanguage(srv.manager().util().getContext());
        buf.append("<form id=mainconfig action=\"").append(srv.contextPath()).append("/configure\" method=POST>\n")
           .append("<div class=\"configPanel lang_").append(lang).append("\"><div class=snarkConfig>\n");
        srv.writeHiddenInputs(buf, req, "Save");
        buf.append("<span class=configTitle>").append(srv._t("Configuration")).append("</span><hr>\n")
           .append("<table border=0 id=configs>\n");

        // Section map: each panel fetches its own manager state so no flag
        // threading crosses panel boundaries.
        appendUIConfig(buf, req);
        appendCommentsRatingsConfig(buf);
        appendTorrentOptionsConfig(buf);
        appendDataStorageConfig(buf);
        appendTunnelConfig(buf);
        appendBrowserIntegrationConfig(buf, req);
        appendSaveConfigRow(buf, req);

        out.append(buf);
        out.flush();
        buf.setLength(0);
    }

    /**
     * User Interface panel: theme, refresh interval, page size, language,
     * collapsible panels, filter bar, lightbox, Add/Create persistence.
     */
    private void appendUIConfig(StringBuilder buf, HttpServletRequest req) {
        boolean collapsePanels = srv.manager().util().collapsePanels();
        boolean showStatusFilter = srv.manager().util().showStatusFilter();
        boolean enableLightbox = srv.manager().util().enableLightbox();
        boolean enableAddCreate = srv.manager().util().enableAddCreate();
        boolean noCollapse = I2PSnarkServlet.noCollapsePanels(req);
        String lang = Translate.getLanguage(srv.manager().util().getContext());

        buf.append("<tr><th class=suboption>").append(srv._t("User Interface"));
        if (srv.context().isRouterContext()) {
            buf.append("&nbsp;&nbsp;<a href=\"/torrents?configure\" target=_top class=script id=embed>")
               .append(srv._t("Switch to Embedded Mode")).append("</a>")
               .append("<a href=\"").append(srv.contextPath()).append("/configure\" target=_top class=script id=fullscreen>")
               .append(srv._t("Switch to Fullscreen Mode")).append("</a>");
        }
        buf.append("</th></tr>\n<tr><td>\n<div class=optionlist>\n").append("<span class=configOption><b>")
           .append(srv._t("Theme")).append("</b> \n");
        if (srv.manager().getUniversalTheming()) {
            buf.append("<select id=themeSelect name=theme disabled title=\"")
               .append(srv._t("To change themes manually, disable universal theming"))
               .append("\"><option>")
               .append(srv.manager().getTheme())
               .append("</option></select> <span id=bwHoverHelp>");
            srv.appendIcon(buf, "details", "", "", true, true);
            buf.append("<span id=bwHelp>")
               .append(srv._t("Universal theming is enabled."))
               .append("</span></span> <a href=\"/configui\" target=_blank>[")
               .append(srv._t("Configure"))
               .append("]</a></span><br>");
        } else {
            buf.append("<select id=themeSelect name=theme>");
            String theme = srv.manager().getTheme();
            String[] themes = srv.manager().getThemes();
            Arrays.sort(themes);
            for (int i = 0; i < themes.length; i++) {
                if (themes[i].equals(theme)) {
                    buf.append("\n<OPTION value=\"").append(themes[i]).append("\" SELECTED>").append(themes[i]);
                } else {
                    buf.append("\n<OPTION value=\"").append(themes[i]).append("\">").append(themes[i]);
                }
            }
            buf.append("</select>\n</span><br>\n");
        }

        buf.append("<span class=configOption><b>").append(srv._t("Refresh time"))
           .append("</b> \n<select name=refreshDelay title=\"")
           .append(srv._t("How frequently torrent status is updated on the main page")).append("\">");
        int delay = srv.manager().getRefreshDelaySeconds();
        for (int i = 0; i < times.length; i++) {
            buf.append("<option value=\"").append(Integer.toString(times[i])).append("\"");
            if (times[i] == delay) {buf.append(" selected");}
            buf.append(">");
            if (times[i] > 0) {buf.append(DataHelper.formatDuration2((long) times[i] * 1000));}
            else {buf.append(srv._t("Never"));}
            buf.append("</option>\n");
        }
        buf.append("</select>\n</span><br>\n")
           .append("<span class=configOption><label><b>")
           .append(srv._t("Page size"))
           .append("</b> <input type=text name=pageSize size=5 maxlength=4 min=10 pattern=\"[0-9]{0,4}\" ")
           .append("class=\"r numeric\" title=\"")
           .append(srv._t("Maximum number of torrents to display per page"))
           .append("\" value=\"").append(srv.manager().getPageSize()).append("\"> ")
           .append(srv._t("torrents"))
           .append("</label></span><br>\n");

        if (srv.isStandalone()) {
            // Reflectively probe the standalone-only ConfigUIHelper; in the
            // webapp build it is absent and the option is simply not shown.
            try {
                Class<?> helper = Class.forName("org.klomp.snark.standalone.ConfigUIHelper");
                Method getLangSettings = helper.getMethod("getLangSettings", I2PAppContext.class);
                String langSettings = (String) getLangSettings.invoke(null, srv.context());
                buf.append("<span class=configOption><b>").append(srv._t("Language")).append("</b> ")
                   .append(langSettings).append("</span><br>\n");
            } catch (ClassNotFoundException | NoSuchMethodException |
                     IllegalAccessException | InvocationTargetException e) {
                // expected in non-standalone builds
            }
        } else {
            buf.append("<span class=configOption><b>").append(srv._t("Language")).append("</b> ")
               .append("<span id=snarkLang>").append(lang).append("</span> ")
               .append("<a href=\"/configui#langheading\" target=_blank>").append("[").append(srv._t("Configure")).append("]</a>")
               .append("</span><br>\n");
        }

        buf.append("<span class=configOption><label for=collapsePanels><b>")
           .append(srv._t("Collapsible panels"))
           .append("</b> </label><input type=checkbox class=\"optbox slider\" ")
           .append("name=collapsePanels id=collapsePanels ")
           .append((collapsePanels ? "checked " : ""))
           .append("title=\"");
        if (noCollapse) {
            String ua = req.getHeader("user-agent");
            buf.append(srv._t("Your browser does not support this feature.")).append("[").append(ua).append("]").append("\" disabled");
        } else {
            buf.append(srv._t("Allow the 'Add Torrent' and 'Create Torrent' panels to be collapsed, and collapse by default in non-embedded mode")).append("\"");
        }
        buf.append("></span><br>\n")
           .append("<span class=configOption><label for=showStatusFilter><b>")
           .append(srv._t("Torrent filter bar"))
           .append("</b> </label><input type=checkbox class=\"optbox slider\" ")
           .append("name=showStatusFilter id=showStatusFilter ")
           .append((showStatusFilter ? "checked " : ""))
           .append("title=\"")
           .append(srv._t("Show filter bar above torrents for selective display based on status"))
           .append(" (").append(srv._t("requires javascript")).append(")")
           .append("\"></span><br>\n")
           .append("<span class=configOption><label for=enableLightbox><b>")
           .append(srv._t("Enable lightbox"))
           .append("</b> </label><input type=checkbox class=\"optbox slider\" ")
           .append("name=enableLightbox id=enableLightbox ")
           .append((enableLightbox ? "checked " : ""))
           .append("title=\"")
           .append(srv._t("Use a lightbox to display images when thumbnails are clicked"))
           .append(" (").append(srv._t("requires javascript")).append(")")
           .append("\"></span><br>\n")
           .append("<span class=configOption><label for=enableAddCreate><b>")
           .append(srv._t("Persist Add/Create"))
           .append("</b> </label><input type=checkbox class=\"optbox slider\" ")
           .append("name=enableAddCreate id=enableAddCreate ")
           .append((enableAddCreate ? "checked " : ""))
           .append("title=\"")
           .append(srv._t("Display the 'Add' and 'Create' sections on all torrent listing pages when in multipage mode"))
           .append("\"></span><br>\n")
           .append("</div>\n</td></tr>\n");
    }

    /** Comments &amp; Ratings panel: global toggles and the author name. */
    private void appendCommentsRatingsConfig(StringBuilder buf) {
        boolean useRatings = srv.manager().util().ratingsEnabled();
        boolean useComments = srv.manager().util().commentsEnabled();

        buf.append("<tr><th class=suboption>")
           .append(srv._t("Comments &amp; Ratings"))
           .append("</th></tr>\n<tr><td>\n<div class=optionlist>\n")
           .append("<span class=configOption><label for=ratings><b>")
           .append(srv._t("Enable Ratings"))
           .append("</b></label> <input type=checkbox class=\"optbox slider\" name=ratings id=ratings ")
           .append(useRatings ? "checked " : "")
           .append("title=\"")
           .append(srv._t("Show ratings on torrent pages"))
           .append("\"></span><br>\n")
           .append("<span class=configOption><label for=comments><b>")
           .append(srv._t("Enable Comments"))
           .append("</b></label> <input type=checkbox class=\"optbox slider\" name=comments id=comments ")
           .append(useComments ? "checked " : "")
           .append("title=\"")
           .append(srv._t("Show comments on torrent pages"))
           .append("\"></span><br>\n")
           .append("<span class=configOption id=configureAuthor><label><b>")
           .append(srv._t("Comment Author"))
           .append("</b> <input type=text name=nofilter_commentsName spellcheck=false value=\"")
           .append(DataHelper.escapeHTML(srv.manager().util().getCommentsName())).append("\" size=15 maxlength=16 title=\"")
           .append(srv._t("Set the author name for your comments and ratings"))
           .append("\"></label></span>\n")
           .append("</div>\n</td></tr>\n");
    }

    /** Torrent Options panel: bandwidth, uploaders, open trackers, DHT, auto-start. */
    private void appendTorrentOptionsConfig(StringBuilder buf) {
        boolean useOpenTrackers = srv.manager().util().shouldUseOpenTrackers();
        boolean useDHT = srv.manager().util().shouldUseDHT();
        boolean autoStart = srv.manager().shouldAutoStart();

        buf.append("<tr><th class=suboption>")
           .append(srv._t("Torrent Options"))
           .append("</th></tr>\n<tr><td>\n<div class=optionlist>\n")
           .append("<span id=bwAllocation class=configOption title=\"").append(srv._t("Half available bandwidth recommended.")).append("\">")
           .append("<b>").append(srv._t("Bandwidth limit")).append("</b> ")
           .append("<span id=bwDown></span><input type=text name=downBW class=\"r numeric\" value=\"")
           .append(srv.manager().getBandwidthListener().getDownBWLimit() / 1024).append("\" size=5 maxlength=4 pattern=\"[0-9]{1,4}\"")
           .append(" title=\"").append(srv._t("Maximum bandwidth allocated for downloading")).append("\"> KB/s down")
           .append(" <span id=bwUp></span><input type=text name=upBW class=\"r numeric\" value=\"")
           .append(srv.manager().util().getMaxUpBW()).append("\" size=5 maxlength=4 pattern=\"[0-9]{1,4}\"")
           .append(" title=\"").append(srv._t("Maximum bandwidth allocated for uploading")).append("\"> KB/s up");
        if (srv.context().isRouterContext()) {
            buf.append(" <a href=\"/config.jsp\" target=_blank title=\"")
               .append(srv._t("View or change router bandwidth"))
               .append("\">[")
               .append(srv._t("Configure"))
               .append("]</a>");
        }

        buf.append("</span><br>\n");
        buf.append("<span class=configOption><label><b>")
           .append(srv._t("Total uploader limit"))
           .append("</b> <input type=text name=upLimit class=\"r numeric\" value=\"")
           .append(srv.manager().util().getMaxUploaders()).append("\" size=5 maxlength=3 pattern=\"[0-9]{1,3}\"")
           .append(" title=\"")
           .append(srv._t("Maximum number of peers to upload to"))
           .append("\"> ")
           .append(srv._t("peers"))
           .append("</label></span><br>\n")
           .append("<span class=configOption><label for=useOpenTrackers><b>")
           .append(srv._t("Use open trackers also").replace(" also", ""))
           .append("</b></label> <input type=checkbox class=\"optbox slider\" name=useOpenTrackers id=useOpenTrackers ")
           .append(useOpenTrackers ? "checked " : "")
           .append("title=\"")
           .append(srv._t("Announce torrents to open trackers as well as trackers listed in the torrent file"))
           .append("\"></span><br>\n")
           .append("<span class=configOption><label for=useDHT><b>")
           .append(srv._t("Enable DHT"))
           .append("</b></label> <input type=checkbox class=\"optbox slider\" name=useDHT id=useDHT ")
           .append(useDHT ? "checked " : "")
           .append("title=\"")
           .append(srv._t("Use DHT to find additional peers"))
           .append("\"></span><br>\n")
           .append("<span class=configOption><label for=autoStart><b>")
           .append(srv._t("Auto start torrents"))
           .append("</b> </label><input type=checkbox class=\"optbox slider\" name=autoStart id=autoStart")
           .append(autoStart ? " checked" : "")
           .append(" title=\"")
           .append(srv._t("Automatically start torrents when added and restart torrents when I2PSnark starts"))
           .append("\"></span>");

        if (srv.context().isRouterContext()) {
            buf.append("<br>\n<span class=configOption id=startupDelay><label><b>")
               .append(srv._t("Startup delay")).append(" (").append(srv._t("minutes")).append(")")
               .append("</b> <input type=text name=startupDelayMin size=5 maxlength=4 pattern=\"[0-9]{1,4}\" class=\"r numeric\"")
               .append(" title=\"")
               .append(srv._t("How long before auto-started torrents are loaded when I2PSnark starts, at the earliest"))
               .append("\" value=\"").append(srv.manager().util().getStartupDelayMin()).append("\"> ")
               .append(srv._t("min"))
               .append("</label> <label><input type=text name=startupDelayMax size=5 maxlength=4 pattern=\"[0-9]{1,4}\" class=\"r numeric\"")
               .append(" title=\"")
               .append(srv._t("How long before auto-started torrents are loaded when I2PSnark starts, at the latest"))
               .append("\" value=\"").append(srv.manager().util().getStartupDelayMax()).append("\"> ")
               .append(srv._t("max"))
               .append("</label></span>");
        }
        buf.append("\n</div>\n</td></tr>\n");

    }

    /** Data Storage panel: directories, permissions, preallocation, file cap. */
    private void appendDataStorageConfig(StringBuilder buf) {
        String dataDir = srv.manager().getDataDir().getAbsolutePath();
        String tempDir = srv.manager().util().getTempDirProp();
        String torrentDir = srv.manager().getTorrentDir().getAbsolutePath();
        boolean filesPublic = srv.manager().areFilesPublic();
        boolean preallocateFiles = srv.manager().util().getPreallocateFiles();
        boolean preserveFileNames = srv.manager().util().getPreserveFileNames();

        buf.append("<tr><th class=suboption>")
           .append(srv._t("Data Storage"))
           .append("</th></tr><tr><td>\n<div class=optionlist>\n")
           .append("<span class=configOption><label><b>")
           .append(srv._t("Data directory"))
           .append("</b> <input type=text name=nofilter_dataDir size=60").append(" title=\"")
           .append(srv._t("Directory where torrents and downloaded/shared files are stored"))
           .append("\" value=\"").append(DataHelper.escapeHTML(dataDir)).append("\" spellcheck=false></label></span><br>\n")
            .append("<span class=configOption><label><b>")
           .append(srv._t("Temp directory"))
           .append("</b> <input type=text name=nofilter_tempDir size=60").append(" title=\"")
           .append(srv._t("Optional directory where downloads are staged before being moved into the data directory on completion. Leave empty to disable."))
           .append("\" value=\"").append(tempDir != null ? DataHelper.escapeHTML(tempDir) : "").append("\" spellcheck=false></label></span><br>\n")
           .append("<span class=configOption><label><b>")
           .append(srv._t("Torrent directory"))
           .append("</b> <input type=text name=nofilter_torrentDir size=60").append(" title=\"")
           .append(srv._t("Optional directory to store .torrent files separately from downloaded data. Leave empty to store in the data directory."))
           .append("\" value=\"").append(DataHelper.escapeHTML(torrentDir)).append("\" spellcheck=false></label></span><br>\n")
           .append("<span class=configOption><label for=filesPublic><b>")
           .append(srv._t("Files readable by all"))
           .append("</b> </label><input type=checkbox class=\"optbox slider\" name=filesPublic id=filesPublic")
           .append(filesPublic ? " checked " : "").append("title=\"")
           .append(srv._t("Set file permissions to allow other local users to access the downloaded files"))
           .append("\"></span><br>\n")
           .append("<span class=configOption><label for=preallocateFiles><b>")
           .append(srv._t("Preallocate files"))
           .append("</b> </label><input type=checkbox class=\"optbox slider\" name=preallocateFiles id=preallocateFiles ")
           .append(preallocateFiles ? "checked " : "").append("title=\"")
           .append(srv._t("Extend new torrent files to their full size and allocate the space on disk immediately when the torrent starts, to prevent a full disk from interrupting downloads and avoid fragmentation as pieces arrive"))
           .append("\"></span><br>\n")
           .append("<span class=configOption><label for=preserveFileNames><b>")
           .append(srv._t("Preserve file names"))
           .append("</b> </label><input type=checkbox class=\"optbox slider\" name=preserveFileNames id=preserveFileNames ")
           .append(preserveFileNames ? "checked " : "").append("title=\"")
           .append(srv._t("Preserve original file names from the torrent. When disabled, filenames are filtered to remove illegal filesystem characters. When enabled, original filenames are used; if a filename contains characters not supported by your filesystem, the file will be created with a safe fallback name and a warning will be shown"))
           .append("\"></span><br>\n")
           .append("<span class=configOption><label for=maxFiles><b>")
           .append(srv._t("Max files per torrent"))
           .append("</b> <input type=text name=maxFiles size=5 maxlength=5 pattern=\"[0-9]{1,5}\" class=\"r numeric\"").append(" title=\"")
           .append(srv._t("Maximum number of files permitted per torrent - note that trackers may set their own limits, and your OS may limit the number of open files, preventing torrents with many files (and subsequent torrents) from loading"))
           .append("\" value=\"").append(srv.manager().getMaxFilesPerTorrent()).append("\" spellcheck=false></label></span>\n")
           .append("</div></td></tr>\n");

    }

    /** Tunnel Configuration panel: destination, hop settings, multi-dest, I2CP. */
    private void appendTunnelConfig(StringBuilder buf) {
        boolean varyInbound = srv.manager().util().enableVaryInboundHops();
        boolean varyOutbound = srv.manager().util().enableVaryOutboundHops();
        boolean multiDest = srv.manager().util().getMultiDest();
        boolean randomizeStartup = srv.manager().getRandomizeStartupDelay();
        String IPString = srv.manager().util().getOurIPString();
        Map<String, String> options = new TreeMap<>(srv.manager().util().getI2CPOptions());

        buf.append("<tr><th class=suboption>").append(srv._t("Tunnel Configuration")).append("&nbsp;");
        if (!IPString.equals("unknown")) {
            // Only truncate if it's an actual dest
            buf.append("&nbsp;<span id=ourDest title=\"");
            if (srv.manager().util().getMultiDest()) {
                buf.append(srv._t("Primary destination (identity) for this session; its DHT, tracker, and blacklist are shared with the per-torrent destinations"));
            } else {
                buf.append(srv._t("Our destination (identity) for this session"));
            }
            buf.append("\">");
            if (srv.manager().util().getMultiDest()) {
                buf.append(srv._t("Primary Dest."));
            } else {
                buf.append(srv._t("Dest."));
            }
            buf.append("<code>").append(IPString.substring(0,4));
            if (!srv.manager().util().getMultiDest()) {
                ClientID.Profile cid = srv.manager().util().getClientID(null);
                if (cid != null) {
                    buf.append(" [").append(cid.getName()).append(']');
                }
            }
            buf.append("</code></span>");
        }
        buf.append("</th></tr>\n<tr><td>\n<div class=optionlist>\n")
           .append("<span class=configOption><b>")
           .append(srv._t("Inbound Settings"))
           .append("</b> \n")
           .append(renderOptions(1, 16, SnarkManager.DEFAULT_TUNNEL_QUANTITY, options.remove("inbound.quantity"), "inbound.quantity", TUNNEL))
           .append("&nbsp;")
           .append(renderOptions(0, 6, 3, options.remove("inbound.length"), "inbound.length", HOP))
           .append("</span><br>\n")
           .append("<span class=configOption><b>")
           .append(srv._t("Outbound Settings"))
           .append("</b> \n")
           .append(renderOptions(1, 32, SnarkManager.DEFAULT_TUNNEL_QUANTITY, options.remove("outbound.quantity"), "outbound.quantity", TUNNEL))
           .append("&nbsp;")
           .append(renderOptions(0, 6, 3, options.remove("outbound.length"), "outbound.length", HOP))
           .append("</span><br>\n")
           .append("<span class=configOption id=hopVariance><b>")
           .append(srv._t("Vary Tunnel Length"))
           .append("</b> \n")
           .append("<label title=\"").append(srv._t("Add 0 or 1 additional hops randomly to Inbound tunnels")).append("\">")
           .append("<input type=checkbox class=\"optbox slider\" name=varyInbound id=varyInbound")
           .append(varyInbound ? " checked" : "").append("> <span>").append(srv._t("Inbound")).append("</span></label>")
           .append("<label title=\"").append(srv._t("Add 0 or 1 additional hops randomly to Outbound tunnels")).append("\">")
           .append("<input type=checkbox class=\"optbox slider\" name=varyOutbound id=varyOutbound")
           .append(varyOutbound ? " checked" : "").append("> <span>").append(srv._t("Outbound")).append("</span></label>")
           .append("</span><br>\n")
           .append("<script src=\"").append(srv.resourcePath()).append("js/toggleVaryTunnelLength.js?").append(CoreVersion.VERSION).append("\" defer></script>\n")
           .append("<noscript><style>#hopVariance .optbox.slider{pointer-events:none!important;opacity:.4!important}</style></noscript>\n")
           .append("<span class=configOption id=multiDest><b>")
           .append(srv._t("Multi-destination"))
           .append("</b> \n")
           .append("<label title=\"")
           .append(srv._t("Use a separate destination for each torrent, so that trackers and the DHT cannot link your torrents to each other. Destinations are temporary and change on restart."))
           .append("\">")
           .append("<input type=checkbox class=\"optbox slider\" name=multiDest id=multiDest")
           .append(multiDest ? " checked" : "").append("></label></span><br>\n")
           .append("<span class=configOption id=maxDest><b>")
           .append(srv._t("Maximum destinations"))
           .append("</b> \n")
           .append("<label title=\"")
           .append(srv._t("When more torrents run than this maximum, the extra torrents share destinations in randomized, variable-size groups, to limit memory use. Zero means one destination per torrent."))
           .append("\">")
           .append("<input type=text name=multiDestMax id=multiDestMax value=\"")
           .append(srv.manager().util().getMaxDest())
           .append("\" class=numeric size=5 maxlength=4 pattern=\"[0-9]{1,4}\" spellcheck=false>")
           .append("</label></span><br>\n")
           .append("<span class=configOption id=randomizeStartup><b>")
           .append(srv._t("Random startup delay"))
           .append("</b> \n")
           .append("<label title=\"")
           .append(srv._t("Stagger multi-dest torrent starts with a random delay so that torrents which start together cannot be correlated by trackers or DHT peers, and tunnel builds are spread out. Disable to start all torrents in a batch immediately."))
           .append("\">")
           .append("<input type=checkbox class=\"optbox slider\" name=randomizeStartup id=randomizeStartup")
           .append(randomizeStartup ? " checked " : "").append("></label></span><br>\n");

        if (srv.isStandalone()) {
            buf.append("<span class=configOption><label><b>")
               .append(srv._t("I2CP host"))
               .append("</b> <input type=text name=i2cpHost value=\"")
               .append(srv.manager().util().getI2CPHost()).append("\" size=5></label></span><br>\n")
               .append("<span class=configOption><label><b>")
               .append(srv._t("I2CP port"))
               .append("</b> <input type=text name=i2cpPort value=\"")
               .append(srv.manager().util().getI2CPPort()).append("\" class=numeric size=5 maxlength=5 pattern=\"[0-9]{1,5}\"></label></span><br>\n");
        }

        options.remove(I2PSnarkUtil.PROP_MAX_BW);
        options.remove(SnarkManager.PROP_OPENTRACKERS); // was accidentally in the I2CP options prior to 0.8.9 so it will be in old config files
        StringBuilder opts = new StringBuilder(256);
        for (Map.Entry<String, String> e : options.entrySet()) {
            String key = e.getKey();
            String val = e.getValue();
            opts.append(key).append('=').append(val).append(' ');
        }
        String ibkey = "inbound.lengthVariance=1 ";
        String obkey = "outbound.lengthVariance=1 ";
        boolean containsIbk = opts.indexOf(ibkey) != -1;
        boolean containsObk = opts.indexOf(obkey) != -1;
        if (varyInbound) {
            if (!containsIbk) {
                opts.append(ibkey);
                srv.manager().util().setVaryInboundHops(true);
            }
        } else if (!varyInbound) {
            if (containsIbk) {
                opts.delete(opts.indexOf(ibkey), opts.indexOf(ibkey) + ibkey.length());
                srv.manager().util().setVaryInboundHops(false);
            }
        }
        if (varyOutbound) {
            if (!containsObk) {
                opts.append(obkey);
                srv.manager().util().setVaryOutboundHops(true);
            }
        } else if (!varyOutbound) {
            if (containsObk) {
                opts.delete(opts.indexOf(obkey), opts.indexOf(obkey) + obkey.length());
                srv.manager().util().setVaryOutboundHops(false);
            }
        }
        buf.append("<span class=configOption id=i2cpOptions><label><b>")
           .append(srv._t("I2CP options"))
           .append("</b> <input type=text id=i2cpOpts name=i2cpOpts value=\"")
           .append(opts.toString().trim()).append("\" size=60></label></span>\n")
           .append("</div>\n</td></tr>\n");
    }

    /** Browser Integration panel: nonce-free adds, allowed hosts, API key. */
    private void appendBrowserIntegrationConfig(StringBuilder buf, HttpServletRequest req) {
        buf.append("<tr><th class=suboption>")
           .append(srv._t("Browser Integration"))
           .append("</th></tr><tr><td>\n<div class=optionlist>\n")
           .append("<span class=configOption><label for=browserApi><b>")
           .append(srv._t("Enable browser API"))
           .append("</b> </label><input type=checkbox class=\"optbox slider\" ")
           .append("name=browserApi id=browserApi ")
           .append((srv.manager().browserApiEnabled() ? "checked " : ""))
           .append("title=\"")
           .append(srv._t("Allow magnet links and torrent URLs to be added without the CSRF nonce, from loopback or the allowed hosts below"))
           .append("\"></span><br>\n")
           .append("<span class=configOption><label for=browserApiHosts><b>")
           .append(srv._t("Allowed hosts"))
           .append("</b> </label><input type=text name=nofilter_browserApiHosts id=browserApiHosts size=40 value=\"")
           .append(DataHelper.escapeHTML(srv.manager().getBrowserApiHosts()))
           .append("\" spellcheck=false title=\"")
           .append(srv._t("Comma-separated hostnames or IPs allowed to add torrents without a nonce; loopback is always allowed"))
           .append("\"></span><br>\n")
           .append("<span class=configOption><label for=browserApiKey><b>")
           .append(srv._t("API key"))
           .append("</b> </label><input type=text name=apiKey id=browserApiKey size=40 value=\"\" autocomplete=off title=\"")
           .append(srv._t("Optional password for remote access, bypasses host allow list"))
           .append("\"></span>\n")
           .append("<input type=hidden name=apiTarget value=\"")
           .append(DataHelper.escapeHTML(browserApiTarget()))
           .append("\">\n")
           .append("</div></td></tr>\n");

    }

    /** Browser-handler install/update control plus the Save row. */
    private void appendSaveConfigRow(StringBuilder buf, HttpServletRequest req) {
        String spacer = "<tr class=spacer><td></td></tr>\n";
        buf.append(spacer)
           .append("<tr><td>");
        if (I2PSnarkServlet.isFirefoxFamilyUserAgent(req.getHeader("User-Agent"))) {
            String installed = req.getHeader(BridgeVersion.HEADER);
            String bundled = srv.getBridgeVersion();
            boolean update = BridgeVersion.isUpdateAvailable(installed, bundled);
            boolean current = installed != null && !update;
            buf.append("<input type=submit name=installBrowserApi class=accept value=\"")
               .append(update ? srv._t("Update browser handler") : srv._t("Install browser handler"))
               .append("\" title=\"")
               .append(update
                   ? srv._t("Update the installed I2PSnark Bridge extension to v{0}", bundled)
                   : srv._t("Open the I2PSnark Bridge extension in your browser to add magnet links; the extension registers itself with the browser, no router-side files are written"))
               .append("\" style=float:left");
            if (current) {
                buf.append(" disabled");
            }
            buf.append("> ");
        } else {
            String script = I2PSnarkServlet.isWindowsUserAgent(req.getHeader("User-Agent"))
                ? "install-i2psnark-browser-handler.ps1"
                : "install-i2psnark-browser-handler.sh";
            String title = I2PSnarkServlet.isWindowsUserAgent(req.getHeader("User-Agent"))
                ? srv._t("Download the PowerShell installer that registers magnet links and .torrent files with your browser; the I2PSnark Bridge extension requires a Firefox-family browser")
                : srv._t("Download the installer script that registers magnet links and .torrent files with your browser; the I2PSnark Bridge extension requires a Firefox-family browser");
            buf.append("<a class=accept href=\"")
               .append(srv.contextPath()).append(srv.warBase()).append("browser/").append(script)
               .append("\" title=\"").append(title)
               .append("\" style=float:left>")
               .append(srv._t("Download browser handler script"))
               .append("</a> ");
        }
        buf.append("<input type=submit class=accept value=\"")
           .append(srv._t("Save configuration"))
           .append("\" name=foo></td></tr>\n")
           .append(spacer).append("</table></div></div></form>");
    }

    /** The API key target for the Browser API panel, defaulting to the context name. */
    private String browserApiTarget() {
        String t = srv.manager().util().getAPITarget();
        if (t != null && !t.isEmpty()) {return t;}
        return srv.contextName();
    }

    /**
     * Writes the HTML form for managing torrent creation file filters.
     *
     * @param out the PrintWriter to which the HTML output will be written
     * @param req the HttpServletRequest containing the current request parameters
     * @throws IOException if an I/O error occurs while writing to the output stream
     * @since 0.9.62+ Added torrent creation filter management form
     */
    void writeTorrentCreateFilterForm(PrintWriter out, HttpServletRequest req) throws IOException {
        StringBuilder buf = new StringBuilder(5*1024);
        buf.append("<form id=createFilterForm action=\"")
           .append(srv.contextPath())
           .append("/configure\" method=POST>\n<div class=configPanel id=fileFilter>\n<div class=snarkConfig>\n");
        srv.writeHiddenInputs(buf, req, "SaveCreateFilters");
        buf.append("<span id=filtersTitle class=\"configTitle expanded\">")
           .append(srv._t("Torrent Create File Filtering"))
           .append("</span><hr>\n<table hidden>\n<tr>")
           .append("<th title=\"")
           .append(srv._t("Mark filter for deletion"))
           .append("\"></th><th>")
           .append(srv._t("Name"))
           .append("</th><th>")
           .append(srv._t("Filter Pattern"))
           .append("</th><th class=radio>")
           .append(srv._t("Starts With"))
           .append("</th><th class=radio>")
           .append(srv._t("Contains"))
           .append("</th><th class=radio>")
           .append(srv._t("Ends With"))
           .append("</th><th>")
           .append(srv._t("Enabled by Default"))
           .append("</th></tr>\n");
        for (TorrentCreateFilter f : srv.manager().getSortedTorrentCreateFilterStrings()) {
            String filterType = f.filterType;
            String nameUnderscore = f.name.replace(" ", "_");
            buf.append("<tr class=createFilterString><td><input type=checkbox class=optbox name=\"delete_")
               .append(f.name)
               .append("\"></td><td>")
               .append(f.name)
               .append("</td><td>")
               .append(f.filterPattern)
               .append("</td><td>")
               .append("<label class=filterStartsWith><input type=radio class=optbox value=starts_with name=\"filterType_")
               .append(nameUnderscore)
               .append("\"")
               .append(filterType.equals("starts_with") ? " checked" : "")
               .append("></label></td><td><label class=filterContains><input type=radio class=optbox value=contains name=\"filterType_")
               .append(nameUnderscore)
               .append("\"")
               .append(filterType.equals("contains") ? " checked" : "")
               .append("></label></td><td><label class=filterEndsWith><input type=radio class=optbox value=ends_with name=\"filterType_")
               .append(nameUnderscore)
               .append("\"")
               .append(filterType.equals("ends_with") ? " checked" : "")
               .append("></label></td><td><input type=checkbox class=optbox name=\"defaultEnabled_")
               .append(f.name)
               .append("\"");
            if (f.isDefault) {buf.append(" checked");}
            buf.append("></td></tr>\n");
        }
        String spacer = "<tr class=spacer><td colspan=7>&nbsp;</td></tr>\n";
        String filterFormElements =
            "<td><input type=text class=torrentCreateFilterName name=fname spellcheck=false></td>" +
            "<td><input type=text class=torrentCreateFilterPattern name=filterPattern spellcheck=false></td>" +
            "<td><label class=filterStartsWith><input type=radio class=optbox name=filterType value=starts_with></label></td>" +
            "<td><label class=filterContains><input type=radio class=optbox name=filterType value=contains checked></label></td>" +
            "<td><label class=filterEndsWith><input type=radio class=optbox name=filterType value=ends_with></label></td>" +
            "<td><input type=checkbox class=optbox name=filterIsDefault></td>";
        String buttons = String.format(
            "<tr><td colspan=7>\n" +
            "<input type=submit name=raction class=delete value=\"%s\">\n" +
            "<input type=submit name=raction class=accept value=\"%s\">\n" +
            "<input type=submit name=raction class=reload value=\"%s\">\n" +
            "<input type=submit name=raction class=add value=\"%s\">\n" +
            "</td></tr>\n",
            srv._t("Delete selected"),
            srv._t("Save Filter Configuration"),
            srv._t("Restore defaults"),
            srv._t("Add File Filter")
        );
        buf.append(spacer)
           .append("<tr id=addFileFilter>")
           .append("<td><b>").append(srv._t("Add")).append(":</b></td>").append(filterFormElements).append("</tr>")
           .append(spacer).append(buttons).append(spacer).append("</table>\n</div>\n</div>\n</form>\n");
        out.append(buf);
        out.flush();
        buf.setLength(0);
    }

    /**
     * Writes the HTML form for managing trackers with optimized string building.
     * Minimizes append() calls by batching HTML fragments, and caches collections for efficient lookups.
     *
     * @param out the PrintWriter to which the HTML output will be written
     * @param req the HttpServletRequest containing the current request parameters
     * @throws IOException if an I/O error occurs while writing to the output stream
     * @since 0.9 Added tracker management form, optimized in 2025 for rendering performance
     */
    void writeTrackerForm(PrintWriter out, HttpServletRequest req) throws IOException {
        StringBuilder buf = new StringBuilder(5 * 1024);

        buf.append("<form id=trackerConfigForm action=\"")
           .append(srv.contextPath())
           .append("/configure\" method=POST>\n<div class=configPanel id=trackers><div class=snarkConfig>\n");
        srv.writeHiddenInputs(buf, req, "SaveTrackers");
        buf.append("<span id=trackersTitle class=\"configTitle expanded\">")
           .append(srv._t("Trackers"))
           .append("</span><hr>\n<table id=trackerconfig hidden>\n<tr><th title=\"")
           .append(srv._t("Select trackers for removal from I2PSnark's known list"))
           .append("\"></th><th>")
           .append(srv._t("Name"))
           .append("</th><th>")
           .append(srv._t("Website URL"))
           .append("</th><th class=radio>")
           .append(srv._t("Standard"))
           .append("</th><th class=radio>")
           .append(srv._t("Open"))
           .append("</th><th class=radio>")
           .append(srv._t("Private"))
           .append("</th><th>")
           .append(srv._t("Announce URL"))
           .append("</th></tr>\n");

        I2PSnarkUtil util = srv.manager().util();
        Set<String> openSet = new HashSet<>(util.getOpenTrackers());
        Set<String> privateSet = new HashSet<>(srv.manager().getPrivateTrackers());

        // Batch all rows to reduce append calls
        StringBuilder rowsBatch = new StringBuilder(8192);
        for (Tracker t : srv.manager().getSortedTrackers()) {
            String name = t.name;
            String homeURL = t.baseURL.endsWith(".i2p/") ? t.baseURL.substring(0, t.baseURL.length() - 1) : t.baseURL;
            String announceURL = t.announceURL;

            boolean isPrivate = privateSet.contains(announceURL);
            boolean isKnownOpen = util.isKnownOpenTracker(announceURL);
            boolean isOpen = isKnownOpen || openSet.contains(announceURL);

            rowsBatch.append("<tr class=knownTracker><td><input type=checkbox class=optbox id=\"")
                     .append(name)
                     .append("\" name=\"delete_")
                     .append(name)
                     .append("\" title=\"")
                     .append(srv._t("Mark tracker for deletion"))
                     .append("\"></td><td><label for=\"")
                     .append(name)
                     .append("\">")
                     .append(name)
                     .append("</label></td><td>")
                     .append(I2PSnarkServlet.urlify(homeURL, 64))
                     .append("</td><td><input type=radio class=optbox value=\"0\" tabindex=-1 name=\"ttype_")
                     .append(announceURL).append("\"");
            if (!(isOpen || isPrivate)) {rowsBatch.append(" checked");}
            else if (isKnownOpen) {rowsBatch.append(" disabled");}
            rowsBatch.append("></td><td><input type=radio class=optbox value=1 tabindex=-1 name=\"ttype_")
                     .append(announceURL)
                     .append("\"");
            if (isOpen) {rowsBatch.append(" checked");}
            else if ("http://diftracker.i2p/announce.php".equals(announceURL) ||
                     "http://tracker2.postman.i2p/announce.php".equals(announceURL) ||
                     "http://torrfreedom.i2p/announce.php".equals(announceURL)) {
                rowsBatch.append(" disabled");
            }
            rowsBatch.append("></td><td><input type=radio class=optbox value=2 tabindex=-1 name=\"ttype_")
                     .append(announceURL)
                     .append("\"");
            if (isPrivate) {rowsBatch.append(" checked");}
            else if (isKnownOpen || "http://diftracker.i2p/announce.php".equals(announceURL) ||
                     "http://tracker2.postman.i2p/announce.php".equals(announceURL) ||
                     "http://torrfreedom.i2p/announce.php".equals(announceURL)) {
                rowsBatch.append(" disabled");
            }
            rowsBatch.append("></td><td>")
                     .append(I2PSnarkServlet.urlify(announceURL, 64))
                     .append("</td></tr>\n");
        }

        buf.append(rowsBatch);

        String spacer = "<tr class=spacer><td colspan=7>&nbsp;</td></tr>\n";
        String trackerFormElements =
            "<td><input type=text class=trackername name=tname spellcheck=false></td>" +
            "<td><input type=text class=trackerhome name=thurl spellcheck=false></td>" +
            "<td><input type=radio class=optbox value=0 name=add_tracker_type checked></td>" +
            "<td><input type=radio class=optbox value=1 name=add_tracker_type></td>" +
            "<td><input type=radio class=optbox value=2 name=add_tracker_type></td>" +
            "<td><input type=text class=trackerannounce name=taurl spellcheck=false></td>";

        String noscript =
            "<noscript><style>" +
            ".configPanel .configTitle{pointer-events:none!important}" +
            "#fileFilter table,#trackers table{display:table!important}" +
            "#fileFilter .configTitle::after,#trackers .configTitle::after{display:none!important}" +
            "</style></noscript>\n";

        buf.append(spacer);
        buf.append("<tr id=addtracker><td><b>")
           .append(srv._t("Add"))
           .append(":</b></td>")
           .append(trackerFormElements)
           .append("</tr>\n")
           .append(spacer);

        String buttons =
            "<tr><td colspan=7>\n" +
            "<input type=submit name=taction class=default value=\"" + srv._t("Add tracker") + "\">\n" +
            "<input type=submit name=taction class=delete value=\"" + srv._t("Delete selected") + "\">\n" +
            "<input type=submit name=taction class=accept value=\"" + srv._t("Save tracker configuration") + "\">\n" +
            "<input type=submit name=taction class=add value=\"" + srv._t("Add tracker") + "\">\n" +
            "<input type=submit name=taction class=reload value=\"" + srv._t("Restore defaults") + "\">\n" +
            "</td></tr>" + spacer +
            "</table>\n</div>\n</div></form>\n" +
            noscript +
            "<script src=\"" + srv.resourcePath() + "js/toggleConfigs.js?" + CoreVersion.VERSION + "\"></script>\n";

        buf.append(buttons);
        out.append(buf);
        out.flush();
        buf.setLength(0);
    }

    /** Copied from ConfigTunnelsHelper. */
    private static final String HOP = "hop";
    private static final String TUNNEL = "tunnel";

    /** Dummies for translation; prevents the ngettext line below from getting tagged. */
    private static final String DUMMY0 = "{0} ";
    private static final String DUMMY1 = "1 ";

    /**
     * Generates HTML for a dropdown selection menu.
    *
    * @param min the minimum value for the dropdown options
    * @param max the maximum value for the dropdown options
    * @param dflt the default value for the dropdown
    * @param strNow the string representation of the current selected option
    * @param selName the name attribute for the select element
    * @param name the base name of the option to be displayed in the dropdown
    * @return a string representing the HTML for the dropdown selection menu
    * @since 0.7.14 Modified from ConfigTunnelsHelper
    */
    private String renderOptions(int min, int max, int dflt, String strNow, String selName, String name) {
        int now = I2PSnarkUtil.parseInt(strNow, dflt);
        StringBuilder buf = new StringBuilder(128);
        buf.append("<select name=\"").append(selName);
        if (selName.contains("quantity")) {
            buf.append("\" title=\"")
               .append(srv._t("This configures the maximum number of tunnels to open, determined by the number of connected peers (actual usage may be less)"));
        }
        if (selName.contains("length")) {
            buf.append("\" title=\"")
               .append(srv._t("Changing this setting to less than 3 hops may improve speed at the expense of anonymity and is not recommended"));
        }
        buf.append("\">\n");
        for (int i = min; i <= max; i++) {
            buf.append("<option value=\"").append(i).append("\"");
            if (i == now) {buf.append(" selected");}
            buf.append(">").append(srv.ngettext(DUMMY1 + name, DUMMY0 + name + 's', i)).append("</option>\n");
        }
        buf.append("</select>\n");
        return buf.toString();
    }
     /**
     * Parses the configuration form POST parameters and applies them via
     * SnarkManager.
     *
     * @param req the configure-page POST request
     */
    public void applySettings(HttpServletRequest req) {
        // Extract parameters and update configuration
        boolean filesPublic = req.getParameter("filesPublic") != null;
        boolean autoStart = req.getParameter("autoStart") != null;
        boolean useOpenTrackers = req.getParameter("useOpenTrackers") != null;
        boolean useDHT = req.getParameter("useDHT") != null;
        boolean ratings = req.getParameter("ratings") != null;
        boolean comments = req.getParameter("comments") != null;
        boolean collapsePanels = req.getParameter("collapsePanels") != null;
        boolean showStatusFilter = req.getParameter("showStatusFilter") != null;
        boolean enableLightbox = req.getParameter("enableLightbox") != null;
        boolean enableAddCreate = req.getParameter("enableAddCreate") != null;
        boolean enableVaryInboundHops = req.getParameter("varyInbound") != null;
        boolean enableVaryOutboundHops = req.getParameter("varyOutbound") != null;
        boolean multiDest = req.getParameter("multiDest") != null;
        String multiDestMax = req.getParameter("multiDestMax");
        boolean randomizeStartup = req.getParameter("randomizeStartup") != null;

        String dataDir = req.getParameter("nofilter_dataDir");
        String i2cpHost = req.getParameter("i2cpHost");
        String i2cpPort = req.getParameter("i2cpPort");
        String i2cpOpts = buildI2CPOpts(req);
        String upLimit = req.getParameter("upLimit");
        String upBW = req.getParameter("upBW");
        String downBW = req.getParameter("downBW");
        String refreshDel = req.getParameter("refreshDelay");
        String startupDelMin = req.getParameter("startupDelayMin");
        String startupDelMax = req.getParameter("startupDelayMax");
        String pageSize = req.getParameter("pageSize");
        String theme = req.getParameter("theme");
        String lang = req.getParameter("lang");
        String commentsName = req.getParameter("nofilter_commentsName");
        String apiTarget = req.getParameter("apiTarget");
        String apiKey = req.getParameter("apiKey");
        String maxFiles = req.getParameter("maxFiles");
        String tempDir = req.getParameter("nofilter_tempDir");
        String torrentDir = req.getParameter("nofilter_torrentDir");
        boolean preallocateFiles = req.getParameter("preallocateFiles") != null;
        boolean preserveFileNames = req.getParameter("preserveFileNames") != null;

        srv.manager().updateConfig(dataDir, filesPublic, autoStart, refreshDel, startupDelMin, startupDelMax, pageSize,
                i2cpHost, i2cpPort, i2cpOpts, upLimit, upBW, downBW, useOpenTrackers, useDHT, theme, lang,
                ratings, comments, commentsName, collapsePanels, showStatusFilter, enableLightbox,
                enableAddCreate, enableVaryInboundHops, enableVaryOutboundHops, multiDest, multiDestMax, randomizeStartup, apiTarget, apiKey,
                maxFiles, preallocateFiles, tempDir, torrentDir, preserveFileNames);
    }

    private static final String[] iopts = {"inbound.length", "inbound.quantity", "outbound.length", "outbound.quantity" };

    private static String buildI2CPOpts(HttpServletRequest req) {
        StringBuilder buf = new StringBuilder(128);
        String p = req.getParameter("i2cpOpts");
        if (p != null) {buf.append(p);}
        for (int i = 0; i < iopts.length; i++) {
            p = req.getParameter(iopts[i]);
            if (p != null) {buf.append(' ').append(iopts[i]).append('=').append(p);}
        }
        return buf.toString();
    }
}
