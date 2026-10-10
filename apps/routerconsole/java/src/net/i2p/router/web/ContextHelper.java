package net.i2p.router.web;

import java.util.List;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;

/**
 * Helper for accessing router contexts by context ID.
 * @since 0.9.35
 */
public class ContextHelper {

    /**
     * Constructor. The only lookup is the static getContext(), which reads the
     * live context list, so an instance carries no state.
     */
    public ContextHelper() {}

    /**
     * to take the first context in the list
     * first context in the list
     *
     * @param contextId base64 prefix of the target router hash, or null or blank
     * @return the context whose router hash starts with contextId, otherwise the
     * @throws IllegalStateException if no context available
     */
    public static RouterContext getContext(String contextId) {
        List<RouterContext> contexts = RouterContext.listContexts();
        if ( (contexts == null) || (contexts.isEmpty()) )
            throw new IllegalStateException("No contexts. This is usually because the router is either starting up or shutting down.");
        if ( (contextId == null) || (contextId.trim().length() <= 0) )
            return contexts.get(0);
        for (int i = 0; i < contexts.size(); i++) {
            RouterContext context = contexts.get(i);
            Hash hash = context.routerHash();
            if (hash == null) continue;
            if (hash.toBase64().startsWith(contextId))
                return context;
        }
        // not found, so just give them the first we can find
        return contexts.get(0);
    }
}
