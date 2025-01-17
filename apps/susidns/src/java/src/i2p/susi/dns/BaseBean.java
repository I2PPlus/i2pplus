package i2p.susi.dns;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;

/**
 * Holds methods common to several Beans.
 * @since 0.9.1
 */
public class BaseBean {
    protected final I2PAppContext _context;
    protected final Properties properties;
    protected String action, lastSerial, serial, method;
    private long configLastLoaded;
    private static final String PRIVATE_BOOK = "private_addressbook";
    private static final String DEFAULT_PRIVATE_BOOK = "../privatehosts.txt";
    private static final String RC_PROP_THEME_NAME = "routerconsole.theme";
    private static final String PROP_THEME_NAME = "theme";
    private static final String DEFAULT_THEME = "dark";
    private static final String BASE_THEME_PATH = "/themes/susidns/";
    public static final String PROP_PW_ENABLE = "routerconsole.auth.enable";
    private static final String RC_PROP_ENABLE_SORA_FONT = "routerconsole.displayFontSora";
    private static final String ADDRESSBOOK_DIR = "addressbook";
    private static final String CONFIG_FILE = "config.txt";

    public BaseBean() {
        _context = I2PAppContext.getGlobalContext();
        properties = new OrderedProperties();
    }

    /**
     * @since 0.9.13 moved from ConfigBean.addressbookPrefix
     */
    protected File addressbookDir() {return new File(_context.getRouterDir(), ADDRESSBOOK_DIR);}

    /**
     * @since 0.9.13 moved from ConfigBean.configFileName
     */
    protected File configFile() {return new File(addressbookDir(), CONFIG_FILE);}

    protected void loadConfig() {
        synchronized (BaseBean.class) {
            long currentTime = System.currentTimeMillis();
            if (!properties.isEmpty() &&  currentTime - configLastLoaded < 10000 ) {return;}
            reload();
        }
    }

    /**
     * @since 0.9.13 moved from ConfigBean
     */
    protected void reload() {
        try {
            synchronized (BaseBean.class) {
                properties.clear();
                DataHelper.loadProps(properties, configFile()); // use loadProps to trim
                if (properties.getProperty(PRIVATE_BOOK) == null) { // added in 0.5, for compatibility with 0.4 config.txt
                    properties.setProperty(PRIVATE_BOOK, DEFAULT_PRIVATE_BOOK);
                }
                // migrate from I2P
                String master = properties.getProperty("master_addressbook");
                if (master != null) {
                    properties.setProperty("master_addressbook", master);
                    properties.remove("local_addressbook");
                }
                configLastLoaded = System.currentTimeMillis();
            }
        }
        catch (IOException e) {warn(e);}
    }

    /**
     * Returns the theme path
     * @since 0.9.1
     */
    public String getTheme() {
        loadConfig();
        String url = BASE_THEME_PATH;
        // Fetch routerconsole theme (or use our default if it doesn't exist)
        String theme = _context.getProperty(RC_PROP_THEME_NAME, DEFAULT_THEME);
        // Apply any override
        theme = properties.getProperty(PROP_THEME_NAME, theme);
        // Ensure that theme exists
        String[] themes = getThemes();
        boolean themeExists = false;
        for (int i = 0; i < themes.length; i++) {
            if (themes[i].equals(theme)) {
                themeExists = true;
                break;
            }
        }
        if (!themeExists) {theme = DEFAULT_THEME;}
        url += theme + "/";
        return url;
    }

    /**
     * Returns the theme name
     * @since 0.9.64+
     */
    public String getThemeName() {
        loadConfig();
        String theme = _context.getProperty(RC_PROP_THEME_NAME, DEFAULT_THEME);
        theme = properties.getProperty(PROP_THEME_NAME, theme);
        String[] themes = getThemes();
        boolean themeExists = false;
        for (int i = 0; i < themes.length; i++) {
            if (themes[i].equals(theme)) {themeExists = true; break;}
        }
        if (!themeExists) {theme = DEFAULT_THEME;}
        return theme;
    }

    /**
     * Get all themes
     * @return String[] -- Array of all the themes found.
     * @since 0.9.2
     */
    public String[] getThemes() {
            String[] themes;
            File dir = new File(_context.getBaseDir(), "docs/themes/susidns");
            FileFilter fileFilter = new FileFilter() { public boolean accept(File file) { return file.isDirectory(); } };
            File[] dirnames = dir.listFiles(fileFilter);
            if (dirnames != null) {
                List<String> th = new ArrayList<String>(dirnames.length);
                for (int i = 0; i < dirnames.length; i++) {
                    String name = dirnames[i].getName();
                    th.add(name);
                }
                themes = th.toArray(new String[th.size()]);
            } else {themes = new String[0];}
            return themes;
    }

    /** @since 0.9.59+ */
    public boolean useSoraFont() {return _context.getBooleanProperty(RC_PROP_ENABLE_SORA_FONT);}

    /**
     * Determine if a user-provided override.css file is active
     * @since 0.9.65+
     */
    public boolean isOverrideCssActive() {
        String slash = String.valueOf(java.io.File.separatorChar);
        String themeBase = _context.getBaseDir().getAbsolutePath() + slash + "docs" + slash + "themes" +
                           slash + "susidns" + slash + getThemeName() + slash;
        File overrideCss = new File(themeBase + "override.css");
        return overrideCss.exists();
    }

    /**
     * @since 0.9.13 moved from subclasses
     */
    public String getAction() {return action;}

    /**
     * @since 0.9.13 moved from subclasses
     */
    public void setAction(String action) {this.action = DataHelper.stripHTML(action);}

    /**
     * @since 0.9.13 moved from subclasses
     */
    public String getSerial() {
        lastSerial = Long.toString(_context.random().nextLong());
        action = null;
        return lastSerial;
    }

    /**
     * @since 0.9.13 moved from subclasses
     */
    public void setSerial(String serial) {this.serial = DataHelper.stripHTML(serial);}

    /**
     * @since 0.9.65
     */
    public void storeMethod(String method) {this.method = method;}

    /**
     * Translate
     * @since 0.9.13 moved from subclasses
     */
    protected static String _t(String s) {return Messages.getString(s);}

    /**
     * Translate
     * @since 0.9.13 moved from subclasses
     */
    protected static String _t(String s, Object o) {return Messages.getString(s, o);}

    /**
     * Translate
     * @since 0.9.13 moved from subclasses
     */
    protected static String _t(String s, Object o, Object o2) {return Messages.getString(s, o, o2);}

    /**
     * Translate (ngettext)
     * @since 0.9.13 moved from subclasses
     */
    protected static String ngettext(String s, String p, int n) {return Messages.getString(n, s, p);}

    /**
     * @since 0.9.13 moved from Debug
     */
    protected void debug(String msg) {
        Log log = _context.logManager().getLog(getClass());
        if (log.shouldDebug()) {log.debug(msg);}
    }

    /**
     * @since 0.9.13 moved from Debug
     */
    protected void warn(Throwable t) {
        Log log = _context.logManager().getLog(getClass());
        if (log.shouldWarn()) {log.warn("SusiDNS", t);}
    }

}
