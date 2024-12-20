package org.klomp.snark.web;

import java.io.File;
import java.io.Serializable;
import java.text.Collator;
import java.util.Collections;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.klomp.snark.MetaInfo;
import org.klomp.snark.Snark;
import org.klomp.snark.Storage;

/**
 *  Comparators for various columns
 *
 *  @since 0.9.16 from TorrentNameComparator, moved from I2PSnarkservlet
 */
class Sorters {

    /**
     * See below
     */
    private static final Pattern PATTERN_DE, PATTERN_EN, PATTERN_ES, PATTERN_FR,
                                 PATTERN_IT, PATTERN_NL, PATTERN_PT;

    /**
     *  Negative is reverse
     *
     *<ul>
     *<li>0, 1: Name
     *<li>2: Status
     *<li>3: Peers
     *<li>4: ETA
     *<li>5: Size
     *<li>6: Downloaded
     *<li>7: Uploaded
     *<li>8: Down rate
     *<li>9: Up rate
     *<li>10: Remaining (needed)
     *<li>11: Upload ratio
     *<li>12: File type
     *</ul>
     *
     *  @param servlet for file type callback only
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
              if (rev) {rv = Collections.reverseOrder(rv);}
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

        }
        return rv;
    }


    /**
     *  Sort alphabetically in current locale, ignore case, ignore leading
     *  articles such as "the" if the pattern is set by setPattern()
     *  @since 0.7.14
     */
    private static class TorrentNameComparator implements Comparator<Snark>, Serializable {

        private final Pattern _p;
        private static final Collator _c = Collator.getInstance();

        /** @param lang may be null */
        private TorrentNameComparator(String lang) {_p = getPattern(lang);}

        public int compare(Snark l, Snark r) {return comp(l, r, _p);}

        /** @param p may be null */
        public static int comp(Snark l, Snark r, Pattern p) {
            // put downloads and magnets first
            if (l.getStorage() == null && r.getStorage() != null) {return -1;}
            if (l.getStorage() != null && r.getStorage() == null) {return 1;}
            String ls = l.getBaseName();
            String rs = r.getBaseName();
            if (p != null) {
                Matcher m = p.matcher(ls);
                if (m.matches()) {ls = ls.substring(m.group(1).length());}
                m = p.matcher(rs);
                if (m.matches()) {rs = rs.substring(m.group(1).length());}
            }
            return _c.compare(ls, rs);
        }
    }

    /**
     *  Forward or reverse sort, but the fallback is always forward
     */
    private static abstract class Sort implements Comparator<Snark>, Serializable {

        private final boolean _rev;
        private final Pattern _p;

        public Sort(boolean rev, String lang) {
            _rev = rev;
            _p = getPattern(lang);
        }

        public int compare(Snark l, Snark r) {
            int rv = compareIt(l, r);
            if (rv != 0) {return _rev ? 0 - rv : rv;}
            return TorrentNameComparator.comp(l, r, _p);
        }

        protected abstract int compareIt(Snark l, Snark r);

        protected static int compLong(long l, long r) {
            if (l < r) {return -1;}
            if (l > r) {return 1;}
            return 0;
        }
    }

    private static final int STATUS_MAGNET = 10;
    private static final int STATUS_UNKNOWN = 20;
    private static final int STATUS_ALLOCATION = 30;
    private static final int STATUS_CHECKING = 40;
    private static final int STATUS_STARTING = 50;
    private static final int STATUS_DOWNLOADING = 60;
    private static final int STATUS_STALLED = 70;
    private static final int STATUS_NO_ACTIVE_PEERS = 80;
    private static final int STATUS_NO_PEERS = 90;
    private static final int STATUS_SEEDING_ACTIVE = 100;
    private static final int STATUS_SEEDING_INACTIVE = 110;
    private static final int STATUS_SEEDING_IDLE = 120;
    private static final int STATUS_STOPPED_OFFSET = 150;

    private static class StatusComparator extends Sort {

        private StatusComparator(boolean rev, String lang) {super(rev, lang);}

