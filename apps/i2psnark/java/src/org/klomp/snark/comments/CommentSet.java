/*
 * Released into the public domain
 * with no warranty of any kind, either expressed or implied.
 */
package org.klomp.snark.comments;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.i2p.util.SecureFileOutputStream;

/**
 * Store comments.
 *
 * <p>Optimized for fast checking of duplicates, and retrieval of ratings. Removes are not really
 * removed, only marked as hidden, so they don't reappear. Duplicates are detected based on an
 * approximate time range. Max size of both elements and total text length is enforced.
 *
 * <p>Supports persistence via save() and File constructor.
 *
 * <p>NOT THREAD SAFE except for iterating AFTER the iterator() call.
 *
 * @since 0.9.31
 */
public class CommentSet extends AbstractSet<Comment> {

    private final HashMap<Integer, List<Comment>> map;
    private int size;
    private int realSize;
    private int myRating;
    private int totalRating;
    private int ratingSize;
    private int totalTextSize;
    private long latestCommentTime;
    private boolean modified;

    public static final int MAX_SIZE = 256;

    // Comment.java enforces max text length of 512, but
    // we don't want 256*512 in memory per-torrent, so
    // track and enforce separately.
    // Assume most comments are short or null.
    private static final int MAX_TOTAL_TEXT_LEN = MAX_SIZE * 16;

    private CommentSet() {
        super();
        map = new HashMap<>(4);
    }

    /**
     * Creates a CommentSet from a collection of comments.
     *
     * @param coll the initial collection of comments
     */
    public CommentSet(Collection<Comment> coll) {
        super();
        map = new HashMap<>(coll.size());
        addAll(coll);
    }

    /**
     * Creates a CommentSet by reading from a gzipped file.
     *
     * @param file the gzipped file to read from
     * @throws IOException if an I/O error occurs
     */
    public CommentSet(File file) throws IOException {
        this();
        BufferedReader br = null;
        try {
            br =
                    new BufferedReader(
                            new InputStreamReader(
                                    new GZIPInputStream(new FileInputStream(file)), "UTF-8"));
            String line = null;
            while ((line = br.readLine()) != null) {
                Comment c = Comment.fromPersistentString(line);
                if (c != null) add(c);
            }
        } finally {
            if (br != null)
                try {
                    br.close();
                } catch (IOException ioe) { /* ignored */ }
        }
        modified = false;
    }

    /**
     * Saves this CommentSet to a gzipped file. Not sorted, includes hidden comments.
     * Sets isModified() to false on success.
     *
     * @param file the file to write to
     * @throws IOException if an I/O error occurs
     */
    public void save(File file) throws IOException {
        PrintWriter out = null;
        try {
            out =
                    new PrintWriter(
                            new OutputStreamWriter(
                                    new GZIPOutputStream(new SecureFileOutputStream(file)),
                                    "UTF-8"));
            for (List<Comment> l : map.values()) {
                for (Comment c : l) {
                    out.println(c.toPersistentString());
                }
            }
            if (out.checkError()) throw new IOException("Failed write to " + file);
            modified = false;
        } finally {
            if (out != null) out.close();
        }
    }

    /**
     * Adds a comment to this set. Enforces max size and max total text length.
     * Checks for duplicates based on approximate time range.
     *
     * @param c the comment to add
     * @return true if added, false if duplicate or at capacity
     */
    @Override
    public boolean add(Comment c) {
        if (realSize >= MAX_SIZE && !c.isMine()) return false;
        String s = c.getText();
        if (s != null && totalTextSize + s.length() > MAX_TOTAL_TEXT_LEN) return false;
        // If isMine and no text and rating changed, don't bother
        if (c.isMine() && c.getText() == null && c.getRating() == myRating) return false;
        int hCode = c.hashCode();
        // check previous and next buckets
        Integer phc = Integer.valueOf(hCode - 1);
        List<Comment> plist = map.get(phc);
        if (plist != null && plist.contains(c)) return false;
        Integer nhc = Integer.valueOf(hCode + 1);
        List<Comment> nxlist = map.get(nhc);
        if (nxlist != null && nxlist.contains(c)) return false;
        // check this bucket
        Integer hc = Integer.valueOf(hCode);
        List<Comment> list = map.get(hc);
        if (list == null) {
            list = Collections.singletonList(c);
            map.put(hc, list);
            addStats(c);
            return true;
        }
        if (list.contains(c)) return false;
        if (list.size() == 1) {
            // presume unmodifiable singletonList
            List<Comment> nlist = new ArrayList<>(2);
            nlist.add(list.get(0));
            map.put(hc, nlist);
            list = nlist;
        }
        list.add(c);
        // If isMine and no text and comment changed, remove old ones
        if (c.isMine() && c.getText() == null) removeMyOldRatings(c.getID());
        addStats(c);
        return true;
    }

