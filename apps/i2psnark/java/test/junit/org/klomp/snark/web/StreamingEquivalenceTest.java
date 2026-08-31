package org.klomp.snark.web;

import static org.junit.Assert.*;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;

import org.junit.BeforeClass;
import org.junit.Test;

import org.klomp.snark.I2PSnarkUtil;
import org.klomp.snark.MetaInfo;
import org.klomp.snark.Snark;
import org.klomp.snark.SnarkManager;
import org.klomp.snark.Storage;
import org.klomp.snark.TorrentCreateFilter;

/**
 * SF-5 golden-equivalence harness: proves that the streamed rendering of a
 * torrent directory page (chunk drains + returned tail) is byte-identical
 * to the buffered rendering (single string) of the same request.
 *
 * Boots a REAL I2PSnarkServlet via its production init() against temp I2P
 * dirs, registers real on-disk torrents (12 and 80 files - one below, one
 * above the STREAM_MIN_FILE_ROWS gate), and drives getListHTML directly.
 *
 * @since 0.9.72+
 */
public class StreamingEquivalenceTest {

    private static final String CTX = "/i2psnark";
    private static final Map<String, String> INIT_PARAMS = new HashMap<>();
    private static I2PSnarkServlet servlet;

    /** PrintWriter that counts flushes so tests can prove the drain path ran. */
    private static class CountingWriter extends PrintWriter {
        int flushes;
        CountingWriter() {super(new StringWriter());}
        @Override public void flush() {flushes++; super.flush();}
        @Override public String toString() {return ((StringWriter) out).toString();}
    }

    @BeforeClass
    public static void boot() throws Exception {
        File tmp = Files.createTempDirectory("snarkeq").toFile();
        for (String p : new String[] {"i2p.dir.base", "i2p.dir.config", "i2p.dir.app",
                                      "i2p.dir.router", "i2p.dir.log", "i2p.dir.pid",
                                      "i2p.dir.temp"}) {
            System.setProperty(p, tmp.getAbsolutePath());
        }
        File dataRoot = new File(tmp, "data");
        dataRoot.mkdirs();

        // config pre-seeded so the manager's data dir is our fixture root
        // (keeps DirMonitor aligned with the torrents we register)
        INIT_PARAMS.put("resourceBase", dataRoot.getAbsolutePath());
        INIT_PARAMS.put("warBase", "/.res/");
        File configDir = new File(tmp, "config");
        configDir.mkdirs();
        Properties props = new Properties();
        props.setProperty(SnarkManager.PROP_DIR, dataRoot.getAbsolutePath());
        props.setProperty(SnarkManager.PROP_TEMP_DIR, ""); // staging disabled, as in prod default
        props.store(Files.newOutputStream(new File(configDir, "i2psnark.config").toPath()), null);
        System.setProperty("i2p.dir.config", configDir.getAbsolutePath());

        servlet = new I2PSnarkServlet();
        ServletContext ctx = proxy(ServletContext.class,
                Collections.<String, Object>singletonMap("getContextPath", CTX));
        ServletConfig cfg = (ServletConfig) Proxy.newProxyInstance(
                StreamingEquivalenceTest.class.getClassLoader(),
                new Class[] {ServletConfig.class},
                (Object proxy, Method m, Object[] args) -> {
                    switch (m.getName()) {
                        case "getServletContext": return ctx;
                        case "getServletName": return "i2psnark";
                        case "getInitParameter": return INIT_PARAMS.get(args[0]);
                        default: break;
                    }
                    Class<?> r = m.getReturnType();
                    if (r == boolean.class) {return Boolean.FALSE;}
                    return null;
                });
        servlet.init(cfg);

        makeTorrent(dataRoot, "TestTorrent", 80);
        makeTorrent(dataRoot, "SmallTorrent", 12);
    }

    /** InvocationHandler: mapped methods return their value; everything else type-defaults. */
    private static InvocationHandler defaults(Map<String, Object> results) {
        return (Object proxy, Method m, Object[] args) -> {
            if (results.containsKey(m.getName())) {return results.get(m.getName());}
            Class<?> r = m.getReturnType();
            if (r == boolean.class) {return Boolean.FALSE;}
            if (r == int.class) {return Integer.valueOf(0);}
            if (r == long.class) {return Long.valueOf(0);}
            return null;
        };
    }

