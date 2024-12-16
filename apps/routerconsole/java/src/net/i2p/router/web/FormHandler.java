package net.i2p.router.web;

import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.servlet.RequestWrapper;
import net.i2p.util.Log;

/**
 * Simple form handler base class - does not depend on servlets or jsp,
 * but instead the subclasses are populated with javabean properties.  e.g.
 * &lt;jsp:setProperty name="handler" property="*" /&gt;
 *
 * The form is "processed" after the properties are set and the first output
 * property is retrieved - either getAll(), getNotices() or getErrors().
 *
 * This Handler will only process a single POST. The jsp bean must be declared scope=request.
 *
 */
public abstract class FormHandler {
    protected RouterContext _context;
    protected Log _log;
    /** Not for multipart/form-data, will be null */
    @SuppressWarnings("rawtypes")
    protected Map _settings;
    /** Only for multipart/form-data. Warning, parameters are NOT XSS filtered */
    protected RequestWrapper _requestWrapper;
    private String _nonce, _nonce1, _nonce2;
    protected String _action;
    protected String _method;
    private final List<Message> _errors;
    private final List<Message> _notices;
    private boolean _processed;
    private boolean _valid;
    protected Writer _out;

    public FormHandler() {
        _errors = new ArrayList<>();
        _notices = new ArrayList<>();
        _valid = true;
    }

    /**
     * Configure this bean to query a particular router context
     *
     * @param contextId beginning few characters of the routerHash, or null to pick
     *                  the first one we come across.
     */
    public void setContextId(String contextId) {
        try {
            _context = ContextHelper.getContext(contextId);
            _log = _context.logManager().getLog(getClass());
        } catch (Throwable t) {t.printStackTrace();}
    }

    public void setNonce(String val) {_nonce = val == null ? null : DataHelper.stripHTML(val);}
    public void setAction(String val) {_action = val == null ? null : DataHelper.stripHTML(val);}

    /**
     * For many forms, it's easiest just to put all the parameters here.
     *
     * @since 0.9.4 consolidated from numerous FormHandlers
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void setSettings(Map settings) {_settings = new HashMap<>(settings);}

    /**
     *  Only set by formhandler.jsi for multipart/form-data
     *
     *  @since 0.9.19
     */
    public void setRequestWrapper(RequestWrapper rw) {_requestWrapper = rw;}

    /**
     *  Same as HelperBase
     *  @since 0.9.14.1
     */
    public boolean isAdvanced() {return _context.getBooleanProperty(HelperBase.PROP_ADVANCED);}

    /**
     * setSettings() must have been called previously
     * Curses Jetty for returning arrays.
     *
     * @since 0.9.4 consolidated from numerous FormHandlers
     * @return trimmed string or null
     */
    protected String getJettyString(String key) {
        if (_settings == null) {return null;}
        String[] arr = (String[]) _settings.get(key);
        if (arr == null) {return null;}
        return arr[0].trim();
    }

    /**
     * Call this to prevent changes using GET
     *
     * @param val the request method
     * @since 0.8.2
     */
    public void storeMethod(String val) {_method = val;}

    /**
     * @since 0.9.38
     */
    public void storeWriter(Writer out) {_out = out;}

    /**
     * The old nonces from the session
     * @since 0.9.4
     */
    public void storeNonces(String n1, String n2) {
        _nonce1 = n1;
        _nonce2 = n2;
    }

    /**
     * Implement this to perform the final processing (in turn, adding formNotice
     * and formError messages, etc)
     *
     * Will only be called if _action is non-null and the nonce is valid.
     */
    protected abstract void processForm();

    /**
     * Add an error message to display
     * Use if it does not include a link.
     * Escapes '&lt;' and '&gt;' before queueing
     */
    protected void addFormError(String errorMsg) {
        addFormError(errorMsg, false);
    }

    /**
     * Add an error message to display
     * Use if it does not include a link.
     * Escapes '&lt;' and '&gt;' before queueing
     * @param canClose If true, the message can be closed by the user.
     */
    protected void addFormError(String errorMsg, boolean canClose) {
        if (errorMsg == null) {return;}
        _errors.add(new Message(DataHelper.escapeHTML(errorMsg), canClose));
    }

    /**
     * Add a non-error message to display
     * Use if it does not include a link.
     * Escapes '&lt;' and '&gt;' before queueing
     */
    protected void addFormNotice(String msg) {
        addFormNotice(msg, false);
    }

    /**
     * Add a non-error message to display
     * Use if it does not include a link.
     * Escapes '&lt;' and '&gt;' before queueing
     * @param canClose If true, the message can be closed by the user.
     */
    protected void addFormNotice(String msg, boolean canClose) {
        if (msg == null) {return;}
        _notices.add(new Message(DataHelper.escapeHTML(msg), canClose));
    }

    /**
     * Add a non-error message to display
     * Use if it includes a link or other formatting.
     * Does not escape '&lt;' and '&gt;' before queueing
     * @since 0.9.14.1
     */
    protected void addFormNoticeNoEscape(String msg) {
        addFormNoticeNoEscape(msg, false);
    }

    /**
     * Add a non-error message to display
     * Use if it includes a link or other formatting.
     * Does not escape '&lt;' and '&gt;' before queueing
     * @param canClose If true, the message can be closed by the user.
     * @since 0.9.14.1
     */
    protected void addFormNoticeNoEscape(String msg, boolean canClose) {
        if (msg == null) {return;}
        _notices.add(new Message(msg, canClose));
    }

