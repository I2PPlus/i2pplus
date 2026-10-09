package net.i2p.router.web.helpers;

import java.io.Serializable;
import java.util.Collections;
import java.util.Comparator;
import net.i2p.I2PAppContext;
import net.i2p.router.transport.udp.PeerState;

/**
 * Comparators for various columns
 *
 * @since 0.9.31 moved from udp; 0.9.18 moved from UDPTransport
 */
class UDPSorters {

    /** Instances are never needed; this is a holder for the flags and comparators. */
    UDPSorters() {}

    /** Sort by peer hash */
    static final int FLAG_ALPHA = 0;
    /** Sort by inbound idle time */
    static final int FLAG_IDLE_IN = 1;
    /** Sort by outbound idle time */
    static final int FLAG_IDLE_OUT = 2;
    /** Sort by inbound rate */
    static final int FLAG_RATE_IN = 3;
    /** Sort by outbound rate */
    static final int FLAG_RATE_OUT = 4;
    /** Sort by clock skew */
    static final int FLAG_SKEW = 5;
    /** Sort by congestion window */
    static final int FLAG_CWND= 6;
    /** Sort by slow start threshold */
    static final int FLAG_SSTHRESH = 7;
    /** Sort by RTT */
    static final int FLAG_RTT = 8;
    /** Sort by RTO */
    static final int FLAG_RTO = 10;
    /** Sort by MTU */
    static final int FLAG_MTU = 11;
    /** Sort by sent message count */
    static final int FLAG_SEND = 12;
    /** Sort by received message count */
    static final int FLAG_RECV = 13;
    /** Sort by retransmitted packets */
    static final int FLAG_RESEND = 14;
    /** Sort by duplicate packets */
    static final int FLAG_DUP = 15;
    /** Sort by uptime */
    static final int FLAG_UPTIME = 16;

    /**
     * Select the comparator for a column, reversing it for a descending sort.
     * @param sortFlags  one of the FLAG_ columns in this class; the magnitude
     * picks the comparator and the sign sets the direction
     * @return comparator ordering PeerState by that column, or descending if
     * sortFlags is negative; FLAG_ALPHA is the fallback
     */
    static Comparator<PeerState> getComparator(int sortFlags) {
        Comparator<PeerState> rv;
        switch (Math.abs(sortFlags)) {
            case FLAG_IDLE_IN:
                rv = new IdleInComparator();
                break;
            case FLAG_IDLE_OUT:
                rv = new IdleOutComparator();
                break;
            case FLAG_RATE_IN:
                rv = new RateInComparator();
                break;
            case FLAG_RATE_OUT:
                rv = new RateOutComparator();
                break;
            case FLAG_UPTIME:
                rv = new UptimeComparator();
                break;
            case FLAG_SKEW:
                rv = new SkewComparator();
                break;
            case FLAG_CWND:
                rv = new CwndComparator();
                break;
            case FLAG_SSTHRESH:
                rv = new SsthreshComparator();
                break;
            case FLAG_RTT:
                rv = new RTTComparator();
                break;
            //case FLAG_DEV:
            //    break;
            case FLAG_RTO:
                rv = new RTOComparator();
                break;
            case FLAG_MTU:
                rv = new MTUComparator();
                break;
            case FLAG_SEND:
                rv = new SendCountComparator();
                break;
            case FLAG_RECV:
                rv = new RecvCountComparator();
                break;
            case FLAG_RESEND:
                rv = new ResendComparator();
                break;
            case FLAG_DUP:
                rv = new DupComparator();
                break;
            case FLAG_ALPHA:
            default:
                rv = new AlphaComparator();
                break;
        }
        if (sortFlags < 0) {rv = Collections.reverseOrder(rv);}
        return rv;
    }

    /**
     * Comparator for sorting UDP peers by peer hash in ascending order.
     * @since 0.9.33
     */
    static class AlphaComparator extends PeerComparator {
        /** Default constructor; no state beyond the superclass. */
        AlphaComparator() {}
    }

    /**
     * Comparator for sorting UDP peers by inbound idle time in ascending order.
     * @since 0.9.33
     */
    static class IdleInComparator extends PeerComparator {
        /** Default constructor; no state beyond the superclass. */
        IdleInComparator() {}

        /**
         * compare.
         */
        @Override
        public int compare(PeerState l, PeerState r) {
            long rv = r.getLastReceiveTime() - l.getLastReceiveTime();
            if (rv == 0) {return super.compare(l, r);} // fallback on alpha
            else {return (int)rv;}
        }
    }

    /**
     * Comparator for sorting UDP peers by outbound idle time in ascending order.
     * @since 0.9.33
     */
    static class IdleOutComparator extends PeerComparator {
        /** Default constructor; no state beyond the superclass. */
        IdleOutComparator() {}

        /**
         * compare.
         */
        @Override
        public int compare(PeerState l, PeerState r) {
            long rv = r.getLastSendTime() - l.getLastSendTime();
            if (rv == 0) {return super.compare(l, r);} // fallback on alpha
            else {return (int)rv;}
        }
    }

