package org.klomp.snark.web;

import java.io.File;
import java.io.Serializable;
import java.text.Collator;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.klomp.snark.MetaInfo;
import org.klomp.snark.Snark;
import org.klomp.snark.Storage;
import org.klomp.snark.TorrentDest;

/**
 * Comparators for various columns.
 *
 * @since 0.9.16 from TorrentNameComparator, moved from I2PSnarkservlet
 */
class Sorters {

    /**
     * The outer class only groups the comparators below and holds their static patterns,
     * so an instance carries nothing.
     */
    Sorters() {}

    /** See below. */
    private static final Pattern PATTERN_DE;
    /** Extracts the sort key from an English torrent name. */
    private static final Pattern PATTERN_EN;
    /** Extracts the sort key from a Spanish torrent name. */
    private static final Pattern PATTERN_ES;
    /** Extracts the sort key from a French torrent name. */
    private static final Pattern PATTERN_FR;
    /** Extracts the sort key from an Italian torrent name. */
    private static final Pattern PATTERN_IT;
    /** Extracts the sort key from a Dutch torrent name. */
    private static final Pattern PATTERN_NL;
    /** Extracts the sort key from a Portuguese torrent name. */
    private static final Pattern PATTERN_PT;

    /**
     * Build the comparator that orders the torrent list by a details-page column.
     *
     * <ul>
     *   <li>0, 1: Name
     *   <li>2: Status
     *   <li>3: Peers
     *   <li>4: ETA
     *   <li>5: Size
     *   <li>6: Downloaded
     *   <li>7: Uploaded
     *   <li>8: Down rate
     *   <li>9: Up rate
     *   <li>10: Remaining (needed)
     *   <li>11: Upload ratio
     *   <li>12: File type
     *   <li>13: Pool, then status
     * </ul>
     *
     * @param type the column to sort by, negative to reverse the order, as
     *             listed in the tag table above
     * @param lang the locale used to collate torrent names
     * @param servlet for file type callback only
     * @return a comparator implementing the requested column ordering, falling
     *         back to name ordering for an unlisted column
     */
    public static Comparator<Snark> getComparator(int type, String lang, I2PSnarkServlet servlet) {
        boolean rev = type < 0;
        Comparator<Snark> rv;
        switch (type) {
            case -1:
            case 0:
            case 1:
            default:
                rv = new TorrentNameComparator(lang);
                if (rev) {
                    rv = Collections.reverseOrder(rv);
                }
                break;

            case -2:
            case 2:
                rv = new StatusComparator(rev, lang);
                break;

            case -3:
            case 3:
                rv = new PeersComparator(rev, lang);
                break;

            case -4:
            case 4:
                rv = new ETAComparator(rev, lang);
                break;

            case -5:
            case 5:
                rv = new SizeComparator(rev, lang);
                break;

            case -6:
            case 6:
                rv = new DownloadedComparator(rev, lang);
                break;

            case -7:
            case 7:
                rv = new UploadedComparator(rev, lang);
                break;

            case -8:
            case 8:
                rv = new DownRateComparator(rev, lang);
                break;

            case -9:
            case 9:
                rv = new UpRateComparator(rev, lang);
                break;

            case -10:
            case 10:
                rv = new RemainingComparator(rev, lang);
                break;

            case -11:
            case 11:
                rv = new RatioComparator(rev, lang);
                break;

            case -12:
            case 12:
                rv = new FileTypeComparator(rev, lang, servlet);
                break;

            case -13:
            case 13:
                // Status+pool ordering; only reachable for multi-dest
                // requests because I2PSnarkServlet.getSortedSnarks()
                // clamps these values elsewhere. The "pool" name in
                // normalizeSortParam() must keep mapping to 13.
                rv = new StatusPoolComparator(rev, lang);
                break;
        }
        return rv;
    }

    /**
     * Sort alphabetically in current locale, ignore case, ignore leading articles such as "the" if
     * the pattern is set by setPattern().
     *
     * @since 0.7.14
     */
    private static class TorrentNameComparator implements Comparator<Snark>, Serializable {

        /** Fixed id, the sorter is never serialized across versions. */
        private static final long serialVersionUID = 1L;

        /** Pattern extracting the name's sort key. */
        private final Pattern _p;
        /** Collator ordering the names for the language. */
        private final Collator _c;

