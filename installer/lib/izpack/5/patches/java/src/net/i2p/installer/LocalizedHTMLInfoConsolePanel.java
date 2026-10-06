package net.i2p.installer;

import com.izforge.izpack.api.resource.Resources;
import com.izforge.izpack.installer.console.ConsolePanel;
import com.izforge.izpack.installer.panel.PanelView;
import com.izforge.izpack.installer.util.PanelHelper;
import com.izforge.izpack.panels.htmlinfo.HTMLInfoConsolePanel;

/**
 * The text-mode counterpart of {@link LocalizedHTMLInfoPanel}.
 *
 * <p>IzPack resolves a console panel by rewriting the declared class name's trailing
 * {@code Panel} to {@code ConsolePanel} ({@code PanelHelper.getConsolePanel}), so subclassing
 * {@code HTMLInfoPanel} without shipping this class makes {@code -console} and
 * {@code -options-auto} die on the welcome panel, before anything is installed:
 *
 * <pre>
 *   SEVERE: IzPackException: Console implementation not found for panel:
 *           net.i2p.installer.LocalizedHTMLInfoPanel
 * </pre>
 *
 * <p>The GUI installer never looks for this class, so the omission is invisible until the
 * text-mode path is used - which is the path the pack banner tells users to fall back to when
 * there is no X server, and the only way to install unattended.
 *
 * <p>The body has to be re-derived here rather than inherited. {@code HTMLInfoConsolePanel}
 * computes its resource name from the declared panel, which for us is
 * {@code LocalizedHTMLInfoPanel.info}, and resolves it against the {@link Resources} picocontainer
 * injects - the same key the GUI panel's proxy rewrites, but with no proxy in the way. Left
 * alone it finds nothing and prints an empty welcome screen. Rebasing through
 * {@link LocalizedHTMLInfoPanel#rebaseResourceName(String)} gives the stock
 * {@code HTMLInfoPanel.info} key, which every langpack carries.
 *
 * <p>Note this is the langpack <em>title</em>, not the HTML body: {@code getText} asks
 * {@code Resources.getString} for the name, and that is what {@code HTMLInfoPanelConsolePanel}
 * prints for the stock panel too. Following the stock behaviour is deliberate - a terminal gets
 * a one-line summary rather than a page of markup.
 *
 * @since 0.9.71+
 */
public class LocalizedHTMLInfoConsolePanel extends HTMLInfoConsolePanel {

    private final Resources resources;

    /** The stock panel's {@code HTMLInfoPanel.info}, not ours. */
    private final String resourceName;

    public LocalizedHTMLInfoConsolePanel(PanelView<ConsolePanel> panel, Resources resources) {
        super(panel, resources);
        this.resources = resources;
        this.resourceName = LocalizedHTMLInfoPanel.rebaseResourceName(
                PanelHelper.getPanelResourceName(panel.getPanel(), "info", resources));
    }

    @Override
    protected String getText() {
        return removeHTML(resources.getString(resourceName, null));
    }
}
