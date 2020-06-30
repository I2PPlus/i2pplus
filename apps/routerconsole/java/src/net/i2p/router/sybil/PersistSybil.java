package net.i2p.router.sybil;

import java.io.BufferedReader;
import java.util.Collections;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.router.web.helpers.SybilRenderer;
import net.i2p.util.Log;
import net.i2p.util.FileSuffixFilter;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFileOutputStream;

/**
 *  Store and retrieve analysis files from disk.
 *  Each file is named with a timestamp.
 *
 *  @since 0.9.38
 */
public class PersistSybil {

    private final I2PAppContext _context;
    private final Log _log;

    private static final String DIR = "sybil-analysis/results";
    private static final String PFX = "sybil-";
    private static final String SFX = ".txt.gz";

    /** access via Analysis.getPersister() */
    PersistSybil(I2PAppContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(PersistSybil.class);
    }

    /**
     *  Store each entry.
     *
     *  @param entries each one should be "entry" at the root
     */
    public synchronized void store(long date, Map<Hash, Points> entries) throws IOException {
        File dir = new SecureDirectory(_context.getConfigDir(), DIR);
        if (!dir.exists())
            dir.mkdirs();
        File file = new File(dir, PFX + date + SFX);
        StringBuilder buf = new StringBuilder(128);
        Writer out = null;
        try {
            out = new OutputStreamWriter(new GZIPOutputStream(new SecureFileOutputStream(file)));
            out.write("# Format (one per line)\n");
            out.write("# Base64 router hash:total points%points:reason%points:reason ...\n");
            for (Map.Entry<Hash, Points> entry : entries.entrySet()) {
                Hash h = entry.getKey();
                Points p = entry.getValue();
                buf.append(h.toBase64()).append(':');
                p.toString(buf);
                buf.append('\n');
                out.write(buf.toString());
                buf.setLength(0);
            }
        } finally {
            if (out != null) try { out.close(); } catch (IOException ioe) {}
        }
    }

    /**
     *  The list of stored analysis sets, as a time stamp.
     *
     *  @return non-null, sorted by updated date, newest first
     */
    public synchronized List<Long> load() {
        File dir = new File(_context.getConfigDir(), DIR);
        List<Long> rv = new ArrayList<Long>();
        File[] files = dir.listFiles(new FileSuffixFilter(PFX, SFX));
        if (files == null)
            return rv;
        for (File file : files) {
            try {
                String name = file.getName();
                long d = Long.parseLong(name.substring(PFX.length(), name.length() - SFX.length())); 
                rv.add(Long.valueOf(d));
            } catch (NumberFormatException nfe) {}
        }
        Collections.sort(rv, Collections.reverseOrder());
        return rv;
    }

    /**
     *  Load the analysis for a certain date.
     *
     *  @return non-null, unsorted
     */
    public synchronized Map<Hash, Points> load(long date) throws IOException {
        File dir = new File(_context.getConfigDir(), DIR);
        File file = new File(dir, PFX + date + SFX);
        Map<Hash, Points> rv = new HashMap<Hash, Points>();
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(file))));
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("#"))
                    continue;
                int colon = line.indexOf(':');
                if (colon != 44)
                    continue;
                if (line.length() < 46)
                    continue;
                Hash h = new Hash();
                try {
                    h.fromBase64(line.substring(0, 44));
                } catch (DataFormatException dfe) {
                    continue;
                }
                Points p = Points.fromString(line.substring(45));
                if (p == null)
                    continue;
                rv.put(h, p);
            }
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
        return rv;
    }

    /**
     *  Load all the analysis for a certain hash.
     *
     *  @return non-null, unsorted
     */
    public synchronized Map<Long, Points> load(Hash h) throws IOException {
        String bh = h.toBase64() + ':';
        File dir = new File(_context.getConfigDir(), DIR);
        Map<Long, Points> rv = new HashMap<Long, Points>();
        List<Long> dates = load();
        for (Long date : dates) {
            File file = new File(dir, PFX + date + SFX);
            BufferedReader in = null;
            try {
                in = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(file))));
                String line;
                while ((line = in.readLine()) != null) {
                    if (!line.startsWith(bh))
                        continue;
                    if (line.length() < 46)
                        continue;
                    Points p = Points.fromString(line.substring(45));
                    if (p == null)
                        continue;
                    rv.put(date, p);
                }
            } finally {
                if (in != null) try { in.close(); } catch (IOException ioe) {}
            }
        }
        return rv;
    }

    /**
     *  Remove all files older than configured threshold
     *  Inline for now, thread later if necessary
     *
     *  @since 0.9.41
     */
    public synchronized void removeOld() {
        long age = _context.getProperty(Analysis.PROP_REMOVETIME, Analysis.DEFAULT_REMOVE_TIME);
        if (age < 60*1000)
            return;
        long cutoff = _context.clock().now() - age;
        File dir = new File(_context.getConfigDir(), DIR);
        File[] files = dir.listFiles(new FileSuffixFilter(PFX, SFX));
        if (files == null)
            return;
        int deleted = 0;
        for (File file : files) {
            try {
                String name = file.getName();
                long d = Long.parseLong(name.substring(PFX.length(), name.length() - SFX.length())); 
                if (d < cutoff) {
                    if (file.delete())
                        deleted++;
                    else if (_log.shouldWarn())
                        _log.warn("Failed to delete: " + file);
                }
            } catch (NumberFormatException nfe) {}
        }
        if (deleted > 0 && _log.shouldWarn())
            _log.warn("Deleted " + deleted + " old analysis files");
    }


    /**
     *  Delete the file for a particular date
     *
     *  @return success
     */
    public synchronized boolean delete(long date) {
        File dir = new File(_context.getConfigDir(), DIR);
        File file = new File(dir, PFX + date + SFX);
        return file.delete();
    }

/****
    public static void main(String[] args) {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        PersistSybil ps = new PersistSybil(ctx);
        byte[] b = new byte[32];
        ctx.random().nextBytes(b);
        Hash h = new Hash(b);
        String rsn = "Test reason";
        Points p = new Points(1.234, rsn);
        rsn = "Test reason2";
        p.addPoints(2.345, rsn);
        Map<Hash, Points> map = new HashMap<Hash, Points>();
        map.put(h, p);
        b = new byte[32];
        ctx.random().nextBytes(b);
        h = new Hash(b);
        map.put(h, p);
        try {
            long now = System.currentTimeMillis();
            System.out.println("storing entries: " + map.size());
            ps.store(System.currentTimeMillis(), map);
            List<Long> dates = ps.load();
            System.out.println("Found sets: " + dates.size());
            map = ps.load(Long.valueOf(now));
            System.out.println("loaded entries: " + map.size());
            for (Map.Entry<Hash, Points> e : map.entrySet()) {
                System.out.println(e.getKey().toString() + ": " + e.getValue());
            }
        } catch (IOException ioe) {
            System.out.println("store error from " + args[0]);
            ioe.printStackTrace();
        }
    }
****/
}