        /**
         * Create the comparator for the language.
         *
         * @param lang may be null
         */
        private TorrentNameComparator(String lang) {
            _p = getPattern(lang);
            _c = getCollator(lang);
        }

        public int compare(Snark l, Snark r) {
            return comp(l, r, _p, _c);
        }

        /**
         * Compare two torrents by name.
         *
         * @param l the first torrent
         * @param r the second torrent
         * @param p may be null
         * @param c may be null
         * @return the order of the two torrents
         */
        public static int comp(Snark l, Snark r, Pattern p, Collator c) {
            // put downloads and magnets first
            if (l.getStorage() == null && r.getStorage() != null) {
                return -1;
            }
            if (l.getStorage() != null && r.getStorage() == null) {
                return 1;
            }
            String ls = l.getBaseName();
            String rs = r.getBaseName();
            if (p != null) {
                Matcher m = p.matcher(ls);
                if (m.matches()) {
                    ls = ls.substring(m.group(1).length());
                }
                m = p.matcher(rs);
                if (m.matches()) {
                    rs = rs.substring(m.group(1).length());
                }
            }
            return c.compare(ls, rs);
        }
    }

    /**
     * The collator for the language, case-insensitive at secondary strength
     * (accents distinct, case not), the default locale when null.
     *
     * @param lang may be null
     * @return a collator for that locale, or for the default locale when lang is null
     */
    private static Collator getCollator(String lang) {
        Collator c = lang != null ? Collator.getInstance(Locale.forLanguageTag(lang)) : Collator.getInstance();
        c.setStrength(Collator.SECONDARY);
        return c;
    }

    /** Forward or reverse sort, but the fallback is always forward. */
    private abstract static class Sort implements Comparator<Snark>, Serializable {

        /** Fixed id, the comparator is never serialized across versions. */
        private static final long serialVersionUID = 1L;

        /** True to reverse the order. */
        private final boolean _rev;
        /** Pattern extracting the name's sort key. */
        private final Pattern _p;
        /** Collator ordering the names for the language. */
        private final Collator _c;

        /**
         * Creates a sorter for one direction and language.
         *
         * @param rev true to reverse the order
         * @param lang the language whose collation to use
         */
        public Sort(boolean rev, String lang) {
            _rev = rev;
            _p = getPattern(lang);
            _c = getCollator(lang);
        }

        public int compare(Snark l, Snark r) {
            int rv = compareIt(l, r);
            if (rv != 0) {
                return _rev ? 0 - rv : rv;
            }
            return TorrentNameComparator.comp(l, r, _p, _c);
        }

        /**
         * Compare two torrents on the subclass's column, falling back to name order.
         *
         * @param l the left-hand torrent to compare
         * @param r the right-hand torrent to compare
         * @return negative, zero or positive as l sorts before, with or after r
         */
        protected abstract int compareIt(Snark l, Snark r);

        /**
         * Compares two longs without overflow.
         *
         * @param l the first value
         * @param r the second value
         * @return negative, zero or positive as l is less, equal or greater
         */
        protected static int compLong(long l, long r) {
            if (l < r) {
                return -1;
            }
            if (l > r) {
                return 1;
            }
            return 0;
        }
    }

    /** Sort rank for a magnet-only torrent. */
    private static final int STATUS_MAGNET = 10;
    /** Sort rank for an unknown state. */
    private static final int STATUS_UNKNOWN = 20;
    /** Sort rank while allocating space. */
    private static final int STATUS_ALLOCATION = 30;
    /** Sort rank while checking existing data. */
    private static final int STATUS_CHECKING = 40;
    /** Sort rank while starting up. */
    private static final int STATUS_STARTING = 50;
    /** Sort rank while downloading. */
    private static final int STATUS_DOWNLOADING = 60;
    /** Sort rank for a stalled download. */
    private static final int STATUS_STALLED = 70;
    /** Sort rank with peers but none active. */
    private static final int STATUS_NO_ACTIVE_PEERS = 80;
    /** Sort rank with no peers at all. */
    private static final int STATUS_NO_PEERS = 90;
    /** Sort rank while seeding with takers. */
    private static final int STATUS_SEEDING_ACTIVE = 100;
    /** Sort rank while seeding with no takers. */
    private static final int STATUS_SEEDING_INACTIVE = 110;
    /** Sort rank while seeding without activity. */
    private static final int STATUS_SEEDING_IDLE = 120;
    /** Sort rank offset added for a stopped torrent. */
    private static final int STATUS_STOPPED_OFFSET = 150;

