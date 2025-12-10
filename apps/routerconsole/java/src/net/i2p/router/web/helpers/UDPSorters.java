package net.i2p.router.web.helpers;

import java.io.Serializable;
import java.util.Collections;
import java.util.Comparator;
import net.i2p.I2PAppContext;
import net.i2p.router.transport.udp.PeerState;

/**
 *  Comparators for various columns
 *
 *  @since 0.9.31 moved from udp; 0.9.18 moved from UDPTransport
 */
class UDPSorters {

    static final int FLAG_ALPHA = 0;
    static final int FLAG_IDLE_IN = 1;
    static final int FLAG_IDLE_OUT = 2;
    static final int FLAG_RATE_IN = 3;
    static final int FLAG_RATE_OUT = 4;
    static final int FLAG_SKEW = 5;
    static final int FLAG_CWND= 6;
    static final int FLAG_SSTHRESH = 7;
    static final int FLAG_RTT = 8;
    // static final int FLAG_DEV = 9;
    static final int FLAG_RTO = 10;
    static final int FLAG_MTU = 11;
    static final int FLAG_SEND = 12;
    static final int FLAG_RECV = 13;
    static final int FLAG_RESEND = 14;
    static final int FLAG_DUP = 15;
    static final int FLAG_UPTIME = 16;
    static final int FLAG_DEBUG = 99;

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
            //    rv = new DevComparator();
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
    static class AlphaComparator extends PeerComparator {}

    /**
     * Comparator for sorting UDP peers by inbound idle time in ascending order.
     * @since 0.9.33
     */
    /**
     * Comparator for sorting UDP peers by inbound idle time in ascending order.
     * @since 0.9.33
     */
    static class IdleInComparator extends PeerComparator {
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
    /**
     * Comparator for sorting UDP peers by outbound idle time in ascending order.
     * @since 0.9.33
     */
    static class IdleOutComparator extends PeerComparator {
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
    /**
     * Comparator for sorting UDP peers by inbound rate in ascending order.
     * @since 0.9.33
     */
    static class RateInComparator extends PeerComparator {
        private final long now = I2PAppContext.getGlobalContext().clock().now();

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
    /**
     * Comparator for sorting UDP peers by outbound rate in ascending order.
     * @since 0.9.33
     */
    static class RateOutComparator extends PeerComparator {
        private final long now = I2PAppContext.getGlobalContext().clock().now();

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
        public int compare(PeerState l, PeerState r) {
            return HashComparator.comp(l.getRemotePeer(), r.getRemotePeer());
        }
    }

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
