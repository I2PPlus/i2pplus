package net.i2p.router.web.helpers;

import java.io.IOException;
import net.i2p.router.web.HelperBase;
import net.i2p.util.Log;

/**
 * Helper for peer profiles page rendering and form processing.
 * @since 0.9.33
 */
public class ProfilesHelper extends HelperBase {
    private int _full;

    private static final String[] titles = {
                                            _x("All"),             // 0
                                            _x("Fast"),            // 1
                                            _x("High Capacity"),   // 2
                                            _x("Floodfill"),       // 3
                                            _x("Banned")           // 4
                                           };

    private static final String[] links =  {
                                            "",                    // 0
                                            "?show=fast",          // 1
                                            "?show=highcap",       // 2
                                            "?show=floodfill",     // 3
                                            "?show=banned"         // 4
                                           };

    /**
     * setFull.
     */
    public void setFull(String f) {
        if (f != null) {
            try {
                _full = Integer.parseInt(f);
                if (_full < 0 || _full > 4) {_full = 0;}
            } catch (NumberFormatException nfe) { /* ignored */ }
        }
    }

    /**
     * Set by show=fast|highcap|floodfill|banned param (alternative to f=N).
     * Overrides _full if present.
     * @since 0.9.70+
     */
    public void setShow(String show) {
        if (show != null) {
            if ("fast".equals(show)) _full = 1;
            else if ("highcap".equals(show)) _full = 2;
            else if ("floodfill".equals(show)) _full = 3;
            else if ("banned".equals(show)) _full = 4;
        }
    }

    /**
     *  Render and return the profile summary page.
     *
     *  @return empty string, writes directly to _out
     *  @since 0.9.1
     */
    public String getSummary() {
        try {renderNavBar();}
        catch (IOException ioe) { /* ignored */ }
        if (_full == 4) getBanlistCompact();
        else getProfileSummary();
        return "";
    }

    /** @return empty string, writes directly to _out */
    public String getProfileSummary() {
        try {
            ProfileOrganizerRenderer rend = new ProfileOrganizerRenderer(_context.profileOrganizer(), _context);
            rend.renderStatusHTML(_out, _full);
        } catch (IOException ioe) {_log.error("Error rendering profile summary", ioe);}
        return "";
    }

    /** @return empty string, writes directly to _out */
    public String getBanlistCompact() {
        try {
            BanlistRenderer rend = new BanlistRenderer(_context);
            rend.renderBanlistCompact(_out);
        } catch (IOException ioe) {_log.error("Error rendering banlist", ioe);}
        return "";
    }

    /**
     *  Return the currently selected profiles tab index.
     *
     *  @since 0.9.1
     * @return the tab
     */
    private int getTab() {
        return _full;
    }

    /**
     *  Render the profiles navigation bar.
     *
     *  @since 0.9.1
     */
    private void renderNavBar() throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<div class=confignav id=confignav>");
        int tab = getTab();
        for (int i = 0; i < titles.length; i++) {
            if (i == tab) {buf.append("<span class=tab2>").append(_t(titles[i]));} // we are there
            else { // we are not there, make a link
                buf.append("<span class=tab>").append("<a href=\"profiles")
                   .append(links[i]).append("\">").append(_t(titles[i])).append("</a>");
            }
            buf.append("</span>\n");
        }
        buf.append("</div>\n");
        _out.append(buf);
    }

}
