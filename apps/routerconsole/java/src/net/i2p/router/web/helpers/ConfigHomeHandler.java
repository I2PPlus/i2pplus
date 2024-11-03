package net.i2p.router.web.helpers;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import net.i2p.router.web.App;
import net.i2p.router.web.FormHandler;

/**
 *  Simple home page configuration.
 *
 *  @since 0.9
 */
public class ConfigHomeHandler extends FormHandler {

    @Override
    protected void processForm() {
        String group = getJettyString("group");
        boolean deleting = _action.equals(_t("Delete selected"));
        boolean adding = _action.equals(_t("Add item"));
        boolean restoring = _action.equals(_t("Restore defaults"));
        if (adding || deleting || restoring) {
            String prop;
            String dflt;
            if ("1".equals(group)) {
                prop = HomeHelper.PROP_FAVORITES;
                dflt = HomeHelper.DEFAULT_FAVORITES;
            } else if ("2".equals(group)) {
                prop = HomeHelper.PROP_SERVICES;
                dflt = HomeHelper.DEFAULT_SERVICES;
            } else if ("3".equals(group)) {
                prop = SearchHelper.PROP_ENGINES;
                dflt = SearchHelper.ENGINES_DEFAULT;
            } else {
                addFormError("Bad group");
                return;
            }
            if (restoring) {
                //_context.router().saveConfig(prop, dflt);
                // remove config so user will see updates
                _context.router().saveConfig(prop, null);
                addFormNotice(_t("Restored default settings"));
                return;
            }
            String config = _context.getProperty(prop, dflt);
            Collection<App> apps;
            if ("3".equals(group)) {apps = HomeHelper.buildSearchApps(config);}
            else {apps = HomeHelper.buildApps(_context, config);}
            if (adding) {
                String name = getJettyString("nofilter_name");
                if (name == null || name.length() <= 0) {
                    addFormError(_t("No name entered"));
                    return;
                }
                String url = getJettyString("nofilter_url");
                if (url == null || url.length() <= 0) {
                    addFormError(_t("No URL entered"));
                    return;
                }
                name = name.replace(",", ".");
                url = url.replace(",", "."); // fail
                App app;
                if ("1".equals(group)) {app = new App(name, "", url, "/themes/console/images/planet.svg");}
                else if ("2".equals(group)) {app = new App(name, "", url, "/themes/console/images/package.svg");}
                else {app = new App(name, "", url, "/themes/console/images/helplink.svg");}
                apps.add(app);
                addFormNotice(_t("Added") + ": " + app.name);
            } else {
                // deleting
                Set<String> toDelete = new HashSet<String>();
                for (Object o : _settings.keySet()) {
                     if (!(o instanceof String)) {continue;}
                     String k = (String) o;
                     if (!k.startsWith("delete_")) {continue;}
                     k = k.substring(7);
                     toDelete.add(k);
                }
                for (Iterator<App> iter = apps.iterator(); iter.hasNext(); ) {
                    App app = iter.next();
                    if (toDelete.contains(app.name)) {
                        iter.remove();
                        addFormNotice(_t("Removed") + ": " + app.name);
                    }
                }
            }
            HomeHelper.saveApps(_context, prop, apps, !("3".equals(group)));
        }
    }

}