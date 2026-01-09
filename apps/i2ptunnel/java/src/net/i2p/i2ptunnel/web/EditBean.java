package net.i2p.i2ptunnel.web;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2005 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.File;
import java.util.List;
import java.util.Set;
import net.i2p.client.I2PClient;
import net.i2p.crypto.SigType;
import net.i2p.data.DataHelper;
import net.i2p.data.PrivateKeyFile;
import net.i2p.data.SigningPrivateKey;
import net.i2p.i2ptunnel.TunnelController;
import net.i2p.i2ptunnel.TunnelControllerGroup;
import net.i2p.i2ptunnel.ui.GeneralHelper;
import net.i2p.util.Addresses;

/**
 * Ugly little accessor for the edit page
 *
 * Warning - This class is not part of the i2ptunnel API,
 * it has been moved from the jar to the war.
 * Usage by classes outside of i2ptunnel.war is deprecated.
 */
/** Web interface bean for editing and configuring I2P tunnel settings */
public class EditBean extends IndexBean {
    /** Default constructor @since 0.8.3 */
    public EditBean() { super(); }

    /**
     *  Is it a client or server in the UI and I2P side?
     *  Note that a streamr client is a UI and I2P client but a server on the localhost side.
     *  Note that a streamr server is a UI and I2P server but a client on the localhost side.
     */
    public static boolean staticIsClient(int tunnel) {
        TunnelControllerGroup group = TunnelControllerGroup.getInstance();
        if (group == null) {return false;}
        List<TunnelController> controllers = group.getControllers();
        if (controllers.size() > tunnel) {
            TunnelController cur = controllers.get(tunnel);
            if (cur == null) {return false;}
            return isClient(cur.getType());
        } else {return false;}
    }

    /** @return the target host for the tunnel @since 0.8.3 */
    public String getTargetHost(int tunnel) {
        return DataHelper.escapeHTML(_helper.getTargetHost(tunnel));
    }

    /** @return the target port for the tunnel, or empty string if none @since 0.8.3 */
    public String getTargetPort(int tunnel) {
        int port = _helper.getTargetPort(tunnel);
        return port > 0 ? Integer.toString(port) : "";
    }

    /** @return the private key file for the tunnel @since 0.8.3 */
    public String getPrivateKeyFile(int tunnel) {
        return _helper.getPrivateKeyFile(tunnel);
    }

    /**
     *  @return path or ""
     *  @since 0.9.30
     */
    public String getAltPrivateKeyFile(int tunnel) {
        return _helper.getAltPrivateKeyFile(tunnel);
    }

