package net.i2p.router.web.helpers;

import java.util.HashMap;
import java.util.Map;

import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.web.FormHandler;

/**
 * Handler to deal with form submissions from the tunnel config form and act
 * upon the values.
 *
 */
public class ConfigTunnelsHandler extends FormHandler {

    private boolean _shouldSave;

    @Override
    protected void processForm() {
        if (_shouldSave) {
            saveChanges();
        } else {
            // noop
        }
    }

    public void setShouldsave(String moo) {
        if ( (moo != null) && (moo.equals(_t("Save changes"))) )
            _shouldSave = true;
    }

    /**
     * The user made changes to the network config and wants to save them, so
     * lets go ahead and do so.
     *
     */
    private void saveChanges() {
        TunnelManagerFacade mgr = _context.tunnelManager();
        boolean saveRequired = false;
        Map<String, String> changes = new HashMap<String, String>();

        if (_log.shouldDebug())
            _log.debug("Saving changes, with props = " + _settings + ".");

        int updated = 0;
        for (int index = 0;  ; index++) {
            Object val = _settings.get("pool." + index);
            if (val == null) break;
            Hash client = new Hash();

            String poolName = (val instanceof String ? (String)val : ((String[])val)[0]);

            TunnelPoolSettings in = null;
            TunnelPoolSettings out = null;
            if ("exploratory".equals(poolName)) {
                in = mgr.getInboundSettings();
                out = mgr.getOutboundSettings();
            } else {
                try {
                    client.fromBase64(poolName);
                } catch (DataFormatException dfe) {
                    addFormError(_t("Internal error: could not resolve pool name {0}.", poolName), true);
                    continue;
                }
                in = mgr.getInboundSettings(client);
                out = mgr.getOutboundSettings(client);
            }

            if ( (in == null) || (out == null) ) {
                addFormError(_t("Internal error: could not find pool settings for {0}.", poolName), true);
                continue;
            }

            Object di = _settings.get(index + ".depthInbound");
            if (di == null) {
                // aliased pools
                continue;
            }
            in.setLength(getInt(di));
            out.setLength(getInt(_settings.get(index + ".depthOutbound")));
            in.setLengthVariance(getInt(_settings.get(index + ".varianceInbound")));
            out.setLengthVariance(getInt(_settings.get(index + ".varianceOutbound")));
            in.setQuantity(getInt(_settings.get(index + ".quantityInbound")));
            out.setQuantity(getInt(_settings.get(index + ".quantityOutbound")));
            in.setBackupQuantity(getInt(_settings.get(index + ".backupInbound")));
            out.setBackupQuantity(getInt(_settings.get(index + ".backupOutbound")));

            if ("exploratory".equals(poolName)) {
                changes.put(TunnelPoolSettings.PREFIX_INBOUND_EXPLORATORY +
                                                   TunnelPoolSettings.PROP_LENGTH, in.getLength()+"");
                changes.put(TunnelPoolSettings.PREFIX_OUTBOUND_EXPLORATORY +
                                                   TunnelPoolSettings.PROP_LENGTH, out.getLength()+"");
                changes.put(TunnelPoolSettings.PREFIX_INBOUND_EXPLORATORY +
                                                   TunnelPoolSettings.PROP_LENGTH_VARIANCE, in.getLengthVariance()+"");
                changes.put(TunnelPoolSettings.PREFIX_OUTBOUND_EXPLORATORY +
                                                   TunnelPoolSettings.PROP_LENGTH_VARIANCE, out.getLengthVariance()+"");
                changes.put(TunnelPoolSettings.PREFIX_INBOUND_EXPLORATORY +
                                                   TunnelPoolSettings.PROP_QUANTITY, in.getQuantity()+"");
                changes.put(TunnelPoolSettings.PREFIX_OUTBOUND_EXPLORATORY +
                                                   TunnelPoolSettings.PROP_QUANTITY, out.getQuantity()+"");
                changes.put(TunnelPoolSettings.PREFIX_INBOUND_EXPLORATORY +
                                                   TunnelPoolSettings.PROP_BACKUP_QUANTITY, in.getBackupQuantity()+"");
                changes.put(TunnelPoolSettings.PREFIX_OUTBOUND_EXPLORATORY +
                                                   TunnelPoolSettings.PROP_BACKUP_QUANTITY, out.getBackupQuantity()+"");
                if (_log.shouldDebug()) {
                    _log.debug("Inbound Exploratory settings: " + in);
                    _log.debug("Outbound Exploratory settings: " + out);
                }
                mgr.setInboundSettings(in);
                mgr.setOutboundSettings(out);
            } else {
                if (_log.shouldDebug()) {
                    _log.debug("Inbound settings for " + client.toBase64() + ": " + in);
                    _log.debug("Outbound settings for " + client.toBase64() + ": " + out);
                }
                mgr.setInboundSettings(client, in);
                mgr.setOutboundSettings(client, out);
                updated++;
            }

            saveRequired = true;
        }

        if (updated > 0)
            addFormNotice(_t("Updated settings for all pools."), true);

        if (saveRequired) {
            boolean saved = _context.router().saveConfig(changes, null);
            if (saved)
                addFormNotice(_t("Exploratory tunnel configuration saved successfully."), true);
            else
                addFormError(_t("Error saving the configuration (applied but not saved) - please see the error logs."), true);
        }
    }

    private static final int getInt(Object val) {
        if (val == null) return 0;
        String str = null;
        if (val instanceof String)
            str = (String)val;
        else
            str = ((String[])val)[0];

        if (str.trim().length() <= 0) return 0;
        try { return Integer.parseInt(str); } catch (NumberFormatException nfe) { return 0; }
    }
}
