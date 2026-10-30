package net.i2p.router.web;

import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.http.HttpSession;

import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.servlet.RequestWrapper;
import net.i2p.util.Log;

/**
 * Simple form handler base class - does not depend on servlets or jsp,
 * but instead the subclasses are populated with javabean properties.  e.g.
 * &lt;jsp:setProperty name="handler" property="*" /&gt;
 * <p>
 * The form is "processed" after the properties are set and the first output
 * property is retrieved - either getAll(), {@link #getNotices()} or getErrors().
 * <p>
 * This Handler will only process a single POST. The jsp bean must be declared scope=request.
 */
public abstract class FormHandler {
    /** Router context. */
    protected RouterContext _context;
    /** Class logger. */
    protected Log _log;
    /** Not for multipart/form-data, will be null. */
    @SuppressWarnings("rawtypes")
    protected Map _settings;
    /** Only for multipart/form-data. Warning, parameters are NOT XSS filtered. */
    protected RequestWrapper _requestWrapper;
    /** The form nonce to check. */
    private String _nonce;
    /** Form action. */
    protected String _action;
    /** Request method. */
    protected String _method;
    /** The error messages to display. */
    private final List<Message> _errors;
    /** The notice messages to display. */
    private final List<Message> _notices;
    /** True once the form was processed. */
    private boolean _processed;
    /** True when the form input was valid. */
    private boolean _valid;
    /** Output writer. */
    protected Writer _out;
    /** HTTP session for nonce validation. */
    protected HttpSession _session;

    /**
     * Length of one rate-limit window, in ms.
     * <p>
     * The rate limiter for form submissions - it exists to slow down brute-force
     * nonce guessing on a failed submission. One budget per key; see validate()
     * for what the key is.
     */
    static final int RATE_LIMIT_WINDOW_MS = 60000; // 1 minute
    /** POSTs admitted per window per key before further ones are refused. */
    static final int RATE_LIMIT_MAX_POSTS = 30;
    /** The single budget key the console charges POSTs to. */
    static final String RATE_LIMIT_GLOBAL_KEY = "global";
    /** Above this many budgets, drop the idle ones before adding another. */
    private static final int RATE_LIMIT_SWEEP_SIZE = 1000;
    /** Live budgets, keyed by the value passed to checkRateLimit(). */
    static final ConcurrentHashMap<String, RateLimitEntry> RATE_LIMIT_MAP =
        new ConcurrentHashMap<>();

    /**
     * One window's POST count. {@link #windowStart} moves with the window, so a
     * client that outlives the first one is counted again instead of waved
     * through forever.
     */
    static class RateLimitEntry {

        /**
         * An empty window, opened by the call that created it.
         */
        RateLimitEntry() {}

        /** POSTs charged to the current window; the creating call charges itself. */
        final AtomicInteger count = new AtomicInteger(0);
        /** When the window this count belongs to began, in ms. */
        volatile long windowStart;
    }

    /**
     * Check and record one form submission against the budget for key.
     * <p>
     * Package-visible, and takes the clock as an argument, so a test can walk a
     * whole window and its rollover without sleeping.
     *
     * @param key the budget to charge
     * @param now current time in ms
     * @return true if the submission is allowed; a null or empty key is allowed
     *         unconditionally, because there is nothing to charge it against
     */
    static boolean checkRateLimit(String key, long now) {
        if (key == null || key.isEmpty()) return true;
        if (RATE_LIMIT_MAP.size() >= RATE_LIMIT_SWEEP_SIZE) {cleanupRateLimits(now);}
        RateLimitEntry entry = RATE_LIMIT_MAP.computeIfAbsent(key, k -> {
            RateLimitEntry e = new RateLimitEntry();
            e.windowStart = now;
            return e;
        });
        // Per-entry, not per-map: the window roll has to set the start and the
        // count together, or two concurrent POSTs can each open a new window and
        // the budget doubles.
        synchronized (entry) {
            if (now - entry.windowStart >= RATE_LIMIT_WINDOW_MS) {
                // Roll the window and charge this POST to the new one. Leaving
                // windowStart at the original time made every later call take
                // this branch, so once a minute had passed the limiter allowed
                // everything until the sweep discarded the entry - a duty cycle,
                // not a limit.
                entry.windowStart = now;
                entry.count.set(1);
                return true;
            }
            return entry.count.incrementAndGet() <= RATE_LIMIT_MAX_POSTS;
        }
    }