        public int compareIt(Snark l, Snark r) {
            int rv = getStatus(l) - getStatus(r);
            if (rv != 0) {return rv;}
            else if ((getStatus(l) == STATUS_SEEDING_IDLE && getStatus(r) == STATUS_SEEDING_IDLE)||
                     (getStatus(l) == STATUS_SEEDING_INACTIVE && getStatus(r) == STATUS_SEEDING_INACTIVE) ||
                     (getStatus(l) == STATUS_MAGNET && getStatus(r) == STATUS_MAGNET) ||
                     (getStatus(l) == STATUS_NO_ACTIVE_PEERS && getStatus(r) == STATUS_NO_ACTIVE_PEERS)) {
                return compLong(r.getTrackerSeenPeers(), l.getTrackerSeenPeers()); // first tie break by swarm size
            } else {return compLong(r.getPeerCount(), l.getPeerCount());} // tie break by active peer count
        }

        private static int getStatus(Snark snark) {
            boolean isMagnet = snark.getRemainingLength() < 0;
            if (snark.isStopped() && !isMagnet) {return STATUS_STOPPED_OFFSET + getStatusImpl(snark);}
            else {return getStatusImpl(snark);}
        }

        private static int getStatusImpl(Snark snark) {
            long remaining = snark.getRemainingLength();
            int activePeers = snark.getPeerCount();
            int peers = snark.getTrackerSeenPeers();
            long downBps = snark.getDownloadRate();
            boolean isMagnet = remaining < 0;
            if (snark.isStarting()) {return STATUS_STARTING;}
            if (snark.isAllocating()) {return STATUS_ALLOCATION;}
            if (snark.isChecking()) {return STATUS_CHECKING;}
            if (downBps > 0) {return STATUS_DOWNLOADING;}
            else if (downBps <= 0) {return STATUS_STALLED;}
            if (isMagnet) {return STATUS_MAGNET;}
            else if (remaining == 0) {
                if (activePeers > 0) {return STATUS_SEEDING_ACTIVE;}
                else if (peers > 0) {return STATUS_SEEDING_INACTIVE;}
                else {return STATUS_SEEDING_IDLE;}
            }
            if (snark.getNeededLength() <= 0) {return STATUS_UNKNOWN;}
            if (peers <= 0) {return STATUS_NO_PEERS;}
            if (activePeers <= 0) {return STATUS_NO_ACTIVE_PEERS;}
            else {return STATUS_UNKNOWN;}
        }
    }

    private static class PeersComparator extends Sort {

        public PeersComparator(boolean rev, String lang) { super(rev, lang); }

        public int compareIt(Snark l, Snark r) {
            return l.getPeerCount() - r.getPeerCount();
        }
    }

    private static class RemainingComparator extends Sort {

        public RemainingComparator(boolean rev, String lang) {super(rev, lang);}

        public int compareIt(Snark l, Snark r) {
            return compLong(l.getNeededLength(), r.getNeededLength());
        }
    }

    private static class ETAComparator extends Sort {

        public ETAComparator(boolean rev, String lang) {super(rev, lang);}

        public int compareIt(Snark l, Snark r) {
            return compLong(eta(l), eta(r)); // TODO For completed torrents, sort by date of completion
        }

        private static long eta(Snark snark) {
            long needed = snark.getNeededLength();
            long remaining = snark.getRemainingLength();
            long upBps = snark.getUploadRate();
            int activePeers = snark.getPeerCount();
            int peers = snark.getTrackerSeenPeers();
            if (snark.isStopped()) {
                if (remaining == 0) {return Long.MAX_VALUE - 9;}
                if (remaining < 0) {return Long.MAX_VALUE - 10;} // magnet
                else {return Long.MAX_VALUE - 11;}
            } else {
                if (remaining < 0) {return Long.MAX_VALUE - 12;} // magnet
                else if (remaining == 0) {
                    if (upBps > 0) {return Long.MAX_VALUE - 8;}
                    if (activePeers > 0) {return Long.MAX_VALUE - 7;}
                    if (peers > 0) {return Long.MAX_VALUE - 6;}
                }
                if (needed > 0 && snark.getDownloadRate() <= 0) {
                    if (activePeers <= 0) {return Long.MAX_VALUE - 16;}
                    else {return Long.MAX_VALUE - 17;}
                }
                long total = snark.getTotalLength();
                if (needed > total) {needed = total;}
                long downBps = snark.getDownloadRate();
                if (downBps > 0) {return needed / downBps;}
                else {return Long.MAX_VALUE - 19;}
            }
        }
    }

    private static class SizeComparator extends Sort {

