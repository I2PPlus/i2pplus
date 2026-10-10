package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.i2p.data.Hash;
import net.i2p.router.Banlist;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.util.MaskedIPSet;
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.util.ArraySet;
import net.i2p.util.SystemVersion;

/**
 * Pick peers randomly out of the not-failing pool, and put them into a tunnel
 * ordered by XOR distance from a random key.
 */
    class ExploratoryPeerSelector extends TunnelPeerSelector {

        /**
         * Counts selections discarded because every candidate was banlisted.
         *
         * <p>Exists to keep the discard branch above honest. The change that
         * replaced restoring the banned selection with discarding it was
         * originally justified by a build-capacity figure that no measurement
         * supported, and the branch then recorded 0 occurrences over roughly
         * two hours. Rather than re-assert a benefit, this counter makes the
         * frequency observable so the branch can be re-evaluated on evidence.
         *
         * <p>Must be created before use: {@code StatManager.addRateData} on an
         * unregistered name silently drops the sample.
         *
         * @since 0.9.71+
         */
        static final String EPS_ALL_BANNED_STAT = "tunnel.peerSelection.epsAllBannedDiscarded";
        private static final long[] EPS_STAT_RATES = {
            RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR
        };

        /**
         * Record a discarded selection, creating the stat on first use.
         *
         * <p>{@code StatManager.addRateData} silently drops samples for an
         * unregistered name, so creation is not optional here. No "already
         * created" guard is needed because {@code createRequiredRateStat} is
         * itself idempotent.
         *
         * @param ctx router context; null is ignored
         * @since 0.9.71+
         */
        private static void noteAllBannedDiscard(RouterContext ctx) {
            if (ctx == null || ctx.statManager() == null) {return;}
            ctx.statManager().createRequiredRateStat(
                EPS_ALL_BANNED_STAT,
                "Exploratory selections discarded because every peer was banlisted",
                "Tunnels", EPS_STAT_RATES);
            ctx.statManager().addRateData(EPS_ALL_BANNED_STAT, 1);
        }

    /**
     * Cooldown entries for exploratory selections, recorded when checkTunnel
     * fails.  Separate from the shared {@link #_peerCooldowns} so exploratory
     * builds cannot pollute the set used by client pools.  Only failures are
     * recorded — healthy selections never cooldown peers.
     * @since 0.9.71+
     */
    private final Map<Hash, Long> _exploratoryCooldowns = new ConcurrentHashMap<>();

    /**
     * Peers from the last selection that were all ghosts, handed to the redraw in
     * {@link #selectPeers(TunnelPoolSettings)}.
     *
     * <p>A field rather than a return value because the ghost filter sits deep inside
     * {@link #selectPeersInternal} and the retry belongs beside the existing cooldown
     * retry, in the one method that already sequences attempts. Per-thread: two selections
     * in flight must not exchange the set they were told to avoid.
     */
    private final ThreadLocal<Set<Hash>> _lastAllGhostSelection = new ThreadLocal<Set<Hash>>();

    /**
     * ExploratoryPeerSelector.
     *
     * @param context the router context supplying peers and network state
     */
    public ExploratoryPeerSelector(RouterContext context) {
        super(context);
    }

    /**
     * Returns ENDPOINT FIRST, GATEWAY LAST!!!!
     * In: us .. closest .. middle .. IBGW
     * Out: OBGW .. middle .. closest .. us
     *
     * @return ordered list of Hash objects (one per peer) specifying what order
     * they should appear in a tunnel (ENDPOINT FIRST).  This includes
     * the local router in the list.  Never null; an empty list means
     * no peers could be selected.
     */
    @Override
    public List<Hash> selectPeers(TunnelPoolSettings settings) {
        int length = getLength(settings);
        if (length < 0) {
            if (log.shouldDebug())
                log.debug("Tunnel length requested is zero: " + settings);
            return Collections.emptyList();
        }

        // Tracked so the ghost retry below reuses the same cooldown decision rather than
        // silently re-enabling cooldowns on the redraw.
        boolean includeCooldownsPass = true;
        List<Hash> rv = selectPeersInternal(settings, length, includeCooldownsPass);
        if (rv != null && rv.size() == 1 && length > 0) {
            // All candidates were excluded by selection cooldowns.
            // Retry once without cooldowns rather than starving the pool.
            if (log.shouldDebug())
                log.debug("EPS all candidates on cooldown, retrying without cooldowns");
            includeCooldownsPass = false;
            rv = selectPeersInternal(settings, length, includeCooldownsPass);
        }
        // Ghost retry: if every peer drawn was a ghost, draw again with those peers
        // excluded rather than restoring them. Restoring dispatched the build through
        // peers just measured as failing, and did so deterministically, so the next
        // cycle drew the same ghosts and burned another attempt.
        Set<Hash> ghosted = _lastAllGhostSelection.get();
        _lastAllGhostSelection.remove();
        if (ghosted != null && rv != null && rv.isEmpty()) {
            if (log.shouldDebug()) {
                log.debug("EPS every peer was a ghost (" + ghosted.size() +
                          "), retrying without them");
            }
            rv = selectPeersInternal(settings, length, includeCooldownsPass, ghosted);
            if (rv == null) {
                return Collections.emptyList();
            }
        }
        if (rv == null) {
            // checkTunnel() rejected the selection — honor the never-null
            // contract so callers can rely on isEmpty() without null checks.
            if (log.shouldDebug())
                log.debug("EPS checkTunnel failed -> empty selection");
            return Collections.emptyList();
        }
        return rv;
    }

    /**
     * The actual peer selection, shared between the normal path and the
     * cooldown-bypass retry in {@link #selectPeers(TunnelPoolSettings)}.
     *
     * @param settings the tunnel pool settings
     * @param length the desired tunnel length
     * @param includeCooldowns whether to exclude peers on selection cooldown
     * @return ordered list of Hash objects (ENDPOINT FIRST), or null if no
     * peers are available or checkTunnel fails
     */
    private List<Hash> selectPeersInternal(TunnelPoolSettings settings, int length,
                                          boolean includeCooldowns) {
        return selectPeersInternal(settings, length, includeCooldowns, null);
    }

    /**
     * Select peers, additionally excluding a set the caller has decided on.
     *
     * <p>Used for the ghost retry: the client pools exclude peers that turned out to be
     * ghosts and redraw, so exploratory has to be able to do the same rather than restore
     * a selection the peer measurements just rejected.
     *
     * @param settings the tunnel pool settings
     * @param length the desired tunnel length
     * @param includeCooldowns whether to exclude peers on selection cooldown
     * @param alsoExclude peers to exclude on top of the usual set, or null
     * @return ordered list of Hash objects (ENDPOINT FIRST), or null if no peers are
     * available or checkTunnel fails
     * @since 0.9.71+
     */
    private List<Hash> selectPeersInternal(TunnelPoolSettings settings, int length,
                                          boolean includeCooldowns, Set<Hash> alsoExclude) {
        boolean isInbound = settings.isInbound();
        long now = ctx.clock().now();
        Set<Hash> exclude = getExclude(isInbound, true);
        exclude.add(ctx.routerHash());
        if (alsoExclude != null) {
            exclude.addAll(alsoExclude);
        }
        // Exclude peers on selection cooldown to ensure diversity.
        // Own map: peers that failed checkTunnel here.  Shared map: peers
        // failed by client pools or rejected at tunnel reuse.
        // Read-time filtering: expired entries are ignored, never swept.
        // Sweeping the shared map on every selection races the other
        // selectors (see ClientPeerSelector.buildExclusions); bulk hygiene
        // is done by prunePeerMaps() for the shared map and on record for
        // the own map (see the checkTunnel failure path below).
        if (includeCooldowns) {
            long cooldownCutoff = now - PEER_SELECTION_COOLDOWN_MS;
            int cooldownExcluded = addFreshCooldownExclusions(_exploratoryCooldowns, cooldownCutoff, exclude);
            cooldownExcluded += addFreshCooldownExclusions(_peerCooldowns, cooldownCutoff, exclude);
            if (log.shouldDebug())
                log.debug("EPS cooldown: own=" + _exploratoryCooldowns.size() +
                          " shared=" + _peerCooldowns.size() +
                          " excluded=" + cooldownExcluded +
                          " from=" + Thread.currentThread().getName());
        }

        // Per-pool diversity: exclude peers already in an active tunnel of this pool.
        // No peer should appear in more than 1 tunnel of the same pool.
        TunnelPool pool = isInbound ? ctx.tunnelManager().getInboundExploratoryPool()
                                    : ctx.tunnelManager().getOutboundExploratoryPool();
        Set<Hash> poolPeers = getPeersInPool(ctx, pool);
        exclude.addAll(poolPeers);
        if (log.shouldInfo() && !poolPeers.isEmpty())
            log.info("EPS per-pool exclusion: " + poolPeers.size() + " peers in active tunnels from=" + Thread.currentThread().getName());
        // Cross-pool diversity: when the fast tier is large enough, exclude peers
        // in ANY active tunnel across ALL pools. Forces exploratory builds to use
        // different fast peers than client pools, driving variability and ensuring
        // fast peers accumulate tunnel history.
        // Under stress (< 60% build success), skip cross-pool exclusion —
        // availability matters more than diversity when builds are degrading.
        // Also skip when cross-pool exclusion would consume most of the fast
        // tier: natural pool-local diversity already provides variability when
        // most fast peers are in active tunnels.
        double buildSuccess = ctx.profileOrganizer().getTunnelBuildSuccess();
        int fastCount = ctx.profileOrganizer().getFastPeerCount();
        if (fastCount > ClientPeerSelector.CROSS_POOL_DIVERSITY_THRESHOLD
            && buildSuccess >= ClientPeerSelector.CROSS_POOL_BUILD_SUCCESS_MIN) {
            Set<Hash> allActive = getPeersInAllPools(ctx);
            if (allActive.size() < fastCount * ClientPeerSelector.CROSS_POOL_EXCLUSION_RATIO) {
                exclude.addAll(allActive);
                if (log.shouldInfo())
                    log.info("EPS cross-pool exclusion: " + allActive.size() + " peers in ANY active tunnel from=" + Thread.currentThread().getName());
            } else if (log.shouldDebug()) {
                log.debug("EPS cross-pool exclusion skipped: " + allActive.size() + " of " + fastCount +
                          " fast peers in active tunnels (ratio exceeds " + ClientPeerSelector.CROSS_POOL_EXCLUSION_RATIO + ")");
            }
        }

        // Special cases
        boolean nonzero = length > 0;
        boolean exploreHighCap = nonzero && shouldPickHighCap();
        boolean v6Only = nonzero && isIPv6Only();
        boolean ntcpDisabled = nonzero && isNTCPDisabled();
        boolean ssuDisabled = nonzero && isSSUDisabled();
        // for these cases, check the closest hop up front,
        // otherwise, will be done in checkTunnel() at the end
        boolean checkClosestHop = v6Only || ntcpDisabled || ssuDisabled;
        boolean hidden = nonzero && (ctx.router().isHidden() ||
                                     ctx.router().getRouterInfo().getAddressCount() <= 0 ||
                                     !ctx.commSystem().haveInboundCapacity(95));
        boolean hiddenInbound = hidden && isInbound;
        boolean hiddenOutbound = hidden && !isInbound;
        boolean lowOutbound = nonzero && !isInbound && !ctx.commSystem().haveHighOutboundCapacity();
        int ipRestriction =  (ctx.getBooleanProperty("i2np.allowLocal") || length <= 1) ? 0 : settings.getIPRestriction();
        MaskedIPSet ipSet = ipRestriction > 0 ? new MaskedIPSet(ipRestriction) : null;

        ArrayList<Hash> rv = new ArrayList<>(length + 3);

        // closest-hop restrictions
        // Since we're applying orderPeers() later, we don't know
        // which will be the closest hop, so select the closest one here if necessary.

        Hash closestHop = null;
        if (v6Only || hiddenInbound || lowOutbound) {
            Set<Hash> closestExclude;
            if (checkClosestHop) {
                closestExclude = getClosestHopExclude(isInbound, exclude);
            } else {
                closestExclude = exclude;
            }

            ArraySet<Hash> closest = new ArraySet<>(1);
            if (hiddenInbound || lowOutbound) {
                // If hidden and inbound, use connected peers to guarantee
                // that the adjacent hop can connect to us.
                if (log.shouldInfo()) {
                    log.info("EPS SANFP closest " + (isInbound ? "IB " : "OB ") + closestExclude);
                }
                ctx.profileOrganizer().selectActiveNotFailingPeers(1, closestExclude, closest, ipRestriction, ipSet);
                if (closest.isEmpty()) {
                    // select from all active peers without restriction
                    ctx.profileOrganizer().selectActiveNotFailingPeers(1, closestExclude, closest, 0, null);
                }

                if (closest.isEmpty() && ctx.commSystem().getEstablished().isEmpty()) {
                    if (log.shouldWarn()) {
                        log.warn("Firewalled router with no established connections -> Allowing 0-hop exploratory tunnel...");
                    }
                    return new ArrayList<>(1); // Empty list = 0-hop tunnel
                }
            } else if (exploreHighCap) {
                if (log.shouldInfo())
                    log.info("EPS SHCP closest " + (isInbound ? "IB " : "OB ") + closestExclude);
                ctx.profileOrganizer().selectHighCapacityPeers(1, closestExclude, closest, ipRestriction, ipSet);
            } else {
                if (log.shouldInfo())
                    log.info("EPS SNFP closest " + (isInbound ? "IB " : "OB ") + closestExclude);
                ctx.profileOrganizer().selectNotFailingPeers(1, closestExclude, closest, false, ipRestriction, ipSet, true);
            }
            // D1: Post-selection first-hop quality check for closest hop
            if (!closest.isEmpty() && !isInbound) {
                Hash peer = closest.iterator().next();
                if (isFirstHopFailing(ctx, peer)) {
                    if (log.shouldInfo())
                        log.info("EPS closest hop " + peer.toBase64().substring(0,6) +
                                 " previously failed as first hop, retrying...");
                    closestExclude.add(peer);
                    closest.clear();
                    ctx.profileOrganizer().selectNotFailingPeers(1, closestExclude, closest, false, ipRestriction, ipSet, true);
                    if (closest.isEmpty()) {
                        ctx.profileOrganizer().selectFastPeers(1, closestExclude, closest, ipRestriction, ipSet);
                    }
                } else if (!ctx.commSystem().isEstablished(peer)) {
                    if (ctx.commSystem().wasUnreachable(peer)) {
                        if (log.shouldInfo())
                            log.info("EPS closest hop " + peer.toBase64().substring(0,6) +
                                     " is unreachable, retrying selection...");
                        closestExclude.add(peer);
                        closest.clear();
                        ctx.profileOrganizer().selectNotFailingPeers(1, closestExclude, closest, false, ipRestriction, ipSet, true);
                        if (closest.isEmpty()) {
                            ctx.profileOrganizer().selectFastPeers(1, closestExclude, closest, ipRestriction, ipSet);
                        }
                    } else if (!ctx.commSystem().isConnecting(peer)) {
                        if (log.shouldInfo())
                            log.info("EPS pre-connecting to closest hop " +
                                     peer.toBase64().substring(0,6) + " for tunnel build");
                        preConnectTo(ctx, peer);
                    }
                }
            }
            if (!closest.isEmpty()) {
                closestHop = closest.get(0);
                exclude.add(closestHop);
                length--;
            }
        }

        // furthest-hop restrictions
        // Since we're applying orderPeers() later, we don't know
        // which will be the furthest hop, so select the furthest one here if necessary.

        Hash furthestHop = null;
        if (hiddenOutbound && length > 0) {
            // OBEP
            // check for hidden and outbound, and the paired (inbound) tunnel is zero-hop
            // if so, we need the OBEP to be connected to us, so we get the build reply back
            // This should be rare except at startup
            TunnelManagerFacade tmf = ctx.tunnelManager();
            TunnelPool tp = tmf.getInboundExploratoryPool();
            TunnelPoolSettings tps = tp.getSettings();
            boolean pickFurthest = isZeroHopSettings(tps) || !hasTunnelLongerThanOne(tp);
            if (pickFurthest) {
                ArraySet<Hash> furthest = new ArraySet<>(1);
                if (log.shouldInfo())
                    log.info("EPS SANFP OBEP exclude " + formatExcludedPeers(exclude));
                ctx.profileOrganizer().selectActiveNotFailingPeers(1, exclude, furthest, ipRestriction, ipSet);
                if (furthest.isEmpty()) {
                    // ANFP does not fall back to non-connected
                    if (log.shouldInfo())
                        log.info("EPS SFP OBEP exclude " + formatExcludedPeers(exclude));
                    ctx.profileOrganizer().selectFastPeers(1, exclude, furthest, ipRestriction, ipSet);
                }
                // D1: Post-selection first-hop quality check for furthest hop
                if (!furthest.isEmpty()) {
                    Hash peer = furthest.iterator().next();
                    if (isFirstHopFailing(ctx, peer)) {
                        if (log.shouldInfo())
                            log.info("EPS furthest hop " + peer.toBase64().substring(0,6) +
                                     " previously failed as first hop, retrying...");
                        exclude.add(peer);
                        furthest.clear();
                        ctx.profileOrganizer().selectFastPeers(1, exclude, furthest, ipRestriction, ipSet);
                    } else if (!ctx.commSystem().isEstablished(peer)) {
                        if (ctx.commSystem().wasUnreachable(peer)) {
                            if (log.shouldInfo())
                                log.info("EPS furthest hop " + peer.toBase64().substring(0,6) +
                                         " is unreachable, retrying selection...");
                            exclude.add(peer);
                            furthest.clear();
                            ctx.profileOrganizer().selectFastPeers(1, exclude, furthest, ipRestriction, ipSet);
                        } else if (!ctx.commSystem().isConnecting(peer)) {
                            if (log.shouldInfo())
                                log.info("EPS pre-connecting to furthest hop " +
                                         peer.toBase64().substring(0,6) + " for tunnel build");
                            preConnectTo(ctx, peer);
                        }
                    }
                }
                if (!furthest.isEmpty()) {
                    furthestHop = furthest.get(0);
                    exclude.add(furthestHop);
                    ctx.commSystem().exemptIncoming(furthestHop);
                    length--;
                }
            }
        }

        if (length > 0) {
            Set<Hash> matches = new ArraySet<>(length);
            if (exploreHighCap) {
                if (log.shouldInfo())
                    log.info("EPS SHCP " + length + (isInbound ? " IB " : " OB ") + formatExcludedPeers(exclude));
                ctx.profileOrganizer().selectHighCapacityPeers(length, exclude, matches, ipRestriction, ipSet);
                // Same widening rule as the client pools: when the network is struggling
                // or the Fast tier is small, complete the draw from Fast rather than
                // returning a short selection built from one tier. Shares
                // shouldWidenBothTiers with ClientPeerSelector so the two cannot disagree.
                if (matches.size() < length && ClientPeerSelector.shouldWidenBothTiers(
                        ctx.profileOrganizer().getTunnelBuildSuccess(),
                        ctx.profileOrganizer().getFastPeerCount())) {
                    ctx.profileOrganizer().selectFastPeers(length - matches.size(), exclude,
                                                          matches, ipRestriction, ipSet);
                }
            } else {
                // As of 0.9.23, we include a max of 2 not failing peers,
                // to improve build success on 3-hop tunnels.
                // Peer org credits existing items in matches
                if (length > 2)
                    ctx.profileOrganizer().selectHighCapacityPeers(length - 2, exclude, matches);
                // select will check both matches and exclude, no need to add matches to exclude here
                if (log.shouldInfo())
                    log.info("EPS SNFP " + length + (isInbound ? " IB " : " OB ") + formatExcludedPeers(exclude));
                ctx.profileOrganizer().selectNotFailingPeers(length, exclude, matches, false, ipRestriction, ipSet, true);
                if (matches.isEmpty()) {
                    // Fallback: try fast peers if not-failing is empty
                    ctx.profileOrganizer().selectFastPeers(length, exclude, matches, ipRestriction, ipSet);
                }
            }
            matches.remove(ctx.routerHash());
            rv.addAll(matches);
        }
        if (log.shouldInfo())
            log.info("EPS " + length + (isInbound ? " IB " : " OB ") + "final: " + formatExcludedPeers(exclude));

        if (closestHop != null) {
            if (isInbound)
                rv.add(0, closestHop);
            else
                rv.add(closestHop);
            length++;
        }
        if (furthestHop != null) {
            // always OBEP for now, nothing special for IBGW
            if (isInbound)
                rv.add(furthestHop);
            else
                rv.add(0, furthestHop);
        }
        if (rv.size() > 1)
            orderPeers(rv, settings.getRandomKey());
        // Ghost-peer filtering: peers with consistent tunnel build timeouts
        // waste build attempts and test cycles.  Client pools already filter
        // these (ClientPeerSelector.filterGhostPeers); apply the same here so
        // exploratory builds do not keep hammering the same failing peers.
        //
        // When every peer is a ghost this now excludes them and redraws, which is
        // what the client pools do.  It used to restore the original selection, on
        // the reasoning that "never fail the build entirely" was the worse outcome —
        // but that dispatches the build through the very peers just measured as
        // failing, and it did so deterministically, so the next cycle drew the same
        // ghosts and burned another attempt.  A redraw can also fail; it simply does
        // not do so for a reason we already know.
        TunnelManagerFacade tmf = ctx.tunnelManager();
        GhostPeerManager ghostManager = tmf.getGhostPeerManager();
        if (ghostManager != null && rv.size() > 1) {
            List<Hash> before = new ArrayList<>(rv);
            rv.removeIf(peer -> ghostManager.isGhost(peer));
            if (rv.isEmpty()) {
                // Recorded rather than restored: selectPeers() retries from these.
                _lastAllGhostSelection.set(new HashSet<>(before));
            } else if (rv.size() != before.size() && log.shouldDebug()) {
                log.debug("EPS ghost-filtered " + (before.size() - rv.size()) + " peer(s)");
            }
        }
        // Banlist filter: drop banlisted peers so builds do not dispatch
        // requests that BuildHandler will reject with "Next peer is banned".
        // Mirrors ClientPeerSelector.filterBannedPeers.
        //
        // When every candidate is banlisted the selection is DISCARDED rather
        // than restored: a restored selection dispatches a build the handler is
        // guaranteed to drop, spending a build slot on a peer already known
        // unusable, and the next cycle draws the same banned peers again.
        //
        // Measured reachability: the branch recorded 0 occurrences over roughly
        // two hours of operation, because the candidate pool is never entirely
        // banlisted in practice. It is kept as a cheap guard for the case where
        // the banlist does grow to cover every candidate — an earlier comment
        // here claimed a measured build-capacity saving, which no measurement
        // supported. tunnel.peerSelection.epsAllBannedDiscarded records the
        // frequency so the claim can be re-tested rather than assumed.
        Banlist banlist = ctx != null ? ctx.banlist() : null;
        if (banlist != null && rv.size() > 1) {
            List<Hash> before = new ArrayList<>(rv);
            rv.removeIf(peer -> peer != null && banlist.isBanlisted(peer));
            if (rv.isEmpty()) {
                if (log.shouldWarn()) {
                    log.warn("EPS all selected peers were banlisted -> discarding selection for redraw");
                }
                if (ctx != null && ctx.statManager() != null) {
                    noteAllBannedDiscard(ctx);
                }
                rv.clear();
            } else if (rv.size() != before.size() && log.shouldDebug()) {
                log.debug("EPS ban-filtered " + (before.size() - rv.size()) + " peer(s)");
            }
        }
        if (isInbound)
            rv.add(0, ctx.routerHash());
        else
            rv.add(ctx.routerHash());

        if (rv.size() > 1) {
            if (!checkTunnel(isInbound, true, rv)) {
                // Record only the peer adjacent to us (IBGW for inbound, OBEP
                // for outbound) in the exploratory cooldown so the next
                // selection avoids it.  An address-family mismatch with us is
                // fundamental and won't change between retries, while innocent
                // middle hops must not be penalized for a failure elsewhere in
                // the chain — blanket-cooldown collapses the pool during
                // cascades (mirrors ClientPeerSelector rationale).
                // Only failures are recorded — successful selections never
                // cooldown peers.
                long failNow = ctx.clock().now();
                int adjIdx = isInbound ? 1 : rv.size() - 2;
                int recorded = 0;
                if (adjIdx >= 0 && adjIdx < rv.size()) {
                    Hash adjPeer = rv.get(adjIdx);
                    if (!adjPeer.equals(ctx.routerHash())) {
                        _exploratoryCooldowns.put(adjPeer, failNow);
                        recorded++;
                    }
                }
                if (log.shouldDebug())
                    log.debug("EPS cooldown record: recorded=" + recorded +
                              " rvSize=" + rv.size() +
                              " from=" + Thread.currentThread().getName());
                // Bound the own map: entries expire on read, but sweep only
                // when the map is large (mirrors prunePeerMaps()).
                if (_exploratoryCooldowns.size() > FAILURE_MAP_MAX_SIZE) {
                    long cutoff = failNow - PEER_SELECTION_COOLDOWN_MS;
                    _exploratoryCooldowns.entrySet().removeIf(e -> e.getValue() < cutoff);
                }
                rv = null;
            }
        }
        if (isInbound && rv != null && rv.size() > 1)
            ctx.commSystem().exemptIncoming(rv.get(1));
        return rv;
    }

    private static int getMinNonfailingPct(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.exploratoryPeer.minNonfailingPct", 15);
    }
    private static int getMinActivePeersStartup(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.exploratoryPeer.minActivePeersStartup", 6);
    }
    private static int getMinActivePeers(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.exploratoryPeer.minActivePeers", 12);
    }

    /**
     * Should we pick from the high cap pool instead of the larger not failing pool?
     * This should return false most of the time, but if the not-failing pool's
     * build success rate is much worse, return true so that reliability
     * is maintained.
     * @return whether pick high cap
     */
    private boolean shouldPickHighCap() {
        if (ctx.getBooleanProperty("router.exploreHighCapacity"))
            return true;

        // If we don't have enough connected peers, use exploratory
        // tunnel building to get us better-connected.
        // This is a tradeoff, we could easily lose our exploratory tunnels,
        // but with so few connected peers, anonymity suffers and reliability
        // will decline also, as we repeatedly try to build tunnels
        // through the same few peers.
        int active = ctx.commSystem().countActivePeers();
        if (active < getMinActivePeersStartup(ctx))
            return false;

        // no need to explore too wildly at first (if we have enough connected peers)
        long uptime = ctx.router().getUptime();
        if (uptime <= (SystemVersion.isAndroid() ? 15*60*1000L : 5*60*1000L))
            return true;
        // wait for first expiration of old RIs, if we had a long downtime
        if (uptime <= 61*60*1000L && ctx.router().getEstimatedDowntime() > 3*24*60*60*1000L)
            return true;
        // or at the end
        if (ctx.router().gracefulShutdownInProgress())
            return true;

        // see above
        if (active < getMinActivePeers(ctx))
            return false;

        // ok, if we aren't explicitly asking for it, we should try to pick peers
        // randomly from the 'not failing' pool.  However, if we are having a
        // hard time building exploratory tunnels, lets fall back again on the
        // high capacity peers, at least for a little bit.
        int failPct;
        // getEvents() will be 0 for first 10 minutes
        if (uptime <= 11*60*1000L) {
            failPct = 100 - getMinNonfailingPct(ctx);
        } else {
            // If well connected or ff, don't pick from high cap
            // even during congestion, because congestion starts from the top
            if (active > 500 || ctx.netDb().floodfillEnabled())
                return false;

            failPct = getExploratoryFailPercentage();
            // always try a little, this helps keep the failPct stat accurate too
            if (failPct > 100 - getMinNonfailingPct(ctx))
                failPct = 100 - getMinNonfailingPct(ctx);
        }
        return (failPct >= ctx.random().nextInt(100));
    }

    /**
     * We should really use the difference between the exploratory fail rate
     * and the high capacity fail rate - but we don't have a stat for high cap,
     * so use the fast (== client) fail rate, it should be close
     * if the expl. and client tunnel lengths aren't too different.
     * So calculate the difference between the exploratory fail rate
     * and the client fail rate, normalized to 100:
     * 100 * ((Efail - Cfail) / (100 - Cfail))
     * Even this isn't the "true" rate for the NonFailingPeers pool, since we
     * are often building exploratory tunnels using the HighCapacity pool.
     * @return the exploratory fail percentage
     */
    private int getExploratoryFailPercentage() {
        int c = getFailPercentage("Client");
        int e = getFailPercentage("Exploratory");
        if (e <= c || e <= 25) // doing very well (unlikely)
            return 0;
        // Doing very badly? This is important to prevent network congestion collapse
        if (c >= 70 || e >= 75)
            return 100 - getMinNonfailingPct(ctx);
        return (100 * (e-c)) / (100-c);
    }

    private int getFailPercentage(String t) {
        String pfx = "tunnel.build" + t;
        int timeout = getEvents(pfx + "Expire", 10*60*1000L);
        int reject = getEvents(pfx + "Reject", 10*60*1000L);
        int accept = getEvents(pfx + "Success", 10*60*1000L);
        if (accept + reject + timeout <= 0)
            return 0;
        double pct = (double)(reject + timeout) / (accept + reject + timeout);
        return (int)(100 * pct);
    }

    /** Use current + last to get more recent and smoother data */
    private int getEvents(String stat, long period) {
        RateStat rs = ctx.statManager().getRate(stat);
        if (rs == null)
            return 0;
        Rate r = rs.getRate(period);
        if (r == null)
            return 0;
        return (int) (r.computeAverages().getTotalEventCount());
    }
}
