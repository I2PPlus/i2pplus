package net.i2p.router.web.helpers;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.i2p.data.DataHelper;
import net.i2p.router.web.CSSHelper;
import net.i2p.router.web.FormHandler;

/**
 * Simple sidebar configuration handler.
 *
 * @since 0.9.1
 */
public class ConfigSidebarHandler extends FormHandler {

    private static final String ORDER_PREFIX = "order_";
    private static final String DELETE_PREFIX = "delete_";
    private static final String MOVE_PREFIX = "move_";

    /**
     * Processes the form submission from the sidebar configuration page.
     * Handles add, delete, move, save order, and restore defaults actions.
     * Delegates to helper methods as appropriate.
     */
    @Override
    protected void processForm() {
        if (_action == null) {
            return;
        }
        net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
        String group = getJettyString("group");

        boolean adding = _action.equals(_t("Add item"));
        boolean deleting = _action.equals(_t("Delete selected"));
        boolean moving = _action.startsWith(MOVE_PREFIX);
        boolean saving = _action.equals(_t("Save order"));
        boolean editing = adding || deleting || moving || saving;

        boolean unifiedSidebar = ctx.getBooleanProperty("routerconsole.unifiedSidebar");
        boolean stickySidebar = ctx.getBooleanProperty("routerconsole.stickySidebar");

        if (_action.equals(_t("Save")) && "0".equals(group)) {handleSave(unifiedSidebar, stickySidebar);}
        else if (_action.equals(_t("Restore full default"))) {restoreDefault(true);}
        else if (_action.equals(_t("Restore minimal default"))) {restoreDefault(false);}
        else if (editing) {moveSection();}
    }

    private void handleSave(boolean currentUnifiedSidebar, boolean currentStickySidebar) {
        Map<String, String> toAdd = new HashMap<>(4);
        try {
            int refreshInterval = parseRefreshInterval(getJettyString("refreshInterval"));
            if (refreshInterval == 0) {
                toAdd.put(CSSHelper.PROP_DISABLE_REFRESH, "true");
                toAdd.put(CSSHelper.PROP_REFRESH, CSSHelper.DEFAULT_REFRESH);
            } else {
                toAdd.put(CSSHelper.PROP_DISABLE_REFRESH, "false");
                toAdd.put(CSSHelper.PROP_REFRESH, Integer.toString(refreshInterval));
            }

            boolean submittedUnifiedSidebar = "true".equals(getJettyStringOrDefault("unifiedSidebar", "false"));
            if (submittedUnifiedSidebar != currentUnifiedSidebar) {
                toAdd.put("routerconsole.unifiedSidebar", Boolean.toString(submittedUnifiedSidebar));
            }

            boolean submittedStickySidebar = "true".equals(getJettyStringOrDefault("stickySidebar", "false"));
            if (submittedStickySidebar != currentStickySidebar) {
                toAdd.put("routerconsole.stickySidebar", Boolean.toString(submittedStickySidebar));
            }

            if (!toAdd.isEmpty()) {
                addFormNotice(_t("Sidebar preferences updated"), true);
                _context.router().saveConfig(toAdd, null);
            }
        } catch (NumberFormatException e) {
            addFormError(_t("Refresh interval must be a number"), true);
        }
    }

    private int parseRefreshInterval(String refreshStr) throws NumberFormatException {
        int refresh = Integer.parseInt(refreshStr);
        if (refresh < 0) {return 0;}
        return (refresh < CSSHelper.MIN_REFRESH) ? CSSHelper.MIN_REFRESH : refresh;
    }

    private String getJettyStringOrDefault(String key, String defaultVal) {
        String val = getJettyString(key);
        return val != null ? val : defaultVal;
    }

    private void restoreDefault(boolean full) {
        String prop = SidebarHelper.PROP_SUMMARYBAR + "default";
        String value = isAdvanced() ? (full ? SidebarHelper.DEFAULT_FULL_ADVANCED : SidebarHelper.DEFAULT_MINIMAL_ADVANCED)
                                    : (full ? SidebarHelper.DEFAULT_FULL : SidebarHelper.DEFAULT_MINIMAL);
        _context.router().saveConfig(prop, value);
        addFormNotice(_t(full ? "Full sidebar defaults restored." : "Minimal sidebar defaults restored."), true);
    }

