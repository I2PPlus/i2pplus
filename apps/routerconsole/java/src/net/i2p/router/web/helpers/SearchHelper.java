package net.i2p.router.web.helpers;

import java.util.Map;
import java.util.TreeMap;

import net.i2p.data.DataHelper;
import net.i2p.router.web.HelperBase;

/**
 *  Helper for searches.
 *
 *  @since 0.9
 */
public class SearchHelper extends HelperBase {

    private String _engine;
    private String _query;
    private Map<String, String> _engines = new TreeMap<String, String>();

    private static final char S = ',';
    // in case engines need to know where it came from
    // this reveals more info than needed, namely that user has enabled console search; neutering.
    //private static final String SOURCE = "&ref=console";
    static final String PROP_ENGINES = "routerconsole.searchEngines";
    private static final String PROP_DEFAULT = "routerconsole.searchEngine";
    private static final String DEFAULT = "shinobi.i2p";

    static final String ENGINES_DEFAULT =
    // TODO: add a checkbox or dropdown to UI to choose default engine
        "ahmia.i2p"                + S +     "http://ahmia.i2p/search/?q=%s" + S +
        "duckduckgo.i2p"           + S +     "http://duckduckgo.i2p/?q=%s" + S +
        "i2pforum.i2p"             + S +     "http://i2pforum.i2p/search.php?keywords=%s" + S +
        "i2psearch.i2p"            + S +     "http://i2psearch.i2p/search?q=%s" + S +
        "hackaday.i2p"             + S +     "http://hackaday.i2p/blog/?s=%s" + S +
        "isitup.i2p"               + S +     "http://isitup.i2p/api/check?sitename=%s" + S +
        "libxsearch.i2p"           + S +     "http://libxsearch.i2p/search.php?q=%s" + S +
        "mojeek.i2p"               + S +     "http://mojeek.i2p/search?date=1&size=1&t=40&q=%s" + S +
        "natter.i2p"               + S +     "http://natter.i2p/search?f=tweets&q=%s" + S +
        "newsnow.i2p"              + S +     "http://newsnow.i2p/?search=%s" + S +
        "notbob.i2p"               + S +     "http://notbob.i2p/cgi-bin/defcon.cgi?search=%s" + S +
        "pinterest"                + S +     "http://binternet.lostskunk-dnr.i2p/search.php?q=%s" + S +
        "psychonaut.i2p"           + S +     "http://psychonaut.i2p/w/index.php?search=%s&fulltext=Search" + S +
        "ramble.i2p"               + S +     "http://ramble.i2p/search?q=%s" + S +
        "slashdot.i2p"             + S +     "http://slashdot.i2p/index2.pl?fhfilter=%s" + S +
        "shinobi.i2p"              + S +     "http://shinobi.i2p/search?query=%s" + S +
        "shmoogle.i2p"             + S +     "http://shmoogle.i2p/yacysearch.html?query=%s" + S +
        "shreddit.i2p/r/i2p"       + S +     "http://shreddit.i2p/r/i2p/search?q=%s&restrict_sr=on" + S +
        "tracker2.postman.i2p"     + S +     "http://tracker2.postman.i2p/?search=%s" + S +
        "tube.i2p"                 + S +     "http://tube.i2p/search?query=%s" + S +
        "wiki.i2p-projekt.i2p"     + S +     "http://wiki.i2p-projekt.i2p/wiki/index.php?search=%s" + S +
        "wikiless.i2p"             + S +     "http://wikiless.i2p/w/index.php?search=%s&fulltext=Search" + S +
        "wordnik.i2p"              + S +     "http://wordnik.i2p/words?myWord=%s" +
        //"ebooks.i2p"               + S +     "http://ebooks.i2p/index.php?page=9&query=%s" + S +
        //"eepsites.i2p"             + S +     "http://eepsites.i2p/Content/Search/SearchResults.aspx?inpQuery=%s" + S +
        //"epsilon.i2p"              + S +     "http://epsilon.i2p/search.jsp?q=%s" +  S +
        //"imperial.i2p"             + S +     "http://imperial.i2p/search/?q=%s" + S +
        //"legwork.i2p"              + S +     "http://legwork.i2p/yacysearch.html?query=%s" + "&maximumRecords=1000" + S +
        //"raklet.i2p"               + S +     "http://raklet.i2p/search?page=1&query=%s" + S +
        //"ransack.i2p"              + S +     "http://ransack.i2p/?q=%s" + S +
        //"searchthis.i2p"           + S +     "http://searchthis.i2p/cgi-bin/search.cgi?q=%s" + S +
        //"seeker.i2p"               + S +     "http://seeker.i2p/index.php?query=%s" + S +
        //"simple-search.i2p"        + S +     "http://simple-search.i2p/search.sh?search=%s" + S +
        //"sprongle.i2p"             + S +     "http://sprongle.i2p/sprongle.php?q=%s" + S +
        //"stackoverflow.i2p"        + S +     "http://duckduckgo.i2p/?q=site%3Astackoverflow.com+%s" + S +
        //"teddit.i2p/r/i2p"         + S +     "http://teddit.i2p/r/i2p/search?q=%s&restrict_sr=on" + S +
        //"tome.i2p"                 + S +     "http://tome.i2p/search.php?q=%s" + S +
        //"torrentfinder.i2p"        + S +     "http://torrentfinder.i2p/?searchq=%s" + S +
        //"trac.i2p2.i2p"            + S +     "http://trac.i2p2.i2p/search?q=%s" + S +
        //"wired.i2p"                + S +     "http://wired.i2p/search/?q=%s" + S +
        //"yacy.idk.i2p"             + S +     "http://yacy.idk.i2p/yacysearch.html?query=%s" + "&maximumRecords=1000" + S +
        //"zzz.i2p"                  + S +     "http://zzz.i2p/search?type=posts&query=%s" + //S +
        "";