    /**
     * Remove budgets that have seen no POST for two full windows.
     * <p>
     * Driven from the map's own size rather than a thread started in a static
     * initializer: with one budget there is nothing to reclaim, and a thread
     * that outlives every request and never stops is not a price worth paying
     * for it.
     *
     * @param now current time in ms
     */
    static void cleanupRateLimits(long now) {
        long idle = RATE_LIMIT_WINDOW_MS * 2;
        RATE_LIMIT_MAP.entrySet().removeIf(e -> now - e.getValue().windowStart >= idle);
    }

    /**
     * Starts a valid handler with no errors or notices.
     */
    public FormHandler() {
        _errors = new ArrayList<>();
        _notices = new ArrayList<>();
        _valid = true;
    }

    /**
     * For nonce validation.
     * @param session the HTTP session
     * @since 0.9.69
     */
    public void storeSession(HttpSession session) { _session = session; }

    /**
     * Configure this bean to query a particular router context.
     *
     * @param contextId beginning few characters of the routerHash, or null to pick
     * the first one we come across.
     */
    public void setContextId(String contextId) {
        try {
            _context = ContextHelper.getContext(contextId);
            _log = _context.logManager().getLog(getClass());
        } catch (Throwable t) {_log.error("Error processing form", t);}
    }

    /**
     * Nonce value.
     * @param val the nonce value
     */
    public void setNonce(String val) {_nonce = val == null ? null : DataHelper.stripHTML(val);}
    /**
     * Action value.
     * @param val the action value
     */
    public void setAction(String val) {_action = val == null ? null : DataHelper.stripHTML(val);}

    /**
     * For many forms, it's easiest just to put all the parameters here.
     *
     * @param settings the form parameters
     * @since 0.9.4 consolidated from numerous FormHandlers
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void setSettings(Map settings) {_settings = new HashMap<>(settings);}

    /**
     * Only set by formhandler.jsi for multipart/form-data.
     *
     * @param rw the request wrapper
     * @since 0.9.19
     */
    public void setRequestWrapper(RequestWrapper rw) {_requestWrapper = rw;}

    /**
     * Same as HelperBase.
     * @return true if advanced mode
     * @since 0.9.14.1
     */
    public boolean isAdvanced() {return _context.getBooleanProperty(HelperBase.PROP_ADVANCED);}

    /**
     * {@link #setSettings} must have been called previously
     * Curses Jetty for returning arrays.
     * For nofilter_ prefixed params, validates for XSS patterns.
     *
     * @param key the setting key
     * @return trimmed string or null
     * @since 0.9.4 consolidated from numerous FormHandlers
     */
    protected String getJettyString(String key) {
        if (_settings == null) {return null;}
        String[] arr = (String[]) _settings.get(key);
        if (arr == null) {return null;}
        String val = arr[0].trim();
        // Validate nofilter_ and nf_ prefixed params for XSS
        if ((key.startsWith("nofilter_") || key.startsWith("nf_")) && containsXSS(val)) {
            return null;
        }
        return val;
    }

    /**
     * Resolve which operation the user actually requested.
     * <p>
     * Forms commonly carry both a hidden action=blah fallback (so Enter-key
     * submission triggers the default save) and real submit buttons named
     * action with their own values. When a button is clicked, the browser
     * submits BOTH values and the servlet layer exposes only the first one -
     * the hidden blah - to {@link #setAction}, which would silently run the default
     * save instead of the requested operation. This helper scans the raw
     * parameter array for any real action value before falling back.
     *
     * @param action the single action value seen by setAction(), may be null
     * @param settings raw parameter map as passed to setSettings(), may be null
     * @return the clicked operation if present, else the fallback, else null
     * @since 0.9.70+
     */
    public static String resolveEffectiveAction(String action, Map<?, ?> settings) {
        Object arr = settings != null ? settings.get("action") : null;
        if (arr instanceof String[]) {
            for (String val : (String[]) arr) {
                if (val != null && !val.trim().isEmpty() && !"blah".equals(val)) {
                    return val;
                }
            }
        }
        return action;
    }

