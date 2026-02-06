package net.i2p.router.tunnel.pool;

import static net.i2p.router.tunnel.pool.BuildExecutor.Result.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import net.i2p.crypto.EncType;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.EmptyProperties;
import net.i2p.data.Hash;
import net.i2p.data.PublicKey;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.ShortTunnelBuildMessage;
import net.i2p.data.i2np.TunnelBuildMessage;
import net.i2p.data.i2np.VariableTunnelBuildMessage;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.LeaseSetKeys;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.crypto.ratchet.MuxedPQSKM;
import net.i2p.router.crypto.ratchet.MuxedSKM;
import net.i2p.router.crypto.ratchet.RatchetSKM;
import net.i2p.router.networkdb.kademlia.MessageWrapper;
import net.i2p.router.networkdb.kademlia.MessageWrapper.OneTimeSession;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.tunnel.TunnelCreatorConfig;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

/**
 * Utility class for creating and dispatching tunnel build request messages.
 *
 * <p>This class handles:
 * <ul>
 *   <li>Preparation of tunnel hop IDs</li>
 *   <li>Selection of paired or exploratory tunnels for replies</li>
 *   <li>Construction of {@link TunnelBuildMessage}, including legacy and
 *       new-style (Short/Variable) formats</li>
 *   <li>Garlic encryption for inbound builds</li>
 *   <li>Timeout and failure handling</li>
 * </ul>
 *
 * <p>Paired tunnels are always used (as of 0.9.50+) to improve client
 * isolation and prevent correlation across tunnel pools.
 */
abstract class BuildRequestor {

    // Configuration constants
    private static final String MIN_NEWTBM_VERSION = "0.9.51";
    private static final boolean SEND_VARIABLE = true;
    private static final int SHORT_RECORDS = 4;
    /** 5 records (~2600 bytes) fit well within 3 tunnel messages */
    private static final int MEDIUM_RECORDS = 5;

    // Static immutable order lists for randomized record placement
    private static final List<Integer> ORDER;
    private static final List<Integer> SHORT_ORDER;
    private static final List<Integer> MEDIUM_ORDER;

    static {
        // Now SAFE to reference SHORT_RECORDS and MEDIUM_RECORDS
        List<Integer> order = new ArrayList<>(TunnelBuildMessage.MAX_RECORD_COUNT);
        for (int i = 0; i < TunnelBuildMessage.MAX_RECORD_COUNT; i++) {
            order.add(i);
        }
        ORDER = Collections.unmodifiableList(order);

        List<Integer> shortOrder = new ArrayList<>(SHORT_RECORDS);
        for (int i = 0; i < SHORT_RECORDS; i++) {
            shortOrder.add(i);
        }
        SHORT_ORDER = Collections.unmodifiableList(shortOrder);

        List<Integer> mediumOrder = new ArrayList<>(MEDIUM_RECORDS);
        for (int i = 0; i < MEDIUM_RECORDS; i++) {
            mediumOrder.add(i);
        }
        MEDIUM_ORDER = Collections.unmodifiableList(mediumOrder);
    }

    private static final int PRIORITY = OutNetMessage.PRIORITY_MY_BUILD_REQUEST;

    /**
     * Timeout for waiting for a full tunnel build reply.
     */
    static final int REQUEST_TIMEOUT = SystemVersion.isSlow() ? 6*1000 : 5*1000;

    /**
     * Shorter timeout for the first hop of an outbound build,
     * to trigger early failure detection.
     */
    private static final int FIRST_HOP_TIMEOUT = 10*1000;

    /**
     * Base expiration for the TunnelBuildMessage itself.
     * Randomized per-message to obscure tunnel length.
     */
    private static final int BUILD_MSG_TIMEOUT = 50*1000;
    private static final int MAX_CONSECUTIVE_CLIENT_BUILD_FAILS = 30;
    private static final int EXPLORATORY_BACKOFF = 200;
    private static final int CLIENT_BACKOFF = 50;

    // Proposal 168 bandwidth property keys
    static final String PROP_MIN_BW = "m";
    static final String PROP_REQ_BW = "r";
    static final String PROP_MAX_BW = "l";
    static final String PROP_AVAIL_BW = "b";

    /**
     * Paired tunnels are always used for client tunnels to prevent correlation
     * between different clients or tunnel pools.
     *
     * <p>Historically this was configurable via {@code router.usePairedTunnels},
     * but it is now permanently enabled for security reasons.
     *
     * @return {@code true} always
     */
    private static boolean usePairedTunnels() {
        return true;
    }

