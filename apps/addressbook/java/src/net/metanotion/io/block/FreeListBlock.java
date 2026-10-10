package net.metanotion.io.block;
// License: BSD-3-Clause. See docs/LICENSES.md

import java.io.IOException;
import net.i2p.I2PAppContext;
import net.i2p.util.Log;
import net.metanotion.io.RandomAccessInterface;

/**
 * Manages free pages in a block file.
 *
 * <p>Tracks available pages for allocation using a linked list of blocks.
 * Each block contains multiple free page references and fits on a single disk page.</p>
 *
 * <p>On-disk format:</p>
 * <pre>
 *    Magic number (long)
 *    next freelist block page (unsigned int)
 *    size (unsigned int)
 *    that many free pages (unsigned ints)
 * </pre>
 *
 * <p>Free page format:</p>
 * <pre>
 *    Magic number (long)
 * </pre>
 */
class FreeListBlock {
private static final long MAGIC = 0x2366724c69737423L;  // "#frList#"
    private static final long MAGIC_FREE = 0x7e2146524545217eL;  // "~!FREE!~"
    private static final int HEADER_LEN = 16;
    private static final int MAX_SIZE = (BlockFile.PAGESIZE - HEADER_LEN) / 4;

    /** Page number of this free list block. */
    public final int page;
    private int nextPage;
    private int len;
    private final int[] branches;
    private final RandomAccessInterface file;

    /**
     * Constructor.
     *
     * @param file the backing store holding this block's disk page
     * @param startPage the disk page on which this block is stored
     * @throws IOException if the page's magic number or recorded free count is
     *     corrupt, or if the free page references cannot be read
     */
    public FreeListBlock(RandomAccessInterface file, int startPage) throws IOException {
        this.file = file;
        this.page = startPage;
        BlockFile.pageSeek(file, startPage);
        long magic = file.readLong();
        if (magic != MAGIC)
            throw new IOException("Bad freelist magic number 0x" + Long.toHexString(magic) + " on page " + startPage);
        nextPage = file.readUnsignedInt();
        len = file.readUnsignedInt();
        if (len > MAX_SIZE)
            throw new IOException("Bad freelist size " + len);
        branches = new int[MAX_SIZE];
        if(len > 0) {
            int good = 0;
            for(int i=0;i<len;i++) {
                int fpg = file.readInt();
                if (fpg > BlockFile.METAINDEX_PAGE)
                    branches[good++] = fpg;
            }
            if (good != len) {
                Log log = I2PAppContext.getGlobalContext().logManager().getLog(BlockFile.class);
                log.error((len - good) + " bad pages in " + this);
                len = good;
                writeBlock();
            }
        }
    }

    /**
     * Write this block's data to disk.
     *
     * @throws IOException if the seek or write to this block's page fails
     */
    public void writeBlock() throws IOException {
        BlockFile.pageSeek(file, page);
        file.writeLong(MAGIC);
        file.writeInt(nextPage);
        file.writeInt(len);
        for(int i=0;i<len;i++) { file.writeInt(branches[i]); }
    }

    /**
     * Write the length only
     */
    private void writeLen() throws IOException {
        BlockFile.pageSeek(file, page);
        file.skipBytes(12);
        file.writeInt(len);
    }

    /**
     * Get the page number of the next block in the free list chain.
     *
     * @return the disk page of the next block, or 0 when this is the last one
     */
    public int getNextPage() {
        return nextPage;
    }

    /**
     * Set and write the next page only
     *
     * @param nxt the disk page of the next block in the free list chain, 0 to end the chain
     * @throws IOException if the seek or write to this block's page fails
     */
    public void setNextPage(int nxt) throws IOException {
        nextPage = nxt;
        BlockFile.pageSeek(file, page);
        file.skipBytes(8);
        file.writeInt(nxt);
    }

