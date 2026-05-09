package net.i2p.router.web.helpers;

import javax.servlet.http.HttpSession;

import net.i2p.router.RouterContext;
import net.i2p.router.web.CSSHelper;
import net.i2p.router.web.ContextHelper;
import net.i2p.router.web.HelperBase;

/**
 * Handler to deal with reseed requests.
 */
public class ReseedHandler extends HelperBase {
    private HttpSession _session;

    public ReseedHandler() {
        this(ContextHelper.getContext(null));
    }
    public ReseedHandler(RouterContext ctx) {
        _context = ctx;
    }

    /**
     *  For form validation
     *  @since 0.9.69
     */
    public void storeSession(HttpSession session) { _session = session; }

    /**
     *  storeSession MUST be called first
     *  @since 0.9.69
     */
    public void setReseedNonce(String nonce) {
        if (nonce == null) return;

        // Try session-based validation first
        if (_session != null && CSSHelper.validateNonce(_session, nonce)) {
            requestReseed();
            return;
        }

        // Fallback: check System properties for backward compatibility
        if (nonce.equals(System.getProperty("net.i2p.router.web.ReseedHandler.nonce")) ||
            nonce.equals(System.getProperty("net.i2p.router.web.ReseedHandler.noncePrev"))) {
            requestReseed();
        }
    }

    private void requestReseed() {
        _context.netDb().reseedChecker().requestReseed();
    }
}