    /** Orders torrents by their download state. */
    private static class StatusComparator extends Sort {

        /** Fixed id, the comparator is never serialized across versions. */
        private static final long serialVersionUID = 1L;

        /**
         * Creates the state comparator.
         *
         * @param rev true to reverse the order
         * @param lang the language whose collation to use
         */
        private StatusComparator(boolean rev, String lang) {
            super(rev, lang);
        }

        public int compareIt(Snark l, Snark r) {
            return comp(l, r);
        }

        /**
         * Compare two torrents by status, with ties broken by swarm size
         * for idle, inactive and magnet states, otherwise by active peer count.
         *
         * @param l the first torrent
         * @param r the second torrent
         * @return the order of the two torrents
         */
        static int comp(Snark l, Snark r) {
            int sl = getStatus(l);
            int sr = getStatus(r);
            int rv = sl - sr;
            if (rv != 0) {
                return rv;
            }
            switch (sl) {
                case STATUS_SEEDING_IDLE:
                case STATUS_SEEDING_INACTIVE:
                case STATUS_MAGNET:
                case STATUS_NO_ACTIVE_PEERS:
                    // first tie break by swarm size
                    return compLong(r.getTrackerSeenPeers(), l.getTrackerSeenPeers());
                default:
                    // tie break by active peer count
                    return compLong(r.getPeerCount(), l.getPeerCount());
            }
        }

        /**
         * Gives the sort rank for a torrent's state.
         *
         * @param snark the torrent to rank
         * @return its state rank
         */
        static int getStatus(Snark snark) {
            boolean isMagnet = snark.getRemainingLength() < 0;
            if (snark.isStopped() && !isMagnet) {
                return STATUS_STOPPED_OFFSET + getStatusImpl(snark);
            } else {
                return getStatusImpl(snark);
            }
        }

        /**
         * Computes the state rank, stopped torrents offset.
         *
         * @param snark the torrent to rank
         * @return its state rank
         */
        private static int getStatusImpl(Snark snark) {
            long remaining = snark.getRemainingLength();
            int activePeers = snark.getPeerCount();
            int peers = snark.getTrackerSeenPeers();
            long downBps = snark.getDownloadRate();
            boolean isMagnet = remaining < 0;
            if (isMagnet) {
                return STATUS_MAGNET;
            }
            if (snark.isStarting()) {
                return STATUS_STARTING;
            }
            if (snark.isAllocating()) {
                return STATUS_ALLOCATION;
            }
            if (snark.isChecking()) {
                return STATUS_CHECKING;
            }
            if (downBps > 0) {
                return STATUS_DOWNLOADING;
            } else if (remaining == 0) {
                if (activePeers > 0) {
                    return STATUS_SEEDING_ACTIVE;
                } else if (peers > 0) {
                    return STATUS_SEEDING_INACTIVE;
                } else {
                    return STATUS_SEEDING_IDLE;
                }
            } else if (snark.getNeededLength() <= 0) {
                return STATUS_UNKNOWN;
            } else if (activePeers > 0) {
                return STATUS_STALLED;
            } else if (peers > 0) {
                return STATUS_NO_ACTIVE_PEERS;
            } else {
                return STATUS_NO_PEERS;
            }
        }
    }

    /**
     * Sort strictly by pool number, then by status; ties fall back to the
     * status tie-breaks. Torrents without a destination (stopped, or
     * multi-dest off) share pool -1 and sort after pool entries in ascending
     * order, first in descending.
     */
    private static class StatusPoolComparator extends Sort {

        /** Fixed id, the comparator is never serialized across versions. */
        private static final long serialVersionUID = 1L;

        /**
         * Creates the state-and-pool comparator.
         *
         * @param rev true to reverse the order
         * @param lang the language whose collation to use
         */
        private StatusPoolComparator(boolean rev, String lang) {
            super(rev, lang);
        }

        public int compareIt(Snark l, Snark r) {
            int rv = compLong(pool(l), pool(r));
            if (rv != 0) {
                return rv;
            }
            return StatusComparator.comp(l, r);
        }