    /**
     * Write the length and new page only
     */
    private void writeFreePage() throws IOException {
        BlockFile.pageSeek(file, page);
        file.skipBytes(12);
        file.writeInt(len);
        if (len > 1)
            file.skipBytes((len - 1) * 4);
        file.writeInt(branches[len - 1]);
    }

    /**
     * Report whether this block has no free page references left.
     *
     * @return true when no free page references remain, so the block holds no
     *     space that could be handed out by takePage()
     */
    public boolean isEmpty() {
        return len <= 0;
    }

    /**
     * Report whether this block already holds as many free page references as
     * fit on one disk page.
     *
     * @return true once the free page references fill the block's share of the
     *     disk page, which makes addPage() reject the next page
     */
    public boolean isFull() {
        return len >= MAX_SIZE;
    }

    /**
     * Adds free page and writes new len to disk
     *
     * @param freePage the disk page to add to this block's free list
     * @throws IOException if the page cannot be marked free, or if the updated
     *     count cannot be written back
     * @throws IllegalStateException if the block is already full
     */
    public void addPage(int freePage) throws IOException {
        if (len >= MAX_SIZE)
            throw new IllegalStateException("full");
        if (getMagic(freePage) == MAGIC_FREE) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(BlockFile.class);
            log.error("Double free page " + freePage, new Exception());
            return;
        }
        branches[len++] = freePage;
        markFree(freePage);
        writeFreePage();
    }

    /**
     * Takes next page and writes new len to disk
     *
     * @return the disk page at the head of this block's free list, released for
     *     the caller to allocate
     * @throws IOException if the page taken is not marked free, which means the
     *     on-disk free list is corrupt
     * @throws IllegalStateException if the block holds no free page references
     */
    public int takePage() throws IOException {
        if (len <= 0)
            throw new IllegalStateException("empty");
        len--;
        writeLen();
        int rv = branches[len];
        if (rv <= BlockFile.METAINDEX_PAGE)
            // shouldn't happen
            throw new IOException("Bad free page " + rv);
        long magic = getMagic(rv);
        if (magic != MAGIC_FREE)
            // TODO keep trying until empty
            throw new IOException("Bad free page magic number 0x" + Long.toHexString(magic) + " on page " + rv);
        return rv;
    }

    private void markFree(int freePage) throws IOException {
        BlockFile.pageSeek(file, freePage);
        file.writeLong(MAGIC_FREE);
    }

    private long getMagic(int freePage) throws IOException {
        BlockFile.pageSeek(file, freePage);
        long magic = file.readLong();
        return magic;
    }

    /**
     * Initialize a new free list block page with default values.
     *
     * @param file the backing store to initialize in
     * @param page the disk page to initialize as an empty free list block
     * @throws IOException if the seek or write to the page fails
     */
    public static void initPage(RandomAccessInterface file, int page) throws IOException {
        BlockFile.pageSeek(file, page);
        file.writeLong(MAGIC);
        file.writeInt(0);
        file.writeInt(0);
    }

    /**
     * Log this block and then, following the next-page chain, every block
     * after it in the free list. Used for diagnostics; it never repairs
     * anything and always reports success.
     *
     * @param fix passed down the chain but unused; nothing is repaired
     * @return always true
     * @throws IOException if a following block in the chain cannot be read
     * @since 0.9.7
     */
    public boolean flbck(boolean fix) throws IOException {
        Log log = I2PAppContext.getGlobalContext().logManager().getLog(BlockFile.class);
        log.info(toString());
        if (nextPage > 0)
            (new FreeListBlock(file, nextPage)).flbck(fix);
        return true;
    }

    /**
     * Summarise this block for log and diagnostic output.
     *
     * @return the block identity, its free page count against the block
     *     capacity, its disk page and the next block in the chain
     */
    @Override
    public String toString() {
        return "FLB with " + len + " / " + MAX_SIZE + " page " + page + " next page " + nextPage;
    }
}