    /**
     * Check for common XSS patterns.
     *
     * @param val the value to test; null or empty is not XSS
     * @return true if the value contains a script tag, inline event handler or
     *         another common XSS pattern
     * @since 0.9.70+
     */
    private boolean containsXSS(String val) {
        if (val == null || val.isEmpty()) return false;
        String lower = val.toLowerCase();
        return lower.contains("<script") || lower.contains("javascript:") ||
               lower.contains("onerror=") || lower.contains("onload=") ||
               lower.contains("onclick=") || lower.contains("<iframe") ||
               lower.contains("alert(") || lower.contains("eval(") ||
               lower.contains("expression(") || lower.contains("vbscript:");
    }

    /**
     * Call this to prevent changes using GET.
     *
     * @param val the request method
     * @since 0.8.2
     */
    public void storeMethod(String val) {_method = val;}

    /**
     * Store the output writer for inline form processing.
     *
     * @param out the writer inline form processing writes its output to
     * @since 0.9.38
     */
    public void storeWriter(Writer out) {_out = out;}

    /**
     * Implement this to perform the final processing (in turn, adding formNotice
     * and formError messages, etc)
     * <p>
     * Will only be called if _action is non-null and the nonce is valid.
     */
    protected abstract void processForm();

    /**
     * Add an error message to display
     * Use if it does not include a link.
     * Escapes '&lt;' and '&gt;' before queueing.
     *
     * @param errorMsg the error message
     */
    protected void addFormError(String errorMsg) {
        addFormError(errorMsg, false);
    }

    /**
     * Add an error message to display
     * Use if it does not include a link.
     * Escapes '&lt;' and '&gt;' before queueing.
     *
     * @param errorMsg the error message
     * @param canClose If true, the message can be closed by the user.
     */
    protected void addFormError(String errorMsg, boolean canClose) {
        if (errorMsg == null) {return;}
        _errors.add(new Message(DataHelper.escapeHTML(errorMsg), canClose));
    }

    /**
     * Add a non-error message to display
     * Use if it does not include a link.
     * Escapes '&lt;' and '&gt;' before queueing.
     *
     * @param msg the notice message
     */
    protected void addFormNotice(String msg) {
        addFormNotice(msg, false);
    }

    /**
     * Add a non-error message to display
     * Use if it does not include a link.
     * Escapes '&lt;' and '&gt;' before queueing.
     *
     * @param msg the notice message
     * @param canClose If true, the message can be closed by the user.
     */
    protected void addFormNotice(String msg, boolean canClose) {
        if (msg == null) {return;}
        _notices.add(new Message(DataHelper.escapeHTML(msg), canClose));
    }

    /**
     * Add a non-error message to display
     * Use if it includes a link or other formatting.
     * Does not escape '&lt;' and '&gt;' before queueing.
     *
     * @param msg the notice message
     * @since 0.9.14.1
     */
    protected void addFormNoticeNoEscape(String msg) {
        addFormNoticeNoEscape(msg, false);
    }

    /**
     * Add a non-error message to display
     * Use if it includes a link or other formatting.
     * Does not escape '&lt;' and '&gt;' before queueing.
     *
     * @param msg the notice message
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
     * Does not escape '&lt;' and '&gt;' before queueing.
     *
     * @param msg the error message
     * @since 0.9.19
     */
    protected void addFormErrorNoEscape(String msg) {
        addFormErrorNoEscape(msg, false);
    }

    /**
     * Add an error message to display
     * Use if it includes a link or other formatting.
     * Does not escape '&lt;' and '&gt;' before queueing.
     *
     * @param msg the error message
     * @param canClose If true, the message can be closed by the user.
     * @since 0.9.19
     */
    protected void addFormErrorNoEscape(String msg, boolean canClose) {
        if (msg == null) {return;}
        _errors.add(new Message(msg, canClose));
    }

    /**
     * Display everything, wrap it in a div for consistent presentation.
     *
     * @return the queued errors and notices wrapped in a div, or an empty
     * string if there are none
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
     * been yet).
     *
     * @return an unordered list of the queued error messages, or an empty
     * string if there are none
     */
    public String getErrors() {
        validate();
        process();
        return render(_errors);
    }