        /**
         * The pool number of a torrent's shared destination, Long.MAX_VALUE when
         * the torrent has no destination.
         *
         * @param snark the torrent whose shared destination is looked up
         * @return the pool number, or Long.MAX_VALUE when there is none
         */
        private static long pool(Snark snark) {
            TorrentDest td = snark.getDest();
            int p = td != null ? td.getPoolNum() : -1;
            if (p < 0) {
                return Long.MAX_VALUE;
            }
            return p;
        }
    }

    /** Orders torrents by peer count. */
    private static class PeersComparator extends Sort {

        /** Fixed id, the comparator is never serialized across versions. */
        private static final long serialVersionUID = 1L;

        /**
         * Creates the peer-count comparator.
         *
         * @param rev true to reverse the order
         * @param lang the language whose collation to use
         */
        public PeersComparator(boolean rev, String lang) {
            super(rev, lang);
        }

        public int compareIt(Snark l, Snark r) {
            return l.getPeerCount() - r.getPeerCount();
        }
    }

    /** Orders torrents by bytes left to download. */
    private static class RemainingComparator extends Sort {

        /** Fixed id, the comparator is never serialized across versions. */
        private static final long serialVersionUID = 1L;

        /**
         * Creates the remaining-bytes comparator.
         *
         * @param rev true to reverse the order
         * @param lang the language whose collation to use
         */
        public RemainingComparator(boolean rev, String lang) {
            super(rev, lang);
        }

        public int compareIt(Snark l, Snark r) {
            return compLong(l.getNeededLength(), r.getNeededLength());
        }
    }

    /** Orders torrents by estimated finish time. */
    private static class ETAComparator extends Sort {

        /** Fixed id, the comparator is never serialized across versions. */
        private static final long serialVersionUID = 1L;

        /**
         * Creates the ETA comparator.
         *
         * @param rev true to reverse the order
         * @param lang the language whose collation to use
         */
        public ETAComparator(boolean rev, String lang) {
            super(rev, lang);
        }

        public int compareIt(Snark l, Snark r) {
            return compLong(
                    eta(l), eta(r)); // TODO For completed torrents, sort by date of completion
        }

        /**
         * Estimates when a download will finish.
         *
         * @param snark the torrent to estimate
         * @return seconds until done, Long.MAX_VALUE if never
         */
        private static long eta(Snark snark) {
            long needed = snark.getNeededLength();
            long remaining = snark.getRemainingLength();
            long upBps = snark.getUploadRate();
            int activePeers = snark.getPeerCount();
            int peers = snark.getTrackerSeenPeers();
            if (snark.isStopped()) {
                if (remaining == 0) {
                    return Long.MAX_VALUE - 9;
                }
                if (remaining < 0) {
                    return Long.MAX_VALUE - 10;
                } // magnet
                else {
                    return Long.MAX_VALUE - 11;
                }
            } else {
                if (remaining < 0) {
                    return Long.MAX_VALUE - 12;
                } // magnet
                else if (remaining == 0) {
                    if (upBps > 0) {
                        return Long.MAX_VALUE - 8;
                    }
                    if (activePeers > 0) {
                        return Long.MAX_VALUE - 7;
                    }
                    if (peers > 0) {
                        return Long.MAX_VALUE - 6;
                    }
                }
                if (needed > 0 && snark.getDownloadRate() <= 0) {
                    if (activePeers <= 0) {
                        return Long.MAX_VALUE - 16;
                    } else {
                        return Long.MAX_VALUE - 17;
                    }
                }
                long total = snark.getTotalLength();
                if (needed > total) {
                    needed = total;
                }
                long downBps = snark.getDownloadRate();
                if (downBps > 0) {
                    return needed / downBps;
                } else {
                    return Long.MAX_VALUE - 19;
                }
            }
        }
    }

    /** Orders torrents by total size. */
    private static class SizeComparator extends Sort {

        /** Fixed id, the comparator is never serialized across versions. */
        private static final long serialVersionUID = 1L;

        /**
         * Creates the size comparator.
         *
         * @param rev true to reverse the order
         * @param lang the language whose collation to use
         */
        public SizeComparator(boolean rev, String lang) {
            super(rev, lang);
        }

        public int compareIt(Snark l, Snark r) {
            return compLong(l.getDataLength(), r.getDataLength());
        }
    }

    /** Orders torrents by bytes downloaded. */
    private static class DownloadedComparator extends Sort {

        /** Fixed id, the comparator is never serialized across versions. */
        private static final long serialVersionUID = 1L;