    /**
     * Comparator for sorting UDP peers by inbound rate in ascending order.
     * @since 0.9.33
     */
    static class RateInComparator extends PeerComparator {
        private final long now = I2PAppContext.getGlobalContext().clock().now();

        /** Default constructor; snapshots the clock for the bps comparison. */
        RateInComparator() {}

        /**
         * compare.
         */
        @Override
        public int compare(PeerState l, PeerState r) {
            int rv = l.getReceiveBps(now) - r.getReceiveBps(now);
            if (rv == 0) {return super.compare(l, r);} // fallback on alpha
            else {return rv;}
        }
    }

    /**
     * Comparator for sorting UDP peers by outbound rate in ascending order.
     * @since 0.9.33
     */
    static class RateOutComparator extends PeerComparator {
        private final long now = I2PAppContext.getGlobalContext().clock().now();

        /** Default constructor; snapshots the clock for the bps comparison. */
        RateOutComparator() {}

        /**
         * compare.
         */
        @Override
        public int compare(PeerState l, PeerState r) {
            int rv = l.getSendBps(now) - r.getSendBps(now);
            if (rv == 0) {return super.compare(l, r);} // fallback on alpha
            else {return rv;}
        }
    }

    /**
     * Comparator for sorting UDP peers by uptime in ascending order.
     * @since 0.9.33
     */
    static class UptimeComparator extends PeerComparator {
        /** Default constructor; no state beyond the superclass. */
        UptimeComparator() {}

        /**
         * compare.
         */
        @Override
        public int compare(PeerState l, PeerState r) {
            long rv = r.getKeyEstablishedTime() - l.getKeyEstablishedTime();
            if (rv == 0) {return super.compare(l, r);} // fallback on alpha
            else {return (int)rv;}
        }
    }

    /**
     * Comparator for sorting UDP peers by clock skew in ascending order.
     * @since 0.9.33
     */
    static class SkewComparator extends PeerComparator {
        /** Default constructor; no state beyond the superclass. */
        SkewComparator() {}

        /**
         * compare.
         */
        @Override
        public int compare(PeerState l, PeerState r) {
            long rv = l.getClockSkew() - r.getClockSkew();
            if (rv == 0) {return super.compare(l, r);} // fallback on alpha
            else {return (int)rv;}
        }
    }

    /**
     * Comparator for sorting UDP peers by congestion window in ascending order.
     * @since 0.9.33
     */
    static class CwndComparator extends PeerComparator {
        /** Default constructor; no state beyond the superclass. */
        CwndComparator() {}

        /**
         * compare.
         */
        @Override
        public int compare(PeerState l, PeerState r) {
            int rv = l.getSendWindowBytes() - r.getSendWindowBytes();
            if (rv == 0) {return super.compare(l, r);} // fallback on alpha
            else {return rv;}
        }
    }

    /**
     * Comparator for sorting UDP peers by slow start threshold in ascending order.
     * @since 0.9.33
     */
    static class SsthreshComparator extends PeerComparator {
        /** Default constructor; no state beyond the superclass. */
        SsthreshComparator() {}

        /**
         * compare.
         */
        @Override
        public int compare(PeerState l, PeerState r) {
            int rv = l.getSlowStartThreshold() - r.getSlowStartThreshold();
            if (rv == 0) {return super.compare(l, r);} // fallback on alpha
            else {return rv;}
        }
    }

    /**
     * Comparator for sorting UDP peers by round trip time in ascending order.
     * @since 0.9.33
     */
    static class RTTComparator extends PeerComparator {
        /** Default constructor; no state beyond the superclass. */
        RTTComparator() {}

        /**
         * compare.
         */
        @Override
        public int compare(PeerState l, PeerState r) {
            int rv = l.getRTT() - r.getRTT();
            if (rv == 0) {return super.compare(l, r);} // fallback on alpha
            else {return rv;}
        }
    }

    /**
     * Comparator for sorting UDP peers by retransmission timeout in ascending order.
     * @since 0.9.33
     */
    static class RTOComparator extends PeerComparator {
        /** Default constructor; no state beyond the superclass. */
        RTOComparator() {}

        /**
         * compare.
         */
        @Override
        public int compare(PeerState l, PeerState r) {
            int rv = l.getRTO() - r.getRTO();
            if (rv == 0) {return super.compare(l, r);} // fallback on alpha
            else {return rv;}
        }
    }

    /**
     * Comparator for sorting UDP peers by maximum transmission unit in ascending order.
     * @since 0.9.33
     */
    static class MTUComparator extends PeerComparator {
        /** Default constructor; no state beyond the superclass. */
        MTUComparator() {}

        /**
         * compare.
         */
        @Override
        public int compare(PeerState l, PeerState r) {
            int rv = l.getMTU() - r.getMTU();
            if (rv == 0) {
                rv = l.getReceiveMTU() - r.getReceiveMTU();
                if (rv == 0) {return super.compare(l, r);} // fallback on alpha
            }
            return rv;
        }
    }

