package net.i2p.router.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import net.i2p.update.UpdateMethod;
import net.i2p.update.UpdatePostProcessor;
import net.i2p.update.UpdateTask;
import net.i2p.update.UpdateType;

/**
 * Tests the pure row merge and dot mapping of
 * {@link ConsoleUpdateManager#buildItemRows} and
 * {@link ConsoleUpdateManager#buildWorkerRows}: items merge across the
 * installed / downloaded / available maps with the most advanced stage
 * winning, and workers report Active only while a matching task runs.
 *
 * @since 0.9.71
 */
public class ConsoleUpdateManagerRowsTest {

    private static class FakeChecker implements net.i2p.update.Checker {
        @Override
        public UpdateTask check(UpdateType type, UpdateMethod method, String id, String currentVersion, long maxTime) {
            return null;
        }
    }

    private static class FakeUpdater implements net.i2p.update.Updater {
        @Override
        public UpdateTask update(UpdateType type, UpdateMethod method, List<URI> updateSources, String id, String newVersion, long maxTime) {
            return null;
        }
    }

    private static class FakePostProcessor implements UpdatePostProcessor {
        @Override
        public void updateDownloadedAndVerified(UpdateType type, int fileType, String version, File file) {}
    }

    private static class FakeTask implements UpdateTask {
        private final UpdateType _type;
        private final UpdateMethod _method;

        FakeTask(UpdateType type, UpdateMethod method) {
            _type = type;
            _method = method;
        }

        @Override
        public void start() {}

        @Override
        public void shutdown() {}

        @Override
        public boolean isRunning() {return false;}

        @Override
        public UpdateType getType() {return _type;}

        @Override
        public UpdateMethod getMethod() {return _method;}

        @Override
        public URI getURI() {return null;}

        @Override
        public String getID() {return "id";}
    }