        /**
         * Creates the downloaded-bytes comparator.
         *
         * @param rev true to reverse the order
         * @param lang the language whose collation to use
         */
        public DownloadedComparator(boolean rev, String lang) {
            super(rev, lang);
        }

        public int compareIt(Snark l, Snark r) {
            long ld = l.getTotalLength() - l.getRemainingLength();
            long rd = r.getTotalLength() - r.getRemainingLength();
            return compLong(ld, rd);
        }
    }

    /** Orders torrents by bytes uploaded. */
    private static class UploadedComparator extends Sort {

        /** Fixed id, the comparator is never serialized across versions. */
        private static final long serialVersionUID = 1L;

        /**
         * Creates the uploaded-bytes comparator.
         *
         * @param rev true to reverse the order
         * @param lang the language whose collation to use
         */
        public UploadedComparator(boolean rev, String lang) {
            super(rev, lang);
        }

        public int compareIt(Snark l, Snark r) {
            return compLong(l.getUploaded(), r.getUploaded());
        }
    }

    /** Orders torrents by download rate. */
    private static class DownRateComparator extends Sort {

        /** Fixed id, the comparator is never serialized across versions. */
        private static final long serialVersionUID = 1L;

        /**
         * Creates the download-rate comparator.
         *
         * @param rev true to reverse the order
         * @param lang the language whose collation to use
         */
        public DownRateComparator(boolean rev, String lang) {
            super(rev, lang);
        }

        public int compareIt(Snark l, Snark r) {
            return compLong(l.getDownloadRate(), r.getDownloadRate());
        }
    }

    /** Orders torrents by upload rate. */
    private static class UpRateComparator extends Sort {

        /** Fixed id, the comparator is never serialized across versions. */
        private static final long serialVersionUID = 1L;

        /**
         * Creates the upload-rate comparator.
         *
         * @param rev true to reverse the order
         * @param lang the language whose collation to use
         */
        public UpRateComparator(boolean rev, String lang) {
            super(rev, lang);
        }

        public int compareIt(Snark l, Snark r) {
            return compLong(l.getUploadRate(), r.getUploadRate());
        }
    }

    /** Orders torrents by upload-to-download ratio. */
    private static class RatioComparator extends Sort {

        /** Fixed id, the comparator is never serialized across versions. */
        private static final long serialVersionUID = 1L;

        /**
         * Creates the ratio comparator.
         *
         * @param rev true to reverse the order
         * @param lang the language whose collation to use
         */
        public RatioComparator(boolean rev, String lang) {
            super(rev, lang);
        }

        public int compareIt(Snark l, Snark r) {
            double lt = l.getDataLength();
            double ld = lt > 0 ? (l.getUploaded() / lt) : 0d;
            double rt = r.getDataLength();
            double rd = rt > 0 ? (r.getUploaded() / rt) : 0d;
            if (ld < rd) {
                return -1;
            }
            if (ld > rd) {
                return 1;
            }
            return 0;
        }
    }

    /** Orders torrents by the type of their main file. */
    private static class FileTypeComparator extends Sort {

        /** Fixed id, the comparator is never serialized across versions. */
        private static final long serialVersionUID = 1L;

        /** Servlet consulted for the torrents' file names. */
        private final I2PSnarkServlet servlet;

        /**
         * Creates the file-type comparator.
         *
         * @param rev true to reverse the order
         * @param lang the language whose collation to use
         * @param servlet the servlet consulted for file names
         */
        public FileTypeComparator(boolean rev, String lang, I2PSnarkServlet servlet) {
            super(rev, lang);
            this.servlet = servlet;
        }

        public int compareIt(Snark l, Snark r) {
            String ls = toName(l);
            String rs = toName(r);
            return ls.compareTo(rs);
        }

        /**
         * Gives the torrent name used for the type comparison.
         *
         * @param snark the torrent to name
         * @return the name compared
         */
        private String toName(Snark snark) {
            MetaInfo meta = snark.getMetaInfo();
            if (meta == null) {
                return "0";
            }
            if (meta.getFiles() != null) {
                return "1";
            }
            return servlet.toIcon(meta.getName()); // arbitrary sort based on icon name
        }
    }

    ////////////// Comparators for details page below