    /**
     * Comparator for sorting UDP peers by sent messages count in ascending order.
     * @since 0.9.33
     */
    static class SendCountComparator extends PeerComparator {
        /** Default constructor; no state beyond the superclass. */
        SendCountComparator() {}

        /**
         * compare.
         */
        @Override
        public int compare(PeerState l, PeerState r) {
            long rv = l.getMessagesSent() - r.getMessagesSent();
            if (rv == 0) {return super.compare(l, r);} // fallback on alpha
            else {return (int)rv;}
        }
    }

    /**
     * Comparator for sorting UDP peers by received messages count in ascending order.
     * @since 0.9.33
     */
    static class RecvCountComparator extends PeerComparator {
        /** Default constructor; no state beyond the superclass. */
        RecvCountComparator() {}

        /**
         * compare.
         */
        @Override
        public int compare(PeerState l, PeerState r) {
            long rv = l.getMessagesReceived() - r.getMessagesReceived();
            if (rv == 0) {return super.compare(l, r);} // fallback on alpha
            else {return (int)rv;}
        }
    }

    /**
     * Comparator for sorting UDP peers by retransmitted packets in ascending order.
     * @since 0.9.33
     */
    static class ResendComparator extends PeerComparator {
        /** Default constructor; no state beyond the superclass. */
        ResendComparator() {}

        /**
         * compare.
         */
        @Override
        public int compare(PeerState l, PeerState r) {
            long rv = l.getPacketsRetransmitted() - r.getPacketsRetransmitted();
            if (rv == 0) {return super.compare(l, r);} // fallback on alpha
            else {return (int)rv;}
        }
    }

    /**
     * Comparator for sorting UDP peers by duplicate packets received in ascending order.
     * @since 0.9.33
     */
    static class DupComparator extends PeerComparator {
        /** Default constructor; no state beyond the superclass. */
        DupComparator() {}

        /**
         * compare.
         */
        @Override
        public int compare(PeerState l, PeerState r) {
            long rv = l.getPacketsReceivedDuplicate() - r.getPacketsReceivedDuplicate();
            if (rv == 0) {return super.compare(l, r);} // fallback on alpha
            else {return (int)rv;}
        }
    }

    /**
     * Base comparator for UDP peers that falls back to peer hash comparison.
     * @since 0.9.33
     */
    static class PeerComparator implements Comparator<PeerState>, Serializable {
        /** Default constructor; the comparison reads the peers and holds no state. */
        PeerComparator() {}

        /**
         * compare.
         */
        @Override
        public int compare(PeerState l, PeerState r) {
            return HashComparator.comp(l.getRemotePeer(), r.getRemotePeer());
        }
    }

    /**
     * Append the pair of sort arrows for one column, marking the live direction
     * of a column that is currently sorted on.
     * @param buf  buffer receiving the spans and anchors
     * @param urlBase  page path the links point at, without a query string;
     * this appends the "?transport=ssu&amp;sort=N" part
     * @param sortFlags  column and direction currently in effect, as passed to
     * {@link #getComparator}
     * @param descr  localized link title, used for both arrows
     * @param ascending  column this control toggles; FLAG_ALPHA (0) emits a
     * single descending link instead of a toggle
     */
    static void appendSortLinks(StringBuilder buf, String urlBase, int sortFlags, String descr, int ascending) {
        if (ascending == FLAG_ALPHA) { // 0
            buf.append("<span class=\"sortdown\"><a href=\"").append(urlBase).append("?transport=ssu&amp;sort=0\" title=\"")
               .append(descr).append("\"><img src=/themes/console/images/inbound.svg alt=\"V\"></a></span>");
        } else if (sortFlags == ascending) {
            buf.append(" <span class=\"sortdown\"><a href=\"").append(urlBase).append("?transport=ssu&amp;sort=").append(0-ascending)
               .append("\" title=\"").append(descr).append("\"><img src=/themes/console/images/inbound.svg alt=\"V\"></a></span>")
               .append("<span class=\"sortupactive\"><b><img src=/themes/console/images/outbound.svg alt=\"^\"></b></span>");
        } else if (sortFlags == 0 - ascending) {
            buf.append(" <span class=\"sortdownactive\"><b><img src=/themes/console/images/inbound.svg alt=\"V\"></b></span>")
               .append("<span class=\"sortup\"><a href=\"").append(urlBase).append("?transport=ssu&amp;sort=").append(ascending)
               .append("\" title=\"").append(descr).append("\"><img src=/themes/console/images/outbound.svg alt=\"^\"></a></span>");
        } else {
            buf.append(" <span class=\"sortdown\"><a href=\"").append(urlBase).append("?transport=ssu&amp;sort=").append(0-ascending)
               .append("\" title=\"").append(descr).append("\"><img src=/themes/console/images/inbound.svg alt=\"V\"></a></span>")
               .append("<span class=\"sortup\"><a href=\"").append(urlBase).append("?transport=ssu&amp;sort=").append(ascending)
               .append("\" title=\"").append(descr).append("\"><img src=/themes/console/images/outbound.svg alt=\"^\"></a></span>");
        }
    }

}
