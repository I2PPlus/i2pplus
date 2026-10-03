package net.i2p.router.web.helpers;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Conformance tests for the {@link TuningHelper} param label and description tables.
 *
 * <p>The console falls back to the raw param name when a label is absent and to an empty
 * description when one is absent, so a {@code Tuner} param added without matching entries
 * here ships a half-translated control with nothing to indicate it. Nothing enforced that,
 * and the two tables are populated by hand in separate static blocks roughly 150 lines
 * apart — the easiest way to forget one.
 *
 * <p>These tests pin the invariant that every registered param has a usable label and a
 * description, and that the two tables describe the same set of params.
 *
 * <p>Scope: this covers drift <em>within</em> the tables. Asserting that every
 * {@code Tuner} param appears here would need a live {@code RouterContext}, and the
 * dependency runs console&rarr;router, so the router's own test tree cannot see this class.
 * What is caught here is the realistic failure: adding an entry to one table and not the other.
 *
 * @since 0.9.71+
 */
public class TuningHelperParamConformanceTest {

    /**
     * Maximum length, in characters, of any single param description.
     *
     * <p>These strings render beside a control on the tuning page, so a longer one
     * visibly stretches the row. The existing table's longest entry is 67 and the
     * mean is ~45. Raising this limit to accommodate a new string is the wrong fix —
     * shorten the string instead.
     */
    private static final int MAX_DESCRIPTION_LENGTH = 70;

    private static List<String> keysMissingFrom(Map<String, String> have, Map<String, String> need) {
        List<String> rv = new ArrayList<>();
        for (String key : need.keySet()) {
            if (!have.containsKey(key)) {rv.add(key);}
        }
        return rv;
    }

    /** A label without a description renders as an unexplained slider. */
    @Test
    public void testEveryLabelHasADescription() {
        List<String> missing = keysMissingFrom(TuningHelper.PARAM_DESCRIPTIONS,
                                               TuningHelper.DISPLAY_NAMES);
        assertTrue("labels with no description: " + missing, missing.isEmpty());
    }

    /** A description with no label is unreachable from the console. */
    @Test
    public void testEveryDescriptionHasALabel() {
        List<String> missing = keysMissingFrom(TuningHelper.DISPLAY_NAMES,
                                               TuningHelper.PARAM_DESCRIPTIONS);
        assertTrue("descriptions with no label: " + missing, missing.isEmpty());
    }

    /** The tables must describe the same number of params. */
    @Test
    public void testTableSizesMatch() {
        assertEquals(TuningHelper.DISPLAY_NAMES.size(), TuningHelper.PARAM_DESCRIPTIONS.size());
    }

    /** Blank labels render as an empty control. */
    @Test
    public void testNoBlankLabels() {
        for (Map.Entry<String, String> e : TuningHelper.DISPLAY_NAMES.entrySet()) {
            assertNotNull("null label for " + e.getKey(), e.getValue());
            assertFalse("blank label for " + e.getKey(), e.getValue().trim().isEmpty());
        }
    }

    /** Blank descriptions are worse than none: they look intentional. */
    @Test
    public void testNoBlankDescriptions() {
        for (Map.Entry<String, String> e : TuningHelper.PARAM_DESCRIPTIONS.entrySet()) {
            assertNotNull("null description for " + e.getKey(), e.getValue());
            assertFalse("blank description for " + e.getKey(), e.getValue().trim().isEmpty());
        }
    }

    /** A description longer than the house limit distorts the tuning page layout. */
    @Test
    public void testDescriptionsWithinLengthLimit() {
        List<String> tooLong = new ArrayList<>();
        for (Map.Entry<String, String> e : TuningHelper.PARAM_DESCRIPTIONS.entrySet()) {
            if (e.getValue().length() > MAX_DESCRIPTION_LENGTH) {
                tooLong.add(e.getKey() + " (" + e.getValue().length() + " chars)");
            }
        }
        assertTrue("descriptions over " + MAX_DESCRIPTION_LENGTH + " chars: " + tooLong,
                   tooLong.isEmpty());
    }

    /** A description that runs to several sentences has the same effect as being too long. */
    @Test
    public void testDescriptionsAreSingleSentence() {
        List<String> multi = new ArrayList<>();
        for (Map.Entry<String, String> e : TuningHelper.PARAM_DESCRIPTIONS.entrySet()) {
            String v = e.getValue().trim();
            // A single sentence ends in one period; count the stops that are not
            // part of a decimal number or a known abbreviation.
            String stripped = v.replaceAll("\\d+\\.\\d+", "").replace("e.g.", "").replace("i.e.", "");
            int stops = stripped.length() - stripped.replace(".", "").length();
            if (stops > 1 || (stops == 1 && !stripped.endsWith("."))) {multi.add(e.getKey());}
        }
        assertTrue("descriptions that are not one sentence: " + multi, multi.isEmpty());
    }

    /**
     * A label identical to the raw param name means the entry was added but never
     * humanized, which is the same user-visible result as no label at all.
     */
    @Test
    public void testLabelsAreHumanized() {
        List<String> raw = new ArrayList<>();
        for (Map.Entry<String, String> e : TuningHelper.DISPLAY_NAMES.entrySet()) {
            if (e.getValue().trim().equals(e.getKey().trim())) {raw.add(e.getKey());}
        }
        assertTrue("labels equal to the raw param name: " + raw, raw.isEmpty());
    }

    /**
     * The streaming params whose values this build changed behaviour for. Pinning them
     * here means a future rename cannot silently drop their console labelling.
     */
    @Test
    public void testStreamingGrowthParamsAreRegistered() {
        String[] params = {
            "i2p.streaming.slowStartGrowthRateFactor",
            "i2p.streaming.congestionAvoidanceGrowthRateFactor",
            "i2p.streaming.minPacingRate",
            "i2p.streaming.maxWindowSize",
            "i2p.streaming.maxSlowStartWindow"
        };
        for (String p : params) {
            assertTrue("missing display name: " + p, TuningHelper.DISPLAY_NAMES.containsKey(p));
            assertTrue("missing description: " + p, TuningHelper.PARAM_DESCRIPTIONS.containsKey(p));
        }
    }

    /** The two growth factors invert the Tuner scale, so the label must say which way is faster. */
    @Test
    public void testGrowthFactorDescriptionsStateDirection() {
        String ss = TuningHelper.PARAM_DESCRIPTIONS.get("i2p.streaming.slowStartGrowthRateFactor");
        String ca = TuningHelper.PARAM_DESCRIPTIONS.get("i2p.streaming.congestionAvoidanceGrowthRateFactor");
        assertNotNull(ss);
        assertNotNull(ca);
        assertTrue("slow-start description must state the direction: " + ss,
                   ss.toLowerCase().contains("faster") || ss.toLowerCase().contains("higher"));
        assertTrue("CA description must state the direction: " + ca,
                   ca.toLowerCase().contains("faster") || ca.toLowerCase().contains("higher"));
    }
}