        public SizeComparator(boolean rev, String lang) { super(rev, lang); }

        public int compareIt(Snark l, Snark r) {
            return compLong(l.getTotalLength(), r.getTotalLength());
        }
    }

    private static class DownloadedComparator extends Sort {

        public DownloadedComparator(boolean rev, String lang) { super(rev, lang); }

        public int compareIt(Snark l, Snark r) {
            long ld = l.getTotalLength() - l.getRemainingLength();
            long rd = r.getTotalLength() - r.getRemainingLength();
            return compLong(ld, rd);
        }
    }

    private static class UploadedComparator extends Sort {

        public UploadedComparator(boolean rev, String lang) { super(rev, lang); }

        public int compareIt(Snark l, Snark r) {
            return compLong(l.getUploaded(), r.getUploaded());
        }
    }

    private static class DownRateComparator extends Sort {

        public DownRateComparator(boolean rev, String lang) { super(rev, lang); }

        public int compareIt(Snark l, Snark r) {
            return compLong(l.getDownloadRate(), r.getDownloadRate());
        }
    }

    private static class UpRateComparator extends Sort {

        public UpRateComparator(boolean rev, String lang) { super(rev, lang); }

        public int compareIt(Snark l, Snark r) {
            return compLong(l.getUploadRate(), r.getUploadRate());
        }
    }

    private static class RatioComparator extends Sort {

        public RatioComparator(boolean rev, String lang) { super(rev, lang); }

        public int compareIt(Snark l, Snark r) {
            double lt = l.getTotalLength();
            double ld = lt > 0 ? (l.getUploaded() / lt) : 0d;
            double rt = r.getTotalLength();
            double rd = rt > 0 ? (r.getUploaded() / rt) : 0d;
            if (ld < rd) {return -1;}
            if (ld > rd) {return 1;}
            return 0;
        }
    }

    private static class FileTypeComparator extends Sort {

        private final I2PSnarkServlet servlet;

        public FileTypeComparator(boolean rev, String lang, I2PSnarkServlet servlet) {
            super(rev, lang);
            this.servlet = servlet;
        }

        public int compareIt(Snark l, Snark r) {
            String ls = toName(l);
            String rs = toName(r);
            return ls.compareTo(rs);
        }

        private String toName(Snark snark) {
            MetaInfo meta = snark.getMetaInfo();
            if (meta == null) {return "0";}
            if (meta.getFiles() != null) {return "1";}
            return servlet.toIcon(meta.getName()); // arbitrary sort based on icon name
        }
    }

    ////////////// Comparators for details page below

    /**
     *  Class to precompute and efficiently sort data on a torrent file entry.
     */
    public static class FileAndIndex {
        public final File file;
        public final boolean isDirectory;
        public final long length;
        public final long remaining;
        public final long preview;
        public final int priority;
        public final int index;

        /**
         *  @param storage may be null
         *  @param remainingArray precomputed, non-null iff storage is non-null
         */
        public FileAndIndex(File file, Storage storage, long[] remainingArray) {
            this(file, storage, remainingArray, null);
        }

        /**
         *  @param storage may be null
         *  @param remainingArray precomputed, non-null iff storage is non-null
         */
        public FileAndIndex(File file, Storage storage, long[] remainingArray, long[] previewArray) {
            this.file = file;
            index = storage != null ? storage.indexOf(file) : -1;
            if (index >= 0) {
                isDirectory = false;
                remaining = remainingArray[index];
                preview = previewArray != null ? previewArray[index] : 0;
                priority = storage.getPriority(index);
            } else {
                isDirectory = file.isDirectory();
                remaining = -1;
                preview = 0;
                priority = -999;
            }
            length = isDirectory ? 0 : file.length();
        }
    }


    /**
     *  Negative is reverse
     *
     *<ul>
     *<li>0, 1: Name
     *<li>5: Size
     *<li>10: Remaining (needed)
     *<li>12: File type
     *<li>13: Priority
     *</ul>
     *
     *  @param servlet for file type callback only
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
              if (rev)
                  rv = Collections.reverseOrder(rv);
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
     *  Sort alphabetically in current locale, ignore case, directories first
     *  @since 0.9.6 moved from I2PSnarkServlet in 0.9.16
     */
    private static class FileNameComparator implements Comparator<FileAndIndex>, Serializable {