    /**
     * Assigns tunnel IDs to each hop in the configuration.
     * For zero-hop tunnels, dummy IDs are assigned to maintain consistency.
     *
     * @param ctx router context
     * @param cfg tunnel configuration to prepare
     */
    private static void prepare(RouterContext ctx, PooledTunnelCreatorConfig cfg) {
        int len = cfg.getLength();
        boolean isIB = cfg.isInbound();
        for (int i = 0; i < len; i++) {
            HopConfig hop = cfg.getConfig(i);
            if (!isIB && i == 0) {
                // Outbound gateway (us) doesn't receive on a tunnel ID
                if (len <= 1) {
                    TunnelId id = ctx.tunnelDispatcher().getNewOBGWID();
                    hop.setSendTunnelId(id);
                }
            } else {
                TunnelId id;
                if (isIB && len == 1) {
                    id = ctx.tunnelDispatcher().getNewIBZeroHopID();
                } else if (isIB && i == len - 1) {
                    id = ctx.tunnelDispatcher().getNewIBEPID();
                } else {
                    id = new TunnelId(1 + ctx.random().nextLong(TunnelId.MAX_ID_VALUE));
                }
                hop.setReceiveTunnelId(id);
            }
            if (i > 0) {
                cfg.getConfig(i - 1).setSendTunnelId(hop.getReceiveTunnelId());
            }
        }
    }

    /**
     * Initiates a tunnel build request.
     *
     * @param ctx   router context
     * @param cfg   tunnel configuration (must have ReplyMessageId set)
     * @param exec  executor to notify on completion
     * @return {@code true} if request was successfully dispatched
     */
    public static boolean request(RouterContext ctx, PooledTunnelCreatorConfig cfg, BuildExecutor exec) {
        prepare(ctx, cfg);

        if (cfg.getLength() <= 1) {
            buildZeroHop(ctx, cfg, exec);
            return true;
        }

        Log log = ctx.logManager().getLog(BuildRequestor.class);
        TunnelPool pool = cfg.getTunnelPool();
        TunnelPoolSettings settings = pool.getSettings();
        boolean isInbound = settings.isInbound();
        TunnelManagerFacade mgr = ctx.tunnelManager();

        TunnelInfo pairedTunnel = selectPairedTunnel(ctx, pool, cfg, exec, log);
        if (pairedTunnel == null) {
            // No tunnel available — not even exploratory. This is severe.
            long uptime = ctx.router().getUptime();
            if (uptime > 3*60*1000) {
                log.warn("Tunnel build failed -> No paired or Exploratory tunnel available for " + cfg);
            }
            int ms = settings.isExploratory() ? EXPLORATORY_BACKOFF : CLIENT_BACKOFF;
            try {Thread.sleep(ms);} catch (InterruptedException ie) {}
            exec.buildComplete(cfg, OTHER_FAILURE);
            return false;
        }

        I2NPMessage msg = createTunnelBuildMessage(ctx, pool, cfg, pairedTunnel, exec);
        if (msg == null) {
            log.warn("Tunnel build failed -> Could not create TunnelBuildMessage for " + cfg);
            exec.buildComplete(cfg, OTHER_FAILURE);
            return false;
        }

        // Store paired gateway ID for profiling
        if (pairedTunnel.getLength() > 1) {
            TunnelId gw = pairedTunnel.isInbound()
                ? pairedTunnel.getReceiveTunnelId(0)
                : pairedTunnel.getSendTunnelId(0);
            cfg.setPairedGW(gw);
        }

        if (isInbound) {
            handleInboundBuild(ctx, cfg, pairedTunnel, msg, log);
        } else {
            handleOutboundBuild(ctx, cfg, pairedTunnel, msg, exec, log);
        }
        return true;
    }

