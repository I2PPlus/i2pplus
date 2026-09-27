package net.i2p.router.tunnel.pool;

import java.util.Properties;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.router.RouterContext;
import net.i2p.router.tunnel.TunnelCreatorConfig;

/**
 *  Data about a tunnel we created
 */
public class PooledTunnelCreatorConfig extends TunnelCreatorConfig {
    private final TunnelPool _pool;
    // we don't store the config, that leads to OOM
    private TunnelId _pairedGW;
    private volatile long _lastActivity;
    private volatile boolean _lastResort;
    private volatile boolean _bypassPacing;
    private static final long ACTIVITY_TIMEOUT = 30*1000L;

    /**
     *  Creates a new instance of PooledTunnelCreatorConfig
     *
     *  @param destination may be null
     *  @param pool non-null
     */
    public PooledTunnelCreatorConfig(RouterContext ctx, int length, boolean isInbound, Hash destination, TunnelPool pool) {
        super(ctx, length, isInbound, destination);
        _pool = pool;
        _lastActivity = System.currentTimeMillis();
        String nickname = pool.getSettings().getDestinationNickname();
        if (nickname != null) {
            setDestinationNickname(nickname);
        }
    }

    /** Called from TestJob. */
    public void testJobSuccessful(int ms) {testSuccessful(ms);}

    /**
     * The tunnel failed a test, so (maybe) stop using it
     */
    @Override
    public boolean tunnelFailed() {
        boolean rv = super.tunnelFailed();
        if (!rv) {
            // remove us from the pool (but not the dispatcher) so that we aren't
            // selected again.  _expireJob is left to do its thing, in case there
            // are any straggling messages coming down the tunnel
            // Todo: Maybe delay or prevent failing if we are near tunnel build capacity,
            // to prevent collapse (loss of all tunnels)
            _pool.tunnelFailed(this);
        }
        return rv;
    }

    /**
     * We failed to contact the first hop for an outbound tunnel,
     * so immediately stop using it.
     * For outbound non-zero-hop tunnels only.
     *
     * Does not blame any peer: first-hop send failures are local congestion,
     * not a dead peer.  Proportional blame still accumulated ~50 points per
     * failure per hop — 8 consecutive failures banlisted the first hop and
     * collapsed the pool.
     *
     * @since 0.9.53
     */
    @Override
    public void tunnelFailedFirstHop() {
        if (isInbound() || getLength() <= 1) {return;}
        super.tunnelFailedFirstHop();
        _pool.tunnelFailedWithoutBlame(this);
    }

    /**
     * Re-warm the session to the first hop of an outbound tunnel whose send
     * failures are approaching the fatal threshold.  A direct transport send
     * forces the connection up again, so the next tunnel message is not
     * queued behind a stale session.
     *
     * Gated by {@link #shouldPreConnect(int)}, so the DatabaseLookupMessage
     * round-trip only ever reaches a hop the router has not yet given up on.
     *
     * @param streak the current first-hop failure streak
     * @since 0.9.71+
     */
    @Override
    public void firstHopSendFailureStreak(int streak) {
        if (isInbound() || getLength() <= 1) {return;}
        if (!shouldPreConnect(streak)) {return;}
        TunnelPeerSelector.preConnectTo(_context, getPeer(1));
    }

    /**
     *  Whether a first-hop send-failure streak warrants a pre-connect re-warm.
     *
     *  Fires only on the strike immediately below
     *  {@link #FIRST_HOP_FAILURE_THRESHOLD} — the last point at which the
     *  tunnel is still alive to benefit.  Earlier strikes have not yet
     *  committed to blaming the peer, so a round-trip would only add load to a
     *  path that may merely be congested; the threshold strike itself retires
     *  the tunnel as soon as the hook returns, so re-warming there would send
     *  a lookup to a hop the router has already decided to blame.
     *
     *  @param streak the current first-hop failure streak
     *  @return true if the first-hop session should be re-warmed
     *  @since 0.9.71+
     */
    static boolean shouldPreConnect(int streak) {
        return streak == FIRST_HOP_FAILURE_THRESHOLD - 1;
    }

    /**
     *  @return non-null
     */
    @Override
    public Properties getOptions() {return _pool.getSettings().getUnknownOptions();}

    /**
     *  @return non-null
     */
    public TunnelPool getTunnelPool() {return _pool;}

    /**
     *  @return true if this tunnel was built as a last-resort fallback
     *  @since 0.9.69+
     */
    public boolean isLastResort() {return _lastResort;}

    /**
     *  Mark this tunnel as a last-resort fallback.
     *  @since 0.9.69+
     */
    public void setLastResort() {_lastResort = true;}

    /**
     *  @return true if this build bypasses the per-peer in-flight guard in
     *  {@link BuildExecutor#buildTunnel(net.i2p.router.tunnel.pool.PooledTunnelCreatorConfig)}
     *  @since 0.9.71+
     */
    public boolean isBypassPacing() {return _bypassPacing;}

    /**
     *  Mark this build as emergency recovery: it must be sent even if the
     *  first-hop peer already has a build in flight.  Set by the pool when
     *  it has zero usable tunnels and cannot wait for the guard to clear.
     *  @since 0.9.71+
     */
    public void setBypassPacing() {_bypassPacing = true;}

    /**
     *  Record activity on this tunnel (message processed).
     *  @since 0.9.69+
     */
    public void recordActivity() {_lastActivity = System.currentTimeMillis();}

    /**
     *  @return timestamp of last activity, or 0 if never used
     *  @since 0.9.69+
     */
    public long getLastActivity() {return _lastActivity;}

    /**
     *  @return true if this tunnel has been recently active (within ACTIVITY_TIMEOUT)
     *  @since 0.9.69+
     */
    public boolean isRecentlyActive() {
        return System.currentTimeMillis() - _lastActivity < ACTIVITY_TIMEOUT;
    }

    /**
     *  The ID of the gateway of the paired tunnel used to send/receive the build request
     *
     *  @param gw for paired inbound, the GW rcv tunnel ID; for paired outbound, the GW send tunnel ID.
     *  @since 0.9.53
     */
    public void setPairedGW(TunnelId gw) {_pairedGW = gw;}

    /**
     *  The ID of the gateway of the paired tunnel used to send/receive the build request
     *
     *  @return for paired inbound, the GW rcv tunnel ID; for paired outbound, the GW send tunnel ID.
     *          null if not previously set
     *  @since 0.9.53
     */
    public TunnelId getPairedGW() {return _pairedGW;}

}