    /** Class to precompute and efficiently sort data on a torrent file entry. */
    public static class FileAndIndex {
        /** The file or directory this entry describes. */
        public final File file;
        /** True if the entry is a directory rather than a file held in storage. */
        public final boolean isDirectory;
        /** Size in bytes, or 0 for a directory. */
        public final long length;
        /** Bytes still needed for this file, or -1 when it is not in storage. */
        public final long remaining;
        /** Bytes of the file already in the preview, or 0 when none was computed. */
        public final long preview;
        /** Storage priority of the file, or -999 to sort an unstored entry last. */
        public final int priority;
        /** Index of the file in storage, or -1 when it was not found. */
        public final int index;
        /**
         * The length the torrent declares for the file, in bytes, or -1 when
         * no declared lengths were supplied. Unlike {@link #length} this does
         * not depend on what is on disk, so it is the total a progress bar and
         * a size label should use: a file staged in the temp directory is
         * shorter than this, and a file that has not been created yet reports 0
         * for {@link File#length()}.
         */
        public final long declaredLength;

        /**
         * Create a file/index entry.
         *
         * @param file the file or directory the entry describes
         * @param storage may be null
         * @param remainingArray precomputed, non-null iff storage is non-null
         */
        public FileAndIndex(File file, Storage storage, long[] remainingArray) {
            this(file, storage, remainingArray, null, null);
        }

        /**
         * Create a file/index entry with preview lengths.
         *
         * @param file the file or directory the entry describes
         * @param storage may be null
         * @param remainingArray precomputed, non-null iff storage is non-null
         * @param previewArray precomputed preview lengths indexed the same way,
         *                     or null when no previews have been computed
         */
        public FileAndIndex(
                File file, Storage storage, long[] remainingArray, long[] previewArray) {
            this(file, storage, remainingArray, previewArray, null);
        }

        /**
         * Create a file/index entry resolving the storage index from a
         * precomputed map; pass {@link Storage#indexMap()} when building a
         * whole listing so the per-file indexOf() scan does not make the
         * listing quadratic.
         *
         * @param file the file or directory the entry describes
         * @param storage may be null
         * @param remainingArray precomputed, non-null iff storage is non-null
         * @param previewArray precomputed preview lengths, or null
         * @param indexMap from {@link Storage#indexMap()}, or null to fall
         *                 back to the linear indexOf() scan
         * @since 0.9.72+
         */
        public FileAndIndex(
                File file, Storage storage, long[] remainingArray, long[] previewArray,
                Map<String, Integer> indexMap) {
            this(file, storage, remainingArray, previewArray, indexMap, null);
        }

        /**
         * Create a file/index entry resolving the storage index from a
         * precomputed map and the declared length from a precomputed array.
         *
         * @param file the file or directory the entry describes
         * @param storage may be null
         * @param remainingArray precomputed, non-null iff storage is non-null
         * @param previewArray precomputed preview lengths, or null
         * @param indexMap from {@link Storage#indexMap()}, or null to fall
         *                 back to the linear indexOf() scan
         * @param lengthsArray from {@link Storage#getFileLengths()}, index-aligned
         *                     with the other arrays, or null to leave
         *                     {@link #declaredLength} unresolved
         * @since 0.9.72+
         */
        public FileAndIndex(
                File file, Storage storage, long[] remainingArray, long[] previewArray,
                Map<String, Integer> indexMap, long[] lengthsArray) {
            this.file = file;
            if (storage == null) {
                index = -1;
            } else if (indexMap != null) {
                Integer ix = indexMap.get(file.getAbsolutePath());
                index = ix != null ? ix.intValue() : -1;
            } else {
                index = storage.indexOf(file);
            }
            if (index >= 0) {
                isDirectory = false;
                remaining = remainingArray[index];
                preview = previewArray != null ? previewArray[index] : 0;
                priority = storage.getPriority(index);
                declaredLength = lengthsArray != null ? lengthsArray[index] : -1;
            } else {
                isDirectory = file.isDirectory();
                remaining = -1;
                preview = 0;
                priority = -999;
                declaredLength = -1;
            }
            length = isDirectory ? 0 : file.length();
        }
    }

