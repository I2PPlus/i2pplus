package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * Tests the client blocklist bookkeeping of BlocklistManager: a logged
 * destination must be recorded in memory as well as on disk, since a
 * repeatedly blocked peer otherwise appends one duplicate line per request.
 * The size cap must also bound the FILE, not just memory.
 *
 * @since 0.9.72+
 */
public class BlocklistManagerTest {

    @Test
    public void testOwnWriteRecordedNoDuplicates() throws IOException {
        File dir = I2PAppContext.getGlobalContext().getConfigDir();
        File file = new File(dir, BlocklistManager.HTTP_BLOCKLIST_CLIENTS);
        File backup = null;
        if (file.exists()) {
            backup = File.createTempFile("blocklistbak", ".txt");
            Files.copy(file.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            if (file.exists()) {file.delete();}
            BlocklistManager mgr = new BlocklistManager(new Log(BlocklistManager.class), 512);
            String dest = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + ".b32.i2p";
            mgr.logBlockedDestination(dest);
            mgr.logBlockedDestination(dest);
            mgr.logBlockedDestination(dest);
            assertTrue(mgr.existsInClientBlocklist(dest));
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            long count = lines.stream().filter(dest::equals).count();
            assertEquals("duplicate appends per repeat block", 1, count);
        } finally {
            if (backup != null) {
                Files.copy(backup.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                backup.delete();
            } else {
                file.delete();
            }
        }
    }

    @Test
    public void testCapRewritesFile() throws IOException {
        File dir = I2PAppContext.getGlobalContext().getConfigDir();
        File file = new File(dir, BlocklistManager.HTTP_BLOCKLIST_CLIENTS);
        File backup = null;
        if (file.exists()) {
            backup = File.createTempFile("blocklistbak", ".txt");
            Files.copy(file.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            if (file.exists()) {file.delete();}
            BlocklistManager mgr = new BlocklistManager(new Log(BlocklistManager.class), 2);
            String d1 = "d1" + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + ".b32.i2p";
            String d2 = "d2" + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" + ".b32.i2p";
            String d3 = "d3" + "ccccccccccccccccccccccccccccccccccccccccccccccccccc" + ".b32.i2p";
            mgr.logBlockedDestination(d1);
            mgr.logBlockedDestination(d2);
            mgr.logBlockedDestination(d3);
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            assertEquals("file must stay bounded by the cap", 2, lines.size());
            assertFalse("evicted entry must not return", mgr.existsInClientBlocklist(d1));
            assertTrue(mgr.existsInClientBlocklist(d3));
            assertEquals(2, mgr.getClientBlocklistSize());
        } finally {
            if (backup != null) {
                Files.copy(backup.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                backup.delete();
            } else {
                file.delete();
            }
        }
    }
}