    /**
     * Selects an appropriate tunnel for sending the build reply.
     * Prefers paired client tunnels; falls back to exploratory if needed.
     * Client tunnels will never use exploratory tunnels with fewer hops than configured.
     * Exploratory pools may use 0-hop tunnels at startup for bootstrapping.
     */
    private static TunnelInfo selectPairedTunnel(RouterContext ctx, TunnelPool pool,
                                                 PooledTunnelCreatorConfig cfg,
                                                 BuildExecutor exec, Log log) {
        TunnelPoolSettings settings = pool.getSettings();
        boolean isInbound = settings.isInbound();
        Hash farEnd = cfg.getFarEnd();
        TunnelManagerFacade mgr = ctx.tunnelManager();
        long uptime = ctx.router().getUptime();
        int clientLength = settings.getLength();
        int clientLengthVariance = settings.getLengthVariance();
        boolean clientWantsMultiHop = clientLength > 0 || clientLengthVariance > 0;

        // Use exploratory tunnels for exploratory pools or if paired disabled (never)
        if (settings.isExploratory() || !usePairedTunnels()) {
            TunnelInfo expl = isInbound
                ? mgr.selectOutboundExploratoryTunnel(farEnd)
                : mgr.selectInboundExploratoryTunnel(farEnd);

            // Client tunnel using exploratory: ensure hop count meets client requirements
            if (expl != null && !settings.isExploratory() && clientWantsMultiHop && expl.getLength() <= 1) {
                if (log.shouldWarn()) {
                    log.warn("Rejecting " + (expl.getLength() <= 0 ? "zero" : "one") + 
                             "-hop exploratory tunnel for client (wants " + clientLength + "+" + clientLengthVariance + " hops)");
                }
                return null;
            }

            // If no exploratory tunnels exist yet, allow zero-hop as fallback for exploratory pools
            if (expl == null && settings.isExploratory()) {
                if (log.shouldInfo()) {
                    log.info("No existing Exploratory tunnels for " + cfg + " -> Allowing zero-hop build...");
                }
                return null;
            }
            // Client pools should never build 0-hop tunnels
            if (expl == null && !settings.isExploratory()) {
                if (log.shouldWarn()) {
                    log.warn("No exploratory tunnel available for client pool " + cfg + " -> Build will fail");
                }
                return null;
            }
            return expl;
        }

        // Client tunnel: try paired first
        int fails = pool.getConsecutiveBuildTimeouts();
        Hash from = settings.getDestination();

        if (fails < MAX_CONSECUTIVE_CLIENT_BUILD_FAILS) {
            TunnelInfo paired = isInbound
                ? mgr.selectOutboundTunnel(from, farEnd)
                : mgr.selectInboundTunnel(from, farEnd);

            if (paired != null) {
                SessionKeyManager skm = ctx.clientManager().getClientSessionKeyManager(from);
                if (skm != null || cfg.getGarlicReplyKeys() == null) {
                    return paired;
                }
                // Client SKM missing but garlic reply expected → fall through to expl
                if (log.shouldInfo()) {
                    log.info("Client SKM unavailable for garlic reply -> Falling back to Exploratory for " + cfg);
                }
            }
        } else {
            if (log.shouldWarn() && uptime > 5*60*1000 && fails > 2) {
                log.warn(fails + " consecutive build timeouts for " + cfg + " → Forcing Exploratory tunnel...");
            }
        }

        // Fallback to exploratory, but only if hop count meets client requirements
        TunnelInfo expl = isInbound
            ? selectFallbackOutboundTunnel(ctx, mgr, log)
            : selectFallbackInboundTunnel(ctx, mgr, log);

        // Client tunnel check: ensure exploratory meets hop requirements
        if (expl != null && clientWantsMultiHop && expl.getLength() <= 1) {
            if (log.shouldWarn()) {
                log.warn("Rejecting " + (expl.getLength() <= 0 ? "zero" : "one") + 
                         "-hop exploratory fallback for client (wants " + clientLength + "+" + clientLengthVariance + " hops)");
            }
            return null;
        }

        if (expl != null && log.shouldInfo()) {
            log.info("Using Exploratory tunnel as fallback for: " + cfg);
        }
        return expl;
    }

    private static TunnelInfo selectFallbackOutboundTunnel(RouterContext ctx, TunnelManagerFacade mgr, Log log) {
        TunnelInfo tunnel = mgr.selectOutboundTunnel();
        if (tunnel == null) {
            return null;
        }
        // For exploratory pools, allow 0/1-hop tunnels at startup
        // For client pools using exploratory as fallback, reject short tunnels
        TunnelPoolSettings explSettings = mgr.getOutboundSettings();
        if (explSettings.isExploratory()) {
            return tunnel;
        }
        // Client pool fallback: reject 0/1-hop exploratory tunnels
        if (tunnel.getLength() <= 1) {
            if (log.shouldWarn()) {
                log.warn("Rejecting " + (tunnel.getLength() <= 0 ? "zero" : "one") + 
                         "-hop exploratory tunnel for client tunnel build reply");
            }
            return null;
        }
        return tunnel;
    }

