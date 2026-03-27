package net.i2p.router.tunnel;

import net.i2p.router.RouterContext;

import java.util.List;

/**
 * Minor extension to track fragmentation
 *
 * @deprecated unused
 */
@Deprecated
class TrivialRouterPreprocessor extends TrivialPreprocessor {

    public TrivialRouterPreprocessor(RouterContext ctx) {
        super(ctx);
    }

    protected void notePreprocessing(long messageId, int numFragments, int totalLength, List<Long> messageIds) {
        _context.messageHistory().fragmentMessage(messageId, numFragments, totalLength, messageIds, null);
    }
}
