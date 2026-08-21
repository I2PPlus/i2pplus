package org.klomp.snark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import net.i2p.data.DataHelper;

/**
 * Registry of known BitTorrent client identities.
 *
 * <p>Two things live here:
 * <ul>
 *   <li>The peer ID prefix recognition table mapping the first three raw peer ID
 *       bytes (as the first four Base64 characters of the ID) to a client name.
 *       This is the single source of truth; I2PSnarkServlet delegates to it.</li>
 *   <li>Spoofing profiles for well-known clearnet clients, each bundling a peer
 *       ID prefix, the HTTP tracker User-Agent, and the BEP 10 extension handshake
 *       "v" string, with consistent version numbers across all three surfaces.</li>
 * </ul>
 *
 * <p>Spoofing is opt-in via the i2psnark.clientId property (empty to identify as
 * I2PSnark, "random" to select a random profile per destination per run, or a
 * profile name to always impersonate that client), optionally restricted to a
 * subset via i2psnark.clientIds. Only clients recognizable by our own
 * identification table are spoofable, so the console peers page keeps showing a
 * real client name for our own IDs.
 *
 * <p>Version strings are pinned to upstream client releases verified as of
 * 2026-08; bump them only when an upstream release changes the UA or "v" format,
 * since point releases do not require updates. All three strings of a profile
 * must carry the same version, as trackers may cross-check them.
 *
 * @since 0.9.71+
 */
public final class ClientID {

    private static final String OS_NAME = System.getProperty("os.name", "");
    private static final String JAVA_VERSION = System.getProperty("java.version", "");

    /** Random tail charset for generated peer IDs, per the BiglyBT/Azureus convention. */
    private static final String TAIL_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /**
     * One impersonatable client: peer ID prefix plus the matching tracker
     * User-Agent and extension handshake "v" strings.
     *
     * @since 0.9.71+
     */
    public static final class Profile {
        private final String name;
        private final String peerIdPrefixStr;
        /** Exact leading peer ID bytes, 5-8 long, e.g. "-AZ5770-" or "TIX34". */
        private final byte[] peerIdPrefix;
        /** User-Agent base; ";<OS>;Java <ver>" appended when appendOsJava is set. */
        private final String uaBase;
        private final boolean appendOsJava;
        private final String extV;

        private Profile(String n, String prefix, String ua, boolean osJava, String v) {
            name = n;
            peerIdPrefixStr = prefix;
            peerIdPrefix = DataHelper.getASCII(prefix);
            uaBase = ua;
            appendOsJava = osJava;
            extV = v;
        }

        /** The profile name, also the accepted i2psnark.clientId token. */
        public String getName() {
            return name;
        }

        /**
         * The ASCII peer ID prefix, e.g. "-AZ5770-". Package visible for tests.
         *
         * @return the prefix string
         */
        String getPeerIdPrefix() {
            return peerIdPrefixStr;
        }

        /**
         * A fresh 20-byte peer ID: this profile's prefix followed by random
         * alphanumeric bytes.
         *
         * @param random the randomness source
         * @return a new 20-byte peer ID
         */
        public byte[] buildPeerId(Random random) {
            byte[] rv = new byte[20];
            System.arraycopy(peerIdPrefix, 0, rv, 0, peerIdPrefix.length);
            for (int i = peerIdPrefix.length; i < rv.length; i++) {
                rv[i] = (byte) TAIL_CHARS.charAt(random.nextInt(TAIL_CHARS.length()));
            }
            return rv;
        }

        /**
         * The HTTP User-Agent for tracker announces and webseed fetches.
         * Never null.
         */
        public String getUserAgent() {
            if (!appendOsJava) {
                return uaBase;
            }
            return uaBase + ';' + OS_NAME + ";Java " + JAVA_VERSION;
        }