    private static TunnelInfo selectFallbackInboundTunnel(RouterContext ctx, TunnelManagerFacade mgr, Log log) {
        TunnelInfo tunnel = mgr.selectInboundTunnel();
        if (tunnel == null) {
            return null;
        }
        // For exploratory pools, allow 0/1-hop tunnels at startup
        // For client pools using exploratory as fallback, reject short tunnels
        TunnelPoolSettings explSettings = mgr.getInboundSettings();
        if (explSettings.isExploratory()) {
            return tunnel;
        }
        // Client pool fallback: reject 0/1-hop exploratory tunnels
        if (tunnel.getLength() <= 1) {
            if (log.shouldWarn()) {
                log.warn("Rejecting " + (tunnel.getLength() <= 0 ? "zero" : "one") + 
                         "-hop exploratory tunnel for client tunnel build reply");
            }
            return null;
        }
        return tunnel;
    }

    private static void handleInboundBuild(RouterContext ctx, PooledTunnelCreatorConfig cfg,
                                           TunnelInfo pairedTunnel, I2NPMessage msg, Log log) {
        Hash ibgw = cfg.getPeer(0);
        // Wrap in garlic if IBGW != OBEP (to hide IBGW from OBEP)
        if (msg.getType() == ShortTunnelBuildMessage.MESSAGE_TYPE && !ibgw.equals(pairedTunnel.getEndpoint())) {
            RouterInfo peer = ctx.netDb().lookupRouterInfoLocally(ibgw);
            if (peer != null) {
                I2NPMessage enc = MessageWrapper.wrap(ctx, msg, peer);
                if (enc != null) {
                    msg = enc;
                } else if (log.shouldWarn()) {
                    log.warn("Failed to garlic-wrap Inbound TunnelBuildMessage to " + ibgw);
                }
            } else if (log.shouldWarn()) {
                log.warn("No RouterInfo for " + ibgw + " -> Cannot wrap Inbound TunnelBuildMessage");
            }
        }

        if (log.shouldInfo()) {
            log.info("Sending Inbound TunnelBuildRequest [MsgID " + msg.getUniqueId() + "] via " + pairedTunnel +
                     " to [" + ibgw.toBase64().substring(0, 6) + "] for " + cfg +
                     " -> Awaiting reply [MsgID " + cfg.getReplyMessageId() + "]...");
        }
        ctx.tunnelDispatcher().dispatchOutbound(msg, pairedTunnel.getSendTunnelId(0), ibgw);
    }

    private static void handleOutboundBuild(RouterContext ctx, PooledTunnelCreatorConfig cfg,
                                            TunnelInfo pairedTunnel, I2NPMessage msg,
                                            BuildExecutor exec, Log log) {
        Hash nextHop = cfg.getPeer(1);
        if (log.shouldInfo()) {
            log.info("Sending outbound TunnelBuildRequest direct to [" + nextHop.toBase64().substring(0, 6) + "] for " + cfg +
                     " -> Reply via " + pairedTunnel + " [MsgID " + msg.getUniqueId() + "]");
        }

        // Add fuzz to expiration to obscure tunnel structure
        long baseExp = ctx.clock().now() + BUILD_MSG_TIMEOUT;
        long fuzz = ctx.random().nextLong(15_000) + ctx.random().nextInt(5_000);
        msg.setMessageExpiration(baseExp + fuzz);

        RouterInfo peer = ctx.netDb().lookupRouterInfoLocally(nextHop);
        if (peer == null) {
            log.warn("Next hop RouterInfo not found for outbound build: " + cfg);
            exec.buildComplete(cfg, OTHER_FAILURE);
            return;
        }

        OutNetMessage outMsg = new OutNetMessage(ctx, msg, ctx.clock().now() + FIRST_HOP_TIMEOUT, PRIORITY, peer);
        outMsg.setOnFailedSendJob(new TunnelBuildFirstHopFailJob(ctx, cfg, exec));

        // Register one-time reply tags
        OneTimeSession ots = cfg.getGarlicReplyKeys();
        if (ots != null) {
            SessionKeyManager replySKM = ctx.clientManager().getClientSessionKeyManager(cfg.getTunnelPool().getSettings().getDestination());
            if (replySKM == null) {
                replySKM = ctx.sessionKeyManager(); // fallback for exploratory
            }
            if (replySKM == null) {
                log.warn("No SessionKeyManager available for garlic reply to: " + cfg);
                exec.buildComplete(cfg, OTHER_FAILURE);
                return;
            }
            if (replySKM instanceof RatchetSKM) {
                ((RatchetSKM) replySKM).tagsReceived(ots.key, ots.rtag, 2 * BUILD_MSG_TIMEOUT);
            } else if (replySKM instanceof MuxedSKM) {
                ((MuxedSKM) replySKM).tagsReceived(ots.key, ots.rtag, 2 * BUILD_MSG_TIMEOUT);
            } else if (replySKM instanceof MuxedPQSKM) {
                ((MuxedPQSKM) replySKM).tagsReceived(ots.key, ots.rtag, 2 * BUILD_MSG_TIMEOUT);
            } else {
                log.warn("Unsupported SessionKeyManager for garlic reply: " + replySKM.getClass());
            }
            cfg.setGarlicReplyKeys(null);
        }

        try {
            ctx.outNetMessagePool().add(outMsg);
        } catch (RuntimeException e) {
            log.error("Failed to send TunnelBuildMessage", e);
            exec.buildComplete(cfg, OTHER_FAILURE);
        }
    }

