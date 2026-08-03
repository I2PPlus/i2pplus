package net.i2p.router.web.helpers;

import java.io.Serializable;
import java.text.Collator;
import java.util.Comparator;
import java.util.Locale;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.router.RouterAddress;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.util.HashDistance;
import net.i2p.router.web.Messages;
import net.i2p.util.Translate;

/**
 *  Comparators used when rendering the network database pages.
 *
 *  @since 0.9.71+
 */
final class NetDbComparators {
    private NetDbComparators() {}

    /**
     *  Comparator for LeaseSets, used in the leaseset listing.
     *  Prioritizes published, nicknamed, named, client, and meta leasesets.
     */
    static class LeaseSetComparator implements Comparator<LeaseSet> {
        private final RouterContext _context;

        public LeaseSetComparator(RouterContext ctx) {_context = ctx;}

        @Override
        public int compare(LeaseSet l, LeaseSet r) {
            Hash keyL = l.getHash();
            Hash keyR = r.getHash();
            TunnelPoolSettings inL = _context.tunnelManager().getInboundSettings(keyL);
            TunnelPoolSettings inR = _context.tunnelManager().getInboundSettings(keyR);
            boolean isClientL = !_context.clientNetDb(keyL).toString().contains("Main");
            boolean isClientR = !_context.clientNetDb(keyR).toString().contains("Main");
            boolean isMetaL = l.getType() == DatabaseEntry.KEY_TYPE_META_LS2;
            boolean isMetaR = r.getType() == DatabaseEntry.KEY_TYPE_META_LS2;
            boolean nicknameL = inL != null && inL.getDestinationNickname() != null;
            boolean nicknameR = inR != null && inR.getDestinationNickname() != null;
            boolean nameL =  _context.namingService().reverseLookup(keyL) != null && !isMetaL;
            boolean nameR =  _context.namingService().reverseLookup(keyR) != null && !isMetaR;
            boolean publishedL = _context.clientManager().shouldPublishLeaseSet(keyL) && !isMetaL;
            boolean publishedR = _context.clientManager().shouldPublishLeaseSet(keyR) && !isMetaR;
            boolean localL = _context.clientManager().isLocal(keyL) && !isMetaL;
            boolean localR = _context.clientManager().isLocal(keyR) && !isMetaR;
            if (publishedL && !publishedR) return -1;
            if (publishedR && !publishedL) return 1;
            if (nicknameL && !nicknameR) return -1;
            if (nicknameR && !nicknameL) return 1;
            if (nameL && !nameR) return -1;
            if (nameR && !nameL) return 1;
            if (isClientL && !isClientR) return -1;
            if (isClientR && !isClientL) return 1;
            return keyL.toBase32().compareTo(keyR.toBase32());
        }
    }

    /**
     *  Comparator for LeaseSets sorted by hash distance from local router.
     *  Used in debug/floodfill mode.
     *  @since 0.7.14
     */
    static class LeaseSetRoutingKeyComparator implements Comparator<LeaseSet>, Serializable {
        private static final long serialVersionUID = 1L;
        private final transient Hash _us;

        public LeaseSetRoutingKeyComparator(Hash us) {_us = us;}

        @Override
        public int compare(LeaseSet l, LeaseSet r) {
            return HashDistance.getDistance(_us, l.getRoutingKey()).compareTo(HashDistance.getDistance(_us, r.getRoutingKey()));
        }
    }

    /**
     *  Comparator for countries by translated name.
     */
    static class CountryComparator implements Comparator<String> {
        private final RouterContext _context;
        private final Collator coll;

        public CountryComparator(RouterContext ctx) {
            _context = ctx;
            coll = Collator.getInstance(new Locale(Messages.getLanguage(_context)));
        }

        @Override
        public int compare(String l, String r) {
            return coll.compare(translated(l), translated(r));
        }

        private String translated(String code) {
            return Translate.getString(_context.commSystem().getCountryName(code), _context, Messages.COUNTRY_BUNDLE_NAME);
        }
    }

    /**
     *  Comparator for router addresses by transport then host.
     *  @since 0.9.38
     */
    static class RAComparator implements Comparator<RouterAddress>, Serializable {
        private static final long serialVersionUID = 1L;

        @Override
        public int compare(RouterAddress l, RouterAddress r) {
            int rv = l.getTransportStyle().compareTo(r.getTransportStyle());
            if (rv != 0) {return rv;}
            String lh = l.getHost();
            String rh = r.getHost();
            if (lh == null) {return (rh == null) ? 0 : -1;}
            if (rh == null) {return 1;}
            return lh.compareTo(rh);
        }
    }
}
