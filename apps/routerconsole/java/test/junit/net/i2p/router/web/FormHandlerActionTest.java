package net.i2p.router.web;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Tests for FormHandler.resolveEffectiveAction().
 *
 * Forms carry a hidden action=blah fallback plus submit buttons named action.
 * When a button is clicked the parameter map holds both values and the
 * servlet layer only surfaces the first (the hidden blah) to setAction(),
 * so the resolver must recover the clicked operation from the raw map.
 *
 * @since 0.9.70+
 */
public class FormHandlerActionTest {

    private static Map<String, Object> settings(String... actionValues) {
        Map<String, Object> m = new HashMap<>();
        m.put("action", actionValues);
        return m;
    }

    @Test
    public void testClickedButtonRecoversRealAction() {
        // hidden first, button second - the shadowing case
        assertEquals("clearBans", FormHandler.resolveEffectiveAction("blah", settings("blah", "clearBans")));
        assertEquals("resetDefaults", FormHandler.resolveEffectiveAction("blah", settings("blah", "resetDefaults")));
    }

    @Test
    public void testButtonOnlySubmission() {
        assertEquals("clearBans", FormHandler.resolveEffectiveAction("clearBans", settings("clearBans")));
        assertEquals("adduser", FormHandler.resolveEffectiveAction("adduser", settings("adduser")));
    }

    @Test
    public void testFallbackWhenOnlyHiddenValue() {
        // Enter-key submission: no real action, keep the fallback for default save
        assertEquals("blah", FormHandler.resolveEffectiveAction("blah", settings("blah")));
    }

    @Test
    public void testNullAndMissingSettings() {
        assertNull(FormHandler.resolveEffectiveAction(null, null));
        assertNull(FormHandler.resolveEffectiveAction(null, new HashMap<String, Object>()));
        assertEquals("blah", FormHandler.resolveEffectiveAction("blah", new HashMap<String, Object>()));
    }

    @Test
    public void testEmptyValuesSkipped() {
        assertEquals("savegraph", FormHandler.resolveEffectiveAction(null, settings("", "  ", "savegraph")));
        assertEquals("savesidebar", FormHandler.resolveEffectiveAction(null, settings(null, "savesidebar")));
    }

    @Test
    public void testMoveActionRecovered() {
        // sidebar move buttons submit values like move_3
        assertEquals("move_3", FormHandler.resolveEffectiveAction("blah", settings("blah", "move_3")));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void testNonStringArrayIgnored() {
        Map m = new HashMap();
        m.put("action", "notAnArray");
        assertEquals("blah", FormHandler.resolveEffectiveAction("blah", m));
    }
}
