package net.i2p.router.tunnel.pool;

import java.util.Locale;

/**
 *  Attributes an expired build to the hop position that failed to answer.
 *
 *  <p>Why this exists: every expired build was logged with per-hop state, but
 *  only once per minute under a global throttle, so roughly 1 in 19,000 expiries
 *  was visible. Sample counts that thin cannot answer "which hop position fails?"
 *  — 18 samples named 47 distinct peers. This class turns the same per-hop state
 *  into rate stats on every expiry, which is the only way to see the distribution
 *  rather than an anecdote.
 *
 *  <p>The first observation on this router was that Hop1 (the first *remote* hop;
 *  Hop0 is ourselves) accounted for the most silent hops, which is what makes the
 *  first-hop failure cooldown the most consequential tuning parameter.
 *
 *  <p>Pure and static so the stat-name derivation is unit-testable without a
 *  router; the caller supplies the already-computed classification from
 *  {@link BuildExecutor#classifyExpiredHop}.
 *
 *  @since 0.9.71+
 */
public class BuildHopAttribution {

    private BuildHopAttribution() {}

    /**
     *  Build the stat name for one hop position and classification.
     *
     *  <p>Shape: {@code tunnel.buildExpired.Hop1.establishedSilent}. Lower-cased
     *  and stripped of non-alphanumerics so a classification containing a space or
     *  comma still yields one legal stat name — those come from
     *  {@link BuildExecutor#HOP_ESTABLISHED_SILENT} and friends, and a stat name
     *  containing a space would not register.
     *
     *  @param hopIndex zero-based position in the build path; 0 is us
     *  @param classification token from {@link BuildExecutor#classifyExpiredHop}
     *  @return a stat name safe to pass to {@code addRateData}
     *  @since 0.9.71+
     */
    public static String statName(int hopIndex, String classification) {
        StringBuilder buf = new StringBuilder(40);
        buf.append("tunnel.buildExpired.Hop").append(hopIndex).append('.');
        if (classification != null) {
            for (int i = 0; i < classification.length(); i++) {
                char c = classification.charAt(i);
                if (Character.isLetterOrDigit(c)) {
                    buf.append(Character.toLowerCase(c));
                }
            }
        }
        return buf.toString();
    }

    /**
     *  Whether this hop position is worth counting as a peer failure.
     *
     *  <p>Hop0 is this router and several classifications are expected states
     *  rather than failures — {@code selfExpected} on the gateway, and an
     *  unassigned hop that simply was not needed. Counting those would make the
     *  distribution say "hop 0 fails most", which is meaningless.
     *
     *  @param hopIndex zero-based position in the build path
     *  @param classification token from {@link BuildExecutor#classifyExpiredHop}
     *  @return true if this hop should be attributed as a failure
     *  @since 0.9.71+
     */
    public static boolean isPeerFailure(int hopIndex, String classification) {
        if (hopIndex <= 0 || classification == null) {return false;}
        return !BuildExecutor.HOP_SELF_EXPECTED.equals(classification)
               && !BuildExecutor.HOP_NEVER_ASSIGNED.equals(classification);
    }

    /**
     *  Whether a classification means the peer never answered at all.
     *
     *  <p>This is the distinction that matters for the build path: an established
     *  peer that stays silent is a different problem from a peer that was never
     *  reachable, and they warrant opposite responses. Confirmed 18/18 and then
     *  3/3 silent on two deployments, so the split is worth measuring rather than
     *  assuming.
     *
     *  @param classification token from {@link BuildExecutor#classifyExpiredHop}
     *  @return true if the hop was established but produced no reply
     *  @since 0.9.71+
     */
    public static boolean isEstablishedSilent(String classification) {
        return BuildExecutor.HOP_ESTABLISHED_SILENT.equals(classification);
    }

    /**
     *  Lower-case a classification for display or comparison.
     *
     *  @param classification token from {@link BuildExecutor#classifyExpiredHop}
     *  @return the token lower-cased, or the empty string when null
     *  @since 0.9.71+
     */
    public static String normalise(String classification) {
        return classification == null ? "" : classification.toLowerCase(Locale.ROOT);
    }
}
