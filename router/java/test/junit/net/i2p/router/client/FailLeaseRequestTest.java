package net.i2p.router.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.router.RouterContext;
import net.i2p.stat.StatManager;
import net.i2p.util.LogManager;
import net.i2p.util.RandomSource;
import net.i2p.util.SimpleTimer2;

/**
 * failLeaseRequest() must tolerate every null state before it dereferences
 * anything: RequestLeaseSetJob calls it on early-failure paths with a null
 * requested LeaseSet or a LeaseSet without a destination.
 */
public class FailLeaseRequestTest {

    private ClientConnectionRunner _runner;
    private File _tmpDir;

    @Before
    public void setUp() {
        _tmpDir = new File(System.getProperty("java.io.tmpdir"), "i2p-leasefail-" + System.nanoTime());
        assertTrue(_tmpDir.mkdirs());

        RouterContext ctx = mock(RouterContext.class);
        when(ctx.getConfigDir()).thenReturn(_tmpDir);
        when(ctx.getProperty(anyString(), anyString()))
                .thenReturn(new File(_tmpDir, "logger.config").getAbsolutePath());
        LogManager lm = new LogManager(ctx);
        when(ctx.logManager()).thenReturn(lm);
        when(ctx.statManager()).thenReturn(mock(StatManager.class));
        when(ctx.simpleTimer2()).thenReturn(mock(SimpleTimer2.class));
        RandomSource rnd = mock(RandomSource.class);
        when(rnd.nextInt()).thenReturn(7);
        when(ctx.random()).thenReturn(rnd);

        _runner = new ClientConnectionRunner(ctx, null, null);
    }

    @After
    public void tearDown() {
        deleteRecursively(_tmpDir);
    }

    /** A null request must be ignored, not dereferenced. */
    @Test
    public void nullRequestIsIgnored() {
        _runner.failLeaseRequest(null);
        assertNull(_runner.getLeaseRequest(Hash.FAKE_HASH));
    }

    /**
     * The live RequestLeaseSetJob path: requested LeaseSet is null, so
     * bookkeeping must clear slots without dereferencing it.
     */
    @Test
    public void nullRequestedLeaseSetIsIgnored() {
        LeaseRequestState req = new LeaseRequestState(null, null, 0, 0, null);
        _runner.failLeaseRequest(req);
        assertNull(_runner.getLeaseRequest(Hash.FAKE_HASH));
    }

    /** A requested set with no destination takes the clear-slots path quietly. */
    @Test
    public void destinationlessLeaseSetClearsQuietly() {
        LeaseRequestState req = new LeaseRequestState(null, null, 0, 0, new LeaseSet());
        _runner.failLeaseRequest(req);
        assertNull(_runner.getLeaseRequest(Hash.FAKE_HASH));
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