    /**
     * Checks if a router supports ShortTunnelBuildMessage (ECIES + version >= 0.9.51).
     */
    private static boolean supportsShortTBM(RouterContext ctx, Hash routerHash) {
        final RouterInfo ri = ctx.netDb().lookupRouterInfoLocally(routerHash);
        if (ri == null) return false;
        if (ri.getIdentity().getPublicKey().getType() != EncType.ECIES_X25519) return false;
        String v = ri.getVersion();
        return VersionComparator.comp(v, MIN_NEWTBM_VERSION) >= 0;
    }

    /**
     * Creates a tunnel build message using the most efficient format supported
     * by all hops and the local router.
     *
     * @return the message, or {@code null} on error
     */
    private static TunnelBuildMessage createTunnelBuildMessage(RouterContext ctx, TunnelPool pool,
                                                               PooledTunnelCreatorConfig cfg,
                                                               TunnelInfo pairedTunnel, BuildExecutor exec) {
        Log log = ctx.logManager().getLog(BuildRequestor.class);
        boolean isInbound = cfg.isInbound();
        Hash replyRouter = isInbound ? ctx.routerHash() : pairedTunnel.getPeer(0);
        long replyTunnel = isInbound ? 0 : pairedTunnel.getReceiveTunnelId(0).getTunnelId();

        boolean useVariable = SEND_VARIABLE && cfg.getLength() <= MEDIUM_RECORDS;
        boolean useShortTBM = ctx.keyManager().getPublicKey().getType() == EncType.ECIES_X25519;

        // For client outbound tunnels, ensure destination supports ECIES
        if (useShortTBM && !isInbound && !pool.getSettings().isExploratory()) {
            LeaseSetKeys lsk = ctx.keyManager().getKeys(pool.getSettings().getDestination());
            if (lsk == null || !lsk.isSupported(EncType.ECIES_X25519)) {
                useShortTBM = false;
            }
        }

        // Validate all hops support ShortTBM if we plan to use it
        if (useShortTBM) {
            int start = isInbound ? 0 : 1;
            int end = cfg.getLength();
            for (int i = start; i < end; i++) {
                if (!supportsShortTBM(ctx, cfg.getPeer(i))) {
                    useShortTBM = false;
                    break;
                }
            }
        }

        // Create message of appropriate type
        TunnelBuildMessage msg;
        List<Integer> order;
        int recordCount;

        if (useShortTBM) {
            if (cfg.getLength() <= SHORT_RECORDS) {
                recordCount = SHORT_RECORDS;
                order = new ArrayList<>(SHORT_ORDER);
            } else if (cfg.getLength() <= MEDIUM_RECORDS) {
                recordCount = MEDIUM_RECORDS;
                order = new ArrayList<>(MEDIUM_ORDER);
            } else {
                recordCount = TunnelBuildMessage.MAX_RECORD_COUNT;
                order = new ArrayList<>(ORDER);
            }
            msg = new ShortTunnelBuildMessage(ctx, recordCount);
        } else if (useVariable) {
            recordCount = cfg.getLength() <= SHORT_RECORDS ? SHORT_RECORDS : MEDIUM_RECORDS;
            msg = new VariableTunnelBuildMessage(ctx, recordCount);
            order = new ArrayList<>(recordCount <= SHORT_RECORDS ? SHORT_ORDER : MEDIUM_ORDER);
        } else {
            msg = new TunnelBuildMessage(ctx);
            order = new ArrayList<>(ORDER);
        }

        // Initialize hop keys if not using ShortTBM (keys are derived in ShortTBM)
        if (!useShortTBM) {
            for (int i = 0; i < cfg.getLength(); i++) {
                HopConfig hop = cfg.getConfig(i);
                hop.setIVKey(ctx.keyGenerator().generateSessionKey());
                hop.setLayerKey(ctx.keyGenerator().generateSessionKey());
                byte[] iv = new byte[TunnelCreatorConfig.REPLY_IV_LENGTH];
                ctx.random().nextBytes(iv);
                cfg.setAESReplyKeys(i, ctx.keyGenerator().generateSessionKey(), iv);
            }
        }

        Collections.shuffle(order, ctx.random());
        cfg.setReplyOrder(order);

        if (log.shouldDebug()) {
            log.debug("Build record order: " + order + " for " + cfg);
        }

        // Prepare bandwidth properties (only for ShortTBM non-exploratory)
        Properties baseProps;
        int bw = 0, variance = 0;
        if (useShortTBM && !pool.getSettings().isExploratory()) {
            bw = pool.getAvgBWPerTunnel();
            if (bw > 7000) {
                baseProps = new Properties();
                variance = 4 * bw / 10;
            } else {
                baseProps = EmptyProperties.INSTANCE;
            }
        } else {
            baseProps = EmptyProperties.INSTANCE;
        }

        // Populate records
        for (int i = 0; i < msg.getRecordCount(); i++) {
            int hopIndex = order.get(i);
            PublicKey key = null;

            if (!BuildMessageGenerator.isBlank(cfg, hopIndex)) {
                Hash peer = cfg.getPeer(hopIndex);
                RouterInfo peerInfo = ctx.netDb().lookupRouterInfoLocally(peer);
                if (peerInfo == null) {
                    log.warn("Peer not found locally for hop " + hopIndex + ": " + peer + " in " + cfg);
                    return null;
                }
                key = peerInfo.getIdentity().getPublicKey();
            }

            Properties props = key != null ? baseProps : EmptyProperties.INSTANCE;
            if (key != null && variance > 0) {
                // Clone to avoid mutating shared instance
                props = new Properties(baseProps);
                int min = Math.max(0, bw - ctx.random().nextInt(variance));
                int req = bw + variance + ctx.random().nextInt(variance);
                props.setProperty(PROP_MIN_BW, Integer.toString(min / 1000));
                props.setProperty(PROP_REQ_BW, Integer.toString(req / 1000));
                if (log.shouldDebug()) {
                    log.debug("BW props for hop " + hopIndex + ": min=" + min + ", req=" + req);
                }
            }

            BuildMessageGenerator.createRecord(i, hopIndex, msg, cfg, replyRouter, replyTunnel, ctx, key, props);
        }

        BuildMessageGenerator.layeredEncrypt(ctx, msg, cfg, order);
        return msg;
    }

