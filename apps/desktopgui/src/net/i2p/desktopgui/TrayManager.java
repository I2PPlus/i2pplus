package net.i2p.desktopgui;

import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.net.URL;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingWorker;
import javax.swing.event.MenuKeyEvent;
import javax.swing.event.MenuKeyListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import net.i2p.I2PAppContext;
import net.i2p.desktopgui.i18n.DesktopguiTranslator;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Abstract base class for managing system tray icons and menus for I2P.
 * Handles tray icon lifecycle, notifications, and provides platform-specific
 * menu creation for both AWT and Swing implementations.
 */
abstract class TrayManager {

    protected final I2PAppContext _appContext;
    protected final boolean _useSwing;
    ///The tray area, or null if unsupported
    protected SystemTray tray;
    ///Our tray icon, or null if unsupported
    protected TrayIcon trayIcon;
    protected volatile boolean _showNotifications;
    protected MenuItem  _notificationItem1;
    protected MenuItem  _notificationItem2;
    protected JMenuItem _jnotificationItem1;
    protected JMenuItem _jnotificationItem2;

    private static final String PNG_DIR = "/desktopgui/resources/images/";
    private static final String MAC_ICON = "itoopie_black_24.png";
    private static final String WIN_ICON = "itoopie_white_24.png";
    private static final String LIN_ICON = "logo.png";
    protected static final String PROP_NOTIFICATIONS = "desktopgui.showNotifications";

    /**
     * Create a new tray manager.
     *
     * @param ctx the I2P application context
     * @param useSwing true to use Swing components, false for AWT
     */
    protected TrayManager(I2PAppContext ctx, boolean useSwing) {
        _appContext = ctx;
        _useSwing = useSwing;
    }

    /**
     * Add the tray icon to the system tray and start everything up.
     *
     * @throws AWTException if the system tray is not supported
     */
    public synchronized void startManager() throws AWTException {
        if (!SystemTray.isSupported())
            throw new AWTException("SystemTray not supported");
        _showNotifications = _appContext.getBooleanPropertyDefaultTrue(PROP_NOTIFICATIONS);
        tray = SystemTray.getSystemTray();
        // Windows typically has tooltips; Linux (at least Ubuntu) doesn't
        String tooltip = SystemVersion.isWindows() ? _t("I2P: Right-click for menu") : null;
        TrayIcon ti;
        if (_useSwing)
            ti = getSwingTrayIcon(tooltip);
        else
            ti = getAWTTrayIcon(tooltip);
        ti.setImageAutoSize(true); //Resize image to fit the system tray
        tray.add(ti);
        trayIcon = ti;
    }

    /**
     * Create an AWT tray icon with popup menu.
     *
     * @param tooltip the tooltip text for the tray icon
     * @return the tray icon
     * @throws AWTException if the tray icon cannot be created
     */
    private TrayIcon getAWTTrayIcon(String tooltip) throws AWTException {
        PopupMenu menu = getMainMenu();
        if (!SystemVersion.isWindows())
            menu.setFont(new Font("Arial", Font.BOLD, 14));
        TrayIcon ti = new TrayIcon(getTrayImage(), tooltip, menu);
        ti.addMouseListener(new MouseListener() {
            @Override public void mouseClicked(MouseEvent m)  { /* no-op */ }
            @Override public void mouseEntered(MouseEvent m)  { /* no-op */ }
            @Override public void mouseExited(MouseEvent m)   { /* no-op */ }
            @Override public void mousePressed(MouseEvent m)  { updateMenu(); }
            @Override public void mouseReleased(MouseEvent m) { updateMenu(); }
        });
        return ti;
    }

