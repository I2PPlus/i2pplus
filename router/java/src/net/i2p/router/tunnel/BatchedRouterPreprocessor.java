package net.i2p.router.tunnel;

import java.util.List;
import java.util.Properties;
import net.i2p.util.SystemVersion;

import net.i2p.router.RouterContext;

/**
 * Honor the 'batchFrequency' tunnel pool setting or the 'router.batchFrequency'
 * router config setting, and track fragmentation.
 *
 */
class BatchedRouterPreprocessor extends BatchedPreprocessor {
    private final TunnelCreatorConfig _config;
    protected final HopConfig _hopConfig;
    private final long _sendDelay;

    /**
     * How frequently should we flush non-full messages, in milliseconds
     * This goes in I2CP custom options for the pool.
     * Only applies to OBGWs.
     */
    public static final String PROP_BATCH_FREQUENCY = "batchFrequency";
    /** This goes in router advanced config */
    public static final String PROP_ROUTER_BATCH_FREQUENCY = "router.batchFrequency";
    /** for client OBGWs only (our data) */
//    public static final int OB_CLIENT_BATCH_FREQ = 37;
    public static final int OB_CLIENT_BATCH_FREQ = SystemVersion.isSlow() ? 37 : 30;
    /** for exploratory OBGWs only (our tunnel tests and build messages) */
//    public static final int OB_EXPL_BATCH_FREQ = 100;
    public static final int OB_EXPL_BATCH_FREQ = SystemVersion.isSlow() ? 100 : 80;
    /** for IBGWs for efficiency (not our data) */
//    public static final int DEFAULT_BATCH_FREQUENCY = 75;
    public static final int DEFAULT_BATCH_FREQUENCY = SystemVersion.isSlow() ? 75 : 60;

    /** for OBGWs */
    public BatchedRouterPreprocessor(RouterContext ctx, TunnelCreatorConfig cfg) {
        super(ctx, getName(cfg));
        _config = cfg;
        _hopConfig = null;
        _sendDelay = initialSendDelay();
    }

    /** for IBGWs */
    public BatchedRouterPreprocessor(RouterContext ctx, HopConfig cfg) {
        super(ctx, getName(cfg));
        _config = null;
        _hopConfig = cfg;
        _sendDelay = initialSendDelay();
    }

    private static String getName(HopConfig cfg) {
        if (cfg == null) return "[Inbound ??]";
        long id = cfg.getReceiveTunnelId();
        if (id != 0)
            return "[Inbound " + id + "]";
        id = cfg.getSendTunnelId();
        if (id != 0)
            return "[Inbound " + id + "]";
        else
            return "[Inbound ??]";
    }

    private static String getName(TunnelCreatorConfig cfg) {
        if (cfg == null) return "[Outbound ??]";
        long id = cfg.getConfig(0).getReceiveTunnelId();
        if (id != 0)
            return "[Outbound " + id + "]";
        id = cfg.getConfig(0).getSendTunnelId();
        if (id != 0)
            return "[Outbound " + id + "]";
        else
            return "[Outbound ??]";
    }

    /**
     *  how long should we wait before flushing
     */
    @Override
    protected long getSendDelay() { return _sendDelay; }

    /*
     *  Extend the batching time for exploratory OBGWs, they have a lot of small
     *  tunnel test messages, and build messages that don't fit perfectly.
     *  And these are not as delay-sensitive.
     *
     *  We won't pick up config changes after the preprocessor is created,
     *  but a preprocessor lifetime is only 10 minutes, so just wait...
     */
    private long initialSendDelay() {
        if (_config != null) {
            Properties opts = _config.getOptions();
            if (opts != null) {
                String freq = opts.getProperty(PROP_BATCH_FREQUENCY);
                if (freq != null) {
                    try {
                        return Integer.parseInt(freq);
                    } catch (NumberFormatException nfe) {}
                }
            }
        }

        int def;
        if (_config != null) {
            if (_config.getDestination() != null)
                def = OB_CLIENT_BATCH_FREQ;
            else
                def = OB_EXPL_BATCH_FREQ;
        } else {
            def = DEFAULT_BATCH_FREQUENCY;
        }
        return _context.getProperty(PROP_ROUTER_BATCH_FREQUENCY, def);
    }

    @Override
    protected void notePreprocessing(long messageId, int numFragments, int totalLength, List<Long> messageIds, String msg) {
        if (_config != null)
            _context.messageHistory().fragmentMessage(messageId, numFragments, totalLength, messageIds, _config, msg);
        else
            _context.messageHistory().fragmentMessage(messageId, numFragments, totalLength, messageIds, _hopConfig, msg);
    }
}
