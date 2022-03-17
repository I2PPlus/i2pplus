package net.i2p.router.tunnel;

import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Minor extension to allow message history integration
 */
class RouterFragmentHandler extends FragmentHandler {

    public RouterFragmentHandler(RouterContext context, DefragmentedReceiver receiver) {
        super(context, receiver);
    }

    @Override
    protected void noteReception(long messageId, int fragmentId, Object status) {
        if (_log.shouldInfo())
//            _log.info("Received fragment " + fragmentId + " for [MsgID " + messageId + "]: " + status);
            _log.info("Received fragment [" + fragmentId + "] " + status + " [MsgID " + messageId + "]");
        _context.messageHistory().receiveTunnelFragment(messageId, fragmentId, status);
    }
    @Override
    protected void noteCompletion(long messageId) {
        if (_log.shouldInfo())
            _log.info("Received complete message [MsgID " + messageId + "]");
        _context.messageHistory().receiveTunnelFragmentComplete(messageId);
    }
    @Override
    protected void noteFailure(long messageId, String status) {
        if (_log.shouldInfo())
            _log.info("Dropped [MsgID " + messageId + "]: " + status);
        _context.messageHistory().droppedFragmentedMessage(messageId, status);
    }
}
