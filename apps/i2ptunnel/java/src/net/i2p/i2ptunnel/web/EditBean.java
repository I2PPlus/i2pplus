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
 * Web interface bean for editing and configuring I2P tunnel settings.
 *
 * <p>Warning - This class is not part of the i2ptunnel API,
 * it has been moved from the jar to the war.
 * Usage by classes outside of i2ptunnel.war is deprecated.</p>
 */
public class EditBean extends IndexBean {
    /**
     * Default constructor.
     * @since 0.8.3
     */
    public EditBean() { super(); }

    /**
     * Is it a client or server in the UI and I2P side?
     * Note that a streamr client is a UI and I2P client but a server on the localhost side.
     * Note that a streamr server is a UI and I2P server but a client on the localhost side.
     *
     * @param tunnel the tunnel
     * @return true if the tunnel is a client type
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

    /**
     *  The target host for the tunnel.
     *  @return the target host for the tunnel
     *  @since 0.8.3
     */
    public String getTargetHost(int tunnel) {
        return DataHelper.escapeHTML(_helper.getTargetHost(tunnel));
    }

    /**
     *  The target port for the tunnel, or empty string if none.
     *  @return the target port, or "" if none
     *  @since 0.8.3
     */
    public String getTargetPort(int tunnel) {
        int port = _helper.getTargetPort(tunnel);
        return port > 0 ? Integer.toString(port) : "";
    }

    /**
     *  The private key file for the tunnel.
     *  @return the private key file
     *  @since 0.8.3
     */
    public String getPrivateKeyFile(int tunnel) {
        return _helper.getPrivateKeyFile(tunnel);
    }

    /**
     *  The alternate private key file for the tunnel.
     *  @return path or ""
     *  @since 0.9.30
     */
    public String getAltPrivateKeyFile(int tunnel) {
        return _helper.getAltPrivateKeyFile(tunnel);
    }

