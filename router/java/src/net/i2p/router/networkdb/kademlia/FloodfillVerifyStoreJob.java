package net.i2p.router.networkdb.kademlia;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.i2p.crypto.EncType;
import net.i2p.data.Certificate;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.LeaseSet2;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.stat.RateConstants;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.LeaseSetKeys;
import net.i2p.router.MessageSelector;
import net.i2p.router.ProfileManager;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.BanLogger;
import net.i2p.router.TunnelInfo;
import net.i2p.router.util.MaskedIPSet;
import net.i2p.util.Log;

/**
 * Two-phase floodfill store verification.
 *
 * Phase 1 queries the peer we stored to, confirming it still holds a fresh
 * entry (store acceptance that outlives the DSM reply). Phase 2 waits longer
 * for the flood to propagate, then queries a different floodfill. A missing
 * or stale reply in phase 2 is retried once against the same peer before
 * blaming the store and flooding again — a single under-aged sample must not
 * condemn a flood that is merely in flight.
 */
class FloodfillVerifyStoreJob extends JobImpl {
    private final Log _log;
    private final BanLogger _banLogger;
    private final Hash _key;
    private final Hash _client;
    private volatile Hash _target;
    private final Hash _sentTo;
    private final FloodfillNetworkDatabaseFacade _facade;
    private long _expiration;
    private long _sendTime;
    private int _attempted;
    private final long _published;
    private final int _type;
    private final boolean _isRouterInfo;
    private final boolean _isLS2;
    private MessageWrapper.WrappedMessage _wrappedMessage;
    private final Set<Hash> _ignore;
    private final MaskedIPSet _ipSet;

    /** Phase 1: confirm the store peer still has the entry. */
    private static final int PHASE_STORE_CHECK = 0;
    /** Phase 2: confirm a different floodfill received the flood. */
    private static final int PHASE_FLOOD_CHECK = 1;

    private int _phase;
    /** One same-peer flood-check retry already spent. */
    private boolean _floodRetried;
    /** Keep the current phase-2 target instead of picking a new one. */
    private boolean _requerySame;
    /** When this verify job was created (store success time). */
    private final long _created;
    private final long _phase1DelayMs;
    private final long _phase2DelayMs;
    private final long _retryDelayMs;

    private static final String PROP_PHASE1_DELAY = "router.netDb.verifyPhase1Delay";
    private static final String PROP_PHASE2_DELAY_LS = "router.netDb.verifyPhase2Delay";
    private static final String PROP_PHASE2_DELAY_RI = "router.netDb.verifyPhase2RiDelay";
    private static final String PROP_RETRY_DELAY = "router.netDb.verifyRetryDelay";

    private static final int DEFAULT_PHASE1_DELAY = 5 * 1000;
    private static final int DEFAULT_PHASE2_DELAY_LS = 20 * 1000;
    private static final int DEFAULT_PHASE2_DELAY_RI = 5 * 1000;
    private static final int DEFAULT_RETRY_DELAY = 5 * 1000;

    private static final int VERIFY_TIMEOUT = 30 * 1000;
    private static final int MAX_PEERS_TO_TRY = 8;
    private static final int IP_CLOSE_BYTES = 3;
    private static final long[] RATES = RateConstants.TUNNEL_VERIFY_RATES;

    /**
     * Result of querying the store peer (phase 1).
     */
    enum StoreCheckResult {
        /** Peer returned a fresh entry — proceed to the flood check. */
        FRESH,
        /** Peer no longer has the key — the store did not stick. */
        MISSING,
        /** Peer returned an older entry than we published. */
        STALE,
        /** Timeout or setup failure — do not blame; still flood-check. */
        INDETERMINATE
    }

    /**
     * Result of querying a second floodfill (phase 2).
     */
    enum FloodCheckResult {
        /** Different floodfill has a fresh entry — full success. */
        FRESH,
        /** Peer has no copy — flood may still be in flight or failed. */
        MISSING,
        /** Peer has an older copy — flood may still be in flight or failed. */
        STALE
    }

    /**
     * Classify a phase-1 reply from the store peer.
     *
     * @param isStoreMsg true if the reply was a DatabaseStoreMessage, false for a search reply
     * @param entryFresh true if the entry's date/published is at least what we stored
     * @return the store-check result
     * @since 0.9.71+
     */
    static StoreCheckResult classifyStoreCheck(boolean isStoreMsg, boolean entryFresh) {
        if (!isStoreMsg) {return StoreCheckResult.MISSING;}
        if (!entryFresh) {return StoreCheckResult.STALE;}
        return StoreCheckResult.FRESH;
    }

    /**
     * Whether phase 1 proved the store failed (missing or stale at the peer we stored to).
     * An indeterminate result must not blame the store peer.
     *
     * @param result phase-1 classification
     * @return true if verify should fail immediately and resend
     * @since 0.9.71+
     */
    static boolean storeCheckFailed(StoreCheckResult result) {
        return result == StoreCheckResult.MISSING || result == StoreCheckResult.STALE;
    }

