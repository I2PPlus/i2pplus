// License: GPLv2+. See docs/LICENSES.md
package i2p.susi.util;

import static org.junit.Assert.*;

import java.util.Iterator;

import org.junit.Test;

/**
 * Pins the range a folder's page size may take.
 *
 * <p>The console hands the page size over as a form field and susimail writes it into
 * the global config file, where it stays across restarts. The field was clamped at the
 * bottom only, so one POST with 2000000000 made every folder in every session render
 * its entire contents as one page - a table row per message, built while the session
 * is locked - and the number was trusted again on every later read, including from the
 * config file.
 *
 * @since 0.9.72+
 */
public class FolderPageSizeTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public FolderPageSizeTest() {}

    /**
     * A folder holding count numbered entries, with the page size set first: a folder
     * that has no size yet reads one from the config file, which is not on a test
     * classpath.
     *
     * @param count how many entries to make
     * @param pageSize the size to set before the entries arrive
     * @return the folder, holding msg-0 through msg-(count - 1) on pages of that size
     */
    private static Folder<String> folder(int count, int pageSize) {
        Folder<String> folder = new Folder<>();
        folder.setPageSize(pageSize);
        String[] elements = new String[count];
        for (int i = 0; i < count; i++) {
            elements[i] = "msg-" + i;
        }
        folder.setElements(elements);
        return folder;
    }

    /**
     * How many entries the current page actually renders.
     *
     * @param folder the folder to look at
     * @return the number of entries the iterator yields
     */
    private static int rendered(Folder<String> folder) {
        int n = 0;
        for (Iterator<String> it = folder.currentPageIterator(); it.hasNext(); it.next()) {
            n++;
        }
        return n;
    }

    /**
     * The defect: a page size from the form is used as given, so a huge one renders
     * the whole folder on one page.
     */
    @Test
    public void testAHugePageSizeIsCapped() {
        Folder<String> folder = folder(500, 2000000000);
        assertEquals(Folder.MAX_PAGESIZE, folder.getPageSize());
        assertEquals(Folder.MAX_PAGESIZE, rendered(folder));
        assertEquals(3, folder.getPages());
    }

    /**
     * The floor the console applied is now the folder's own: a size below it is
     * raised, not stored.
     */
    @Test
    public void testATinyPageSizeIsRaised() {
        Folder<String> folder = folder(500, 1);
        assertEquals(Folder.MIN_PAGESIZE, folder.getPageSize());
        assertEquals(Folder.MIN_PAGESIZE, rendered(folder));
    }

    /**
     * A size that is not a size at all leaves the folder alone, which is what the
     * settings page relies on when it compares the old value with the new one.
     */
    @Test
    public void testANonPositiveSizeChangesNothing() {
        Folder<String> folder = folder(500, 40);
        folder.setPageSize(0);
        assertEquals(40, folder.getPageSize());
        folder.setPageSize(-3);
        assertEquals(40, folder.getPageSize());
    }

    /**
     * A size inside the range is stored exactly as asked for, at both ends.
     */
    @Test
    public void testASizeInsideTheRangeIsStoredAsGiven() {
        Folder<String> folder = folder(500, Folder.MIN_PAGESIZE);
        assertEquals(Folder.MIN_PAGESIZE, folder.getPageSize());
        folder.setPageSize(Folder.MAX_PAGESIZE);
        assertEquals(Folder.MAX_PAGESIZE, folder.getPageSize());
        folder.setPageSize(77);
        assertEquals(77, folder.getPageSize());
        assertEquals(77, rendered(folder));
    }

    /**
     * The clamp is the single place the range is applied, and it is what the settings
     * page writes into the config file, so the stored number is one the folder will
     * use.
     */
    @Test
    public void testTheClampCoversTheWholeRange() {
        assertEquals(Folder.MIN_PAGESIZE, Folder.clampPageSize(Integer.MIN_VALUE));
        assertEquals(Folder.MIN_PAGESIZE, Folder.clampPageSize(-1));
        assertEquals(Folder.MIN_PAGESIZE, Folder.clampPageSize(0));
        assertEquals(Folder.MIN_PAGESIZE, Folder.clampPageSize(1));
        assertEquals(Folder.MIN_PAGESIZE, Folder.clampPageSize(Folder.MIN_PAGESIZE));
        assertEquals(Folder.DEFAULT_PAGESIZE, Folder.clampPageSize(Folder.DEFAULT_PAGESIZE));
        assertEquals(Folder.MAX_PAGESIZE, Folder.clampPageSize(Folder.MAX_PAGESIZE));
        assertEquals(Folder.MAX_PAGESIZE, Folder.clampPageSize(Folder.MAX_PAGESIZE + 1));
        assertEquals(Folder.MAX_PAGESIZE, Folder.clampPageSize(Integer.MAX_VALUE));
    }

    /**
     * The default sits inside the range, so a folder that never had a size set renders
     * the same number of rows whatever the clamp does to it.
     */
    @Test
    public void testTheDefaultIsInsideTheRange() {
        assertTrue(Folder.DEFAULT_PAGESIZE >= Folder.MIN_PAGESIZE);
        assertTrue(Folder.DEFAULT_PAGESIZE <= Folder.MAX_PAGESIZE);
    }

    /**
     * Paging still walks the whole folder once the size is capped: the last page holds
     * the remainder, and no entry is skipped or shown twice.
     */
    @Test
    public void testEveryEntryIsStillReachable() {
        Folder<String> folder = folder(500, 2000000000);
        assertEquals(3, folder.getPages());
        boolean[] seen = new boolean[500];
        folder.firstPage();
        for (int page = 0; page < folder.getPages(); page++) {
            for (Iterator<String> it = folder.currentPageIterator(); it.hasNext();) {
                String id = it.next();
                seen[Integer.parseInt(id.substring(4))] = true;
            }
            folder.nextPage();
        }
        for (int i = 0; i < seen.length; i++) {
            assertTrue("msg-" + i + " is on no page", seen[i]);
        }
    }
}
