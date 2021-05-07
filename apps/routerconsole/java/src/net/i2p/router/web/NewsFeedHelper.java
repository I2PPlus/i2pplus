package net.i2p.router.web;

import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.app.ClientAppManager;
import net.i2p.data.DataHelper;
import net.i2p.router.news.NewsEntry;
import net.i2p.router.news.NewsManager;
import net.i2p.util.SystemVersion;


/**
 *  HTML-formatted full news entries
 *
 *  @since 0.9.23
 */
public class NewsFeedHelper extends HelperBase {

    private int _start;
    private int _limit = 2;

    /**
     *  @param limit less than or equal to zero means all
     */
    public void setLimit(int limit) {
        _limit = limit;
    }

    public void setStart(int start) {
        _start = start;
    }

    public String getEntries() {
        return getEntries(_context, _start, _limit, 0);
    }

    /**
     *  @param max less than or equal to zero means all
     *  @param ageLimit time before now, less than or equal to zero means all (after the first)
     *  @return non-null, "" if none
     */
    static String getEntries(I2PAppContext ctx, int start, int max, long ageLimit) {
        if (max <= 0)
            max = Integer.MAX_VALUE;
        StringBuilder buf = new StringBuilder(512);
        List<NewsEntry> entries = Collections.emptyList();
        ClientAppManager cmgr = ctx.clientAppManager();
        if (cmgr != null) {
            NewsManager nmgr = (NewsManager) cmgr.getRegisteredApp(NewsManager.APP_NAME);
            if (nmgr != null) {
                entries = nmgr.getEntries();
                NewsEntry init = nmgr.getInitialNews();
                if (init != null) {
                    // crude check to see if it's already in there
                    if (entries.size() != 1 || !DataHelper.eq(entries.get(0).title, init.title))
                        if (entries.isEmpty())
                            entries = Collections.singletonList(init);  // in case it was an emtpyList
                        else
                            entries.add(init);
                }
            }
        }
        if (!entries.isEmpty()) {
            DateFormat fmt = DateFormat.getDateInstance(DateFormat.MEDIUM);
            // the router sets the JVM time zone to UTC but saves the original here so we can get it
            fmt.setTimeZone(SystemVersion.getSystemTimeZone(ctx));
            int i = 0;
            for (NewsEntry entry : entries) {
                if (i < start)
                    continue;
                if (i > start && entry.updated > 0 && ageLimit > 0 &&
                    entry.updated < ctx.clock().now() - ageLimit)
                    break;
                buf.append("<div class=\"newsentry lazy\">\n<h3>");
                if (entry.updated > 0) {
                    Date date = new Date(entry.updated);
                    buf.append("<span class=\"newsDate\">")
                       .append(fmt.format(date).replace("-", " "))
                       .append("</span> ");
                }
                if (entry.link != null)
                    buf.append("<a href=\"").append(DataHelper.escapeHTML(entry.link)).append("\" target=\"_blank\">");
                buf.append(entry.title);
                if (entry.link != null)
                    buf.append("</a>");
                if (entry.authorName != null) {
                                                              // FIXME translate
                    buf.append(" <span class=\"newsAuthor\" title=\"Post author\"><i>")
                       .append(DataHelper.escapeHTML(entry.authorName))
                       .append("</i></span>\n");
                }
                buf.append("</h3>\n<div class=\"newscontent\">\n")
                   .append(entry.content.replace("<a href", "<a target=\"_blank\" href"))
                   .append("\n</div>\n</div>\n");
                if (++i >= start + max)
                    break;
            }
        }
        return buf.toString();
    }
}