    /**
     * Classify a phase-2 reply from the alternate floodfill.
     *
     * @param isStoreMsg true if the reply was a DatabaseStoreMessage, false for a search reply
     * @param entryFresh true if the entry's date/published is at least what we stored
     * @return the flood-check result
     * @since 0.9.71+
     */
    static FloodCheckResult classifyFloodCheck(boolean isStoreMsg, boolean entryFresh) {
        if (!isStoreMsg) {return FloodCheckResult.MISSING;}
        if (!entryFresh) {return FloodCheckResult.STALE;}
        return FloodCheckResult.FRESH;
    }

    /**
     * Whether to re-query the same phase-2 peer once before blaming.
     * Missing and stale are the only retryable outcomes, and only on the first miss.
     *
     * @param result phase-2 classification
     * @param alreadyRetried true if a same-peer retry was already performed
     * @return true to schedule a retry against the same target
     * @since 0.9.71+
     */
    static boolean shouldRetryFloodCheck(FloodCheckResult result, boolean alreadyRetried) {
        if (alreadyRetried) {return false;}
        return result == FloodCheckResult.MISSING || result == FloodCheckResult.STALE;
    }

    /**
     * Absolute time phase 2 may start, relative to verify-job creation.
     *
     * @param createdMs when the verify job was created
     * @param phase2DelayMs configured flood-check delay
     * @return epoch ms at which the flood check may begin
     * @since 0.9.71+
     */
    static long phase2StartTime(long createdMs, long phase2DelayMs) {
        return createdMs + phase2DelayMs;
    }

    /**
     *  Delay, then confirm the store peer still holds the entry;
     *  after a longer delay, confirm a different floodfill got the flood.
     *
     *  @param ctx the router context
     *  @param key the key
     *  @param client generally the same as key, unless encrypted LS2; non-null
     *  @param published getDate() for RI or LS1, getPublished() for LS2
     *  @param type the database entry type
     *  @param sentTo who to give the credit or blame to, can be null
     *  @param toSkip don't query any of these peers, may be null
     *  @param facade the floodfill network database facade
     *  @since 0.9.53 added toSkip param
     */
    public FloodfillVerifyStoreJob(RouterContext ctx, Hash key, Hash client, long published, int type,
                                   Hash sentTo, Set<Hash> toSkip, FloodfillNetworkDatabaseFacade facade) {
        super(ctx);
        facade.verifyStarted(key);
        _key = key;
        _client = client;
        _published = published;
        _isRouterInfo = type == DatabaseEntry.KEY_TYPE_ROUTERINFO;
        _isLS2 = !_isRouterInfo && type != DatabaseEntry.KEY_TYPE_LEASESET;
        _type = type;
        _log = ctx.logManager().getLog(getClass());
        _banLogger = new BanLogger();
        _banLogger.initialize(ctx);
        _sentTo = sentTo;
        _facade = facade;
        _ignore = Collections.synchronizedSet(new HashSet<>(8));
        if (toSkip != null) {
            synchronized(toSkip) {_ignore.addAll(toSkip);}
        }
        if (sentTo != null) {
            _ipSet = new MaskedIPSet(ctx, sentTo, IP_CLOSE_BYTES);
            _ignore.add(_sentTo);
        } else {_ipSet = new MaskedIPSet(16);}
        _created = ctx.clock().now();
        _phase1DelayMs = clampDelay(ctx.getProperty(PROP_PHASE1_DELAY, (long) DEFAULT_PHASE1_DELAY),
                                    DEFAULT_PHASE1_DELAY);
        if (_isRouterInfo) {
            _phase2DelayMs = clampDelay(ctx.getProperty(PROP_PHASE2_DELAY_RI, (long) DEFAULT_PHASE2_DELAY_RI),
                                        DEFAULT_PHASE2_DELAY_RI);
        } else {
            _phase2DelayMs = clampDelay(ctx.getProperty(PROP_PHASE2_DELAY_LS, (long) DEFAULT_PHASE2_DELAY_LS),
                                        DEFAULT_PHASE2_DELAY_LS);
        }
        _retryDelayMs = clampDelay(ctx.getProperty(PROP_RETRY_DELAY, (long) DEFAULT_RETRY_DELAY),
                                   DEFAULT_RETRY_DELAY);
        if (sentTo != null) {
            _phase = PHASE_STORE_CHECK;
            getTiming().setStartAfter(_created + _phase1DelayMs);
        } else {
            _phase = PHASE_FLOOD_CHECK;
            getTiming().setStartAfter(phase2StartTime(_created, _phase2DelayMs));
        }
        getContext().statManager().createRequiredRateStat("netDb.floodfillVerifyOK", "Time for successful Floodfill verify (ms)", "NetworkDatabase", RATES);
        getContext().statManager().createRequiredRateStat("netDb.floodfillVerifyFail", "Time for failed Floodfill verify (ms)", "NetworkDatabase", RATES);
        getContext().statManager().createRateStat("netDb.floodfillVerifyTimeout", "Floodfill verify timeout (ms)", "NetworkDatabase", RATES);
        getContext().statManager().createRateStat("netDb.floodfillVerifyPhase1OK", "Time to confirm store peer still has entry (ms)", "NetworkDatabase", RATES);
        getContext().statManager().createRateStat("netDb.floodfillVerifyPhase1Fail", "Time until store peer missing or stale (ms)", "NetworkDatabase", RATES);
        getContext().statManager().createRateStat("netDb.floodfillVerifyPhase1Timeout", "Store-peer check timeout (ms)", "NetworkDatabase", RATES);
        getContext().statManager().createRateStat("netDb.floodfillVerifyFailNoKey", "Flood check missing key after retry (ms)", "NetworkDatabase", RATES);
        getContext().statManager().createRateStat("netDb.floodfillVerifyFailStale", "Flood check stale key after retry (ms)", "NetworkDatabase", RATES);
        getContext().statManager().createRateStat("netDb.floodfillVerifyRetry", "Flood check same-peer retries", "NetworkDatabase", RATES);
    }

