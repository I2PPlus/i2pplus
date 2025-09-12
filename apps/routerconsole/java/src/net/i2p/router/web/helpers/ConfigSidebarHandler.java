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
 *  Simple sidebar configuration.
 *
 *  @since 0.9.1
 */
public class ConfigSidebarHandler extends FormHandler {

    @Override
    protected void processForm() {
        if (_action == null) {return;}
        net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
        String group = getJettyString("group");
        boolean adding = _action.equals(_t("Add item"));
        boolean deleting = _action.equals(_t("Delete selected"));
        boolean moving = _action.startsWith("move_");
        boolean saving = _action.equals(_t("Save order"));
        boolean editing = adding || deleting || moving || saving;
        boolean unifiedSidebar = ctx.getBooleanProperty("routerconsole.unifiedSidebar");
        boolean stickySidebar = ctx.getBooleanProperty("routerconsole.stickySidebar");
        if (_action.equals(_t("Save")) && "0".equals(group)) {
            try {
                Map<String, String> toAdd = new HashMap<String, String>(4);

                int refreshInterval = Integer.parseInt(getJettyString("refreshInterval"));
                if (refreshInterval < 0) {refreshInterval = 0;}
                else if (refreshInterval < CSSHelper.MIN_REFRESH) {refreshInterval = CSSHelper.MIN_REFRESH;}
                if (refreshInterval == 0) {
                    toAdd.put(CSSHelper.PROP_DISABLE_REFRESH, "true");
                    toAdd.put(CSSHelper.PROP_REFRESH, CSSHelper.DEFAULT_REFRESH);
                } else {
                    toAdd.put(CSSHelper.PROP_DISABLE_REFRESH, "false");
                    toAdd.put(CSSHelper.PROP_REFRESH, Integer.toString(refreshInterval));
                }

                String unifiedSidebarStr = getJettyString("unifiedSidebar");
                if (unifiedSidebarStr == null) {unifiedSidebarStr = "false";}
                boolean submittedUnifiedSidebar = "true".equals(unifiedSidebarStr);
                if (submittedUnifiedSidebar != unifiedSidebar) {
                    toAdd.put("routerconsole.unifiedSidebar", Boolean.toString(submittedUnifiedSidebar));
                }

                String stickySidebarStr = getJettyString("stickySidebar");
                boolean submittedStickySidebar = "true".equals(stickySidebarStr);
                if (submittedStickySidebar != stickySidebar) {
                    toAdd.put("routerconsole.stickySidebar", Boolean.toString(submittedStickySidebar));
                }

                if (!toAdd.isEmpty()) {
                    addFormNotice(_t("Sidebar preferences updated"), true);
                    _context.router().saveConfig(toAdd, null);
                }
            } catch (java.lang.NumberFormatException e) {
                addFormError(_t("Refresh interval must be a number"), true);
                return;
            }
        } else if (_action.equals(_t("Restore full default"))) {
            _context.router().saveConfig(SidebarHelper.PROP_SUMMARYBAR + "default", isAdvanced() ? SidebarHelper.DEFAULT_FULL_ADVANCED : SidebarHelper.DEFAULT_FULL);
            addFormNotice(_t("Full sidebar defaults restored.") + " " + _t("Sidebar will refresh shortly."), true);
        } else if (_action.equals(_t("Restore minimal default"))) {
            _context.router().saveConfig(SidebarHelper.PROP_SUMMARYBAR + "default", isAdvanced() ? SidebarHelper.DEFAULT_MINIMAL_ADVANCED : SidebarHelper.DEFAULT_MINIMAL);
            addFormNotice(_t("Minimal sidebar defaults restored.") + " " + _t("Sidebar will refresh shortly."), true);
        } else if (editing) {moveSection();}
    }

    public void moveSection() {
        boolean deleting = _action.equals(_t("Delete selected"));
        boolean adding = _action.equals(_t("Add item"));
        boolean saving = _action.equals(_t("Save order"));
        boolean moving = _action.startsWith("move_");

        Map<Integer, String> sections = new TreeMap<Integer, String>();
        for (Object o : _settings.keySet()) {
            if (!(o instanceof String)) {continue;}
            String k = (String) o;
            if (!k.startsWith("order_")) {continue;}
            String v = getJettyString(k);
            k = k.substring(6);
            k = k.substring(k.indexOf('_') + 1);
            try {
                int order = Integer.parseInt(v);
                sections.put(order, k);
            } catch (java.lang.NumberFormatException e) {
                addFormError(_t("Order must be an integer"), true);
                return;
            }
        }
        if (adding) {
            String name = getJettyString("name");
            if (name == null || name.length() <= 0) {
                addFormError(_t("No section selected"), true);
                return;
            }
            String order = getJettyString("order");
            if (order == null || order.length() <= 0) {
                addFormError(_t("No order entered"), true);
                return;
            }
            name = DataHelper.escapeHTML(name).replace(",", "&#44;");
            order = DataHelper.escapeHTML(order).replace(",", "&#44;");
            try {
                int ki = Integer.parseInt(order);
                sections.put(ki, name);
                addFormNotice(_t("Added") + ": " + name);
            } catch (java.lang.NumberFormatException e) {
                addFormError(_t("Order must be an integer"), true);
                return;
            }
        } else if (deleting) {
            Set<Integer> toDelete = new HashSet<Integer>();
            for (Object o : _settings.keySet()) {
                if (!(o instanceof String))
                    continue;
                String k = (String) o;
                if (!k.startsWith("delete_"))
                    continue;
                k = k.substring(7);
                try {
                    int ki = Integer.parseInt(k);
                    toDelete.add(ki);
                } catch (java.lang.NumberFormatException e) {
                    continue;
                }
            }
            for (Iterator<Map.Entry<Integer, String>> iter = sections.entrySet().iterator(); iter.hasNext(); ) {
                Map.Entry<Integer, String> e = iter.next();
                Integer i = e.getKey();
                if (toDelete.contains(i)) {
                    String removedName = e.getValue();
                    iter.remove();
                    addFormNotice(_t("Removed") + ": " + removedName, true);
                }
            }
        } else if (moving) {
            String parts[] = DataHelper.split(_action, "_");
            try {
                int from = Integer.parseInt(parts[1]);
                int to = 0;
                if ("up".equals(parts[2]))
                    to = from - 1;
                if ("down".equals(parts[2]))
                    to = from + 1;
                if ("bottom".equals(parts[2]))
                    to = sections.size() - 1;
                int n = -1;
                if ("down".equals(parts[2]) || "bottom".equals(parts[2]))
                    n = 1;
                for (int i = from; n * i < n * to; i += n) {
                    String temp = sections.get(i + n);
                    sections.put(i + n, sections.get(i));
                    sections.put(i, temp);
                }
                addFormNotice(_t("Moved") + ": " + sections.get(to), true);
            } catch (java.lang.NumberFormatException e) {
                addFormError(_t("Order must be an integer"), true);
                return;
            }
        }
        SidebarHelper.saveSummaryBarSections(_context, "default", sections);
        addFormNotice(_t("Saved order of sections.") + " " + _t("Sidebar will refresh shortly."), true);
    }

    public void setMovingAction() {
        for (Object o : _settings.keySet()) {
            if (!(o instanceof String)) {continue;}
            String k = (String) o;
            if (k.startsWith("move_") && k.endsWith(".x") && _settings.get(k) != null) {
                _action = k.substring(0, k.length() - 2);
                break;
            }
        }
    }

}