        public int compare(FileAndIndex l, FileAndIndex r) {return comp(l, r);}

        public static int comp(FileAndIndex l, FileAndIndex r) {
            boolean ld = l.isDirectory;
            boolean rd = r.isDirectory;
            if (ld && !rd) {return -1;}
            if (rd && !ld) {return 1;}
            return Collator.getInstance().compare(l.file.getName(), r.file.getName());
        }
    }

    /**
     *  Forward or reverse sort, but the fallback is always forward
     */
    private static abstract class FAISort implements Comparator<FileAndIndex>, Serializable {

        private final boolean _rev;

        public FAISort(boolean rev) {_rev = rev;}

        public int compare(FileAndIndex l, FileAndIndex r) {
            int rv = compareIt(l, r);
            if (rv != 0) {return _rev ? 0 - rv : rv;}
            return FileNameComparator.comp(l, r);
        }

        protected abstract int compareIt(FileAndIndex l, FileAndIndex r);

        protected static int compLong(long l, long r) {
            if (l < r) {return -1;}
            if (l > r) {return 1;}
            return 0;
        }
    }

    private static class FAIRemainingComparator extends FAISort {

        public FAIRemainingComparator(boolean rev) { super(rev); }

        public int compareIt(FileAndIndex l, FileAndIndex r) {
            return compLong(l.remaining, r.remaining);
        }
    }

    private static class FAISizeComparator extends FAISort {

        public FAISizeComparator(boolean rev) { super(rev); }

        public int compareIt(FileAndIndex l, FileAndIndex r) {
            return compLong(l.length, r.length);
        }
    }

    private static class FAITypeComparator extends FAISort {

        private final I2PSnarkServlet servlet;

        public FAITypeComparator(boolean rev, I2PSnarkServlet servlet) {
            super(rev);
            this.servlet = servlet;
        }

        public int compareIt(FileAndIndex l, FileAndIndex r) {
            String ls = toName(l);
            String rs = toName(r);
            return ls.compareTo(rs);
        }

        private String toName(FileAndIndex fai) {
            if (fai.isDirectory)
                return "0";
            // arbitrary sort based on icon name
            return servlet.toIcon(fai.file.getName());
        }
    }

    private static class FAIPriorityComparator extends FAISort {

        public FAIPriorityComparator(boolean rev) { super(rev); }

        /** highest first */
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
        PATTERN_DE = Pattern.compile("^((der|die|das|des|dem|den|ein|eine|einer|eines|einem|einen)[\\s\\._]+).*", Pattern.CASE_INSENSITIVE);
        PATTERN_EN = Pattern.compile("^((a|an|the)[\\s\\._]+).*", Pattern.CASE_INSENSITIVE);
        PATTERN_ES = Pattern.compile("^((el|la|lo|los|las|un|una|unos|unas)[\\s\\._]+).*", Pattern.CASE_INSENSITIVE);
        PATTERN_FR = Pattern.compile("^(l'|((le|la|les|un|une|des)[\\s\\._]+)).*", Pattern.CASE_INSENSITIVE);
        PATTERN_IT = Pattern.compile("^(l'|un'|((il|lo|la|i|gli|le|uno|una|un)[\\s\\._]+)).*", Pattern.CASE_INSENSITIVE);
        PATTERN_NL = Pattern.compile("^((de|het|het'n|een|een'n)[\\s\\._]+).*", Pattern.CASE_INSENSITIVE);
        PATTERN_PT = Pattern.compile("^((o|a|os|as|um|uma|uns|umas)[\\s\\._]+).*", Pattern.CASE_INSENSITIVE);
    }

    /**
     * @param lang null for none
     * @return null for none
     * @since 0.9.23
     */
    private static Pattern getPattern(String lang) {
        Pattern p;
        if (lang == null) {p = null;}
        else if (lang.equals("de")) {p = PATTERN_DE;}
        else if (lang.equals("en")) {p = PATTERN_EN;}
        else if (lang.equals("es")) {p = PATTERN_ES;}
        else if (lang.equals("fr")) {p = PATTERN_FR;}
        else if (lang.equals("it")) {p = PATTERN_IT;}
        else if (lang.equals("nl")) {p = PATTERN_NL;}
        else if (lang.equals("pt")) {p = PATTERN_PT;}
        else {p = null;}
        return p;
    }

}