    /**
     * Reject nonsensical delay properties so a bad config cannot busy-loop.
     *
     * @param configured value from the router config
     * @param fallback default to use when configured is out of range
     * @return a delay between 0 and 2 minutes
     */
    private static long clampDelay(long configured, int fallback) {
        if (configured < 0 || configured > 120 * 1000L) {return fallback;}
        return configured;
    }

    public String getName() { return "Verify NetDb Store"; }

    /**
     *  Run the current phase: first ask the peer we stored to, then a
     *  different floodfill after the flood-check delay has elapsed.
     *
     *  Phase 1 failure (missing/stale at the store peer) resends immediately.
     *  Phase 2 missing/stale retries the same peer once, then resends.
     */
    public void runJob() {
        if (_phase == PHASE_STORE_CHECK) {
            if (_sentTo == null) {
                enterFloodCheck();
                return;
            }
            _target = _sentTo;
        } else if (_requerySame) {
            _requerySame = false;
            // keep _target
        } else {
            _target = pickTarget();
        }
        if (_target == null) {
            if (_phase == PHASE_STORE_CHECK) {
                enterFloodCheck();
            } else {
                _facade.verifyFinished(_key);
            }
            return;
        }

        final RouterContext ctx = getContext();
        boolean isInboundExploratory;
        TunnelInfo replyTunnelInfo;
        if (_isRouterInfo || ctx.keyRing().get(_key) != null || _type == DatabaseEntry.KEY_TYPE_META_LS2) {
            replyTunnelInfo = ctx.tunnelManager().selectInboundExploratoryTunnel(_target);
            isInboundExploratory = true;
        } else {
            replyTunnelInfo = ctx.tunnelManager().selectInboundTunnel(_client, _target);
            isInboundExploratory = false;
        }
        if (replyTunnelInfo == null) {
            if (_log.shouldWarn()) {_log.warn("No Inbound tunnels to get a reply from");}
            if (_phase == PHASE_STORE_CHECK) {
                enterFloodCheck();
            } else {
                _facade.verifyFinished(_key);
            }
            return;
        }
        DatabaseLookupMessage lookup = buildLookup(replyTunnelInfo);

        // If we are verifying a leaseset, use the destination's own tunnels,
        // to avoid association by the exploratory tunnel OBEP.
        // Unless it is an encrypted leaseset.
        TunnelInfo outTunnel;
        if (_isRouterInfo || ctx.keyRing().get(_key) != null || _type == DatabaseEntry.KEY_TYPE_META_LS2) {
            outTunnel = ctx.tunnelManager().selectOutboundExploratoryTunnel(_target);
        } else {outTunnel = ctx.tunnelManager().selectOutboundTunnel(_client, _target);}
        if (outTunnel == null) {
            if (_log.shouldWarn()) {_log.warn("No Outbound tunnels to verify a store");}
            if (_phase == PHASE_STORE_CHECK) {
                enterFloodCheck();
            } else {
                _facade.verifyFinished(_key);
            }
            return;
        }

        // garlic encrypt to hide contents from the OBEP
        RouterInfo peer = ctx.netDb().lookupRouterInfoLocally(_target);
        if (peer == null) {
            if (_log.shouldWarn()) {
                _log.warn("LOCAL lookup of RouterInfo for target " + _target + " failed [DbId: " + _facade + "]");
            }
            if (_phase == PHASE_STORE_CHECK) {
                enterFloodCheck();
            } else {
                _facade.verifyFinished(_key);
            }
            return;
        }
        EncType type = peer.getIdentity().getPublicKey().getType();
        boolean supportsElGamal = true;
        boolean supportsRatchet = false;
        if (DatabaseLookupMessage.supportsEncryptedReplies(peer)) {
            // register the session with the right SKM
            MessageWrapper.OneTimeSession sess;
            if (isInboundExploratory) {
                EncType ourType = ctx.keyManager().getPublicKey().getType();
                supportsElGamal = ourType == EncType.ELGAMAL_2048 && type == EncType.ELGAMAL_2048;
                supportsRatchet = ourType == EncType.ECIES_X25519 && type == EncType.ECIES_X25519;
                if (supportsElGamal || supportsRatchet) {
                    sess = MessageWrapper.generateSession(ctx, ctx.sessionKeyManager(), VERIFY_TIMEOUT, !supportsRatchet);
                } else {
                    // We don't have a compatible way to get a reply, skip it for now.
                    if (_log.shouldWarn()) {_log.warn("Skipping NetDbStore verify for incompatible Router " + peer);}
                    if (_phase == PHASE_STORE_CHECK) {
                        enterFloodCheck();
                    } else {
                        _facade.verifyFinished(_key);
                    }
                    return;
                }
            } else {
                LeaseSetKeys lsk = ctx.keyManager().getKeys(_client);
                // an ElG router supports ratchet replies
                supportsRatchet = lsk != null && lsk.isSupported(EncType.ECIES_X25519) && DatabaseLookupMessage.supportsRatchetReplies(peer);
                // but an ECIES router does not supports ElGamal requests
                supportsElGamal = lsk != null && lsk.isSupported(EncType.ELGAMAL_2048) && type == EncType.ELGAMAL_2048;
                if (supportsElGamal || supportsRatchet) {
                    sess = MessageWrapper.generateSession(ctx, _client, VERIFY_TIMEOUT, !supportsRatchet); // garlic encrypt
                    if (sess == null) {
                        if (_log.shouldWarn()) {_log.warn("No SessionKeyManager for connection to [" + _target.toBase64().substring(0,6) + "]");}
                        if (_phase == PHASE_STORE_CHECK) {
                            enterFloodCheck();
                        } else {
                            _facade.verifyFinished(_key);
                        }
                        return;
                    }
                } else {
                    // We don't have a compatible way to get a reply, skip it for now.
                    if (_log.shouldWarn()) {
                        _log.warn("Skipping NetDbStore verify to [" + _target.toBase64().substring(0,6) +
                                   "] for ECIES or ElG-only client [" + _client.toBase32().substring(0,8) + "]");
                    }
                    if (_phase == PHASE_STORE_CHECK) {
                        enterFloodCheck();
                    } else {
                        _facade.verifyFinished(_key);
                    }
                    return;
                }
            }
            if (sess.tag != null) {
                if (_log.shouldDebug()) {
                _log.debug("Requesting AES reply from [" + _target.toBase64().substring(0,6) +
                           "]\n* Session key: " + sess.key + "\n* Tag: " + sess.tag);
                }
                lookup.setReplySession(sess.key, sess.tag);
            } else {
                if (_log.shouldDebug()) {
                _log.debug("Requesting AEAD reply from [" + _target.toBase64().substring(0,6) +
                           "]\n* Session key: " + sess.key + "\n* Tag: " + sess.rtag);
                }
                lookup.setReplySession(sess.key, sess.rtag);
            }
        }
        Hash fromKey;
        I2NPMessage sent;
        if (supportsElGamal) {
            if (_isRouterInfo) {fromKey = null;}
            else {fromKey = _client;}
            _wrappedMessage = MessageWrapper.wrap(ctx, lookup, fromKey, peer);
            if (_wrappedMessage == null) {
                if (_log.shouldWarn()) {_log.warn("Garlic encryption failure");}
                if (_phase == PHASE_STORE_CHECK) {
                    enterFloodCheck();
                } else {
                    _facade.verifyFinished(_key);
                }
                return;
            }
            sent = _wrappedMessage.getMessage();
        } else {
            // force full ElG for ECIES fromkey or forces ECIES for ECIES peer
            sent = MessageWrapper.wrap(ctx, lookup, peer);
            if (sent == null) {
                if (_log.shouldWarn()) {_log.warn("Garlic encryption failure");}
                if (_phase == PHASE_STORE_CHECK) {
                    enterFloodCheck();
                } else {
                    _facade.verifyFinished(_key);
                }
                return;
            }
        }

        if (_log.shouldInfo()) {
            LeaseSet ls = _facade.lookupLeaseSetLocally(_key);
            String tunnelName = ls != null ? _facade.getTunnelName(ls.getDestination()) : "";
            String name = !tunnelName.isEmpty() ? " of \'" + tunnelName + "\'" : " of key";
            String phase = _phase == PHASE_STORE_CHECK ? "Store check" : "Flood check";
            _log.info(phase + " for Floodfill store" + name + " [" + _key.toBase32().substring(0,8) +
                      "] sent to [" + (_sentTo != null ? _sentTo.toBase64().substring(0,6) : "?") +
                      "] -> Querying: [" + _target.toBase64().substring(0,6) + "]");
        }
        _sendTime = ctx.clock().now();
        _expiration = _sendTime + VERIFY_TIMEOUT;
        ctx.messageRegistry().registerPending(new VerifyReplySelector(),
                                              new VerifyReplyJob(getContext()),
                                              new VerifyTimeoutJob(getContext()));
        ctx.tunnelDispatcher().dispatchOutbound(sent, outTunnel.getSendTunnelId(0), _target);
        _attempted++;
    }

