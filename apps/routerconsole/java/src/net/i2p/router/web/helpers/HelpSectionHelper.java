package net.i2p.router.web.helpers;

import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.i2p.router.web.HelperBase;
import net.i2p.router.web.Messages;
import net.i2p.router.web.PluginStarter;

/**
 * Render the configuration menu at the top of all the config pages.
 * refactored from confignav.jsp to reduce size and make translation easier
 * @author zzz
 */
public class HelpSectionHelper extends HelperBase {

    /** help-X.jsp */
    private static final String pages[] =
                                          {"", "configuration", "sidebar", "reseeding", "advancedsettings",
                                           "faq", "reachability", "reseed", "legal" };

    private static final String titles[] =
                                          {_x("Overview"),
                                           _x("Configuration"),
                                           _x("Sidebar"),
                                           _x("Reseeding"),
                                           _x("FAQ") };

    /** @since 0.9.19 */
    private static class Tab {
        public final String page, title;
        public Tab(String p, String t) {
            page = p; title = t;
        }
    }

    /** @since 0.9.19 */
    private class TabComparator implements Comparator<Tab> {
         private static final long serialVersionUID = 1L;
         private final Collator coll;

         public TabComparator() {
             super();
             coll = Collator.getInstance(new Locale(Messages.getLanguage(_context)));
         }

         public int compare(Tab l, Tab r) {
             return coll.compare(l.title, r.title);
        }
    }

    /**
     *  @param graphical false for text-mode browsers
     */
    public void renderNavBar(String requestURI, boolean graphical) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        List<Tab> tabs = new ArrayList<Tab>(pages.length);
        boolean hidePlugins = !PluginStarter.pluginsEnabled(_context);
        for (int i = 0; i < pages.length; i++) {tabs.add(new Tab(pages[i], _t(titles[i])));}
        Collections.sort(tabs, new TabComparator());
        for (int i = 0; i < tabs.size(); i++) {
            String page = "help-" + tabs.get(i).page;
            if (requestURI.endsWith(page) || requestURI.endsWith(page + ".jsp")) {
                buf.append("<span class=tab2>").append(tabs.get(i).title); // we are there
            } else { // we are not there, make a link
                buf.append("<span class=tab>").append("<a href=\"").append(page).append("\">")
                   .append(tabs.get(i).title).append("</a>");
            }
            buf.append("</span>\n");
        }
        _out.append(buf);
    }

}