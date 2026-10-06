package net.i2p.installer;

import com.izforge.izpack.api.adaptator.IXMLElement;
import com.izforge.izpack.api.data.InstallData;
import com.izforge.izpack.api.data.Overrides;
import com.izforge.izpack.installer.automation.PanelAutomation;

/**
 * The {@code -options-auto} counterpart of {@link LocalizedHTMLInfoPanel}.
 *
 * <p>IzPack resolves an automation helper the same way it resolves a console panel, from the
 * declared class name ({@code PanelHelper.getAutomatedPanel}), so a subclass of a panel the
 * distribution ships has to ship one too or the automated path cannot get past this panel.
 *
 * <p>All three methods are empty, exactly as in the stock {@code HTMLInfoPanelAutomationHelper}:
 * an info panel has no settings to read from the auto-install file and contributes nothing to
 * the installation record. It exists purely so the class name resolves.
 *
 * @since 0.9.71+
 */
public class LocalizedHTMLInfoPanelAutomationHelper implements PanelAutomation {

    @Override
    public void createInstallationRecord(InstallData data, IXMLElement panelRoot) {
    }

    @Override
    public void runAutomated(InstallData data, IXMLElement panelRoot) {
    }

    @Override
    public void processOptions(InstallData data, Overrides overrides) {
    }
}