    /**
     * Leave the store-peer check and schedule the flood check at
     * created + phase2 delay (immediately if that time already passed).
     * Does not release the verify-in-progress slot.
     */
    private void enterFloodCheck() {
        if (_phase == PHASE_FLOOD_CHECK) {return;}
        _phase = PHASE_FLOOD_CHECK;
        _floodRetried = false;
        _requerySame = false;
        _target = null;
        _attempted = 0;
        final long readyAt = phase2StartTime(_created, _phase2DelayMs);
        final long now = getContext().clock().now();
        getContext().statManager().addRateData("netDb.floodfillVerifyPhase2", now - _created);
        if (now >= readyAt) {
            runJob();
            return;
        }
        Job cont = new JobImpl(getContext()) {
            public String getName() { return "Flood Verify Phase 2"; }
            public void runJob() { FloodfillVerifyStoreJob.this.runJob(); }
        };
        cont.getTiming().setStartAfter(readyAt);
        getContext().jobQueue().addJob(cont);
    }

    /**
     * Re-query the same phase-2 peer after the retry delay (one shot).
     * Does not release the verify-in-progress slot.
     */
    private void scheduleFloodRetry() {
        _floodRetried = true;
        _requerySame = true;
        getContext().statManager().addRateData("netDb.floodfillVerifyRetry", 1);
        final long readyAt = getContext().clock().now() + _retryDelayMs;
        if (_log.shouldInfo()) {
            _log.info("Flood check retry for [" + _key.toBase32().substring(0,8) +
                      "] -> Requerying [" + (_target != null ? _target.toBase64().substring(0,6) : "?") +
                      "] in " + _retryDelayMs + "ms");
        }
        Job cont = new JobImpl(getContext()) {
            public String getName() { return "Flood Verify Retry"; }
            public void runJob() { FloodfillVerifyStoreJob.this.runJob(); }
        };
        cont.getTiming().setStartAfter(readyAt);
        getContext().jobQueue().addJob(cont);
    }