    private static void buildZeroHop(RouterContext ctx, PooledTunnelCreatorConfig cfg, BuildExecutor exec) {
        Log log = ctx.logManager().getLog(BuildRequestor.class);
        if (log.shouldDebug()) {
            log.debug("Building zero-hop tunnel: " + cfg);
        }
        boolean ok = cfg.isInbound()
            ? ctx.tunnelDispatcher().joinInbound(cfg)
            : ctx.tunnelDispatcher().joinOutbound(cfg);
        exec.buildComplete(cfg, ok ? SUCCESS : DUP_ID);
    }

    /**
     * Job executed when the first hop of an outbound tunnel build fails to receive the request.
     * Notifies the executor and updates peer profile.
     */
    private static class TunnelBuildFirstHopFailJob extends JobImpl {
        private final PooledTunnelCreatorConfig _cfg;
        private final BuildExecutor _exec;

        TunnelBuildFirstHopFailJob(RouterContext ctx, PooledTunnelCreatorConfig cfg, BuildExecutor exec) {
            super(ctx);
            _cfg = cfg;
            _exec = exec;
        }

        @Override
        public String getName() {
            return "Timeout OB Tunnel Build First Hop";
        }

        @Override
        public void runJob() {
            _exec.buildComplete(_cfg, OTHER_FAILURE);
            getContext().profileManager().tunnelFailed(_cfg.getPeer(1), 500);
            getContext().profileManager().tunnelTimedOut(_cfg.getPeer(1));
            getContext().statManager().addRateData("tunnel.buildFailFirstHop", 1);
        }
    }
}