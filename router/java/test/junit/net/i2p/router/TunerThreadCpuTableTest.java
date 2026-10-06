package net.i2p.router;

import static org.junit.Assert.*;

import org.junit.Test;

import net.i2p.router.Tuner.ThreadCpuTable;

/**
 * Tests the thread-id keyed sample table behind {@code Tuner.sampleStageCpu()}.
 *
 * <p>Every 5s cycle the sampler rebuilds this table from the live id list, reads
 * the stage each thread was classified into, and updates the CPU baseline. The
 * values it publishes drive live thread-pool sizing, so three invariants matter:
 *
 * <ol>
 *   <li>State survives a cycle for a thread that is still alive and is
 *       attributed to the stage it was classified into.</li>
 *   <li>State does not survive for a thread that died, so a later thread
 *       cannot inherit its stage or baseline.</li>
 *   <li>Per-stage CPU percentages come out the same as the pre-table sampler
 *       computed them from a {@code HashMap} of baselines.</li>
 * </ol>
 *
 * <p>Stage classification is pinned here too, because it is what makes the name
 * lookup cacheable: {@code Tuner.stageFor} has to answer exactly what the old
 * inline {@code startsWith} loop answered, including its first-match-wins
 * ordering over the stage prefixes.
 *
 * @since 0.9.71+
 */
public class TunerThreadCpuTableTest {

    private static final long NS = 1_000_000_000L;

    /**
     * One sampling pass, mirroring the loop in {@code Tuner.sampleStageCpu()}
     * with the MXBean calls replaced by the supplied arrays.
     *
     * @param table  table under test
     * @param ids    live thread ids this cycle
     * @param names  thread names, index-aligned with ids
     * @param cpuNs  absolute CPU nanoseconds per thread, as the MXBean reports
     * @param wallMs wall clock elapsed since the previous pass
     * @return sum of per-thread cores-percent this pass would publish
     */
    private static double samplePass(ThreadCpuTable table, long[] ids, String[] names,
                                     long[] cpuNs, long wallMs) {
        table.beginCycle(ids, ids.length);
        for (int i = 0; i < ids.length; i++) {
            if (table.stageOf(ids[i]) == ThreadCpuTable.UNRESOLVED)
                table.setStage(ids[i], Tuner.stageFor(names[i]));
        }
        double sum = 0;
        for (int i = 0; i < ids.length; i++) {
            long id = ids[i];
            if (table.stageOf(id) < 0) continue;
            long prev = table.cpuOf(id);
            if (prev >= 0) {
                long delta = cpuNs[i] - prev;
                if (delta < 0) {
                    // Backwards counter means the id now belongs to a younger
                    // thread: drop the carried stage rather than trust it.
                    table.setStage(id, ThreadCpuTable.UNRESOLVED);
                    continue;
                }
                double cores = delta / 1e9 / (wallMs / 1000.0);
                if (cores > 0)
                    sum += Math.min(1000.0, cores * 100.0);
            }
            table.setCpu(id, cpuNs[i]);
        }
        return sum;
    }

    // ---- stage classification ----

    @Test
    public void stageForMatchesEveryTrackedPrefix() {
        String[] names = {
            "UDPPktHandler", "UDPPktHandler-12", "UDPPktPusher-3", "UDPSender", "UDPReceiver",
            "UDPEstab-1", "UDMMsgRX", "NTCPReader", "NTCPWriter", "NTCPPumper"
        };
        for (String name : names)
            assertTrue(name + " should match a tracked stage", Tuner.stageFor(name) >= 0);
    }

    @Test
    public void stageForRejectsUntrackedNames() {
        String[] names = {
            "main", "SimpleTimer2.4", "NTCPFinis.7", "HttpServer", "UDPPkt", ""
        };
        for (String name : names)
            assertEquals(name + " matches no stage", ThreadCpuTable.NO_STAGE, Tuner.stageFor(name));
    }

    @Test
    public void stageForIsPrefixMatchNotExactMatch() {
        // Pool counters are appended to worker names, so attribution must not
        // depend on the suffix.
        assertEquals(Tuner.stageFor("UDPPktHandler"), Tuner.stageFor("UDPPktHandler-99"));
    }

    // ---- carry-forward across a cycle ----

    @Test
    public void liveThreadKeepsStageAndBaseline() {
        ThreadCpuTable table = new ThreadCpuTable();
        long[] ids = { 11L };
        table.beginCycle(ids, ids.length);
        assertEquals(ThreadCpuTable.UNRESOLVED, table.stageOf(11L));
        assertEquals(ThreadCpuTable.NO_CPU, table.cpuOf(11L));

        table.setStage(11L, Tuner.stageFor("UDPSender"));
        table.setCpu(11L, 5 * NS);

        table.beginCycle(ids, ids.length);
        assertEquals(Tuner.stageFor("UDPSender"), table.stageOf(11L));
        assertEquals(5 * NS, table.cpuOf(11L));
    }

    @Test
    public void nonStageThreadIsClassifiedNotRetried() {
        ThreadCpuTable table = new ThreadCpuTable();
        long[] ids = { 3L };
        table.beginCycle(ids, ids.length);
        table.setStage(3L, Tuner.stageFor("main"));
        table.beginCycle(ids, ids.length);
        // NO_STAGE, not UNRESOLVED: a thread that matched nothing must not go
        // back on the ThreadInfo path on every cycle.
        assertEquals(ThreadCpuTable.NO_STAGE, table.stageOf(3L));
    }