    /**
     *  Pick a responsive floodfill close to the key, but not the one we sent to
     */
    private Hash pickTarget() {
        Hash rkey = getContext().routingKeyGenerator().getRoutingKey(_key);
        FloodfillPeerSelector sel = (FloodfillPeerSelector)_facade.getPeerSelector();
        Certificate keyCert = null;
        if (!_isRouterInfo) {
            Destination dest = _facade.lookupDestinationLocally(_key);
            if (dest != null) {
                Certificate cert = dest.getCertificate();
                if (cert.getCertificateType() == Certificate.CERTIFICATE_TYPE_KEY) {keyCert = cert;}
            }
        }
        if (keyCert != null) {
            int limit = 0;
            while (limit++ < 100) {
                List<Hash> peers = sel.selectFloodfillParticipants(rkey, 1, _ignore, _facade.getKBuckets());
                if (peers.isEmpty()) {break;}
                Hash peer = peers.get(0);
                RouterInfo ri = getContext().netDb().lookupRouterInfoLocally(peer);
                if (ri != null && StoreJob.shouldStoreTo(ri) && (_type != DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2 || StoreJob.shouldStoreEncLS2To(ri))) {
                    Set<String> peerIPs = new MaskedIPSet(getContext(), ri, IP_CLOSE_BYTES);
                    if (!_ipSet.containsAny(peerIPs)) {
                        _ipSet.addAll(peerIPs);
                        return peer;
                    } else {
                        if (_log.shouldDebug()) {
                            _log.debug("Skipping Floodfill Verify for Router [" + peer.toBase64().substring(0,6) + "] -> Too close to the store");
                        }
                    }
                } else {
                    if (_log.shouldDebug()) {
                        _log.debug("Skipping Floodfill Verify for Router [" + peer.toBase64().substring(0,6) + "] -> Router is too old");
                    }
                }
                _ignore.add(peer);
            }
        } else {
            List<Hash> peers = sel.selectFloodfillParticipants(rkey, 256, _ignore, _facade.getKBuckets());
            if (!peers.isEmpty()) {
                Collections.shuffle(peers);
                return peers.get(0);
            }
        }

        if (_log.shouldWarn()) {_log.warn("No other peers to verify Floodfill with, using the one we sent to");}
        return _sentTo;
    }