    /**
     * Build the comparator that orders a torrent's file entries by a details-page column.
     *
     * <ul>
     *   <li>0, 1: Name
     *   <li>5: Size
     *   <li>10: Remaining (needed)
     *   <li>12: File type
     *   <li>13: Priority
     * </ul>
     *
     * @param type the column to sort by, negative to reverse the order, as
     *             listed in the tag table above
     * @param servlet for file type callback only
     * @return a comparator implementing the requested column ordering, falling
     *         back to name ordering for an unlisted column
     */
    public static Comparator<FileAndIndex> getFileComparator(int type, I2PSnarkServlet servlet) {
        boolean rev = type < 0;
        Comparator<FileAndIndex> rv;

        switch (type) {
            case -1:
            case 0:
            case 1:
            default:
                rv = new FileNameComparator();
                if (rev) rv = Collections.reverseOrder(rv);
                break;

            case -5:
            case 5:
                rv = new FAISizeComparator(rev);
                break;

            case -10:
            case 10:
                rv = new FAIRemainingComparator(rev);
                break;

            case -12:
            case 12:
                rv = new FAITypeComparator(rev, servlet);
                break;

            case -13:
            case 13:
                rv = new FAIPriorityComparator(rev);
                break;
        }
        return rv;
    }

    /**
     * Sort alphabetically in current locale, ignore case, directories first.
     *
     * @since 0.9.6 moved from {@link I2PSnarkServlet} in 0.9.16
     */
    private static class FileNameComparator implements Comparator<FileAndIndex>, Serializable {

        /** Orders a torrent's files by name. */
        private FileNameComparator() {}
        /** Fixed id, the comparator is never serialized across versions. */
        private static final long serialVersionUID = 1L;

        /**
         * Collator is not thread-safe and request threads sort concurrently,
         * so each thread keeps its own cached instance.
         */
        private static final ThreadLocal<Collator> _c = ThreadLocal.withInitial(() -> {
            Collator c = Collator.getInstance();
            c.setStrength(Collator.SECONDARY);
            return c;
        });

        public int compare(FileAndIndex l, FileAndIndex r) {
            return comp(l, r);
        }

        /**
         * Compares two files of a torrent.
         *
         * @param l the first file
         * @param r the second file
         * @return negative, zero or positive as l sorts before, with or after r
         */
        public static int comp(FileAndIndex l, FileAndIndex r) {
            boolean ld = l.isDirectory;
            boolean rd = r.isDirectory;
            if (ld && !rd) {
                return -1;
            }
            if (rd && !ld) {
                return 1;
            }
            return _c.get().compare(l.file.getName(), r.file.getName());
        }
    }

    /** Forward or reverse sort, but the fallback is always forward. */
    private abstract static class FAISort implements Comparator<FileAndIndex>, Serializable {

        /** Fixed id, the sorter is never serialized across versions. */
        private static final long serialVersionUID = 1L;

        /** True to reverse the order. */
        private final boolean _rev;

        /**
         * Creates a file sorter for one direction.
         *
         * @param rev true to reverse the order
         */
        public FAISort(boolean rev) {
            _rev = rev;
        }

        public int compare(FileAndIndex l, FileAndIndex r) {
            int rv = compareIt(l, r);
            if (rv != 0) {
                return _rev ? 0 - rv : rv;
            }
            return FileNameComparator.comp(l, r);
        }

        /**
         * Compare two file/index entries.
         *
         * @param l the first entry
         * @param r the second entry
         * @return the order of the two entries
         */
        protected abstract int compareIt(FileAndIndex l, FileAndIndex r);

        /**
         * Compares two longs without overflow.
         *
         * @param l the first value
         * @param r the second value
         * @return negative, zero or positive as l is less, equal or greater
         */
        protected static int compLong(long l, long r) {
            if (l < r) {
                return -1;
            }
            if (l > r) {
                return 1;
            }
            return 0;
        }
    }

    /** Orders files by bytes left to download. */
    private static class FAIRemainingComparator extends FAISort {

        /** Fixed id, the comparator is never serialized across versions. */
        private static final long serialVersionUID = 1L;

        /**
         * Creates the file remaining-bytes comparator.
         *
         * @param rev true to reverse the order
         */
        public FAIRemainingComparator(boolean rev) {
            super(rev);
        }

        public int compareIt(FileAndIndex l, FileAndIndex r) {
            return compLong(l.remaining, r.remaining);
        }
    }

    /** Orders files by size. */
    private static class FAISizeComparator extends FAISort {

        /** Fixed id, the comparator is never serialized across versions. */
        private static final long serialVersionUID = 1L;