    /**
     *  @since 0.9.26
     *  @return key or null
     */
    public SigningPrivateKey getSigningPrivateKey(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun == null)
            return null;
        String keyFile = tun.getPrivKeyFile();
        if (keyFile != null && keyFile.trim().length() > 0) {
            File f = new File(keyFile);
            if (!f.isAbsolute())
                f = new File(_context.getConfigDir(), keyFile);
            PrivateKeyFile pkf = new PrivateKeyFile(f);
            return pkf.getSigningPrivKey();
        }
        return null;
    }

    /**
     *  @param tunnel the tunnel index
     *  @return true if the tunnel is configured to start when the router starts
     *  @since 0.8.3
     */
    public boolean startAutomatically(int tunnel) {
        return _helper.shouldStartAutomatically(tunnel);
    }

    /**
     *  @param tunnel the tunnel index
     *  @return the minimum startup delay in seconds for server tunnels
     *  @since 0.9.68+
     */
    public int getStartupDelayMin(int tunnel) {
        TunnelController tc = _helper.getController(tunnel);
        if (tc == null) return 0;
        return tc.getStartupDelayMin();
    }

    /**
     *  @param tunnel the tunnel index
     *  @return the maximum startup delay in seconds for server tunnels
     *  @since 0.9.68+
     */
    public int getStartupDelayMax(int tunnel) {
        TunnelController tc = _helper.getController(tunnel);
        if (tc == null) return 0;
        return tc.getStartupDelayMax();
    }

    /**
     *  @param tunnel the tunnel index
     *  @return true if the tunnel connection should be delayed until the first client connects
     *  @since 0.8.3
     */
    public boolean shouldDelay(int tunnel) {
        return _helper.shouldDelayConnect(tunnel);
    }

    /**
     *  @param tunnel the tunnel index
     *  @return true if the tunnel is interactive (requires immediate response)
     *  @since 0.8.3
     */
    public boolean isInteractive(int tunnel) {
        return _helper.isInteractive(tunnel);
    }

    /**
     *  @param tunnel the tunnel index
     *  @param defaultLength the default depth if not configured
     *  @return the tunnel depth (number of hops) for inbound tunnels, or -1 for default
     */
    public int getTunnelDepth(int tunnel, int defaultLength) {
        return _helper.getTunnelDepth(tunnel, defaultLength);
    }

    /** in or both in/out */
    public int getTunnelQuantity(int tunnel, int defaultQuantity) {
        return _helper.getTunnelQuantity(tunnel, defaultQuantity);
    }

    /** in or both in/out */
    public int getTunnelBackupQuantity(int tunnel, int defaultBackupQuantity) {
        return _helper.getTunnelBackupQuantity(tunnel, defaultBackupQuantity);
    }

    /** in or both in/out */
    public int getTunnelVariance(int tunnel, int defaultVariance) {
        return _helper.getTunnelVariance(tunnel, defaultVariance);
    }

    /** @since 0.9.33 */
    public int getTunnelDepthOut(int tunnel, int defaultLength) {
        return _helper.getTunnelDepthOut(tunnel, defaultLength);
    }

    /** @since 0.9.33 */
    public int getTunnelQuantityOut(int tunnel, int defaultQuantity) {
        return _helper.getTunnelQuantityOut(tunnel, defaultQuantity);
    }

    /** @since 0.9.33 */
    public int getTunnelBackupQuantityOut(int tunnel, int defaultBackupQuantity) {
        return _helper.getTunnelBackupQuantityOut(tunnel, defaultBackupQuantity);
    }

    /** @since 0.9.33 */
    public int getTunnelVarianceOut(int tunnel, int defaultVariance) {
        return _helper.getTunnelVarianceOut(tunnel, defaultVariance);
    }

    /** @return true if the tunnel should reduce on idle @since 0.8.3 */
    public boolean getReduce(int tunnel) {
        return _helper.getReduceOnIdle(tunnel, false);
    }

    /** @return the reduce count for the tunnel @since 0.8.3 */
    public int getReduceCount(int tunnel) {
        return _helper.getReduceCount(tunnel, 1);
    }

    /** @return the reduce time in minutes for the tunnel @since 0.8.3 */
    public int getReduceTime(int tunnel) {
        return _helper.getReduceTime(tunnel, 20);
    }

    /** @return the certificate for the tunnel @since 0.8.3 */
    public int getCert(int tunnel) {
        return _helper.getCert(tunnel);
    }

    /** @return the encryption effort for the tunnel @since 0.8.3 */
    public int getEffort(int tunnel) {
        return _helper.getEffort(tunnel);
    }

    /** @return the signer for the tunnel @since 0.8.3 */
    public String getSigner(int tunnel) {
        return _helper.getSigner(tunnel);
    }

    /** @return true if encryption is enabled for the tunnel @since 0.8.3 */
    public boolean getEncrypt(int tunnel) {
        return _helper.getEncrypt(tunnel);
    }

    /**
     *  @since 0.9.40
     */
    public String getEncryptMode(int tunnel) {
        return Integer.toString(_helper.getEncryptMode(tunnel));
    }

    /**
     *  @since 0.9.40
     */
    public String getBlindedPassword(int tunnel) {
        return _helper.getBlindedPassword(tunnel);
    }

    /**
     *  List of b64 name : b64key
     *  Pubkeys for DH, privkeys for PSK
     *  @param isDH true for DH, false for PSK
     *  @return non-null
     *  @since 0.9.41
     */
    public List<String> getClientAuths(int tunnel, boolean isDH) {
        return _helper.getClientAuths(tunnel, isDH);
    }

    /**
     *  @param newTunnelType used if tunnel &lt; 0
     *  @since 0.9.12
     */
    public int getSigType(int tunnel, String newTunnelType) {
        return _helper.getSigType(tunnel, newTunnelType);
    }

    /** @since 0.9.12 */
    public boolean isSigTypeAvailable(int code) {
        return SigType.isAvailable(code);
    }

    /** @since 0.9.33 */
    public boolean canChangeSigType(int tunnel) {
        if (tunnel < 0) {return true;}
        if (getDestination(tunnel) != null) {return false;}
        return getTunnelStatus(tunnel) == GeneralHelper.NOT_RUNNING;
    }

    /** @since 0.9.46 */
    public boolean canChangeEncType(int tunnel) {
        if (tunnel < 0) {return true;}
        return getTunnelStatus(tunnel) == GeneralHelper.NOT_RUNNING;
    }

    /** @since 0.9.46 */
    public boolean canChangePort(int tunnel) {
        if (tunnel < 0) {return true;}
        return getTunnelStatus(tunnel) == GeneralHelper.NOT_RUNNING;
    }

    /**
     *  @param encType code
     *  @since 0.9.44
     */
    public boolean hasEncType(int tunnel, int encType) {
        return _helper.hasEncType(tunnel, encType);
    }

    /**
     *  Random keys, hidden in forms
     *  @since 0.9.18
     */
    public String getKey1(int tunnel) {
        String v = _helper.getInboundRandomKey(tunnel);
        return encrypt(tunnel, "inbound.randomKey", v);
    }

    /** @return the encrypted outbound random key @since 0.8.3 */
    public String getKey2(int tunnel) {
        String v = _helper.getOutboundRandomKey(tunnel);
        return encrypt(tunnel, "outbound.randomKey", v);
    }

    /** @return the encrypted lease set signing private key @since 0.8.3 */
    public String getKey3(int tunnel) {
        String v = _helper.getLeaseSetSigningPrivateKey(tunnel);
        return encrypt(tunnel, "i2cp.leaseSetSigningPrivateKey", v);
    }

    /** @return the encrypted lease set private key @since 0.8.3 */
    public String getKey4(int tunnel) {
        String v = _helper.getLeaseSetPrivateKey(tunnel);
        return encrypt(tunnel, "i2cp.leaseSetPrivateKey", v);
    }

    /** @return true if DCC is enabled for the tunnel @since 0.8.9 */
    public boolean getDCC(int tunnel) {
        return _helper.getDCC(tunnel);
    }

    /** @return the encryption key for the tunnel @since 0.8.3 */
    public String getEncryptKey(int tunnel) {
        return _helper.getEncryptKey(tunnel);
    }

    /** @return the access mode for the tunnel @since 0.8.3 */
    public String getAccessMode(int tunnel) {
        return Integer.toString(_helper.getAccessMode(tunnel));
    }

    /** @return the access list for the tunnel @since 0.8.3 */
    public String getAccessList(int tunnel) {
        return _helper.getAccessList(tunnel);
    }

    /**
     *  @since 0.9.40
     */
    public String getFilterDefinition(int tunnel) {
        return _helper.getFilterDefinition(tunnel);
    }

    /** @return the jump list for the tunnel @since 0.8.3 */
    public String getJumpList(int tunnel) {
        return _helper.getJumpList(tunnel);
    }

    /** @return true if the tunnel should close on idle @since 0.8.3 */
    public boolean getClose(int tunnel) {
        return _helper.getCloseOnIdle(tunnel, false);
    }

    /** @return the close time in minutes for the tunnel @since 0.8.3 */
    public int getCloseTime(int tunnel) {
        return _helper.getCloseTime(tunnel, 30);
    }

    /** @return true if a new destination should be created @since 0.8.3 */
    public boolean getNewDest(int tunnel) {
        return _helper.getNewDest(tunnel);
    }

    /** @return true if the client key should be persistent @since 0.8.3 */
    public boolean getPersistentClientKey(int tunnel) {
        return _helper.getPersistentClientKey(tunnel);
    }

    /** @return true if the tunnel open should be delayed @since 0.8.3 */
    public boolean getDelayOpen(int tunnel) {
        return _helper.getDelayOpen(tunnel);
    }

    /** @since 0.9.14 */
    public boolean getAllowUserAgent(int tunnel) {
        return _helper.getAllowUserAgent(tunnel);
    }

    /** @since 0.9.14 */
    public boolean getAllowReferer(int tunnel) {
        return _helper.getAllowReferer(tunnel);
    }

    /** @since 0.9.14 */
    public boolean getAllowAccept(int tunnel) {
        return _helper.getAllowAccept(tunnel);
    }

    /** @since 0.9.14 */
    public boolean getAllowInternalSSL(int tunnel) {
        return _helper.getAllowInternalSSL(tunnel);
    }

    /** @return true if multihoming is enabled @since 0.9.18 */
    public boolean getMultihome(int tunnel) {
        return _helper.getMultihome(tunnel);
    }

    /** @return the user agents string @since 0.9.25 */
    public String getUserAgents(int tunnel) {
        return _helper.getUserAgents(tunnel);
    }

    /** @return true if proxy authentication is enabled @since 0.8.2 */
    public boolean getProxyAuth(int tunnel) {
        return _helper.getProxyAuth(tunnel) != "false";
    }
    /** @return true if outproxy authentication is enabled @since 0.8.3 */
    public boolean getOutproxyAuth(int tunnel) {
        return _helper.getOutproxyAuth(tunnel) &&
               getOutproxyUsername(tunnel).length() > 0 &&
               getOutproxyPassword(tunnel).length() > 0;
    }

    /** @return the outproxy username @since 0.8.3 */
    public String getOutproxyUsername(int tunnel) {
        return _helper.getOutproxyUsername(tunnel);
    }

    /** @return the outproxy password @since 0.8.3 */
    public String getOutproxyPassword(int tunnel) {
        return _helper.getOutproxyPassword(tunnel);
    }

    /** @return the SSL proxies string @since 0.9.11 */
    public String getSslProxies(int tunnel) {
        return _helper.getSslProxies(tunnel);
    }

    /** @return true if the outproxy plugin should be used @since 0.9.11 */
    public boolean getUseOutproxyPlugin(int tunnel) {
        return _helper.getUseOutproxyPlugin(tunnel);
    }

    /** @return the outproxy type @since 0.9.57 */
    public String getOutproxyType(int tunnel) {
        return _helper.getOutproxyType(tunnel);
    }

    /** @return the per-minute limit for the tunnel @since 0.8.3 */
    public int getLimitMinute(int tunnel) {
        return _helper.getLimitMinute(tunnel);
    }

    /** @return the per-hour limit for the tunnel @since 0.8.3 */
    public int getLimitHour(int tunnel) {
        return _helper.getLimitHour(tunnel);
    }

    /** @return the per-day limit for the tunnel @since 0.8.3 */
    public int getLimitDay(int tunnel) {
        return _helper.getLimitDay(tunnel);
    }

    /** @return the per-minute total for the tunnel @since 0.8.3 */
    public int getTotalMinute(int tunnel) {
        return _helper.getTotalMinute(tunnel);
    }

    /** @return the per-hour total for the tunnel @since 0.8.3 */
    public int getTotalHour(int tunnel) {
        return _helper.getTotalHour(tunnel);
    }

    /** @return the per-day total for the tunnel @since 0.8.3 */
    public int getTotalDay(int tunnel) {
        return _helper.getTotalDay(tunnel);
    }

    /** @return the maximum number of streams for the tunnel @since 0.8.3 */
    public int getMaxStreams(int tunnel) {
        return _helper.getMaxStreams(tunnel);
    }

    /**
     * POST limits
     * @since 0.9.9
     */
    public int getPostMax(int tunnel) {
        return _helper.getPostMax(tunnel);
    }

    /** @return the POST total max for the tunnel @since 0.9.9 */
    public int getPostTotalMax(int tunnel) {
        return _helper.getPostTotalMax(tunnel);
    }

    /** @return the POST check time for the tunnel @since 0.9.9 */
    public int getPostCheckTime(int tunnel) {
        return _helper.getPostCheckTime(tunnel);
    }

    /** @return the POST ban time for the tunnel @since 0.9.9 */
    public int getPostBanTime(int tunnel) {
        return _helper.getPostBanTime(tunnel);
    }

    /** @return the POST total ban time for the tunnel @since 0.9.9 */
    public int getPostTotalBanTime(int tunnel) {
        return _helper.getPostTotalBanTime(tunnel);
    }

    /** @return true if unique local addresses should be used @since 0.9.13 */
    public boolean getUniqueLocal(int tunnel) {
        return _helper.getUniqueLocal(tunnel);
    }

    /** @return true if running in router context @since 0.8.3 */
    public boolean isRouterContext() {
        return _context.isRouterContext();
    }

    /** @return the set of network interfaces @since 0.8.3 */
    public Set<String> interfaceSet() {
        // exclude IPv6 temporary
        return Addresses.getAddresses(true, true, true, false);
    }

    /** @return true if advanced mode is enabled @since 0.9.12 */
    public boolean isAdvanced() {
        return _context.getBooleanProperty(PROP_ADVANCED);
    }

    /** @return the I2CP host for the tunnel @since 0.8.3 */
    public String getI2CPHost(int tunnel) {
        if (_context.isRouterContext()) {return _t("internal");}
        TunnelController tun = getController(tunnel);
        if (tun != null) {return tun.getI2CPHost();}
        else {return "127.0.0.1";}
    }

    /** @return the I2CP port for the tunnel @since 0.8.3 */
    public String getI2CPPort(int tunnel) {
        if (_context.isRouterContext()) {return _t("internal");}
        TunnelController tun = getController(tunnel);
        if (tun != null) {return tun.getI2CPPort();}
        else {return Integer.toString(I2PClient.DEFAULT_LISTEN_PORT);}
    }

    /** @return the custom options string for the tunnel @since 0.8.3 */
    public String getCustomOptions(int tunnel) {
        return _helper.getCustomOptionsString(tunnel);
    }

    private static final String PROP_ADVANCED = "routerconsole.advanced";
    private static final int DFLT_LENGTH = 3;
    private static final int DFLT_QUANTITY = 2;
    private static final int MAX_ADVANCED_QUANTITY = 16;
    private static final int MAX_CLIENT_QUANTITY = 8;
    private static final int MAX_SERVER_QUANTITY = 8;

    /**
     *  @param mode 0=both, 1=in, 2=out
     *  @since 0.9.7
     */
    public String getQuantityOptions(int tunnel, int mode) {
        int tunnelDepth = getTunnelDepth(tunnel, DFLT_LENGTH);

        // Special case: if tunnel depth is 0
        if (tunnelDepth == 0) {
            StringBuilder buf = new StringBuilder(64);
            buf.append("<option value=\"1\" selected disabled>");
            buf.append(ngettext("{0} inbound, {0} outbound tunnel", "{0} inbound, {0} outbound tunnels", 1));
            buf.append("</option>\n");
            return buf.toString();
        }

        int tunnelQuantity = mode == 2 ? getTunnelQuantityOut(tunnel, DFLT_QUANTITY)
                                       : getTunnelQuantity(tunnel, DFLT_QUANTITY);
        boolean adv = isAdvanced();
        int maxQuantity = adv ? MAX_ADVANCED_QUANTITY :
                             (isClient(tunnel) ? MAX_CLIENT_QUANTITY : MAX_SERVER_QUANTITY);
        if (tunnelQuantity > maxQuantity) {
            maxQuantity = tunnelQuantity;
        }

        StringBuilder buf = new StringBuilder(256);
        for (int i = 1; i <= maxQuantity; i++) {
            buf.append("<option value=\"").append(i).append('"');
            if (i == tunnelQuantity) {buf.append(" selected");}
            buf.append('>');
            if (mode == 1) {buf.append(ngettext("{0} inbound tunnel", "{0} inbound tunnels", i));}
            else if (mode == 2) {buf.append(ngettext("{0} outbound tunnel", "{0} outbound tunnels", i));}
            else {buf.append(ngettext("{0} inbound, {0} outbound tunnel", "{0} inbound, {0} outbound tunnels", i));}
            if (i <= 3 && !adv) {
                buf.append(" (");
                if (i == 1) {buf.append(_t("lower bandwidth and reliability"));}
                else if (i == 2) {buf.append(_t("standard bandwidth and reliability"));}
                else if (i == 3) {buf.append(_t("higher bandwidth and reliability"));}
                buf.append(')');
            }
            buf.append("</option>\n");
        }
        return buf.toString();
    }

    /**
     *  @return translated s or ""
     *  @since 0.9.47
     */
    public String unlessAdvanced(String s) {
        if (isAdvanced()) {return "";}
        return " (" + _t(s) + ')';
    }

}