package net.i2p.router.web;

import java.io.File;
import java.util.Locale;

import net.i2p.util.FileUtil;

public class ContentHelper extends HelperBase {
    protected String _page;
    private int _maxLines;
    private boolean _startAtBeginning;
    private String _lang;

    /**
     * Caution, use absolute paths only, do not assume files are in CWD
     */
    public void setPage(String page) { _page = page; }
    public void setStartAtBeginning(String moo) {
        _startAtBeginning = Boolean.parseBoolean(moo);
    }
    public void setLang(String l) {
/*****
        if((_lang == null || !_lang.equals(l)) && (l != null)) {
            //Set language for router console
            _lang = l;
 TODO - Temporary for 0.8.4
        Needed for desktopgui. But there's no nonce protection.
        Move the following to CSSHelper setLang(), or disable completely,
        See comments in CSSHelper
            if(_context == null) {
                setContextId(null);
            }

            if (_context.getBooleanProperty("desktopgui.enabled")) {
                //Set language persistently throughout I2P
                _context.router().setConfigSetting(Messages.PROP_LANG, _lang);
                _context.router().saveConfig();
                _context.setProperty(Messages.PROP_LANG, _lang);
            }
        }
*****/
    }

    public void setMaxLines(String lines) {
        if (lines != null) {
            try {
                _maxLines = Integer.parseInt(lines);
            } catch (NumberFormatException nfe) {
                _maxLines = -1;
            }
        } else {
            _maxLines = -1;
        }
    }
    public String getContent() {
        String str = FileUtil.readTextFile(filename(), _maxLines, _startAtBeginning);
        if (str == null)
            return "";
        else
            return str;
    }
    public String getTextContent() {
        String str = FileUtil.readTextFile(filename(), _maxLines, _startAtBeginning);
        if (str == null)
            return "";
        else {
            StringBuilder sb = new StringBuilder(str.length()+11);
            sb.append("<pre>");
            for (int i=0; i < str.length(); i++) {
                char c = str.charAt(i);
                switch (str.charAt(i)) {
                    case '<': sb.append("&lt;"); break;
                    case '>': sb.append("&gt;"); break;
                    case '&': sb.append("&amp;"); break;
                    default: sb.append(c); break;
                }
            }
            return sb.append("</pre>").toString();
   }
    }

    /**
     * Convert file.ext to file_lang.ext if it exists.
     * Get lang from the cgi lang param, then properties, then from the default locale.
     * _context must be set to check the property.
     */
    private String filename() {
        int lastdot = _page.lastIndexOf('.');
        if (lastdot <= 0)
            return _page;
        String lang = _lang;
        if (lang == null || lang.length() <= 0) {
            if (_context != null)
                lang = _context.getProperty(Messages.PROP_LANG);
            if (lang == null || lang.length() <= 0) {
                lang = Locale.getDefault().getLanguage();
                if (lang == null || lang.length() <= 0)
                    return _page;
            }
        }
        if (lang.equals("en"))
            return _page;
        String newname = _page.substring(0, lastdot) + '_' + lang + _page.substring(lastdot);
        File newfile = new File(newname);
        if (newfile.exists())
            return newname;
        return _page;
    }
}