        /**
         * Creates the file size comparator.
         *
         * @param rev true to reverse the order
         */
        public FAISizeComparator(boolean rev) {
            super(rev);
        }

        public int compareIt(FileAndIndex l, FileAndIndex r) {
            return compLong(l.length, r.length);
        }
    }

    /** Orders files by type. */
    private static class FAITypeComparator extends FAISort {

        /** Fixed id, the comparator is never serialized across versions. */
        private static final long serialVersionUID = 1L;

        /** Servlet consulted for the files' names. */
        private final I2PSnarkServlet servlet;

        /**
         * Creates the file type comparator.
         *
         * @param rev true to reverse the order
         * @param servlet the servlet consulted for file names
         */
        public FAITypeComparator(boolean rev, I2PSnarkServlet servlet) {
            super(rev);
            this.servlet = servlet;
        }

        public int compareIt(FileAndIndex l, FileAndIndex r) {
            String ls = toName(l);
            String rs = toName(r);
            return ls.compareTo(rs);
        }

        /**
         * Gives the file name used for the type comparison.
         *
         * @param fai the file to name
         * @return the name compared
         */
        private String toName(FileAndIndex fai) {
            if (fai.isDirectory) return "0";
            // arbitrary sort based on icon name
            return servlet.toIcon(fai.file.getName());
        }
    }

    /** Orders files by download priority. */
    private static class FAIPriorityComparator extends FAISort {

        /** Fixed id, the comparator is never serialized across versions. */
        private static final long serialVersionUID = 1L;

        /**
         * Creates the file priority comparator.
         *
         * @param rev true to reverse the order
         */
        public FAIPriorityComparator(boolean rev) {
            super(rev);
        }

        /** Highest first. */
        public int compareIt(FileAndIndex l, FileAndIndex r) {
            return r.priority - l.priority;
        }
    }

    /*
     *  Match an indefinite or definite article in the language, followed by one or more spaces, '.', or '_'.
     *  Does not match "partitive" articles.
     *
     *  https://en.wikipedia.org/wiki/Article_%28grammar%29
     *  http://www.loc.gov/marc/bibliographic/bdapndxf.html
     */
    static {
        PATTERN_DE =
                Pattern.compile(
                        "^((der|die|das|des|dem|den|ein|eine|einer|eines|einem|einen)[\\s\\._]+).*",
                        Pattern.CASE_INSENSITIVE);
        PATTERN_EN = Pattern.compile("^((a|an|the)[\\s\\._]+).*", Pattern.CASE_INSENSITIVE);
        PATTERN_ES =
                Pattern.compile(
                        "^((el|la|lo|los|las|un|una|unos|unas)[\\s\\._]+).*",
                        Pattern.CASE_INSENSITIVE);
        PATTERN_FR =
                Pattern.compile(
                        "^(l'|((le|la|les|un|une|des)[\\s\\._]+)).*", Pattern.CASE_INSENSITIVE);
        PATTERN_IT =
                Pattern.compile(
                        "^(l'|un'|((il|lo|la|i|gli|le|uno|una|un)[\\s\\._]+)).*",
                        Pattern.CASE_INSENSITIVE);
        PATTERN_NL =
                Pattern.compile(
                        "^((de|het|het'n|een|een'n)[\\s\\._]+).*", Pattern.CASE_INSENSITIVE);
        PATTERN_PT =
                Pattern.compile(
                        "^((o|a|os|as|um|uma|uns|umas)[\\s\\._]+).*", Pattern.CASE_INSENSITIVE);
    }

    /**
     * The translated value, null for none.
     *
     * @param lang null for none
     * @return null for none
     * @since 0.9.23
     */
    private static Pattern getPattern(String lang) {
        Pattern p;
        if (lang == null) {
            p = null;
        } else if (lang.equals("de")) {
            p = PATTERN_DE;
        } else if (lang.equals("en")) {
            p = PATTERN_EN;
        } else if (lang.equals("es")) {
            p = PATTERN_ES;
        } else if (lang.equals("fr")) {
            p = PATTERN_FR;
        } else if (lang.equals("it")) {
            p = PATTERN_IT;
        } else if (lang.equals("nl")) {
            p = PATTERN_NL;
        } else if (lang.equals("pt")) {
            p = PATTERN_PT;
        } else {
            p = null;
        }
        return p;
    }
}