    /**
     * Builds the database lookup message for verification.
     *
     * @return non-null
     */
    private DatabaseLookupMessage buildLookup(TunnelInfo replyTunnelInfo) {
        // If we are verifying a leaseset, use the destination's own tunnels,
        // to avoid association by the exploratory tunnel OBEP.
        // Unless it is an encrypted leaseset.
        DatabaseLookupMessage m = new DatabaseLookupMessage(getContext(), true);
        m.setMessageExpiration(getContext().clock().now() + VERIFY_TIMEOUT);
        m.setReplyTunnel(replyTunnelInfo.getReceiveTunnelId(0));
        m.setFrom(replyTunnelInfo.getPeer(0));
        m.setSearchKey(_key);
        m.setSearchType(_isRouterInfo ? DatabaseLookupMessage.Type.RI : DatabaseLookupMessage.Type.LS);
        return m;
    }

    /**
     * Is the entry at least as new as what we published?
     *
     * @param entry peer-supplied entry
     * @return true if fresh enough to count as success
     */
    private boolean isFresh(DatabaseEntry entry) {
        if (_isLS2 &&
            entry.getType() != DatabaseEntry.KEY_TYPE_ROUTERINFO &&
            entry.getType() != DatabaseEntry.KEY_TYPE_LEASESET) {
            LeaseSet2 ls2 = (LeaseSet2) entry;
            return ls2.getPublished() >= _published;
        }
        return entry.getDate() >= _published;
    }

    /**
     * Release the verify-in-progress slot for this key.
     */
    private void finishVerify() {
        _facade.verifyFinished(_key);
    }

    /**
     * Ban a peer that returned an entry with a bad signature, then fail.
     *
     * @param delayMs reply latency for stats
     * @return always (control does not continue after a ban)
     */
    private void banBadEntry(long delayMs) {
        if (_log.shouldWarn()) {
            _log.warn("Banning Router [" + _target.toBase64().substring(0,6) + "] for duration of session -> Sent us BAD data (spoofed?)");
        }
        ProfileManager pm = getContext().profileManager();
        pm.dbLookupFailed(_target);
        getContext().banlist().banlistRouterForever(_target, "Sent bad NetDb data");
        _banLogger.logBanForever(_target, getContext(), "Sent bad NetDb data");
        getContext().statManager().addRateData("netDb.floodfillVerifyFail", delayMs);
        finishVerify();
        resend();
    }

    /**
     * Store-peer said the entry is missing or stale — the store itself failed.
     * Blame only the store peer, then resend. No alternate peer was queried yet.
     *
     * @param result never FRESH or INDETERMINATE
     * @param delayMs reply latency for stats
     */
    private void failStoreCheck(StoreCheckResult result, long delayMs) {
        if (_log.shouldWarn()) {
            _log.warn("Floodfill store check failed for [" + _key.toBase32().substring(0,8) + "] -> Store peer [" +
                      _target.toBase64().substring(0,6) + "] " +
                      (result == StoreCheckResult.MISSING ? "doesn't have the key" : "returned a stale entry"));
        }
        if (_sentTo != null) {getContext().profileManager().dbStoreFailed(_sentTo);}
        getContext().statManager().addRateData("netDb.floodfillVerifyPhase1Fail", delayMs);
        getContext().statManager().addRateData("netDb.floodfillVerifyFail", delayMs);
        finishVerify();
        resend();
    }

    /**
     * Full success: a different floodfill holds a fresh copy — the flood worked.
     *
     * @param delayMs reply latency for stats
     */
    private void succeedFloodCheck(long delayMs) {
        ProfileManager pm = getContext().profileManager();
        pm.dbLookupSuccessful(_target, delayMs);
        if (_sentTo != null) {pm.dbStoreSuccessful(_sentTo);}
        getContext().statManager().addRateData("netDb.floodfillVerifyOK", delayMs);
        if (_log.shouldInfo()) {
            LeaseSet ls = _facade.lookupLeaseSetLocally(_key);
            String tunnelName = ls != null ? _facade.getTunnelName(ls.getDestination()) : "";
            String name = !tunnelName.isEmpty() ? " for \'" + tunnelName + "\'" : " for key";
            _log.info("Floodfill Verify succeeded" + name + " [" + _key.toBase32().substring(0,8) + "]");
        }
        if (_isRouterInfo) {_facade.routerInfoPublishSuccessful();}
        finishVerify();
    }

