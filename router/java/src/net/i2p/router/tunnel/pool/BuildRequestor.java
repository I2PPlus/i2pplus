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
import net.i2p.data.i2np.TunnelBuildMessageBase;
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
public abstract class BuildRequestor {

    private BuildRequestor() {}

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
        List<Integer> order = new ArrayList<>(TunnelBuildMessageBase.MAX_RECORD_COUNT);
        for (int i = 0; i < TunnelBuildMessageBase.MAX_RECORD_COUNT; i++) {
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
     * How long we wait for a reply before trying a different pool/peers.
     * Adaptive mechanism in BuildExecutor adjusts this upward when
     * success rates are low or timeout rates are high.
     * Default: 13s (normal), 18s (slow systems).
     */
    public static int getRequestTimeout(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.build.requestTimeout", 15*1000);
    }

    /**
     * How long the OutNetMessage for the first hop gets before
     * TunnelBuildFirstHopFailJob fires. Shorter than request timeout
     * so first-hop failure is detected before the full build timeout.
     */
    public static int getFirstHopTimeout(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.build.firstHopTimeout", 10*1000);
    }

    /**
     * Base expiration for the TunnelBuildMessage itself.
     * Randomized per-message by +/- 20s jitter to obscure tunnel length.
     */
    private static final int BUILD_MSG_TIMEOUT = 60*1000;
    private static int getExploratoryBackoff(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.build.exploratoryBackoff", 200);
    }

    /**
     *  Maximum consecutive client build timeouts before forcing exploratory tunnel for replies.
     *  Tunable via i2p.tunnel.buildRequest.maxConsecutiveFails (default: 3)
     */
    static int getMaxConsecutiveClientBuildFails(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.buildRequest.maxConsecutiveFails", 3);
    }
    private static int getClientBackoff(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.build.clientBackoff", 50);
    }

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
     * @param firstHopTimeout  adaptive first-hop timeout ms (from BuildExecutor stats)
     * @return {@code true} if request was successfully dispatched
     */
    public static boolean request(RouterContext ctx, PooledTunnelCreatorConfig cfg,
                                  BuildExecutor exec, long firstHopTimeout) {
        prepare(ctx, cfg);

        if (cfg.getLength() <= 1) {
            buildZeroHop(ctx, cfg, exec);
            return true;
        }

        Log log = ctx.logManager().getLog(BuildRequestor.class);
        TunnelPool pool = cfg.getTunnelPool();
        TunnelPoolSettings settings = pool.getSettings();
        boolean isInbound = settings.isInbound();

        TunnelInfo pairedTunnel = selectPairedTunnel(ctx, pool, cfg, exec, log);
        if (pairedTunnel == null) {
            log.warn("Tunnel build failed -> No paired or exploratory tunnel available for " + cfg);
            int ms = settings.isExploratory() ? getExploratoryBackoff(ctx) : getClientBackoff(ctx);
            try {Thread.sleep(ms);} catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            exec.buildComplete(cfg, OTHER_FAILURE, "No paired tunnel");
            return false;
        }

        I2NPMessage msg = createTunnelBuildMessage(ctx, pool, cfg, pairedTunnel, exec);
        if (msg == null) {
            log.warn("Tunnel build failed -> Could not create TunnelBuildMessage for " + cfg);
            exec.buildComplete(cfg, OTHER_FAILURE, "No build message");
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
            handleOutboundBuild(ctx, cfg, pairedTunnel, msg, exec, log, firstHopTimeout);
        }
        return true;
    }

    /**
     * Selects an appropriate tunnel for sending the build reply.
     * Exploratory pools use exploratory tunnels; client pools try their
     * own tunnels first, then any client tunnel, then exploratory as a
     * last-resort bootstrap when zero client tunnels exist system-wide.
     */
    private static TunnelInfo selectPairedTunnel(RouterContext ctx, TunnelPool pool,
                                                  PooledTunnelCreatorConfig cfg,
                                                  BuildExecutor exec, Log log) {
        TunnelPoolSettings settings = pool.getSettings();
        boolean isInbound = settings.isInbound();
        Hash farEnd = cfg.getFarEnd();
        TunnelManagerFacade mgr = ctx.tunnelManager();

        if (settings.isExploratory() || !usePairedTunnels()) {
            // Exploratory pools: use exploratory tunnels for replies.
            // Client pools with usePairedTunnels=false (never): ditto.
            TunnelInfo expl = isInbound
                ? mgr.selectOutboundExploratoryTunnel(farEnd)
                : mgr.selectInboundExploratoryTunnel(farEnd);

            if (expl == null && log.shouldInfo()) {
                log.info("No existing exploratory tunnels for " + cfg + " -> allowing zero-hop build");
            }
            return expl; // null => zero-hop fallback
        }

        // Client tunnel: try matching pool first, then any pool, then
        // exploratory as last resort (cold-start bootstrap only).
        // Using exploratory for replies congests the exploratory path
        // and causes OB builds to timeout at 2x the rate of IB builds
        // (54% vs 80%), so it's strictly a last resort.
        Hash from = settings.getDestination();
        int fails = pool.getConsecutiveBuildTimeouts();
        TunnelInfo paired;

        // Step 1: try this pool's own tunnels (preferred).
        // Skip when the pool itself has too many consecutive failures
        // (its tunnels are likely stale/dropping replies).
        if (fails < getMaxConsecutiveClientBuildFails(ctx)) {
            paired = isInbound
                ? mgr.selectOutboundTunnel(from, farEnd)
                : mgr.selectInboundTunnel(from, farEnd);

            if (paired != null) {
                SessionKeyManager skm = ctx.clientManager().getClientSessionKeyManager(from);
                if (skm != null || cfg.getGarlicReplyKeys() == null) {
                    return paired;
                }
                if (log.shouldInfo()) {
                    log.info("Client SKM unavailable for garlic reply, cannot build: " + cfg);
                }
                return null;
            }
        }

        // Step 2: try any client tunnel (cross-pool — the reply just
        // needs to reach the gateway, any tunnel of the right type works).
        paired = isInbound
            ? mgr.selectAnyOutboundTunnel()
            : mgr.selectAnyInboundTunnel();
        if (paired != null) {
            if (log.shouldInfo()) {
                log.info("Cross-pool reply tunnel for " + cfg + ": " + paired);
            }
            return paired;
        }

        // Step 3: fall back to exploratory tunnels for cold-start bootstrap.
        // Only when zero client tunnels exist system-wide.
        TunnelInfo expl = isInbound
            ? mgr.selectOutboundExploratoryTunnel(farEnd)
            : mgr.selectInboundExploratoryTunnel(farEnd);
        if (expl != null) {
            if (log.shouldInfo()) {
                log.info("Exploratory reply tunnel for " + cfg + ": " + expl);
            }
            return expl;
        }

        return null;
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
            log.info("Sending Inbound TunnelBuildRequest [MsgID " + msg.getUniqueId() + "] for " + cfg +
                     "\n* Via: " + pairedTunnel + " to [" + ibgw.toBase64().substring(0,6) +
                     "] -> Awaiting reply [MsgID " + cfg.getReplyMessageId() + "]...");
        }
        ctx.tunnelDispatcher().dispatchOutbound(msg, pairedTunnel.getSendTunnelId(0), ibgw);
    }

