package net.i2p.router.web.helpers;

import static org.junit.Assert.assertEquals;

import java.util.Map;
import org.junit.Test;

/**
 * Differential tests for {@link CapabilitiesRenderer}: the pre-built tier-state tables and the
 * cheaper {@code linkify} must render byte-identically to the string-concatenating forms they
 * replaced, because the netdb and sybil views show their output to the user.
 *
 * <p>Each reference implementation below is a verbatim copy of the code as it stood before the
 * optimization, so a divergence fails the test rather than silently changing a page.
 */
public class CapabilitiesRendererTest {

    /** Capability letters I2P routers advertise, in the combinations seen in the wild. */
    private static final String[] CAPS = {
        "", "f", "LfR", "NLR", "Xf", "UPf", "LR", "GR", "fLR", "REf", "XLR", "XfLR",
        "Base", "EfKf", "GfR", "Nf", "LfUf", "XfRf", "BDGf", "CBEHf", "0fLR", "nLRx",
        "fLRxyz", "KLMNOP", "KLMNOPXf", "BR", "XPGf", "dfLR", "f" + 'f' + 'f'
    };

    /** Tiers and reachability suffixes the two views pass. */
    private static final char[] TIERS = { '\0', 'D', 'E', 'G' };
    private static final char[] SUFFIXES = { '\0', 'R', 'U' };

    /**
     * The original {@code linkify}, which boxed and copied every unrecognised letter.
     *
     * @param caps the raw capability string
     * @param replacements the letter to link HTML map
     * @return the linkified capability string
     */
    private static String referenceLinkify(String caps, Map<Character, String> replacements) {
        StringBuilder buf = new StringBuilder(caps.length() * 2);
        for (int i = 0; i < caps.length(); i++) {
            char c = caps.charAt(i);
            buf.append(replacements.getOrDefault(c, String.valueOf(c)));
        }
        return buf.toString();
    }

    /** The original {@code applyTierState}, which built every replacement string per call. */
    private static String referenceApplyTierState(String caps, char tier, char suffix, boolean suffixAll) {
        if (tier == 0) {return caps;}
        String rv = caps.replace(String.valueOf(tier), "");
        rv = rv.replace("class=tier", "class=\"tier is" + tier + "\"");
        if (suffix != 0) {
            if (suffixAll) {
                rv = rv.replace("\"><span class", suffix + "" + tier + "\"><span class");
            } else {
                for (char c : CapabilitiesRenderer.TIER_LINK_LETTERS) {
                    rv = rv.replace("href=\"/netdb?caps=" + c, "href=\"/netdb?caps=" + c + suffix + tier);
                }
            }
        }
        return rv;
    }

    /** Every (tier, suffix, suffixAll) combination either view can produce. */
    @Test
    public void testLinkifyMatchesReference() {
        for (String caps : CAPS) {
            assertEquals(caps,
                         CapabilitiesRenderer.linkify(caps, CapabilitiesRenderer.CAP_REPLACEMENTS),
                         referenceLinkify(caps, CapabilitiesRenderer.CAP_REPLACEMENTS));
            assertEquals(caps,
                         CapabilitiesRenderer.linkify(caps, CapabilitiesRenderer.SYBIL_REPLACEMENTS),
                         referenceLinkify(caps, CapabilitiesRenderer.SYBIL_REPLACEMENTS));
        }
    }

    /** The table-driven rewrite must reproduce the concatenation it replaced, for every input. */
    @Test
    public void testApplyTierStateMatchesReference() {
        for (String caps : CAPS) {
            String linked = CapabilitiesRenderer.linkify(caps, CapabilitiesRenderer.CAP_REPLACEMENTS);
            for (char tier : TIERS) {
                for (char suffix : SUFFIXES) {
                    for (boolean suffixAll : new boolean[] { false, true }) {
                        assertEquals(caps + '/' + tier + '/' + suffix + '/' + suffixAll,
                                     referenceApplyTierState(linked, tier, suffix, suffixAll),
                                     CapabilitiesRenderer.applyTierState(linked, tier, suffix, suffixAll));
                    }
                }
            }
        }
    }

    /**
     * An unrecognized tier or suffix letter must fall back to the original behaviour rather than
     * being dropped, so a future capability letter degrades to today's rendering.
     */
    @Test
    public void testUnknownTierFallsBack() {
        String linked = CapabilitiesRenderer.linkify("LfR", CapabilitiesRenderer.CAP_REPLACEMENTS);
        assertEquals(referenceApplyTierState(linked, 'Z', 'R', false),
                     CapabilitiesRenderer.applyTierState(linked, 'Z', 'R', false));
        assertEquals(referenceApplyTierState(linked, 'D', 'Z', false),
                     CapabilitiesRenderer.applyTierState(linked, 'D', 'Z', false));
        assertEquals(referenceApplyTierState(linked, 'D', 'Z', true),
                     CapabilitiesRenderer.applyTierState(linked, 'D', 'Z', true));
    }

    /** No tier is a pass-through, so a tier-free row costs nothing. */
    @Test
    public void testNoTierIsUnchanged() {
        String linked = CapabilitiesRenderer.linkify("XfR", CapabilitiesRenderer.CAP_REPLACEMENTS);
        assertEquals(linked, CapabilitiesRenderer.applyTierState(linked, '\0', 'R', false));
    }
}