    /**
     * Create a Swing tray icon with JPopupMenu.
     *
     * @param tooltip the tooltip text for the tray icon
     * @return the tray icon
     * @throws AWTException if the tray icon cannot be created
     */
    private TrayIcon getSwingTrayIcon(String tooltip) throws AWTException {
        // A JPopupMenu by itself is hard to get rid of,
        // so we hang it off a zero-size, undecorated JFrame.
        // http://stackoverflow.com/questions/1498789/jpopupmenu-behavior
        // http://stackoverflow.com/questions/2581314/how-do-you-hide-a-swing-popup-when-you-click-somewhere-else
        final JFrame frame = new JFrame();
        // http://stackoverflow.com/questions/2011601/jframe-without-frame-border-maximum-button-minimum-button-and-frame-icon
        frame.setUndecorated(true);
        frame.setMinimumSize(new Dimension(0, 0));
        frame.setSize(0, 0);
        final JPopupMenu menu = getSwingMainMenu();
        menu.setFocusable(true);
        frame.add(menu);
        TrayIcon ti = new TrayIcon(getTrayImage(), tooltip, null);
        ti.addMouseListener(new MouseListener() {
            @Override
            public void mouseClicked(MouseEvent e)  { /* no-op */ }
            @Override
            public void mouseEntered(MouseEvent e)  { /* no-op */ }
            @Override
            public void mouseExited(MouseEvent e)   { /* no-op */ }
            public void mousePressed(MouseEvent e)  { handle(e); }
            @Override
            public void mouseReleased(MouseEvent e) { handle(e); }
            private void handle(MouseEvent e) {
                if (!frame.isVisible()) {
                    frame.setLocation(e.getX(), e.getY());
                    frame.setVisible(true);
                    menu.show(frame, 0, 0);
                }
                updateMenu();
            }
        });
        menu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuCanceled(PopupMenuEvent e)            { /* no-op */ }
            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { frame.setVisible(false); }
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e)   { /* no-op */ }
        });
        menu.addFocusListener(new FocusListener() {
            @Override
            public void focusGained(FocusEvent e) { /* no-op */ }
            @Override
            public void focusLost(FocusEvent e)   { frame.setVisible(false); }
        });
        menu.addMenuKeyListener(new MenuKeyListener() {
            @Override
            public void menuKeyPressed(MenuKeyEvent e)  { /* no-op */ }
            @Override
            public void menuKeyReleased(MenuKeyEvent e) { /* no-op */ }
            @Override
            public void menuKeyTyped(MenuKeyEvent e)    {
                if (e.getKeyChar() == (char) 0x1b)
                    frame.setVisible(false);
            }
        });
        return ti;
    }

    /**
     * Remove the tray icon from the system tray
     *
     * @since 0.9.26
     */
    public synchronized void stopManager() {
        if (tray != null && trayIcon != null) {
            tray.remove(trayIcon);
            tray = null;
            trayIcon = null;
        }
    }

    /**
     * Update the tray menu when the language changes.
     */
    public synchronized void languageChanged() {
        if (trayIcon != null && !_useSwing)
            trayIcon.setPopupMenu(getMainMenu());
    }

    /**
     * Build a popup menu, adding callbacks to the different items.
     * @return popup menu
     */
    protected abstract PopupMenu getMainMenu();

    /**
     * Build a popup menu, adding callbacks to the different items.
     * @return popup menu
     * @since 0.9.26
     */
    protected abstract JPopupMenu getSwingMainMenu();

    /**
     * Update the menu
     * @since 0.9.26
     */
    protected abstract void updateMenu();

    /**
     * Get tray icon image from the desktopgui resources in the jar file.
     * @return image used for the tray icon
     * @throws AWTException if image not found
     */
    private Image getTrayImage() throws AWTException {
        String img;
        if (SystemVersion.isWindows())
            img = WIN_ICON;
        else if (SystemVersion.isMac())
            img = MAC_ICON;
        else
            img = LIN_ICON;
        URL url = getClass().getResource(PNG_DIR + img);
        if (url == null)
            throw new AWTException("cannot load tray image " + img);
        Image image = Toolkit.getDefaultToolkit().getImage(url);
        return image;
    }

    /**
     *  Send a notification to the user.
     *
     *  @param title for the popup, translated
     *  @param message translated
     *  @param path unsupported
     *  @return 0, or -1 on failure
     */
    public int displayMessage(int priority, String title, String message, String path) {
        if (!_showNotifications)
            return -1;
        final TrayIcon ti;
        synchronized(this) {
            ti = trayIcon;
        }
        if (ti == null)
            return -1;
        TrayIcon.MessageType type;
        if (priority <= Log.DEBUG)
            type = TrayIcon.MessageType.NONE;
        else if (priority <= Log.INFO)
            type = TrayIcon.MessageType.INFO;
        else if (priority <= Log.WARN)
            type = TrayIcon.MessageType.WARNING;
        else
            type = TrayIcon.MessageType.ERROR;
        ti.displayMessage(title, message, type);
        return 0;
    }

    /**
     *  Does not save. See InternalTrayManager.
     *
     *  @since 0.9.58 moved up from InternalTrayManager
     */
    protected void configureNotifications(boolean enable) {
        _showNotifications = enable;
    }

    /**
     *  Initializes _notificationItem 1 and 2
     *
     *  @since 0.9.58 pulled out of InternalTrayManager
     */
    protected void initializeNotificationItems() {
        final MenuItem notificationItem2 = new MenuItem(_t("Enable notifications"));
        notificationItem2.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                new SwingWorker<Object, Object>() {
                    @Override
                    protected Object doInBackground() throws Exception {
                        configureNotifications(true);
                        return null;
                    }
                }.execute();
            }
        });
        _notificationItem2 = notificationItem2;

        final MenuItem notificationItem1 = new MenuItem(_t("Disable notifications"));
        notificationItem1.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                new SwingWorker<Object, Object>() {
                    @Override
                    protected Object doInBackground() throws Exception {
                        configureNotifications(false);
                        return null;
                    }
                }.execute();
            }
        });
        _notificationItem1 = notificationItem1;
    }

    /**
     *  Initializes _jnotificationItem 1 and 2
     *
     *  @since 0.9.58 pulled out of InternalTrayManager
     */
    protected void initializeJNotificationItems() {
        final JMenuItem notificationItem2 = new JMenuItem(_t("Enable notifications"));
        notificationItem2.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                new SwingWorker<Object, Object>() {
                    @Override
                    protected Object doInBackground() throws Exception {
                        configureNotifications(true);
                        return null;
                    }
                }.execute();
            }
        });
        _jnotificationItem2 = notificationItem2;

        final JMenuItem notificationItem1 = new JMenuItem(_t("Disable notifications"));
        notificationItem1.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                new SwingWorker<Object, Object>() {
                    @Override
                    protected Object doInBackground() throws Exception {
                        configureNotifications(false);
                        return null;
                    }
                }.execute();
            }
        });
        _jnotificationItem1 = notificationItem1;
    }

    /**
     * Translate a string.
     *
     * @param s the string to translate
     * @return the translated string
     */
    protected String _t(String s) {
        return DesktopguiTranslator._t(_appContext, s);
    }

    /**
     * Translate a string with one parameter.
     *
     * @param s the string to translate
     * @param o the parameter to insert
     * @return the translated string
     * @since 0.9.26
     */
    protected String _t(String s, Object o) {
        return DesktopguiTranslator._t(_appContext, s, o);
    }
}