    private static void handleOutboundBuild(RouterContext ctx, PooledTunnelCreatorConfig cfg,
                                             TunnelInfo pairedTunnel, I2NPMessage msg,
                                             BuildExecutor exec, Log log, long firstHopTimeout) {
        Hash nextHop = cfg.getPeer(1);

        // Add fuzz to expiration to obscure tunnel structure
        msg.setMessageExpiration(ctx.clock().now() + BUILD_MSG_TIMEOUT + ctx.random().nextLong(20*1000L));

        RouterInfo peer = ctx.netDb().lookupRouterInfoLocally(nextHop);
        if (peer == null) {
            log.warn("Next hop RouterInfo not found for outbound build: " + cfg);
            exec.buildComplete(cfg, OTHER_FAILURE, "Next hop not in netdb");
            return;
        }

        // Register one-time reply tags (needed for both direct and tunnel routing)
        OneTimeSession ots = cfg.getGarlicReplyKeys();
        if (ots != null) {
            SessionKeyManager replySKM = ctx.clientManager().getClientSessionKeyManager(cfg.getTunnelPool().getSettings().getDestination());
            if (replySKM == null) {
                replySKM = ctx.sessionKeyManager();
            }
            if (replySKM == null) {
                log.warn("No SessionKeyManager available for garlic reply to: " + cfg);
                exec.buildComplete(cfg, OTHER_FAILURE, "No session key manager");
                return;
            }
            if (replySKM instanceof RatchetSKM) {
                ((RatchetSKM) replySKM).tagsReceived(ots.key, ots.rtag, 2L * BUILD_MSG_TIMEOUT);
            } else if (replySKM instanceof MuxedSKM) {
                ((MuxedSKM) replySKM).tagsReceived(ots.key, ots.rtag, 2L * BUILD_MSG_TIMEOUT);
            } else if (replySKM instanceof MuxedPQSKM) {
                ((MuxedPQSKM) replySKM).tagsReceived(ots.key, ots.rtag, 2L * BUILD_MSG_TIMEOUT);
            }
            cfg.setGarlicReplyKeys(null);
        }

        // When the first hop isn't directly connected, try routing the TBM through
        // an exploratory outbound tunnel to avoid transport establishment delays.
        // The tunnel endpoint unwraps the garlic and forwards the raw TBM to nextHop.
        // (Not used for exploratory outbound builds — that would be circular.)
        boolean connected = ctx.commSystem().isEstablished(nextHop);
        if (!connected && !cfg.getTunnelPool().getSettings().isExploratory()) {
            TunnelInfo outTunnel = ctx.tunnelManager().selectOutboundExploratoryTunnel(nextHop);
            if (outTunnel != null) {
                // Garlic-wrap to hide TBM content from tunnel endpoint
                if (msg.getType() == ShortTunnelBuildMessage.MESSAGE_TYPE && !nextHop.equals(outTunnel.getEndpoint())) {
                    I2NPMessage enc = MessageWrapper.wrap(ctx, msg, peer);
                    if (enc != null) {
                        msg = enc;
                    }
                }
                if (log.shouldInfo()) {
                    log.info("Sending outbound TunnelBuildRequest via exploratory tunnel to [" +
                             nextHop.toBase64().substring(0,6) + "] for " + cfg +
                             "\n* Via: " + outTunnel + " Reply via: " + pairedTunnel);
                }
                ctx.tunnelDispatcher().dispatchOutbound(msg, outTunnel.getSendTunnelId(0), nextHop);
                return;
            }
        }

        // Fall back to direct transport send
        long effectiveTimeout = firstHopTimeout;
        if (!connected) {
            // preConnectTo() was called in configureNewTunnel(), which starts
            // transport establishment (~8.5s SSU2 handshake).  Use the full
            // request timeout so the message survives handshake + propagation
            // + reply.  Using firstHopTimeout here caused "First hop connection
            // still in progress" failures because the handshake consumes most
            // of the timeout before the message can even be sent.
            effectiveTimeout = getRequestTimeout(ctx);
        }

        if (log.shouldInfo()) {
            log.info("Sending outbound TunnelBuildRequest direct to [" + nextHop.toBase64().substring(0,6) + "] for " + cfg +
                     "\n* Reply via: " + pairedTunnel + " [MsgID " + msg.getUniqueId() + "]");
        }

        OutNetMessage outMsg = new OutNetMessage(ctx, msg, ctx.clock().now() + effectiveTimeout, PRIORITY, peer);
        outMsg.setOnFailedSendJob(new TunnelBuildFirstHopFailJob(ctx, cfg, exec));

        try {
            ctx.outNetMessagePool().add(outMsg);
        } catch (RuntimeException e) {
            log.error("Failed to send TunnelBuildMessage", e);
            exec.buildComplete(cfg, OTHER_FAILURE, "Send failed");
        }
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

        // Pre-fetch RouterInfos once for all hops, reused by ShortTBM check and record population
        RouterInfo[] hopRIs = new RouterInfo[cfg.getLength()];
        for (int i = 0; i < cfg.getLength(); i++) {
            hopRIs[i] = ctx.netDb().lookupRouterInfoLocally(cfg.getPeer(i));
        }

        // Validate all hops support ShortTBM if we plan to use it
        if (useShortTBM) {
            int start = isInbound ? 0 : 1;
            int end = cfg.getLength();
            for (int i = start; i < end; i++) {
                RouterInfo ri = hopRIs[i];
                if (ri == null || ri.getIdentity().getPublicKey().getType() != EncType.ECIES_X25519 ||
                    VersionComparator.comp(ri.getVersion(), MIN_NEWTBM_VERSION) < 0) {
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
                recordCount = TunnelBuildMessageBase.MAX_RECORD_COUNT;
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
        int bw = 0;
        int variance = 0;
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
                RouterInfo peerInfo = hopRIs[hopIndex];
                if (peerInfo == null) {
                    log.warn("Peer not found for hop " + hopIndex + ": " + cfg.getPeer(hopIndex) + " in " + cfg);
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
            Hash hopPeer = _cfg.getPeer(1);
            RouterContext ctx = getContext();
            boolean connected = ctx.commSystem().isEstablished(hopPeer);
            boolean connecting = ctx.commSystem().isConnecting(hopPeer);
            boolean backlogged = ctx.commSystem().isBacklogged(hopPeer);
            if (connected) {
                // Peer accepted the build message but never replied.
                // Don't nuke the profile — the peer is connected, so the
                // failure is likely transient (congestion, build queue overflow).
                Log log = ctx.logManager().getLog(BuildRequestor.class);
                if (log.shouldInfo()) {
                    int estCount = ctx.commSystem().getEstablished() != null ?
                        ctx.commSystem().getEstablished().size() : 0;
                    log.info("First hop " + hopPeer.toBase64().substring(0, 8) +
                             " connected but no reply | estCount=" + estCount +
                             " | fast=" + ctx.profileOrganizer().isFast(hopPeer) +
                             " | hc=" + ctx.profileOrganizer().isHighCapacity(hopPeer));
                }
                _exec.buildComplete(_cfg, OTHER_FAILURE, "No reply from first hop");
                ctx.profileManager().tunnelTimedOut(hopPeer);
                ctx.statManager().addRateData("tunnel.buildFailFirstHop", 1);
            } else if (connecting) {
                // Transport has accepted the message and is still establishing
                // the handshake — the connection just needs more time.
                // Fail the build but don't penalize the peer profile or demote it.
                if (ctx.logManager().getLog(BuildRequestor.class).shouldInfo())
                    ctx.logManager().getLog(BuildRequestor.class)
                        .info("Build failed -> First hop connection still in progress for " + _cfg);
                _exec.buildComplete(_cfg, OTHER_FAILURE, "First hop connection still in progress");
                ctx.statManager().addRateData("tunnel.buildFailFirstHop", 1);
            } else {
                // No connection: the peer isn't established and the build
                // message couldn't be delivered.
                Log log = ctx.logManager().getLog(BuildRequestor.class);
                if (log.shouldInfo()) {
                    int estCount = ctx.commSystem().getEstablished() != null ?
                        ctx.commSystem().getEstablished().size() : 0;
                    StringBuilder sb = new StringBuilder(256);
                    sb.append("First hop ").append(backlogged ? "backlogged" : "unreachable")
                      .append(" for ").append(_cfg)
                      .append("\n * Peer [").append(hopPeer.toBase64().substring(0, 8)).append("]")
                      .append(" | inEst=").append(ctx.commSystem().getEstablished().contains(hopPeer))
                      .append(" | estCount=").append(estCount)
                      .append(" | ri=").append(ctx.netDb().lookupRouterInfoLocally(hopPeer) != null)
                      .append(" | connecting=").append(connecting)
                      .append(" | backlog=").append(backlogged)
                      .append(" | fast=").append(ctx.profileOrganizer().isFast(hopPeer))
                      .append(" | hc=").append(ctx.profileOrganizer().isHighCapacity(hopPeer));
                    log.info(sb.toString());
                }
                _exec.buildComplete(_cfg, OTHER_FAILURE, backlogged ? "First hop unreachable (backlogged)" : "First hop unreachable (no connection)");
                ctx.statManager().addRateData("tunnel.buildFailFirstHop", 1);
            }
        }
    }
}