        /**
         * The BEP 10 extension handshake "v" value. Never null.
         */
        public String getExtHandshakeName() {
            return extV;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    // Version data verified against upstream releases/captures as of 2026-08.
    // Vuze sends brand "Azureus" with OS/Java in the tracker UA but plain
    // "Vuze" in the handshake; the mismatch is authentic.
    public static final Profile VUZE =
            new Profile("Vuze", "-AZ5770-", "Azureus 5.7.7.0", true, "Vuze 5.7.7.0");
    // BiglyBT sends no OS/Java by default (upstream config default off).
    public static final Profile BIGLYBT =
            new Profile("BiglyBT", "-BI4100-", "BiglyBT 4.1.0.0", false, "BiglyBT 4.1.0.0");
    // Transmission uses a slash in the UA but a space in the handshake.
    public static final Profile TRANSMISSION =
            new Profile("Transmission", "-TR4130-", "Transmission/4.1.3", false, "Transmission 4.1.3");
    // Gear-era peer ID digits and handshake string unverified against a capture.
    public static final Profile KTORRENT =
            new Profile("KTorrent", "-KT2604-", "KTorrent/26.04.3", false, "KTorrent 26.04.3");
    // Deluge's libtorrent part should pair with a plausible bundled libtorrent.
    public static final Profile DELUGE =
            new Profile(
                    "Deluge",
                    "-DE2200-",
                    "Deluge/2.2.0 libtorrent/2.0.13",
                    false,
                    "Deluge/2.2.0 libtorrent/2.0.13");
    public static final Profile QBITTORRENT =
            new Profile("qBittorrent", "-qB5230-", "qBittorrent/5.2.3", false, "qBittorrent/5.2.3");
    // Real libtorrent 2.x uses a lowercase -lt prefix we do not recognize, so
    // this profile pins the last fully-authentic 1.2.x identity instead.
    public static final Profile LIBTORRENT =
            new Profile("libtorrent", "-LT1219-", "libtorrent/1.2.19", false, "libtorrent/1.2.19");
    // Tixati peer ID has no dashes; UA and handshake strings unverified.
    public static final Profile TIXATI =
            new Profile("Tixati", "TIX34", "Tixati/3.44", false, "Tixati 3.44");

    private static final List<Profile> PROFILES;

    static {
        List<Profile> l = new ArrayList<Profile>(8);
        l.add(VUZE);
        l.add(BIGLYBT);
        l.add(TRANSMISSION);
        l.add(KTORRENT);
        l.add(DELUGE);
        l.add(QBITTORRENT);
        l.add(LIBTORRENT);
        l.add(TIXATI);
        PROFILES = Collections.unmodifiableList(l);
    }

    /**
     * The client name for a peer ID prefix, from the same table used to
     * identify remote peers in the console.
     *
     * @param ch the first four Base64 characters of the peer ID, i.e. the
     *           Base64 of its first three non-zero bytes
     * @return the client name, or null if unknown
     */
    public static String getClientName(String ch) {
        if ("AwMD".equals(ch)) {return "I2PSnark";}
        else if ("LUFa".equals(ch)) {return "Vuze";}
        else if ("LUJJ".equals(ch)) {return "BiglyBT";}
        else if ("LVhE".equals(ch)) {return "XD";}
        else if (ch.startsWith("LV")) {return "Transmission";}
        else if ("LUtU".equals(ch)) {return "KTorrent";}
        else if ("LUVU".equals(ch)) {return "EepTorrent";}
        else if ("LURF".equals(ch)) {return "Deluge";}
        else if ("LXFC".equals(ch)) {return "qBittorrent";}
        else if ("LUxU".equals(ch)) {return "libtorrent";}
        else if ("VElY".equals(ch)) {return "Tixati";}
        else if ("LUky".equals(ch)) {return "i2pd";}
        else if ("ZV".equals(ch.substring(2, 4)) || "VUZP".equals(ch)) {return "Robert";}
        else if ("CwsL".equals(ch)) {return "I2PSnarkXL";}
        else if ("BFJT".equals(ch)) {return "I2PRufus";}
        else if ("TTMt".equals(ch)) {return "I2P-BT";}
        return null;
    }

    /**
     * All spoofable client profiles, unmodifiable.
     *
     * @return an unmodifiable list of profiles, never null
     */
    public static List<Profile> profiles() {
        return PROFILES;
    }

    /**
     * Look up a profile by name, case-insensitively.
     *
     * @param name the profile name, e.g. "Vuze" or "qbittorrent"
     * @return the profile, or null if unknown
     */
    public static Profile getByName(String name) {
        for (Profile p : PROFILES) {
            if (p.name.equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    /**
     * A random profile, optionally restricted to a candidate subset.
     *
     * @param random the randomness source
     * @param candidates profiles to choose from; null or empty for all
     * @return one of the candidates, never null
     */
    public static Profile getRandomProfile(Random random, List<Profile> candidates) {
        List<Profile> pool =
                (candidates != null && !candidates.isEmpty()) ? candidates : PROFILES;
        return pool.get(random.nextInt(pool.size()));
    }

    /**
     * Parse a comma-separated i2psnark.clientIds value into profiles, dropping
     * whitespace, duplicates, and names that match no known client.
     *
     * @param raw the raw property value, may be null or empty
     * @return the recognized profiles in order, unmodifiable; empty if none
     */
    public static List<Profile> parseCandidateList(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Profile> rv = new LinkedHashSet<Profile>(4);
        for (String tok : raw.split(",")) {
            String name = tok.trim();
            if (name.isEmpty()) {
                continue;
            }
            Profile p = getByName(name);
            if (p != null) {
                rv.add(p);
            }
        }
        return Collections.unmodifiableList(new ArrayList<Profile>(rv));
    }
}