    /**
     * Hides a comment (does not actually remove it from the underlying set).
     *
     * @param o the Comment to hide
     * @return true if present and not previously hidden
     */
    @Override
    public boolean remove(Object o) {
        if (o == null || !(o instanceof Comment)) return false;
        Comment c = (Comment) o;
        Integer hc = Integer.valueOf(c.hashCode());
        List<Comment> list = map.get(hc);
        if (list == null) return false;
        int i = list.indexOf(c);
        if (i >= 0) {
            Comment cc = list.get(i);
            if (!cc.isHidden()) {
                removeStats(cc);
                cc.setHidden();
                return true;
            }
        }
        return false;
    }

    /**
     * Hides a comment by its unique ID (as returned by Comment.getID()).
     *
     * @param id the comment ID to hide
     * @return true if present and not previously hidden
     */
    public boolean remove(int id) {
        // not the most efficient but should be rare.
        for (List<Comment> l : map.values()) {
            for (Comment c : l) {
                if (c.getID() == id) {
                    return remove(c);
                }
            }
        }
        return false;
    }

    /**
     * Hides all of my ratings with empty text, except the specified ID.
     *
     * @param exceptID the comment ID to preserve
     */
    private void removeMyOldRatings(int exceptID) {
        for (List<Comment> l : map.values()) {
            for (Comment c : l) {
                if (c.isMine() && c.getText() == null && c.getID() != exceptID && !c.isHidden()) {
                    removeStats(c);
                    c.setHidden();
                }
            }
        }
    }

    /**
     * Updates statistics when adding a comment. The comment may be hidden.
     *
     * @param c the comment to add stats for
     */
    private void addStats(Comment c) {
        realSize++;
        if (!c.isHidden()) {
            size++;
            int r = c.getRating();
            if (r > 0) {
                if (c.isMine()) {
                    myRating = r;
                } else {
                    totalRating += r;
                    ratingSize++;
                }
            }
            long time = c.getTime();
            if (time > latestCommentTime) latestCommentTime = time;
        }
        String t = c.getText();
        if (t != null) totalTextSize += t.length();
        modified = true;
    }

    /**
     * Updates statistics when removing a comment. Call before setting hidden.
     *
     * @param c the comment to remove stats for
     */
    private void removeStats(Comment c) {
        if (!c.isHidden()) {
            size--;
            int r = c.getRating();
            if (r > 0) {
                if (c.isMine()) {
                    if (myRating == r) myRating = 0;
                } else {
                    totalRating -= r;
                    ratingSize--;
                }
            }
            modified = true;
        }
    }

    /**
     * Returns the timestamp of the most recent non-hidden comment.
     * Not adjusted when the latest comment is subsequently hidden.
     *
     * @return the timestamp in milliseconds
     */
    public long getLatestCommentTime() {
        return latestCommentTime;
    }

    /**
     * Returns whether this set has been modified since instantiation or last save.
     *
     * @return true if modified
     */
    public boolean isModified() {
        return modified;
    }

    /**
     * Returns the local user's rating, or 0 if none.
     *
     * @return 0 if none, or 1-5
     */
    public int getMyRating() {
        return myRating;
    }

    /**
     * Returns the number of ratings making up the average rating.
     *
     * @return the count of ratings
     */
    public int getRatingCount() {
        return ratingSize;
    }

    /**
     * Returns the average rating from all non-hidden, non-local comments.
     *
     * @return 0 if none, or 1-5
     */
    public double getAverageRating() {
        if (ratingSize <= 0) return 0.0d;
        return totalRating / (double) ratingSize;
    }

    /**
     * Clears all comments including hidden ones. Resets all statistics to zero.
     */
    @Override
    public void clear() {
        if (realSize > 0) {
            modified = true;
            realSize = 0;
            map.clear();
            size = 0;
            myRating = 0;
            totalRating = 0;
            ratingSize = 0;
            totalTextSize = 0;
        }
    }

    /**
     * Returns the number of non-hidden comments. May be more than what iterator() returns
     * due to additional deduping.
     *
     * @return the non-hidden count
     */
    public int size() {
        return size;
    }

    /**
     * Returns an iterator over non-hidden comments in reverse-sort order (newest first).
     * Thread-safe after this call. Changes after this call are not reflected.
     * iter.remove() has no effect on the underlying set.
     *
     * <p>Returned values may be fewer than size() due to additional deduping.
     *
     * @return an iterator over non-hidden comments
     */
    public Iterator<Comment> iterator() {
        if (size <= 0) return Collections.<Comment>emptyList().iterator();
        List<Comment> list = new ArrayList<>(size);
        for (List<Comment> l : map.values()) {
            int hc = l.get(0).hashCode();
            List<Comment> prevList = map.get(Integer.valueOf(hc - 1));
            for (Comment c : l) {
                if (!c.isHidden()) {
                    // additional deduping at boundary
                    if (prevList != null) {
                        boolean dup = false;
                        for (Comment pc : prevList) {
                            if (c.equalsIgnoreTimestamp(pc)) {
                                dup = true;
                                break;
                            }
                        }
                        if (dup) continue;
                    }
                    list.add(c);
                }
            }
        }
        Collections.sort(list);
        return list.iterator();
    }
}