    /**
     *  The signing private key for the tunnel.
     *  @return key or null
     *  @since 0.9.26
     */
    public SigningPrivateKey getSigningPrivateKey(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun == null)
            return null;
        String keyFile = tun.getPrivKeyFile();
        if (keyFile != null && !keyFile.trim().isEmpty()) {
            File f = new File(keyFile);
            if (!f.isAbsolute())
                f = new File(_context.getConfigDir(), keyFile);
            PrivateKeyFile pkf = new PrivateKeyFile(f);
            return pkf.getSigningPrivKey();
        }
        return null;
    }

    /**
     *  Whether the tunnel is configured to start when the router starts.
     *  @param tunnel the tunnel
     *  @return true if the tunnel starts automatically
     *  @since 0.8.3
     */
    public boolean startAutomatically(int tunnel) {
        return _helper.shouldStartAutomatically(tunnel);
    }

    /**
     *  The minimum startup delay in seconds for server tunnels.
     *  @param tunnel the tunnel
     *  @return the minimum startup delay in seconds
     *  @since 0.9.68+
     */
    public int getStartupDelayMin(int tunnel) {
        TunnelController tc = _helper.getController(tunnel);
        if (tc == null) return 0;
        return tc.getStartupDelayMin();
    }

    /**
     *  The maximum startup delay in seconds for server tunnels.
     *  @param tunnel the tunnel
     *  @return the maximum startup delay in seconds
     *  @since 0.9.68+
     */
    public int getStartupDelayMax(int tunnel) {
        TunnelController tc = _helper.getController(tunnel);
        if (tc == null) return 0;
        return tc.getStartupDelayMax();
    }

    /**
     *  The minimum shutdown delay in seconds for server tunnels.
     *  @param tunnel the tunnel
     *  @return the minimum shutdown delay in seconds
     *  @since 0.9.68+
     */
    public int getShutdownDelayMin(int tunnel) {
        TunnelController tc = _helper.getController(tunnel);
        if (tc == null) return 0;
        return tc.getShutdownDelayMin();
    }

    /**
     *  The maximum shutdown delay in seconds for server tunnels.
     *  @param tunnel the tunnel
     *  @return the maximum shutdown delay in seconds
     *  @since 0.9.68+
     */
    public int getShutdownDelayMax(int tunnel) {
        TunnelController tc = _helper.getController(tunnel);
        if (tc == null) return 0;
        return tc.getShutdownDelayMax();
    }

    /**
     *  Whether the tunnel connection should be delayed until the first client connects.
     *  @param tunnel the tunnel
     *  @return true if the connection is delayed
     *  @since 0.8.3
     */
    public boolean shouldDelay(int tunnel) {
        return _helper.shouldDelayConnect(tunnel);
    }

    /**
     *  Whether the tunnel is interactive (requires immediate response).
     *  @param tunnel the tunnel
     *  @return true if the tunnel is interactive
     *  @since 0.8.3
     */
    public boolean isInteractive(int tunnel) {
        return _helper.isInteractive(tunnel);
    }

    /**
     * Gets the tunnel depth (number of hops) for inbound tunnels.
     *
     * @param tunnel the tunnel
     * @param defaultLength the default depth if not configured
     * @return the tunnel depth, or -1 for default
     */
    public int getTunnelDepth(int tunnel, int defaultLength) {
        return _helper.getTunnelDepth(tunnel, defaultLength);
    }

    /**
     * Gets the tunnel quantity for inbound or both in/out.
     *
     * @param tunnel the tunnel
     * @param defaultQuantity the default quantity if not configured
     * @return the tunnel quantity
     */
    public int getTunnelQuantity(int tunnel, int defaultQuantity) {
        return _helper.getTunnelQuantity(tunnel, defaultQuantity);
    }

    /**
     * Gets the backup tunnel quantity for inbound or both in/out.
     *
     * @param tunnel the tunnel
     * @param defaultBackupQuantity the default backup quantity if not configured
     * @return the backup tunnel quantity
     */
    public int getTunnelBackupQuantity(int tunnel, int defaultBackupQuantity) {
        return _helper.getTunnelBackupQuantity(tunnel, defaultBackupQuantity);
    }

    /**
     * Gets the tunnel variance for inbound or both in/out.
     *
     * @param tunnel the tunnel
     * @param defaultVariance the default variance if not configured
     * @return the tunnel variance
     */
    public int getTunnelVariance(int tunnel, int defaultVariance) {
        return _helper.getTunnelVariance(tunnel, defaultVariance);
    }

    /**
     *  Returns the outbound tunnel depth.
     *
     *  @param tunnel the tunnel
     *  @param defaultLength the default depth if not configured
     *  @return the outbound tunnel depth, or -1 for default
     *  @since 0.9.33
     */
    public int getTunnelDepthOut(int tunnel, int defaultLength) {
        return _helper.getTunnelDepthOut(tunnel, defaultLength);
    }

    /**
     *  Returns the outbound tunnel quantity.
     *
     *  @param tunnel the tunnel
     *  @param defaultQuantity the default quantity if not configured
     *  @return the outbound tunnel quantity
     *  @since 0.9.33
     */
    public int getTunnelQuantityOut(int tunnel, int defaultQuantity) {
        return _helper.getTunnelQuantityOut(tunnel, defaultQuantity);
    }

    /**
     *  Returns the outbound backup tunnel quantity.
     *
     *  @param tunnel the tunnel
     *  @param defaultBackupQuantity the default backup quantity if not configured
     *  @return the outbound backup tunnel quantity
     *  @since 0.9.33
     */
    public int getTunnelBackupQuantityOut(int tunnel, int defaultBackupQuantity) {
        return _helper.getTunnelBackupQuantityOut(tunnel, defaultBackupQuantity);
    }

    /**
     *  Returns the outbound tunnel variance.
     *
     *  @param tunnel the tunnel
     *  @param defaultVariance the default variance if not configured
     *  @return the outbound tunnel variance
     *  @since 0.9.33
     */
    public int getTunnelVarianceOut(int tunnel, int defaultVariance) {
        return _helper.getTunnelVarianceOut(tunnel, defaultVariance);
    }

    /**
     *  Whether the tunnel should reduce on idle.
     *  @return true if the tunnel should reduce on idle
     *  @since 0.8.3
     */
    public boolean getReduce(int tunnel) {
        return _helper.getReduceOnIdle(tunnel, false);
    }

    /**
     *  The reduce count for the tunnel.
     *  @return the reduce count for the tunnel
     *  @since 0.8.3
     */
    public int getReduceCount(int tunnel) {
        return _helper.getReduceCount(tunnel, 1);
    }

    /**
     *  The reduce time in minutes for the tunnel.
     *  @return the reduce time in minutes for the tunnel
     *  @since 0.8.3
     */
    public int getReduceTime(int tunnel) {
        return _helper.getReduceTime(tunnel, 20);
    }

    /**
     *  The certificate for the tunnel.
     *  @return the certificate for the tunnel
     *  @since 0.8.3
     */
    public int getCert(int tunnel) {
        return _helper.getCert(tunnel);
    }

    /**
     *  The encryption effort for the tunnel.
     *  @return the encryption effort for the tunnel
     *  @since 0.8.3
     */
    public int getEffort(int tunnel) {
        return _helper.getEffort(tunnel);
    }

    /**
     *  The signer for the tunnel.
     *  @return the signer for the tunnel
     *  @since 0.8.3
     */
    public String getSigner(int tunnel) {
        return _helper.getSigner(tunnel);
    }

    /**
     *  Whether encryption is enabled for the tunnel.
     *  @return true if encryption is enabled for the tunnel
     *  @since 0.8.3
     */
    public boolean getEncrypt(int tunnel) {
        return _helper.getEncrypt(tunnel);
    }

    /**
     *  Returns the encryption mode for the tunnel.
     *
     *  @param tunnel the tunnel
     *  @return the encryption mode as a string
     *  @since 0.9.40
     */
    public String getEncryptMode(int tunnel) {
        return Integer.toString(_helper.getEncryptMode(tunnel));
    }

    /**
     *  Returns the blinded password for the tunnel.
     *
     *  @param tunnel the tunnel
     *  @return the blinded password, or empty string if none
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
     *  Returns the signature type for the tunnel.
     *
     *  @param tunnel the tunnel
     *  @param newTunnelType used if tunnel &lt; 0
     *  @return the signature type code
     *  @since 0.9.12
     */
    public int getSigType(int tunnel, String newTunnelType) {
        return _helper.getSigType(tunnel, newTunnelType);
    }

    /**
     *  Returns whether the given signature type is available.
     *
     *  @param code the signature type code
     *  @return true if available
     *  @since 0.9.12
     */
    public boolean isSigTypeAvailable(int code) {
        return SigType.isAvailable(code);
    }

    /**
     *  Returns whether the tunnel signature type can be changed.
     *  The type is fixed if the tunnel has an existing destination.
     *
     *  @param tunnel the tunnel
     *  @return true if the signature type can be changed
     *  @since 0.9.33
     */
    public boolean canChangeSigType(int tunnel) {
        if (tunnel < 0) {return true;}
        if (getDestination(tunnel) != null) {return false;}
        return getTunnelStatus(tunnel) == GeneralHelper.NOT_RUNNING;
    }

    /**
     *  Returns whether the tunnel encryption type can be changed.
     *
     *  @param tunnel the tunnel
     *  @return true if the encryption type can be changed
     *  @since 0.9.46
     */
    public boolean canChangeEncType(int tunnel) {
        if (tunnel < 0) {return true;}
        return getTunnelStatus(tunnel) == GeneralHelper.NOT_RUNNING;
    }

    /**
     *  Returns whether the tunnel port setting can be changed.
     *
     *  @param tunnel the tunnel
     *  @return true if the port can be changed
     *  @since 0.9.46
     */
    public boolean canChangePort(int tunnel) {
        if (tunnel < 0) {return true;}
        return getTunnelStatus(tunnel) == GeneralHelper.NOT_RUNNING;
    }

    /**
     *  Returns whether the tunnel supports the specified encryption type.
     *
     *  @param tunnel the tunnel
     *  @param encType the encryption type code
     *  @return true if the tunnel has the encryption type
     *  @since 0.9.44
     */
    public boolean hasEncType(int tunnel, int encType) {
        return _helper.hasEncType(tunnel, encType);
    }

    /**
     *  Returns the encrypted inbound random key, hidden in forms.
     *
     *  @param tunnel the tunnel
     *  @return the encrypted inbound random key
     *  @since 0.9.18
     */
    public String getKey1(int tunnel) {
        String v = _helper.getInboundRandomKey(tunnel);
        return encrypt(tunnel, "inbound.randomKey", v);
    }

    /**
     *  The encrypted outbound random key.
     *  @return the encrypted outbound random key
     *  @since 0.8.3
     */
    public String getKey2(int tunnel) {
        String v = _helper.getOutboundRandomKey(tunnel);
        return encrypt(tunnel, "outbound.randomKey", v);
    }

    /**
     *  The encrypted lease set signing private key.
     *  @return the encrypted lease set signing private key
     *  @since 0.8.3
     */
    public String getKey3(int tunnel) {
        String v = _helper.getLeaseSetSigningPrivateKey(tunnel);
        return encrypt(tunnel, "i2cp.leaseSetSigningPrivateKey", v);
    }

    /**
     *  The encrypted lease set private key.
     *  @return the encrypted lease set private key
     *  @since 0.8.3
     */
    public String getKey4(int tunnel) {
        String v = _helper.getLeaseSetPrivateKey(tunnel);
        return encrypt(tunnel, "i2cp.leaseSetPrivateKey", v);
    }

    /**
     *  Whether DCC is enabled for the tunnel.
     *  @return true if DCC is enabled for the tunnel
     *  @since 0.8.9
     */
    public boolean getDCC(int tunnel) {
        return _helper.getDCC(tunnel);
    }

    /**
     *  The encryption key for the tunnel.
     *  @return the encryption key for the tunnel
     *  @since 0.8.3
     */
    public String getEncryptKey(int tunnel) {
        return _helper.getEncryptKey(tunnel);
    }

    /**
     *  The access mode for the tunnel.
     *  @return the access mode for the tunnel
     *  @since 0.8.3
     */
    public String getAccessMode(int tunnel) {
        return Integer.toString(_helper.getAccessMode(tunnel));
    }

    /**
     *  The access list for the tunnel.
     *  @return the access list for the tunnel
     *  @since 0.8.3
     */
    public String getAccessList(int tunnel) {
        return _helper.getAccessList(tunnel);
    }

    /**
     *  Returns the filter definition for the tunnel.
     *
     *  @param tunnel the tunnel
     *  @return the filter definition, or empty string if none
     *  @since 0.9.40
     */
    public String getFilterDefinition(int tunnel) {
        return _helper.getFilterDefinition(tunnel);
    }

    /**
     *  The jump list for the tunnel.
     *  @return the jump list for the tunnel
     *  @since 0.8.3
     */
    public String getJumpList(int tunnel) {
        return _helper.getJumpList(tunnel);
    }

    /**
     *  Whether the tunnel should close on idle.
     *  @return true if the tunnel should close on idle
     *  @since 0.8.3
     */
    public boolean getClose(int tunnel) {
        return _helper.getCloseOnIdle(tunnel, false);
    }

    /**
     *  The close time in minutes for the tunnel.
     *  @return the close time in minutes for the tunnel
     *  @since 0.8.3
     */
    public int getCloseTime(int tunnel) {
        return _helper.getCloseTime(tunnel, 30);
    }

    /**
     *  Whether a new destination should be created.
     *  @return true if a new destination should be created
     *  @since 0.8.3
     */
    public boolean getNewDest(int tunnel) {
        return _helper.getNewDest(tunnel);
    }

    /**
     *  Whether the client key should be persistent.
     *  @return true if the client key should be persistent
     *  @since 0.8.3
     */
    public boolean getPersistentClientKey(int tunnel) {
        return _helper.getPersistentClientKey(tunnel);
    }

    /**
     *  Whether the tunnel open should be delayed.
     *  @return true if the tunnel open should be delayed
     *  @since 0.8.3
     */
    public boolean getDelayOpen(int tunnel) {
        return _helper.getDelayOpen(tunnel);
    }

    /**
     *  Returns whether User-Agent header passthrough is allowed.
     *
     *  @param tunnel the tunnel
     *  @return true if User-Agent passthrough is allowed
     *  @since 0.9.14
     */
    public boolean getAllowUserAgent(int tunnel) {
        return _helper.getAllowUserAgent(tunnel);
    }

    /**
     *  Returns whether Referer header passthrough is allowed.
     *
     *  @param tunnel the tunnel
     *  @return true if Referer passthrough is allowed
     *  @since 0.9.14
     */
    public boolean getAllowReferer(int tunnel) {
        return _helper.getAllowReferer(tunnel);
    }

    /**
     *  Returns whether Accept header passthrough is allowed.
     *
     *  @param tunnel the tunnel
     *  @return true if Accept passthrough is allowed
     *  @since 0.9.14
     */
    public boolean getAllowAccept(int tunnel) {
        return _helper.getAllowAccept(tunnel);
    }

    /**
     *  Returns whether internal SSL connections are allowed.
     *
     *  @param tunnel the tunnel
     *  @return true if internal SSL is allowed
     *  @since 0.9.14
     */
    public boolean getAllowInternalSSL(int tunnel) {
        return _helper.getAllowInternalSSL(tunnel);
    }

    /**
     *  Whether multihoming is enabled.
     *  @return true if multihoming is enabled
     *  @since 0.9.18
     */
    public boolean getMultihome(int tunnel) {
        return _helper.getMultihome(tunnel);
    }

    /**
     *  The user agents string.
     *  @return the user agents string
     *  @since 0.9.25
     */
    public String getUserAgents(int tunnel) {
        return _helper.getUserAgents(tunnel);
    }

    /**
     *  Whether proxy authentication is enabled.
     *  @return true if proxy authentication is enabled
     *  @since 0.8.2
     */
    public boolean getProxyAuth(int tunnel) {
        return !_helper.getProxyAuth(tunnel).equals("false");
    }
    /**
     *  Whether outproxy authentication is enabled.
     *  @return true if outproxy authentication is enabled
     *  @since 0.8.3
     */
    public boolean getOutproxyAuth(int tunnel) {
        return _helper.getOutproxyAuth(tunnel) &&
               !getOutproxyUsername(tunnel).isEmpty() &&
               !getOutproxyPassword(tunnel).isEmpty();
    }

    /**
     *  The outproxy username.
     *  @return the outproxy username
     *  @since 0.8.3
     */
    public String getOutproxyUsername(int tunnel) {
        return _helper.getOutproxyUsername(tunnel);
    }

    /**
     *  The outproxy password.
     *  @return the outproxy password
     *  @since 0.8.3
     */
    public String getOutproxyPassword(int tunnel) {
        return _helper.getOutproxyPassword(tunnel);
    }

    /**
     *  The SSL proxies string.
     *  @return the SSL proxies string
     *  @since 0.9.11
     */
    public String getSslProxies(int tunnel) {
        return _helper.getSslProxies(tunnel);
    }

    /**
     *  Whether the outproxy plugin should be used.
     *  @return true if the outproxy plugin should be used
     *  @since 0.9.11
     */
    public boolean getUseOutproxyPlugin(int tunnel) {
        return _helper.getUseOutproxyPlugin(tunnel);
    }

    /**
     *  The outproxy type.
     *  @return the outproxy type
     *  @since 0.9.57
     */
    public String getOutproxyType(int tunnel) {
        return _helper.getOutproxyType(tunnel);
    }

    /**
     *  The per-minute limit for the tunnel.
     *  @return the per-minute limit for the tunnel
     *  @since 0.8.3
     */
    public int getLimitMinute(int tunnel) {
        return _helper.getLimitMinute(tunnel);
    }

    /**
     *  The per-hour limit for the tunnel.
     *  @return the per-hour limit for the tunnel
     *  @since 0.8.3
     */
    public int getLimitHour(int tunnel) {
        return _helper.getLimitHour(tunnel);
    }

    /**
     *  The per-day limit for the tunnel.
     *  @return the per-day limit for the tunnel
     *  @since 0.8.3
     */
    public int getLimitDay(int tunnel) {
        return _helper.getLimitDay(tunnel);
    }

    /**
     *  The per-minute total for the tunnel.
     *  @return the per-minute total for the tunnel
     *  @since 0.8.3
     */
    public int getTotalMinute(int tunnel) {
        return _helper.getTotalMinute(tunnel);
    }

    /**
     *  The per-hour total for the tunnel.
     *  @return the per-hour total for the tunnel
     *  @since 0.8.3
     */
    public int getTotalHour(int tunnel) {
        return _helper.getTotalHour(tunnel);
    }

    /**
     *  The per-day total for the tunnel.
     *  @return the per-day total for the tunnel
     *  @since 0.8.3
     */
    public int getTotalDay(int tunnel) {
        return _helper.getTotalDay(tunnel);
    }

    /**
     *  The maximum number of streams for the tunnel.
     *  @return the maximum number of streams for the tunnel
     *  @since 0.8.3
     */
    public int getMaxStreams(int tunnel) {
        return _helper.getMaxStreams(tunnel);
    }

    /**
     * POST limits
     * @since 0.9.9
     * @return the post max
     */
    public int getPostMax(int tunnel) {
        return _helper.getPostMax(tunnel);
    }

    /**
     *  The POST total max for the tunnel.
     *  @return the POST total max for the tunnel
     *  @since 0.9.9
     */
    public int getPostTotalMax(int tunnel) {
        return _helper.getPostTotalMax(tunnel);
    }

    /**
     *  The POST check time for the tunnel.
     *  @return the POST check time for the tunnel
     *  @since 0.9.9
     */
    public int getPostCheckTime(int tunnel) {
        return _helper.getPostCheckTime(tunnel);
    }

    /**
     *  The POST ban time for the tunnel.
     *  @return the POST ban time for the tunnel
     *  @since 0.9.9
     */
    public int getPostBanTime(int tunnel) {
        return _helper.getPostBanTime(tunnel);
    }

    /**
     *  The POST total ban time for the tunnel.
     *  @return the POST total ban time for the tunnel
     *  @since 0.9.9
     */
    public int getPostTotalBanTime(int tunnel) {
        return _helper.getPostTotalBanTime(tunnel);
    }

    /**
     *  Whether unique local addresses should be used.
     *  @return true if unique local addresses should be used
     *  @since 0.9.13
     */
    public boolean getUniqueLocal(int tunnel) {
        return _helper.getUniqueLocal(tunnel);
    }

    /**
     *  Whether running in router context.
     *  @return true if running in router context
     *  @since 0.8.3
     */
    public boolean isRouterContext() {
        return _context.isRouterContext();
    }

    /**
     *  The set of network interfaces.
     *  @return the set of network interfaces
     *  @since 0.8.3
     */
    public Set<String> interfaceSet() {
        // exclude IPv6 temporary
        return Addresses.getAddresses(true, true, true, false);
    }

    /**
     *  Whether advanced mode is enabled.
     *  @return true if advanced mode is enabled
     *  @since 0.9.12
     */
    public boolean isAdvanced() {
        return _context.getBooleanProperty(PROP_ADVANCED);
    }

    /**
     *  The I2CP host for the tunnel.
     *  @return the I2CP host for the tunnel
     *  @since 0.8.3
     */
    public String getI2CPHost(int tunnel) {
        if (_context.isRouterContext()) {return _t("internal");}
        TunnelController tun = getController(tunnel);
        if (tun != null) {return tun.getI2CPHost();}
        else {return "127.0.0.1";}
    }

    /**
     *  The I2CP port for the tunnel.
     *  @return the I2CP port for the tunnel
     *  @since 0.8.3
     */
    public String getI2CPPort(int tunnel) {
        if (_context.isRouterContext()) {return _t("internal");}
        TunnelController tun = getController(tunnel);
        if (tun != null) {return tun.getI2CPPort();}
        else {return Integer.toString(I2PClient.DEFAULT_LISTEN_PORT);}
    }

    /**
     *  The custom options string for the tunnel.
     *  @return the custom options string for the tunnel
     *  @since 0.8.3
     */
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
     *  The quantity options for the tunnel, as HTML.
     *  @param mode 0=both, 1=in, 2=out
     *  @since 0.9.7
     *  @return the quantity options
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
     *  The translated string wrapped in parentheses, or empty in advanced mode.
     *  @return translated s or ""
     *  @since 0.9.47
     */
    public String unlessAdvanced(String s) {
        if (isAdvanced()) {return "";}
        return " (" + _t(s) + ')';
    }

}