    /**
     * Phase 2 still missing or stale after any retry — blame store peer for
     * not flooding and the verify peer for not having it, then resend.
     *
     * @param result FRESH is not a failure
     * @param delayMs reply latency for stats
     */
    private void failFloodCheck(FloodCheckResult result, long delayMs) {
        ProfileManager pm = getContext().profileManager();
        if (result == FloodCheckResult.MISSING) {
            getContext().statManager().addRateData("netDb.floodfillVerifyFailNoKey", delayMs);
            if (_log.shouldWarn()) {
                _log.warn("Floodfill Verify failed for [" + _key.toBase32().substring(0,8) + "] -> " +
                          "Queried peer [" + _target.toBase64().substring(0,6) + "] didn't have the key");
            }
        } else {
            getContext().statManager().addRateData("netDb.floodfillVerifyFailStale", delayMs);
            if (_log.shouldWarn()) {
                _log.warn("Floodfill Verify failed for [" + _key.toBase32().substring(0,8) + "] -> Key was stale" +
                          (_log.shouldDebug() ? "\n* peer still has an older copy" : ""));
            }
        }
        // blame the sent-to peer, but not if we never had one
        if (_sentTo != null) {pm.dbStoreFailed(_sentTo);}
        // Blame the verify peer also.
        // We must use dbLookupFailed() or dbStoreFailed(), neither of which is exactly correct,
        // but we have to use one of them to affect the FloodfillPeerSelector ordering.
        // If we don't do this we get stuck using the same verify peer every time even
        // though it is the real problem.
        if (!_target.equals(_sentTo)) {pm.dbLookupFailed(_target);}
        getContext().statManager().addRateData("netDb.floodfillVerifyFail", delayMs);
        finishVerify();
        resend();
    }

    /**
     * Handle a search reply (peer does not have the key) for the current phase.
     *
     * @param dsrm the search reply
     * @param delayMs reply latency for stats
     */
    private void handleSearchReply(DatabaseSearchReplyMessage dsrm, long delayMs) {
        ProfileManager pm = getContext().profileManager();
        // assume 0 old, all new, 0 invalid, 0 dup
        pm.dbLookupReply(_target, 0, dsrm.getNumReplies(), 0, 0, delayMs);
        if (_phase == PHASE_STORE_CHECK) {
            failStoreCheck(StoreCheckResult.MISSING, delayMs);
            return;
        }
        if (shouldRetryFloodCheck(FloodCheckResult.MISSING, _floodRetried)) {
            scheduleFloodRetry();
            return;
        }
        // final miss after any retry
        // only for RI... LS too dangerous?
        if (_isRouterInfo) {
            if (_facade.isClientDb() && _log.shouldWarn()) {
                _log.warn("Warning! Client is starting a SingleLookupJob (DIRECT?) for RouterInfo [DbId: " + _facade + "]");
            }
            getContext().jobQueue().addJob(new SingleLookupJob(getContext(), dsrm));
        }
        failFloodCheck(FloodCheckResult.MISSING, delayMs);
    }

    /**
     * Handle a store message (entry present) for the current phase.
     *
     * @param entry the entry from the reply
     * @param delayMs reply latency for stats
     */
    private void handleStoreMessage(DatabaseEntry entry, long delayMs) {
        if (!entry.verifySignature()) {
            banBadEntry(delayMs);
            return;
        }
        boolean fresh = isFresh(entry);
        if (_phase == PHASE_STORE_CHECK) {
            StoreCheckResult result = classifyStoreCheck(true, fresh);
            if (storeCheckFailed(result)) {
                failStoreCheck(result, delayMs);
                return;
            }
            getContext().statManager().addRateData("netDb.floodfillVerifyPhase1OK", delayMs);
            if (_log.shouldInfo()) {
                _log.info("Floodfill store check OK for [" + _key.toBase32().substring(0,8) +
                          "] -> Store peer still holds the entry");
            }
            enterFloodCheck();
            return;
        }
        FloodCheckResult result = classifyFloodCheck(true, fresh);
        if (result == FloodCheckResult.FRESH) {
            succeedFloodCheck(delayMs);
            return;
        }
        if (shouldRetryFloodCheck(result, _floodRetried)) {
            scheduleFloodRetry();
            return;
        }
        failFloodCheck(result, delayMs);
    }

    private class VerifyReplySelector implements MessageSelector {
        public boolean continueMatching() {return false;} // only want one match
        public long getExpiration() { return _expiration; }
        public boolean isMatch(I2NPMessage message) {
            int type = message.getType();
            if (type == DatabaseStoreMessage.MESSAGE_TYPE) {
                DatabaseStoreMessage dsm = (DatabaseStoreMessage)message;
                return _key.equals(dsm.getKey());
            } else if (type == DatabaseSearchReplyMessage.MESSAGE_TYPE) {
                DatabaseSearchReplyMessage dsrm = (DatabaseSearchReplyMessage)message;
                return _key.equals(dsrm.getSearchKey());
            }
            return false;
        }
    }

    private class VerifyReplyJob extends JobImpl implements ReplyJob {
        private I2NPMessage _message;
        public VerifyReplyJob(RouterContext ctx) {super(ctx);}
        public String getName() { return "Handle Floodfill Verification Reply"; }

