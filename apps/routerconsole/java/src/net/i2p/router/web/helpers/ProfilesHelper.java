package net.i2p.router.web.helpers;

import java.io.IOException;
import net.i2p.router.web.HelperBase;

/**
 * Helper for peer profiles page rendering and form processing.
 * @since 0.9.33
 */
public class ProfilesHelper extends HelperBase {
    private int _full;
    private boolean _graphical;

    private static final String[] titles = {
                                            _x("All"),             // 0
                                            _x("High Capacity"),   // 1
                                            _x("Floodfill"),       // 2
                                            _x("Banned")          // 3
                                           };

    private static final String[] links =  {
                                            "",                    // 0
                                            "?f=1",                // 1
                                            "?f=2",                // 2
                                            "?f=3"                 // 3 (Session Bans)
                                           };

    public void setFull(String f) {
        if (f != null) {
            try {
                _full = Integer.parseInt(f);
                if (_full < 0 || _full > 3) {_full = 0;}
            } catch (NumberFormatException nfe) { /* ignored */ }
        }
    }

    /**
     *  call for non-text-mode browsers
     *  @since 0.9.1
     */
    public void allowGraphical() {_graphical = true;}

    /**
     *  @return empty string, writes directly to _out
     *  @since 0.9.1
     */
    public String getSummary() {
        try {renderNavBar();}
        catch (IOException ioe) { /* ignored */ }
        if (_full == 3) getBanlistCompact();
        else getProfileSummary();
        return "";
    }

    /** @return empty string, writes directly to _out */
    public String getProfileSummary() {
        try {
            ProfileOrganizerRenderer rend = new ProfileOrganizerRenderer(_context.profileOrganizer(), _context);
            rend.renderStatusHTML(_out, _full);
        } catch (IOException ioe) {ioe.printStackTrace();}
        return "";
    }

    /** @return empty string, writes directly to _out */
    public String getBanlistCompact() {
        try {
            BanlistRenderer rend = new BanlistRenderer(_context);
            rend.renderBanlistCompact(_out);
        } catch (IOException ioe) {ioe.printStackTrace();}
        return "";
    }

    /**
     *  @since 0.9.1
     */
    private int getTab() {
        if (_full == 1) return 1;
        if (_full == 2) return 2;
        if (_full == 3) return 3;
        return 0;
    }

    /**
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