    /**
     * Moves, adds, deletes, or saves sidebar sections ordering based on the current _action.
     * Updates the sidebar sections order and persists the changes.
     */
    public void moveSection() {
        boolean adding = _action.equals(_t("Add item"));
        boolean deleting = _action.equals(_t("Delete selected"));
        boolean moving = _action.startsWith(MOVE_PREFIX);

        Map<Integer, String> sections = new TreeMap<>();

        for (Object o : _settings.keySet()) {
            if (!(o instanceof String)) {continue;}
            String k = (String) o;
            if (!k.startsWith(ORDER_PREFIX)) {continue;}
            String v = getJettyString(k);
            String keyTrimmed = k.substring(ORDER_PREFIX.length());
            int underscoreIndex = keyTrimmed.indexOf('_');
            if (underscoreIndex == -1) {continue;}
            k = keyTrimmed.substring(underscoreIndex + 1);
            try {
                int order = Integer.parseInt(v);
                sections.put(order, k);
            } catch (NumberFormatException e) {
                addFormError(_t("Order must be an integer"), true);
                return;
            }
        }

        if (adding) {handleAddSection(sections);}
        else if (deleting) {handleDeleteSections(sections);}
        else if (moving) {handleMoveSection(sections);}

        SidebarHelper.saveSummaryBarSections(_context, "default", sections);
        addFormNotice(_t("Saved order of sections.") + " " + _t("Sidebar will refresh shortly."), true);
    }

    private void handleAddSection(Map<Integer, String> sections) {
        String name = getJettyString("name");
        if (name == null || name.isEmpty()) {
            addFormError(_t("No section selected"), true);
            return;
        }
        String orderStr = getJettyString("order");
        if (orderStr == null || orderStr.isEmpty()) {
            addFormError(_t("No order entered"), true);
            return;
        }
        name = DataHelper.escapeHTML(name);
        orderStr = DataHelper.escapeHTML(orderStr);
        try {
            int order = Integer.parseInt(orderStr);
            sections.put(order, name);
            addFormNotice(_t("Added") + ": " + name);
        } catch (NumberFormatException e) {
            addFormError(_t("Order must be an integer"), true);
        }
    }

    private void handleDeleteSections(Map<Integer, String> sections) {
        Set<Integer> toDelete = new HashSet<>();
        for (Object o : _settings.keySet()) {
            if (!(o instanceof String)) {continue;}
            String k = (String) o;
            if (!k.startsWith(DELETE_PREFIX)) {continue;}
            k = k.substring(DELETE_PREFIX.length());
            try {
                int keyInt = Integer.parseInt(k);
                toDelete.add(keyInt);
            } catch (NumberFormatException e) {}
        }
        Iterator<Map.Entry<Integer, String>> iter = sections.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<Integer, String> e = iter.next();
            if (toDelete.contains(e.getKey())) {
                addFormNotice(_t("Removed") + ": " + e.getValue(), true);
                iter.remove();
            }
        }
    }

    private void handleMoveSection(Map<Integer, String> sections) {
        String[] parts = DataHelper.split(_action, "_");
        try {
            int from = Integer.parseInt(parts[1]);
            int to = 0;
            switch (parts[2]) {
                case "top":
                    to = 0;
                    break;
                case "up":
                    to = from - 1;
                    break;
                case "down":
                    to = from + 1;
                    break;
                case "bottom":
                    to = sections.size() - 1;
                    break;
                default:
                    to = from;
                    break;
            }
            int direction = (parts[2].equals("down") || parts[2].equals("bottom")) ? 1 : -1;
            for (int i = from; direction * i < direction * to; i += direction) {
                String temp = sections.get(i + direction);
                sections.put(i + direction, sections.get(i));
                sections.put(i, temp);
            }
            addFormNotice(_t("Moved") + ": " + sections.get(to), true);
        } catch (NumberFormatException e) {
            addFormError(_t("Order must be an integer"), true);
        }
    }

    /**
     * Scans settings to detect move button actions and sets the _action field accordingly.
     */
    public void setMovingAction() {
        for (Object o : _settings.keySet()) {
            if (!(o instanceof String)) {continue;}
            String k = (String) o;
            if (k.startsWith(MOVE_PREFIX) && k.endsWith(".x") && _settings.get(k) != null) {
                _action = k.substring(0, k.length() - 2);
                break;
            }
        }
    }

}