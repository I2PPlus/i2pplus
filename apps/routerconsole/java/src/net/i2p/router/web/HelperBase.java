package net.i2p.router.web;

import java.io.Writer;
import net.i2p.router.RouterContext;

/**
 *  Base helper class for JSP helper beans.
 *  Provides common functionality for router context access,
 *  translation services, and UI helper methods.
 */
public abstract class HelperBase {
    /** the router context */
    protected RouterContext _context;
    /** the writer for output */
    protected Writer _out;

    /**
     *  Property key for advanced mode.
     *  @since public since 0.9.33, was package private
     */
    public static final String PROP_ADVANCED = "routerconsole.advanced";
    /**  Checked attribute for HTML checkboxes.
     *  @since public since 0.9.33, was package private
     */
    public static final String CHECKED = " checked ";
    /**  Selected attribute for HTML select options.
     *  @since 0.9.43
     */
    public static final String SELECTED = " selected ";

    /**
     *  Configure this bean to query a particular router context.
     *
     *  @param contextId beginning few characters of the routerHash, or null to pick
     *                  the first one we come across.
     */
    public void setContextId(String contextId) {
        try {_context = ContextHelper.getContext(contextId);}
        catch (Throwable t) {t.printStackTrace();}
    }

    /**
     *  Check if advanced mode is enabled.
     *  @return true if advanced mode is enabled
     *  @since 0.9.9
     */
    public boolean isAdvanced() {
        return _context.getBooleanProperty(PROP_ADVANCED);
    }

    /** might be useful in the jsp's */
    //public RouterContext getContext() { return _context; }


    /**
     *  Store the writer for output.
     *  Renamed from setWriter, we really don't want setFoo(non-String)
     *  to prevent jsp.error.beans.property.conversion 500 error for ?writer=foo
     *  @param out the writer to store
     *  @since 0.8.2
     */
    public void storeWriter(Writer out) { _out = out; }

    /**
     *  Get the checked attribute for a boolean property.
     *  @param prop the property name, must default to false
     *  @return non-null, either "" or " checked "
     *  @since 0.9.24 consolidated from various helpers
     */
    protected String getChecked(String prop) {
        if (_context.getBooleanProperty(prop)) {return CHECKED;}
        return "";
    }

    /**
     *  Get the checked attribute for a boolean property that defaults to true.
     *  @param prop the property name
     *  @return non-null, either "" or " checked "
     *  @since 0.9.24 consolidated from various helpers
     */
    protected String getCheckedDefaultTrue(String prop) {
        if (_context.getBooleanPropertyDefaultTrue(prop)) {return CHECKED;}
        return "";
    }

    /**
     *  Translate a string.
     *  @param s the string to translate
     *  @return the translated string
     */
    public String _t(String s) {
        return Messages.getString(s, _context);
    }

    /**
     *  Translate a string with a parameter.
     *  This is a lot more expensive than _t(s), so use sparingly.
     *
     *  @param s string to be translated containing {0}
     *    The {0} will be replaced by the parameter.
     *    Single quotes must be doubled, i.e. ' -&gt; '' in the string.
     *  @param o parameter, not translated.
     *    To translate parameter also, use _t("foo {0} bar", _t("baz"))
     *    Do not double the single quotes in the parameter.
     *    Use autoboxing to call with ints, longs, floats, etc.
     *  @return the translated string
     */
    public String _t(String s, Object o) {
        return Messages.getString(s, o, _context);
    }

    /**
     *  Translate a string with two parameters.
     *  @param s string to be translated containing {0} and {1}
     *  @param o first parameter
     *  @param o2 second parameter
     *  @return the translated string
     *  @since 0.7.14
     */
    public String _t(String s, Object o, Object o2) {
        return Messages.getString(s, o, o2, _context);
    }

    /**
     *  Translate a string with plural forms (ngettext).
     *  @param s singular form
     *  @param p plural form
     *  @param n the count to determine which form to use
     *  @return the translated string
     *  @since 0.7.14
     */
    public String ngettext(String s, String p, int n) {
        return Messages.getString(n, s, p, _context);
    }

    /**
     *  Mark a string for extraction by xgettext and translation.
     *  Use this only in static initializers.
     *  It does not translate!
     *  @param s the string to mark
     *  @return s
     */
    public static String _x(String s) {
        return s;
    }
}