        public void runJob() {
            long delay = getContext().clock().now() - _sendTime;
            if (_wrappedMessage != null) {_wrappedMessage.acked();}
            final int type = _message.getType();
            if (type == DatabaseStoreMessage.MESSAGE_TYPE) {
                DatabaseStoreMessage dsm = (DatabaseStoreMessage)_message;
                handleStoreMessage(dsm.getEntry(), delay);
            } else if (type == DatabaseSearchReplyMessage.MESSAGE_TYPE) {
                handleSearchReply((DatabaseSearchReplyMessage) _message, delay);
            }
        }

        public void setMessage(I2NPMessage message) {_message = message;}
    }

    /**
     *  the netDb store failed to verify, so resend it to a random floodfill peer
     *  Fixme - since we now store closest-to-the-key, this is likely to store to the
     *  very same ff as last time, until the stats get bad enough to switch.
     *  Therefore, pass the failed ff through as a don't-store-to.
     *  Let's also add the one we just tried to verify with, as they could be a pair of no-flooders.
     *  So at least we'll try THREE ffs round-robin if things continue to fail...
     */
    private void resend() {
        DatabaseEntry ds = _facade.lookupLocally(_key);
        if (ds != null) {
            // By the time we get here, a minute or more after the store started,
            // we may have already started a new store
            // (probably, for LS, and we don't verify by default for RI)
            long newDate;
            LeaseSet ls = _facade.lookupLeaseSetLocally(_key);
            String tunnelName = ls != null ? _facade.getTunnelName(ls.getDestination()) : "";
            String name = !tunnelName.isEmpty() ? " for \'" + tunnelName + "\'" : " for key";

            if (_isLS2 &&
                ds.getType() != DatabaseEntry.KEY_TYPE_ROUTERINFO &&
                ds.getType() != DatabaseEntry.KEY_TYPE_LEASESET) {
                LeaseSet2 ls2 = (LeaseSet2) ds;
                newDate = ls2.getPublished();
            } else {newDate = ds.getDate();}
            if (newDate > _published) {
                if (_log.shouldInfo()) {
                    _log.info("Floodfill Verify failed" + name + " [" + _key.toBase32().substring(0,8) + "] but new NetDbStore already succeeded");
                }
                return;
            }
            Set<Hash> toSkip = new HashSet<>(8);
            if (_sentTo != null) {toSkip.add(_sentTo);}
            if (_target != null) {toSkip.add(_target);}
            // pass over all the ignores for the next attempt
            // unless we've had a crazy number of attempts, then start over
            if (_ignore.size() < 50) {toSkip.addAll(_ignore);}
            if (_log.shouldWarn()) {
                _log.warn("Floodfill Verify failed" + name + " [" + _key.toBase32().substring(0,8) + "] -> Starting new NetDbStore...");
            }
            _facade.sendStore(_key, ds, null, null, FloodfillNetworkDatabaseFacade.PUBLISH_TIMEOUT, toSkip);
        }
    }

    private class VerifyTimeoutJob extends JobImpl {
        public VerifyTimeoutJob(RouterContext ctx) {super(ctx);}
        public String getName() { return "Timeout Floodfill Verification"; }
        public void runJob() {
            if (_wrappedMessage != null) {_wrappedMessage.fail();}
            long elapsed = getContext().clock().now() - _sendTime;
            if (_phase == PHASE_STORE_CHECK) {
                // Indeterminate: tunnel or peer unreachability is not proof the
                // store peer lost the entry — do not blame; still flood-check.
                getContext().statManager().addRateData("netDb.floodfillVerifyPhase1Timeout", elapsed);
                if (_log.shouldInfo()) {
                    _log.info("Floodfill store check timed out for [" + _key.toBase32().substring(0,8) +
                              "] -> Proceeding to flood check without blaming [" +
                              (_target != null ? _target.toBase64().substring(0,6) : "?") + "]");
                }
                enterFloodCheck();
                return;
            }
            getContext().profileManager().dbLookupFailed(_target); // Only blame the verify peer
            getContext().statManager().addRateData("netDb.floodfillVerifyTimeout", elapsed);
            if (_log.shouldWarn()) {
                LeaseSet ls = _facade.lookupLeaseSetLocally(_key);
                String tunnelName = ls != null ? _facade.getTunnelName(ls.getDestination()) : "";
                String name = !tunnelName.isEmpty() ? " for \'" + tunnelName + "\'" : " for key";
                _log.warn("Floodfill Verify timed out" + name + " [" + _key.toBase32().substring(0,8) + "] -> " +
                          "Ignoring [" + _target.toBase64().substring(0,6) + "] and selecting a new peer...");
            }
            if (_attempted < MAX_PEERS_TO_TRY) {
                // Don't resend, simply rerun FVSJ.this inline and chose somebody besides _target for verification
                _ignore.add(_target);
                FloodfillVerifyStoreJob.this.runJob();
            } else {
                finishVerify();
                resend();
            }
        }
    }
}