    @Test
    public void installedOnlyIsGreenInstalled() {
        ConsoleUpdateManager.UpdateItem item = new ConsoleUpdateManager.UpdateItem(UpdateType.API, "");
        Map<ConsoleUpdateManager.UpdateItem, ConsoleUpdateManager.Version> installed = new HashMap<>();
        installed.put(item, new ConsoleUpdateManager.Version("0.9.70"));
        List<String[]> rows = ConsoleUpdateManager.buildItemRows(installed, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        assertEquals(1, rows.size());
        assertEquals("Installed", rows.get(0)[0]);
        assertEquals("API", rows.get(0)[1]);
        assertEquals("API Update", rows.get(0)[2]);
        assertEquals("0.9.70", rows.get(0)[3]);
        assertEquals("—", rows.get(0)[4]);
        assertEquals("green", ConsoleUpdateManager.dotClass(rows.get(0)[0]));
    }

    @Test
    public void availableUpdateMergesIntoOneRow() {
        ConsoleUpdateManager.UpdateItem item = new ConsoleUpdateManager.UpdateItem(UpdateType.PLUGIN, "zzzot");
        Map<ConsoleUpdateManager.UpdateItem, ConsoleUpdateManager.Version> installed = new HashMap<>();
        installed.put(item, new ConsoleUpdateManager.Version("0.30"));
        Map<ConsoleUpdateManager.UpdateItem, ConsoleUpdateManager.VersionAvailable> available = new HashMap<>();
        available.put(item, new ConsoleUpdateManager.VersionAvailable("0.31", "", UpdateMethod.HTTP, Collections.<URI>emptyList()));
        List<String[]> rows = ConsoleUpdateManager.buildItemRows(installed, Collections.emptyMap(), available, Collections.emptyMap());
        assertEquals(1, rows.size());
        assertEquals("Available", rows.get(0)[0]);
        assertEquals("zzzot", rows.get(0)[2]);
        assertEquals("0.30", rows.get(0)[3]);
        assertEquals("0.31", rows.get(0)[4]);
        assertEquals("yellow", ConsoleUpdateManager.dotClass(rows.get(0)[0]));
    }

    @Test
    public void downloadedBeatsAvailableAndIsBlue() {
        ConsoleUpdateManager.UpdateItem item = new ConsoleUpdateManager.UpdateItem(UpdateType.ROUTER_SIGNED_SU3, "");
        Map<ConsoleUpdateManager.UpdateItem, ConsoleUpdateManager.Version> downloaded = new HashMap<>();
        downloaded.put(item, new ConsoleUpdateManager.Version("2.14.0"));
        Map<ConsoleUpdateManager.UpdateItem, ConsoleUpdateManager.VersionAvailable> available = new HashMap<>();
        available.put(item, new ConsoleUpdateManager.VersionAvailable("2.14.0", "no room"));
        List<String[]> rows = ConsoleUpdateManager.buildItemRows(Collections.emptyMap(), downloaded, available, Collections.emptyMap());
        assertEquals(1, rows.size());
        assertEquals("Downloaded", rows.get(0)[0]);
        assertEquals("blue", ConsoleUpdateManager.dotClass(rows.get(0)[0]));
        assertTrue(rows.get(0)[4].contains("no room"));
    }

    @Test
    public void nullMapsTreatedAsEmpty() {
        List<String[]> rows = ConsoleUpdateManager.buildItemRows(null, null, null, null);
        assertEquals(0, rows.size());
    }

    @Test
    public void itemsSortedByDisplayName() {
        Map<ConsoleUpdateManager.UpdateItem, ConsoleUpdateManager.Version> installed = new HashMap<>();
        installed.put(new ConsoleUpdateManager.UpdateItem(UpdateType.PLUGIN, "zzzot"), new ConsoleUpdateManager.Version("1"));
        installed.put(new ConsoleUpdateManager.UpdateItem(UpdateType.PLUGIN, "api"), new ConsoleUpdateManager.Version("1"));
        installed.put(new ConsoleUpdateManager.UpdateItem(UpdateType.NEWS, ""), new ConsoleUpdateManager.Version("1"));
        List<String[]> rows = ConsoleUpdateManager.buildItemRows(installed, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        // collator order on the display name: api < News Feed < zzzot
        assertEquals("api", rows.get(0)[2]);
        assertEquals("NEWS", rows.get(1)[1]);
        assertEquals("zzzot", rows.get(2)[2]);
    }

    @Test
    public void numericVersionDatesFormatted() {
        assertEquals("0.9.70", ConsoleUpdateManager.fmtVersion("0.9.70"));
        String formatted = ConsoleUpdateManager.fmtVersion("1774700886000");
        // fmtVersion delegates to DataHelper.formatTime, whose formatter is bound
        // to the JVM default locale at class-load, so the exact rendering varies
        // by locale (US "Nov 27, 2025, 10:28 PM" vs a 24-hour locale's
        // "27 nov. 2025, 22:28"). Assert the locale-independent contract instead:
        // the 13-digit numeric version is reformatted (not echoed) and names a
        // four-digit year.
        assertFalse("1774700886000 should be reformatted, not echoed", formatted.equals("1774700886000"));
        assertTrue(formatted, formatted.matches(".*\\b\\d{4}\\b.*"));
    }

    @Test
    public void checkerActiveOnlyWithMatchingTask() {
        ConsoleUpdateManager.RegisteredChecker rc = new ConsoleUpdateManager.RegisteredChecker(
                new FakeChecker(), UpdateType.NEWS, UpdateMethod.HTTP, 500);
        List<UpdateTask> active = new ArrayList<>();
        active.add(new FakeTask(UpdateType.NEWS, UpdateMethod.HTTP));
        List<String[]> rows = ConsoleUpdateManager.buildWorkerRows(
                Collections.singletonList(rc), Collections.emptyList(), Collections.emptyMap(),
                active, Collections.emptyMap(), Collections.emptyMap());
        assertEquals(1, rows.size());
        assertEquals("Active", rows.get(0)[0]);
        assertEquals("Checker", rows.get(0)[1]);
        assertEquals("—", rows.get(0)[5]);  // no installed version for NEWS

        List<String[]> idle = ConsoleUpdateManager.buildWorkerRows(
                Collections.singletonList(rc), Collections.emptyList(), Collections.emptyMap(),
                Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap());
        assertEquals("Idle", idle.get(0)[0]);
        assertEquals("grey", ConsoleUpdateManager.dotClass(idle.get(0)[0]));
    }

    @Test
    public void updaterActiveViaDownloaders() {
        ConsoleUpdateManager.RegisteredUpdater ru = new ConsoleUpdateManager.RegisteredUpdater(
                new FakeUpdater(), UpdateType.PLUGIN, UpdateMethod.HTTP, 500);
        UpdateTask task = new FakeTask(UpdateType.PLUGIN, UpdateMethod.HTTP);
        Map<UpdateTask, List<ConsoleUpdateManager.RegisteredUpdater>> downloaders = new HashMap<>();
        downloaders.put(task, Collections.singletonList(ru));
        List<String[]> rows = ConsoleUpdateManager.buildWorkerRows(
                Collections.emptyList(), Collections.singletonList(ru), Collections.emptyMap(),
                Collections.emptyList(), downloaders, Collections.emptyMap());
        assertEquals(1, rows.size());
        assertEquals("Active", rows.get(0)[0]);
        assertEquals("Updater", rows.get(0)[1]);
        assertEquals("green", ConsoleUpdateManager.dotClass(rows.get(0)[0]));
    }

    @Test
    public void postProcessorRowHasNoState() {
        Map<Integer, UpdatePostProcessor> pps = new HashMap<>();
        pps.put(4, new FakePostProcessor());
        List<String[]> rows = ConsoleUpdateManager.buildWorkerRows(
                Collections.emptyList(), Collections.emptyList(), pps,
                Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap());
        assertEquals(1, rows.size());
        assertEquals("—", rows.get(0)[0]);
        assertEquals("PostProcessor", rows.get(0)[1]);
        assertEquals("4", rows.get(0)[3]);
    }

    @Test
    public void workersSortedByRoleThenClass() {
        ConsoleUpdateManager.RegisteredChecker rc = new ConsoleUpdateManager.RegisteredChecker(
                new FakeChecker(), UpdateType.NEWS, UpdateMethod.HTTP, 500);
        ConsoleUpdateManager.RegisteredUpdater ru = new ConsoleUpdateManager.RegisteredUpdater(
                new FakeUpdater(), UpdateType.PLUGIN, UpdateMethod.HTTP, 500);
        Map<Integer, UpdatePostProcessor> pps = new HashMap<>();
        pps.put(4, new FakePostProcessor());
        List<String[]> rows = ConsoleUpdateManager.buildWorkerRows(
                Collections.singletonList(rc), Collections.singletonList(ru), pps,
                Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap());
        assertEquals(3, rows.size());
        assertEquals("Checker", rows.get(0)[1]);
        assertEquals("PostProcessor", rows.get(1)[1]);
        assertEquals("Updater", rows.get(2)[1]);
    }

    @Test
    public void workerShowsInstalledVersionForItsType() {
        ConsoleUpdateManager.RegisteredChecker rc = new ConsoleUpdateManager.RegisteredChecker(
                new FakeChecker(), UpdateType.ROUTER_SIGNED_SU3, UpdateMethod.HTTP, 500);
        Map<UpdateType, String> typeVersions = new HashMap<>();
        typeVersions.put(UpdateType.ROUTER_SIGNED_SU3, "0.9.62.1");
        List<String[]> rows = ConsoleUpdateManager.buildWorkerRows(
                Collections.singletonList(rc), Collections.emptyList(), Collections.emptyMap(),
                Collections.emptyList(), Collections.emptyMap(), typeVersions);
        assertEquals("0.9.62.1", rows.get(0)[5]);
    }

    @Test
    public void unnamedItemsGetDisplayNamesAndSortByName() {
        Map<ConsoleUpdateManager.UpdateItem, ConsoleUpdateManager.Version> installed = new HashMap<>();
        installed.put(new ConsoleUpdateManager.UpdateItem(UpdateType.ROUTER_SIGNED_SU3, ""), new ConsoleUpdateManager.Version("0.9.62"));
        installed.put(new ConsoleUpdateManager.UpdateItem(UpdateType.ROUTER_UNSIGNED, ""), new ConsoleUpdateManager.Version("0.9.62"));
        installed.put(new ConsoleUpdateManager.UpdateItem(UpdateType.PLUGIN, "i2psnark"), new ConsoleUpdateManager.Version("1.0"));
        List<String[]> rows = ConsoleUpdateManager.buildItemRows(installed, null, null, null);
        assertEquals(3, rows.size());
        // sorted by display name, not type
        assertEquals("i2psnark", rows.get(0)[2]);
        assertEquals("Signed Update (SU3)", rows.get(1)[2]);
        assertEquals("Unsigned Update", rows.get(2)[2]);
    }

    @Test
    public void displayNamesPerType() {
        assertEquals("Signed Update", ConsoleUpdateManager.itemDisplayName(UpdateType.ROUTER_SIGNED, ""));
        assertEquals("Development Update (SU3)", ConsoleUpdateManager.itemDisplayName(UpdateType.ROUTER_DEV_SU3, ""));
        assertEquals("News Feed", ConsoleUpdateManager.itemDisplayName(UpdateType.NEWS_SU3, ""));
        assertEquals("API Update", ConsoleUpdateManager.itemDisplayName(UpdateType.API, ""));
        assertEquals("Internal Placeholder", ConsoleUpdateManager.itemDisplayName(UpdateType.TYPE_DUMMY, ""));
        assertEquals("Sybil Blocklist", ConsoleUpdateManager.itemDisplayName(UpdateType.BLOCKLIST, "sybil"));
        assertEquals("Tor Blocklist", ConsoleUpdateManager.itemDisplayName(UpdateType.BLOCKLIST, "tor"));
        assertEquals("System Blocklist", ConsoleUpdateManager.itemDisplayName(UpdateType.BLOCKLIST, "system"));
        assertEquals("Blocklist", ConsoleUpdateManager.itemDisplayName(UpdateType.BLOCKLIST, ""));
        assertEquals("GeoIPv4", ConsoleUpdateManager.itemDisplayName(UpdateType.GEOIP, "GeoIPv4"));
        assertEquals("GeoIP Database", ConsoleUpdateManager.itemDisplayName(UpdateType.GEOIP, ""));
        // named items keep their id
        assertEquals("susi", ConsoleUpdateManager.itemDisplayName(UpdateType.PLUGIN, "susi"));
    }

    @Test
    public void methodsComeFromRegisteredCheckers() {
        Map<ConsoleUpdateManager.UpdateItem, ConsoleUpdateManager.Version> installed = new HashMap<>();
        installed.put(new ConsoleUpdateManager.UpdateItem(UpdateType.ROUTER_SIGNED_SU3, ""), new ConsoleUpdateManager.Version("0.9.62"));
        Map<UpdateType, Set<UpdateMethod>> checkMethods = new HashMap<>();
        Set<UpdateMethod> methods = new HashSet<>();
        methods.add(UpdateMethod.HTTPS_CLEARNET);
        methods.add(UpdateMethod.HTTP);
        checkMethods.put(UpdateType.ROUTER_SIGNED_SU3, methods);
        List<String[]> rows = ConsoleUpdateManager.buildItemRows(installed, null, null, checkMethods);
        assertEquals("HTTP, HTTPS_CLEARNET", rows.get(0)[5]);
        // a type with no registered checker shows a dash
        installed.put(new ConsoleUpdateManager.UpdateItem(UpdateType.NEWS_SU3, ""), new ConsoleUpdateManager.Version("1"));
        rows = ConsoleUpdateManager.buildItemRows(installed, null, null, checkMethods);
        for (String[] r : rows) {
            if ("News Feed".equals(r[2])) {assertEquals("—", r[5]);}
        }
    }

    @Test
    public void updateTableSharesOneColumnGrid() {
        List<String[]> items = new ArrayList<>();
        items.add(new String[] {"Installed", "Router", "router", "0.9.62", "—", "HTTP"});
        List<String[]> workers = new ArrayList<>();
        workers.add(new String[] {"Active", "Checker", "HttpUpdateChecker", "Router", "HTTP", "0.9.62"});
        String html = ConsoleUpdateManager.buildUpdateTable(items, workers);
        assertTrue(html, html.startsWith("<table id=updatemgr>"));
        // one header grid repeated for both sections, status last
        assertEquals(2, html.split("<th>Name</th><th>Type</th><th>Method</th><th>Role</th>", -1).length - 1);
        assertTrue(html, html.contains("<th>Installed/Version</th><th>Available</th><th>Status</th>"));
        // two sections in one table
        assertTrue(html, html.contains("<th colspan=7>Update Items (1)</th>"));
        assertTrue(html, html.contains("<th colspan=7>Update Workers (1)</th>"));
        assertEquals(2, html.split("<thead>", -1).length - 1);
        assertEquals(2, html.split("<tbody>", -1).length - 1);
        // status is a dot with a tooltip, not text
        assertTrue(html, html.contains("<span class=\"appdot green\" title=\"Installed\"></span>"));
        assertTrue(html, html.contains("<span class=\"appdot green\" title=\"Active\"></span>"));
        // item row: name, type and method lead, role blank, version shown
        assertTrue(html, html.contains("<tr><td>router</td><td>Router</td><td>HTTP</td><td>—</td><td>0.9.62</td><td>—</td><td>"));
        // worker row: class, type, method, role lead, installed version cross-referenced
        assertTrue(html, html.contains("<tr><td>HttpUpdateChecker</td><td>Router</td><td>HTTP</td><td>Checker</td><td>0.9.62</td><td>—</td><td>"));
    }
}