    @Test
    public void setStageOnAbsentIdInsertsRatherThanDropping() {
        ThreadCpuTable table = new ThreadCpuTable();
        table.beginCycle(new long[] { 21L }, 1);
        table.setStage(21L, Tuner.stageFor("NTCPReader"));
        assertEquals(Tuner.stageFor("NTCPReader"), table.stageOf(21L));
        assertEquals(1, table.size());
    }

    // ---- dead threads drop out ----

    @Test
    public void deadThreadDropsOutOfTheTable() {
        ThreadCpuTable table = new ThreadCpuTable();
        table.beginCycle(new long[] { 4L, 5L }, 2);
        table.setStage(4L, Tuner.stageFor("UDPPktHandler"));
        table.setCpu(4L, 9 * NS);
        table.setStage(5L, Tuner.stageFor("NTCPReader"));

        table.beginCycle(new long[] { 5L }, 1);
        assertEquals(1, table.size());
        assertEquals(ThreadCpuTable.UNRESOLVED, table.stageOf(4L));
        assertEquals(ThreadCpuTable.NO_CPU, table.cpuOf(4L));
        assertEquals(Tuner.stageFor("NTCPReader"), table.stageOf(5L));
    }

    @Test
    public void recycledIdAfterAGapStartsUnresolved() {
        ThreadCpuTable table = new ThreadCpuTable();
        table.beginCycle(new long[] { 12L }, 1);
        table.setStage(12L, Tuner.stageFor("UDPPktHandler"));
        table.setCpu(12L, 42 * NS);

        table.beginCycle(new long[0], 0);
        table.beginCycle(new long[] { 12L }, 1);
        assertEquals(ThreadCpuTable.UNRESOLVED, table.stageOf(12L));
        assertEquals(ThreadCpuTable.NO_CPU, table.cpuOf(12L));
    }

    @Test
    public void recycledIdWithoutAGapIsCaughtByBackwardsDelta() {
        ThreadCpuTable table = new ThreadCpuTable();
        long[] ids = { 12L };
        String[] names = { "UDPPktHandler" };
        table.beginCycle(ids, ids.length);
        table.setStage(12L, Tuner.stageFor(names[0]));
        table.setCpu(12L, 42 * NS);

        // The id never left the live list but is now a younger thread, so its
        // CPU total starts below the carried baseline. Nothing may be
        // attributed and the carried stage must be dropped for re-reading.
        assertEquals(0.0, samplePass(table, ids, names, new long[] { 1_000_000L }, 5000L), 0.0);
        assertEquals(ThreadCpuTable.UNRESOLVED, table.stageOf(12L));
    }

    // ---- attribution matches the pre-table sampler ----

    @Test
    public void summedStagePctMatchesDirectComputation() {
        ThreadCpuTable table = new ThreadCpuTable();
        long[] ids = { 1L, 2L, 3L };
        String[] names = { "UDPSender", "UDPSender", "main" };
        long wallMs = 5000L;

        // The first pass establishes baselines only; nothing is attributed.
        assertEquals(0.0, samplePass(table, ids, names, new long[] { NS, 4 * NS, 2 * NS }, wallMs), 0.0);

        // 0.5s and 1.0s of CPU over a 5s window is 0.1 and 0.2 cores, i.e.
        // 10% and 20% of a core. The third thread matches no stage.
        long[] second = { NS + NS / 2, 5 * NS, 3 * NS };
        assertEquals(30.0, samplePass(table, ids, names, second, wallMs), 1e-9);
    }

    @Test
    public void zeroDeltaAttributesNothing() {
        ThreadCpuTable table = new ThreadCpuTable();
        long[] ids = { 1L };
        String[] names = { "UDPSender" };
        samplePass(table, ids, names, new long[] { 3 * NS }, 5000L);
        // No time passed: cores is 0, so no stat data and no streak change.
        assertEquals(0.0, samplePass(table, ids, names, new long[] { 3 * NS }, 5000L), 0.0);
    }

    @Test
    public void hugeDeltaIsClampedToOneThousandPct() {
        ThreadCpuTable table = new ThreadCpuTable();
        long[] ids = { 1L };
        String[] names = { "NTCPPumper" };
        samplePass(table, ids, names, new long[] { 0L }, 1000L);
        // 30s of CPU in a 1s window is 30 cores, clamped to the 1000% ceiling.
        assertEquals(1000.0, samplePass(table, ids, names, new long[] { 30 * NS }, 1000L), 1e-9);
    }

    // ---- capacity ----

    @Test
    public void growsPastInitialCapacityAndStaysConsistent() {
        ThreadCpuTable table = new ThreadCpuTable();
        int count = 5000;
        long[] ids = new long[count];
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            ids[i] = i + 1L;
            names[i] = (i % 3 == 0) ? "UDPSender" : "HttpServer";
        }
        table.beginCycle(ids, ids.length);
        for (int i = 0; i < count; i++)
            table.setStage(ids[i], Tuner.stageFor(names[i]));
        assertEquals(count, table.size());

        // Rehashing must preserve every entry across the generation swap.
        table.beginCycle(ids, ids.length);
        assertEquals(count, table.size());
        for (int i = 0; i < count; i++)
            assertEquals(Tuner.stageFor(names[i]), table.stageOf(ids[i]));
    }
}