    private static <T> T proxy(Class<T> iface, Map<String, Object> results) {
        return iface.cast(Proxy.newProxyInstance(StreamingEquivalenceTest.class.getClassLoader(),
                new Class[] {iface}, defaults(results)));
    }

    /**
     * Builds an on-disk torrent directory with n files, creates its metainfo
     * via Storage's create-from-directory constructor, writes the .torrent,
     * boots a stopped-mode Snark from it (running Storage.check()), and
     * registers it with the manager under the directory name.
     */
    private static void makeTorrent(File dataRoot, String name, int n) throws Exception {
        File base = new File(dataRoot, name);
        base.mkdirs();
        for (int i = 0; i < n; i++) {
            File f = new File(base, (i % 10 == 0 ? "song" + i + ".mp3" : "file" + i + ".txt"));
            Files.write(f.toPath(), Arrays.copyOf("x".getBytes(StandardCharsets.UTF_8), 64));
        }

        I2PSnarkUtil util = manager().util();
        // listener=null: the Snark itself is the default StorageListener
        Storage storage = new Storage(util, base, null, null, null, false, null, null, null,
                    java.util.Collections.<TorrentCreateFilter>emptyList());
        byte[] data = storage.getMetaInfo().getTorrentData();
        File tfile = new File(dataRoot, name + ".torrent");
        Files.write(tfile.toPath(), data);

        Snark snark = new Snark(util, tfile.getPath(), null, 0,
                                null, null, manager(), null, null,
                                dataRoot.getAbsolutePath(), base);

        Field f = SnarkManager.class.getDeclaredField("_snarks");
        f.setAccessible(true);
        ((Map<String, Snark>) f.get(manager())).put(name, snark);

        // getTorrentByBaseName() reads _filteredBaseNameToSnark, not _snarks
        Storage snarkStorage = snark.getStorage();
        if (snarkStorage != null) {
            Field fb = SnarkManager.class.getDeclaredField("_filteredBaseNameToSnark");
            fb.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Snark> baseNameMap = (Map<String, Snark>) fb.get(manager());
            baseNameMap.put(snarkStorage.getBaseName(), snark);
        }
    }

    private static SnarkManager manager() throws Exception {
        Field f = I2PSnarkServlet.class.getDeclaredField("_manager");
        f.setAccessible(true);
        return (SnarkManager) f.get(servlet);
    }

    /** Proxied GET for a torrent dir page; only parameters dir pages read are modeled. */
    private HttpServletRequest get(String path) {
        Map<String, String[]> params = new HashMap<>();
        Map<String, Object> results = new HashMap<>();
        results.put("getMethod", "GET");
        results.put("getRequestURI", CTX + path);
        results.put("getRequestURL", new StringBuffer("http://127.0.0.1:7657" + CTX + path));
        results.put("getParameterMap", Collections.unmodifiableMap(params));
        return proxy(HttpServletRequest.class, results);
    }

    private static String render(String rel, boolean stream, CountingWriter cw) throws Exception {
        File resource = new File(new File(servlet.manager().getDataDir(), rel).getAbsolutePath());
        PrintWriter pw = stream ? (PrintWriter) cw : null;
        return servlet.getListHTML(resource, CTX + "/" + rel, true, null, null, pw);
    }

    @Test
    public void testLargePageStreamedEqualsBuffered() throws Exception {
        String rel = "TestTorrent/";

        String buffered = render(rel, false, null);

        CountingWriter cw = new CountingWriter();
        String tail = render(rel, true, cw);
        String streamed = cw.toString() + tail;

        assertEquals(buffered, streamed);
        assertTrue("page suspiciously small: " + streamed.length(), streamed.length() > 2000);
        assertTrue("expected multiple drains, got " + cw.flushes, cw.flushes >= 3);
    }

    @Test
    public void testSmallPageBelowGateNeverDrains() throws Exception {
        String rel = "SmallTorrent/";
        String buffered = render(rel, false, null);

        CountingWriter cw = new CountingWriter();
        String tail = render(rel, true, cw);
        String streamed = cw.toString() + tail;

        assertEquals(buffered, streamed);
        assertEquals("below the gate no drain may occur", 0, cw.flushes);
    }
}