    /**
     * Add an error message to display
     * Use if it includes a link or other formatting.
     * Does not escape '&lt;' and '&gt;' before queueing
     * @since 0.9.19
     */
    protected void addFormErrorNoEscape(String msg) {
        addFormErrorNoEscape(msg, false);
    }

    /**
     * Add an error message to display
     * Use if it includes a link or other formatting.
     * Does not escape '&lt;' and '&gt;' before queueing
     * @param canClose If true, the message can be closed by the user.
     * @since 0.9.19
     */
    protected void addFormErrorNoEscape(String msg, boolean canClose) {
        if (msg == null) {return;}
        _errors.add(new Message(msg, canClose));
    }

    /**
     * Display everything, wrap it in a div for consistent presentation
     *
     */
    public String getAllMessages() {
        validate();
        process();
        if (_errors.isEmpty() && _notices.isEmpty()) {return "";}
        StringBuilder buf = new StringBuilder(512);

        // Determine if any message can be closed
        boolean canClose = _errors.stream().anyMatch(Message::isCanClose) || _notices.stream().anyMatch(Message::isCanClose);

        buf.append("<div class=\"messages");
        if (canClose) {buf.append(" canClose");}
        buf.append("\" id=messages>");

        if (!_errors.isEmpty()) {buf.append("<div class=error>").append(render(_errors)).append("</div>");}
        if (!_notices.isEmpty()) {buf.append("<div class=notice>").append(render(_notices)).append("</div>");}

        buf.append("</div>\n").append("<script src=/js/clickToClose.js></script>\n");
        return buf.toString();
    }

    /**
     * Display any error messages (processing the form if it hasn't
     * been yet)
     *
     */
    public String getErrors() {
        validate();
        process();
        return render(_errors);
    }

    /**
     * Display any non-error messages (processing the form if it hasn't
     * been yet)
     *
     */
    public String getNotices() {
        validate();
        process();
        return render(_notices);
    }

    /**
     * Make sure the nonce was set correctly, otherwise someone could just
     * create a link like /confignet.jsp?hostname=localhost and break the
     * user's node (or worse).
     *
     */
    private void validate() {
        if (_processed) {return;}

        _valid = true;
        if (_action == null) {
            // not a form submit
            _valid = false;
            return;
        }
        // To prevent actions with GET, jsps must call storeMethod()
        if (_method != null && !"POST".equals(_method)) {
            addFormError(_t("Invalid form submission, requires POST"), true);
            _valid = false;
            return;
        }
        // If passwords are turned on, all is assumed good
        if (_context.getBooleanProperty(RouterConsoleRunner.PROP_PW_ENABLE)) {
            _valid = true;
            return;
        }
        if (_nonce == null) {
            //addFormError("You trying to mess with me?  Huh?  Are you?");
            _valid = false;
            return;
        }

        String sharedNonce = CSSHelper.getNonce();
        if (sharedNonce.equals(_nonce)) {return;}

        if (!_nonce.equals(_nonce1) && !_nonce.equals(_nonce2)) {
            addFormError(_t("Invalid form submission, probably because you used the 'back' or 'reload' button on your browser. Please resubmit.") + ' ' +
                         _t("If the problem persists, verify that you have cookies enabled in your browser."), true);
            _valid = false;
        }
    }

    private void process() {
        if (!_processed) {
            if (_valid) {processForm();}
            _processed = true;
        }
    }

    private static String render(List<Message> source) {
        if (source.isEmpty()) {return "";}
        else {
            StringBuilder buf = new StringBuilder(512);
            buf.append("<ul>\n");
            for (Message message : source) {
                buf.append("<li>").append(message.getText()).append("</li>\n");
            }
            buf.append("</ul>\n");
            return buf.toString();
        }
    }

    /**
     *  Generate a new nonce.
     *  Only call once per page!
     *  @return a new random long as a String
     *  @since 0.8.5
     */
    public String getNewNonce() {
        String rv = Long.toString(_context.random().nextLong());
        return rv;
    }

    /** translate a string */
    public String _t(String s) {return Messages.getString(s, _context);}

    /**
     *  translate a string with a parameter
     *  This is a lot more expensive than _t(s), so use sparingly.
     *
     *  @param s string to be translated containing {0}
     *    The {0} will be replaced by the parameter.
     *    Single quotes must be doubled, i.e. ' -&gt; '' in the string.
     *  @param o parameter, not translated.
     *    To translate parameter also, use _t("foo {0} bar", _t("baz"))
     *    Do not double the single quotes in the parameter.
     *    Use autoboxing to call with ints, longs, floats, etc.
     */
    public String _t(String s, Object o) {return Messages.getString(s, o, _context);}

    /** two params @since 0.8.2 */
    public String _t(String s, Object o, Object o2) {return Messages.getString(s, o, o2, _context);}

    /**
     *  Mark a string for extraction by xgettext and translation.
     *  Use this only in static initializers.
     *  It does not translate!
     *  @return s
     */
    public static String _x(String s) {return s;}

    /**
     * Message class to encapsulate the message text and the canClose flag
     */
    public static class Message {
        private String text;
        private boolean canClose;

        public Message(String text, boolean canClose) {
            this.text = text;
            this.canClose = canClose;
        }

        public String getText() {
            return text;
        }

        public boolean isCanClose() {
            return canClose;
        }
    }
}