    public void setEngine(String s) {
        _engine = s;
        if (s != null) {
            String dflt = _context.getProperty(PROP_DEFAULT, DEFAULT);
            if (!s.equals(dflt))
                _context.router().saveConfig(PROP_DEFAULT, s);
        }
    }

    public void setQuery(String s) {
        _query = s;
    }

    private static final String SS = Character.toString(S);

    private void buildEngineMap() {
        String config = _context.getProperty(PROP_ENGINES, ENGINES_DEFAULT);
        String[] args = DataHelper.split(config, SS);
        for (int i = 0; i < args.length - 1; i += 2) {
            String name = args[i];
            String url = args[i+1];
            _engines.put(name, url);
        }
    }

    public String getSelector() {
        buildEngineMap();
        if (_engines.isEmpty())
            return "<b>No search engines specified</b>";
        String dflt = _context.getProperty(PROP_DEFAULT, DEFAULT);
        if (dflt == null || !_engines.containsKey(dflt)) {
            // pick a random one as default and save it
            int idx = _context.random().nextInt(_engines.size());
            int i = 0;
            for (String name : _engines.keySet()) {
                dflt = name;
                if (i++ >= idx) {
                    _context.router().saveConfig(PROP_DEFAULT, dflt);
                    break;
                }
            }
        }
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<select name=\"engine\" title=\"").append(_t("Select search engine")).append("\">");
        for (String name : _engines.keySet()) {
            buf.append("<option value=\"").append(name).append('\"');
            if (name.equals(dflt))
                buf.append(SELECTED);
            buf.append('>').append(name).append("</option>\n");
        }
        buf.append("</select>\n");
        return buf.toString();
    }

    /**
     *  @return null on error
     */
    public String getURL() {
        if (_engine == null || _query == null)
            return null;
        _query = DataHelper.escapeHTML(_query).trim();
        if (_query.length() <= 0)
            return null;
        buildEngineMap();
        String url = _engines.get(_engine);
        if (url == null)
            return null;
        if (url.contains("%s"))
            url = url.replace("%s", _query);
        else
            url += _query;
        return url;
    }
}