    /**
     * Display any non-error messages (processing the form if it hasn't
     * been yet).
     *
     * @return an unordered list of the queued notice messages, or an empty
     * string if there are none
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
        // Rate limiting for form submissions - prevent brute-force nonce guessing.
        // One shared budget for the whole console: FormHandler has no reference to
        // the request, so the client address is not reachable here. A shared budget
        // is the stricter of the two against a single attacker, at the cost of
        // letting one client spend it for the others.
        if (_method != null && "POST".equals(_method) &&
            !checkRateLimit(RATE_LIMIT_GLOBAL_KEY, System.currentTimeMillis())) {
            addFormError(_t("Too many form submissions, please try again later"), true);
            _valid = false;
            return;
        }
        // CSRF validation is done at the handler level in HostCheckHandler
        // Always validate nonce - don't skip based on console password
        if (_nonce == null) {
            _valid = false;
            if (_log != null && _log.shouldDebug()) {
                _log.debug("Form nonce rejected -> No nonce in the request");
            }
            return;
        }

        // Session-bound queue validation (primary method)
        if (_session != null) {
            boolean sessionValid = CSSHelper.validateNonce(_session, _nonce);
            if (!sessionValid && _log.shouldDebug()) {
                _log.debug("validate(): session queue FAILED session=" +
                           System.identityHashCode(_session) + " id=" + _session.getId());
            }
            if (sessionValid) {return;}
        }

        // Fallback to static nonce for backward compatibility (pre-0.9.69 nonces)
        String sharedNonce = CSSHelper.getNonce();
        if (sharedNonce.equals(_nonce)) {return;}

        // Also check recent nonces from CSSHelper rotation (last 10 min)
        String recent0 = CSSHelper.getRecentNonce(0);
        String recent1 = CSSHelper.getRecentNonce(1);
        if ((recent0 != null && recent0.equals(_nonce)) ||
            (recent1 != null && recent1.equals(_nonce))) {return;}

        if (_log != null && _log.shouldDebug()) {
            _log.debug("Form nonce rejected -> Not in the session queue or the shared set"
                       + "\n* Session present: " + (_session != null) + " * Method: " + _method);
        }
        addFormError(_t("Invalid form submission, probably because you used the 'back' or 'reload' button on your browser. Please resubmit.") + ' ' +
                     _t("If the problem persists, verify that you have cookies enabled in your browser."), true);
        _valid = false;
    }

    /** Processes the submitted form. */
    private void process() {
        if (!_processed) {
            if (_valid) {processForm();}
            _processed = true;
        }
    }

    /**
     * Renders the messages as html.
     *
     * @param source the messages to render
     * @return the html text
     */
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
     * Translate a string.
     *
     * @param s the string to translate
     * @return the translated string
     */
    public String _t(String s) {return Messages.getString(s, _context);}

    /**
     * Translate a string with a parameter
     * This is a lot more expensive than _t(s), so use sparingly.
     *
     * @param s string to be translated containing {0}
     * The {0} will be replaced by the parameter.
     * Single quotes must be doubled, i.e. ' -&gt; '' in the string.
     * @param o parameter, not translated.
     * To translate parameter also, use _t("foo {0} bar", _t("baz"))
     * Do not double the single quotes in the parameter.
     * Use autoboxing to call with ints, longs, floats, etc.
     * @return the translated string
     */
    public String _t(String s, Object o) {return Messages.getString(s, o, _context);}

    /**
     * Translate a string with two parameters.
     *
     * @param s the string to translate containing {0} and {1}
     * @param o the first parameter
     * @param o2 the second parameter
     * @return the translated string
     * @since 0.8.2
     */
    public String _t(String s, Object o, Object o2) {return Messages.getString(s, o, o2, _context);}

    /**
     * Mark a string for extraction by xgettext and translation.
     * Use this only in static initializers.
     * It does not translate!
     * @param s the string to mark
     * @return the string unchanged; marking it is what lets xgettext find it
     */
    public static String _x(String s) {return s;}

    /**
     * Message class to encapsulate the message text and the canClose flag.
     */
    public static class Message {
        /** The text of the message. */
        private String text;
        /** True if the message can be closed. */
        private boolean canClose;

        /**
         * Holds a message and whether the user may dismiss it.
         *
         * @param text the message text shown to the user
         * @param canClose whether the user is allowed to dismiss it
         */
        public Message(String text, boolean canClose) {
            this.text = text;
            this.canClose = canClose;
        }

        /**
         * Returns the message text.
         * @return the message text as it was queued, not HTML-escaped
         */
        public String getText() {
            return text;
        }

        /**
         * Whether the user may close this form message.
         * @return true if the message can be closed by the user
         */
        public boolean isCanClose() {
            return canClose;
        }
    }
}
