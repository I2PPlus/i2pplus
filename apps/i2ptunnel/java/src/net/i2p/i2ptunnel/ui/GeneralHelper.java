package net.i2p.i2ptunnel.ui;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKeyFile;
import net.i2p.i2ptunnel.I2PTunnelClientBase;
import net.i2p.i2ptunnel.I2PTunnelHTTPClient;
import net.i2p.i2ptunnel.I2PTunnelHTTPClientBase;
import net.i2p.i2ptunnel.I2PTunnelHTTPServer;
import net.i2p.i2ptunnel.I2PTunnelIRCClient;
import net.i2p.i2ptunnel.I2PTunnelServer;
import net.i2p.i2ptunnel.SSLClientUtil;
import net.i2p.i2ptunnel.TunnelController;
import net.i2p.i2ptunnel.TunnelControllerGroup;
import net.i2p.i2ptunnel.socks.I2PSOCKSTunnel;
import net.i2p.util.ConvertToHash;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;
import net.i2p.util.SecureFile;

/**
 * General helper functions used by all UIs.
 *
 * This class is also used by Android.
 * Maintain as a stable API and take care not to break Android.
 *
 * @since 0.9.19
 */
public class GeneralHelper {
    /** Tunnel is fully operational */
    public static final int RUNNING = 1;
    /** Tunnel is in the process of starting up */
    public static final int STARTING = 2;
    /** Tunnel is not running */
    public static final int NOT_RUNNING = 3;
    /** Tunnel is alive but idle, ready for activity */
    public static final int STANDBY = 4;

    /** Property key for enabling access list filtering */
    protected static final String PROP_ENABLE_ACCESS_LIST = "i2cp.enableAccessList";
    /** Property key for enabling blacklist filtering */
    protected static final String PROP_ENABLE_BLACKLIST = "i2cp.enableBlackList";

    private static final String OPT = TunnelController.PFX_OPTION;

    private final I2PAppContext _context;
    /**
     * _group.
     */
    protected final TunnelControllerGroup _group;

    /**
     *  Construct a new helper with the global context.
     *
     *  @param tcg the group of tunnel controllers, may be null
     */
    public GeneralHelper(TunnelControllerGroup tcg) {
        this(I2PAppContext.getGlobalContext(), tcg);
    }

    /**
     *  Construct a new helper with a specific context.
     *
     *  @param context the I2P app context to use
     *  @param tcg the group of tunnel controllers, may be null
     */
    public GeneralHelper(I2PAppContext context, TunnelControllerGroup tcg) {
        _context = context;
        _group = tcg;
    }

    /**
     *  Retrieve a tunnel controller by index.
     *
     *  @param tunnel the tunnel index
     *  @return the controller, or null if not found
     */
    public TunnelController getController(int tunnel) {
        return getController(_group, tunnel);
    }

    /**
     *  @param tcg may be null
     *  @return null if not found or tcg is null
     */
    public static TunnelController getController(TunnelControllerGroup tcg, int tunnel) {
        if (tunnel < 0) return null;
        if (tcg == null) return null;
        List<TunnelController> controllers = tcg.getControllers();
        if (controllers.size() > tunnel) {return controllers.get(tunnel);}
        else {return null;}
    }

    /**
     *  Save the configuration for a new or existing tunnel to disk.
     *  For new tunnels, adds to controller and (if configured) starts it.
     *
     *  @param tunnel the tunnel index
     *  @param config the configuration to apply
     *  @return list of status messages
     */
    public List<String> saveTunnel(int tunnel, TunnelConfig config) {
        return saveTunnel(_context, _group, tunnel, config);
    }

    /**
     *  Save the configuration for a new or existing tunnel to disk.
     *  For new tunnels, adds to controller and (if configured) starts it.
     *
     *  @param context unused, taken from tcg
     */
    public static List<String> saveTunnel(I2PAppContext context, TunnelControllerGroup tcg, int tunnel, TunnelConfig config) {
        List<String> msgs = new ArrayList<>();
        TunnelController cur = updateTunnelConfig(tcg, tunnel, config, msgs);
        msgs.addAll(saveConfig(tcg, cur));
        return msgs;
    }

    /**
     *  Update the config and if shared, adjust and save the config of other shared clients.
     *  If a new tunnel, this will call tcg.addController(), and start it if so configured.
     *  This does NOT save this tunnel's config. Caller must call saveConfig() also.
     */
    protected static List<String> updateTunnelConfig(TunnelControllerGroup tcg, int tunnel, TunnelConfig config) {
        List<String> msgs = new ArrayList<>();
        updateTunnelConfig(tcg, tunnel, config, msgs);
        return msgs;
    }

    /**
     *  Update the config and if shared, adjust and save the config of other shared clients.
     *  If a new tunnel, this will call tcg.addController(), and start it if so configured.
     *  This does NOT save this tunnel's config. Caller must call saveConfig() also.
     *
     *  @param msgs out parameter, messages will be added
     *  @return the old or new controller, non-null.
     *  @since 0.9.49
     */
    private static TunnelController updateTunnelConfig(TunnelControllerGroup tcg, int tunnel, TunnelConfig config, List<String> msgs) {
        // Get current tunnel controller
        TunnelController cur = getController(tcg, tunnel);
        Properties props = config.getConfig();
        String type = props.getProperty(TunnelController.PROP_TYPE);

        if ((TunnelController.TYPE_STD_CLIENT.equals(type) || TunnelController.TYPE_IRC_CLIENT.equals(type)) &&
            Boolean.parseBoolean(props.getProperty(OPT + I2PTunnelClientBase.PROP_USE_SSL))) {
            // If we switch to SSL, create the keystore here, so we can store the new properties.
            // Down in I2PTunnelClientBase it's very hard to save the config.
                // Add the local interface and all targets to the cert
                String intfc = props.getProperty(TunnelController.PROP_INTFC);
                Set<String> altNames = new HashSet<>(4);
                if (intfc != null && !intfc.equals("0.0.0.0") && !intfc.equals("::") &&
                    !intfc.equals("0:0:0:0:0:0:0:0")) {
                    altNames.add(intfc);
                }
                String tgts = props.getProperty(TunnelController.PROP_DEST);
                if (tgts != null) {
                    altNames.add(intfc);
                    String[] hosts = DataHelper.split(tgts, "[ ,]");
                    for (String h : hosts) {
                        int colon = h.indexOf(':');
                        if (colon >= 0) {h = h.substring(0, colon);}
                        altNames.add(h);
                        if (!h.endsWith(".b32.i2p")) {
                            Hash hash = ConvertToHash.getHash(h);
                            if (hash != null) {altNames.add(hash.toBase32());}
                        }
                    }
                }
                try {
                    boolean created = SSLClientUtil.verifyKeyStore(props, OPT, altNames);
                    if (created) {
                        // config now contains new keystore props
                        String name = props.getProperty(TunnelController.PROP_NAME, "");
                        msgs.add("Created new self-signed certificate for tunnel " + name);
                    }
                } catch (IOException ioe) {
                    msgs.add("Failed to create new self-signed certificate for tunnel " +
                            getTunnelName(tcg, tunnel) + ", check logs: " + ioe);
                }
        }
        if (cur == null) {
            // creating new
            cur = new TunnelController(props, "", true);
            tcg.addController(cur);
            if (cur.getStartOnLoad()) {cur.startTunnelBackground();}
        } else {cur.setConfig(props, "");}

        // Only modify other shared tunnels if the current tunnel is shared, and of supported type
        if (Boolean.parseBoolean(cur.getSharedClient()) && TunnelController.isClient(cur.getType())) {
            // All clients use the same I2CP session, and as such, use the same I2CP options
            List<TunnelController> controllers = tcg.getControllers();
            for (int i = 0; i < controllers.size(); i++) {
                TunnelController c = controllers.get(i);
                if (c == cur) continue; // Current tunnel modified by user, skip
                // Only modify this non-current tunnel if it belongs to a shared destination, and of supported type
                if (Boolean.parseBoolean(c.getSharedClient()) && TunnelController.isClient(c.getType())) {
                    copySharedOptions(config, props, c);
                    try {tcg.saveConfig(c);}
                    catch (IOException ioe) {
                        msgs.add(0, _t("Failed to save configuration", tcg.getContext()) + ": " + ioe);
                    }
                }
            }
        }
        return cur;
    }

    /**
     *  I2CP/Dest/LS options affecting shared client tunnels.
     *  Streaming options should not be here, each client gets its own SocketManger.
     *  All must be prefixed with "option."
     *  @since 0.9.46
     */
    private static final String[] SHARED_OPTIONS = {
        // I2CP
        "i2cp.reduceOnIdle", "i2cp.closeOnIdle", "i2cp.newDestOnResume",
        "i2cp.reduceIdleTime", "i2cp.reduceQuantity", "i2cp.closeIdleTime",
        // dest / LS
        I2PClient.PROP_SIGTYPE, "i2cp.leaseSetEncType", "i2cp.leaseSetType",
        "persistentClientKey",
        // following are mostly server but could also be persistent client
        "inbound.randomKey", "outbound.randomKey", "i2cp.leaseSetSigningPrivateKey", "i2cp.leaseSetPrivateKey"
    };

    /**
     *  Copy relevant options over
     *  @since 0.9.46 pulled out of updateTunnelConfig
     */
    private static void copySharedOptions(TunnelConfig fromConfig, Properties from,
                                          TunnelController to) {
        Properties cOpt = to.getConfig("");
        fromConfig.updateTunnelQuantities(cOpt);
        cOpt.setProperty("option.inbound.nickname", TunnelConfig.SHARED_CLIENT_NICKNAME);
        cOpt.setProperty("option.outbound.nickname", TunnelConfig.SHARED_CLIENT_NICKNAME);
        for (String p : SHARED_OPTIONS) {
            String k = TunnelController.PFX_OPTION + p;
            String v = from.getProperty(k);
            if (v != null) {cOpt.setProperty(k, v);}
            else {cOpt.remove(k);}
        }
        // persistent client key, not prefixed with "option."
        String v = from.getProperty(TunnelController.PROP_FILE);
        if (v != null) {cOpt.setProperty(TunnelController.PROP_FILE, v);}
        to.setConfig(cOpt, "");
    }

    /**
     *  Save the configuration for an existing tunnel to disk.
     *  New tunnels must use saveConfig(..., TunnelController).
     *
     *  @param context unused, taken from tcg
     *  @param tunnel must already exist
     *  @since 0.9.49
     */
    protected static List<String> saveConfig(I2PAppContext context, TunnelControllerGroup tcg, int tunnel) {
        TunnelController cur = getController(tcg, tunnel);
        if (cur == null) {
            List<String> rv = tcg.clearAllMessages();
            rv.add("✖ Invalid tunnel number");
            return rv;
        }
        return saveConfig(tcg, cur);
    }

    /**
     *  Save the configuration to disk.
     *  For new and existing tunnels.
     *  Does NOT call tcg.addController() for new tunnels. See udpateConfig()
     *
     *  @since 0.9.49
     */
    private static List<String> saveConfig(TunnelControllerGroup tcg, TunnelController cur) {
        I2PAppContext context = tcg.getContext();
        List<String> rv = tcg.clearAllMessages();
        try {
            tcg.saveConfig(cur);
            rv.add(0, "✔ " + _t("Configuration changes saved", context));
        } catch (IOException ioe) {
            Log log = context.logManager().getLog(GeneralHelper.class);
            log.error("Failed to save config file", ioe);
            rv.add(0, "✖ " + _t("Failed to save configuration", context) + ": " + ioe.toString());
        }
        return rv;
    }

    /**
     *  Stop and delete the tunnel, remove its configuration, and rename
     *  the private key file if it uses a default name in the default directory.
     *
     *  @param tunnel the tunnel index
     *  @param privKeyFile the private key file name from the edit form, may be null
     *  @return list of status messages
     */
    public List<String> deleteTunnel(int tunnel, String privKeyFile) {
        return deleteTunnel(_context, _group, tunnel, privKeyFile);
    }

    /**
     * Stop the tunnel, delete from config,
     * rename the private key file if in the default directory
     *
     * @param privKeyFile The priv key file name from the tunnel edit form. Can
     *                    be null if not known.
     */
    public static List<String> deleteTunnel(I2PAppContext context, TunnelControllerGroup tcg, int tunnel, String privKeyFile) {
        List<String> msgs;
        TunnelController cur = getController(tcg, tunnel);
        if (cur == null) {
            msgs = new ArrayList<>();
            msgs.add("✖ Invalid tunnel number");
            return msgs;
        }

        msgs = tcg.removeController(cur);
        try {tcg.removeConfig(cur);}
        catch (IOException ioe) {msgs.add(ioe.toString());}

        /*
         * Rename private key file if it was a default name in the default directory,
         * so it doesn't get reused when a new tunnel is created.
         * Use configured file name if available, not the one from the form.
         */
        String pk = cur.getPrivKeyFile();
        if (pk == null) {pk = privKeyFile;}
        if (pk != null && pk.startsWith("i2ptunnel") && pk.endsWith("-privKeys.dat") &&
            ((!TunnelController.isClient(cur.getType())) || cur.getPersistentClientKey())) {
            File pkf = new File(context.getConfigDir(), pk);
            if (pkf.exists()) {
                String name = cur.getName();
                if (name == null) {
                    name = cur.getDescription();
                    if (name == null) {
                        name = cur.getType();
                        if (name == null) {name = Long.toString(context.clock().now());}
                    }
                }
                name = name.replace(' ', '_').replace(':', '_').replace("..", "_").replace('/', '_').replace('\\', '_');
                name = "i2ptunnel-deleted-" + name + '-' + context.clock().now() + "-privkeys.dat";
                File backupDir = new SecureFile(context.getConfigDir(), TunnelController.KEY_BACKUP_DIR);
                File to;
                if (backupDir.isDirectory() || backupDir.mkdir()) {to = new File(backupDir, name);}
                else {to = new File(context.getConfigDir(), name);}
                boolean success = FileUtil.rename(pkf, to);
                if (success) {msgs.add("✔ Private key file " + pkf.getAbsolutePath() + " renamed to " + to.getAbsolutePath());}
            }
        }
        return msgs;
    }

    //
    // Accessors
    //

    /**
     *  Return the tunnel type string (e.g. &quot;httpclient&quot;, &quot;httpserver&quot;).
     *
     *  @param tunnel the tunnel index
     *  @return the type string, or empty string if tunnel not found
     */
    public String getTunnelType(int tunnel) {
        TunnelController tun = getController(tunnel);
        return (tun != null && tun.getType() != null) ? tun.getType() : "";
    }

    /**
     *  Return the tunnel name.
     *
     *  @param tunnel the tunnel index
     *  @return null if unset
     */
    public String getTunnelName(int tunnel) {
        return getTunnelName(_group, tunnel);
    }

    /**
     *  Return the tunnel name from a specific group.
     *
     *  @param tcg the controller group, may be null
     *  @param tunnel the tunnel index
     *  @return null if unset
     */
    public static String getTunnelName(TunnelControllerGroup tcg, int tunnel) {
        TunnelController tun = getController(tcg, tunnel);
        return tun != null ? tun.getName() : null;
    }

    /**
     *  Return the tunnel description.
     *
     *  @param tunnel the tunnel index
     *  @return the description, or empty string if not found
     */
    public String getTunnelDescription(int tunnel) {
        TunnelController tun = getController(tunnel);
        return (tun != null && tun.getDescription() != null) ? tun.getDescription() : "";
    }

    /**
     *  Return the target host for the tunnel.
     *
     *  @param tunnel the tunnel index
     *  @return the target host, defaults to &quot;127.0.0.1&quot;
     */
    public String getTargetHost(int tunnel) {
        TunnelController tun = getController(tunnel);
        return (tun != null && tun.getTargetHost() != null) ? tun.getTargetHost() : "127.0.0.1";
    }

    /**
     *  Return the target port for the tunnel.
     *
     *  @param tunnel the tunnel index
     *  @return the port number, or -1 if unset or invalid
     */
    public int getTargetPort(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null && tun.getTargetPort() != null) {
            try {return Integer.parseInt(tun.getTargetPort());}
            catch (NumberFormatException e) {return -1;}
        } else {return -1;}
    }

    /**
     *  Return the spoofed HTTP host header.
     *
     *  @param tunnel the tunnel index
     *  @return the spoofed host, or empty string if not set
     */
    public String getSpoofedHost(int tunnel) {
        TunnelController tun = getController(tunnel);
        return (tun != null && tun.getSpoofedHost() != null) ? tun.getSpoofedHost() : "";
    }

    /**
     *  Return the private key file path for the tunnel.
     *
     *  @param tunnel the tunnel index
     *  @return path, non-null, non-empty
     */
    public String getPrivateKeyFile(int tunnel) {
        return getPrivateKeyFile(_group, tunnel);
    }

    /**
     *  Return the private key file path, computing a default if none is configured.
     *
     *  @param tcg the controller group, may be null
     *  @param tunnel the tunnel index
     *  @return path, non-null, non-empty
     */
    public String getPrivateKeyFile(TunnelControllerGroup tcg, int tunnel) {
        TunnelController tun = getController(tcg, tunnel);
        if (tun != null) {
            String rv = tun.getPrivKeyFile();
            if (rv != null) {return rv;}
        }
        if (tunnel < 0) {tunnel = tcg == null ? 999 : tcg.getControllers().size();}
        String rv = "i2ptunnel" + tunnel + "-privKeys.dat";
        // Don't default to a file that already exists,
        // which could happen after other tunnels are deleted.
        int i = 0;
        StringBuilder buf = new StringBuilder(32);
        while ((new File(_context.getConfigDir(), rv)).exists()) {
            buf.setLength(0);
            rv = buf.append("i2ptunnel").append(tunnel).append('.').append(++i).append("-privKeys.dat").toString();
        }
        return rv;
    }

    /**
     *  Return the alternate private key file path.
     *
     *  @param tunnel the tunnel index
     *  @return path or &quot;&quot;
     *  @since 0.9.30
     */
    public String getAltPrivateKeyFile(int tunnel) {
        return getAltPrivateKeyFile(_group, tunnel);
    }

    /**
     *  Return the alternate private key file path from a specific group.
     *
     *  @param tcg the controller group, may be null
     *  @param tunnel the tunnel index
     *  @return path or &quot;&quot;
     *  @since 0.9.30
     */
    public String getAltPrivateKeyFile(TunnelControllerGroup tcg, int tunnel) {
        TunnelController tun = getController(tcg, tunnel);
        if (tun != null) {
            File f = tun.getAlternatePrivateKeyFile();
            if (f != null) {return f.getAbsolutePath();}
        }
        return "";
    }

    /**
     *  Return the interface address the client tunnel listens on.
     *
     *  @param tunnel the tunnel index
     *  @return the interface address, defaults to &quot;127.0.0.1&quot;
     */
    public String getClientInterface(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            if ("streamrclient".equals(tun.getType())) {return tun.getTargetHost();}
            else {return tun.getListenOnInterface();}
        } else {return "127.0.0.1";}
    }

    /**
     *  Return the port the client tunnel listens on.
     *
     *  @param tunnel the tunnel index
     *  @return the port number, or -1 if unset or invalid
     */
    public int getClientPort(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null && tun.getListenPort() != null) {
            try {return Integer.parseInt(tun.getListenPort());}
            catch (NumberFormatException e) {return -1;}
        } else {return -1;}
    }

    /**
     *  Return the operational status of the tunnel.
     *
     *  @param tunnel the tunnel index
     *  @return RUNNING, STARTING, NOT_RUNNING, or STANDBY
     */
    public int getTunnelStatus(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun == null) return NOT_RUNNING;
        if (tun.getIsRunning()) {
            if (tun.isClient() && tun.getIsStandby()) {return STANDBY;}
            else {return RUNNING;}
        } else if (tun.getIsStarting()) return STARTING;
        else {return NOT_RUNNING;}
    }

    /**
     *  Get the remaining startup delay time for tunnels with delayed startup.
     *  @param tunnel the tunnel index
     *  @return remaining delay in seconds, or 0 if not applicable
     *  @since 0.9.68+
     */
    public int getRemainingStartupDelay(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun == null) return 0;
        return tun.getRemainingStartupDelay();
    }

    /**
     *  Return the client destination (base64) or proxy list for the tunnel.
     *
     *  @param tunnel the tunnel index
     *  @return the destination string or proxy list, never null
     */
    public String getClientDestination(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun == null) return "";
        String rv;
        if (TunnelController.TYPE_STD_CLIENT.equals(tun.getType()) ||
            TunnelController.TYPE_IRC_CLIENT.equals(tun.getType()) ||
            TunnelController.TYPE_STREAMR_CLIENT.equals(tun.getType())) {
            rv = tun.getTargetDestination();
        } else {rv = tun.getProxyList();}
        return rv != null ? rv : "";
    }

    /**
     *  Retrieve the tunnel Destination, reading from the key file if the tunnel is not running.
     *
     *  @param tunnel the tunnel index
     *  @return Destination or null
     */
    public Destination getDestination(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            Destination rv = tun.getDestination();
            if (rv != null) {return rv;}
            // if not running, do this the hard way
            File keyFile = tun.getPrivateKeyFile();
            if (keyFile != null) {
                PrivateKeyFile pkf = new PrivateKeyFile(keyFile);
                try {
                    rv = pkf.getDestination();
                    if (rv != null) {return rv;}
                }
                catch (I2PException e) { /* ignored */ }
                catch (IOException e) { /* ignored */ }
            }
        }
        return null;
    }

    /**
     *  Retrieve the alternate tunnel Destination from the key file.
     *
     *  @param tunnel the tunnel index
     *  @return Destination or null
     *  @since 0.9.30
     */
    public Destination getAltDestination(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            // do this the hard way
            File keyFile = tun.getAlternatePrivateKeyFile();
            if (keyFile != null) {
                PrivateKeyFile pkf = new PrivateKeyFile(keyFile);
                try {
                    Destination rv = pkf.getDestination();
                    if (rv != null) {return rv;}
                }
                catch (I2PException e) { /* ignored */ }
                catch (IOException e) { /* ignored */ }
            }
        }
        return null;
    }

    /**
     *  Check whether the tunnel uses offline keys.
     *
     *  @param tunnel the tunnel index
     *  @return true if offline keys
     *  @since 0.9.40
     */
    public boolean isOfflineKeys(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            if (tun.getIsRunning()) {return tun.getIsOfflineKeys();}
            // do this the hard way
            File keyFile = tun.getPrivateKeyFile();
            if (keyFile != null) {
                PrivateKeyFile pkf = new PrivateKeyFile(keyFile);
                return pkf.isOffline();
            }
        }
        return false;
    }

    /**
     *  Check whether the tunnel is configured to start automatically.
     *
     *  @param tunnel the tunnel index
     *  @return true if auto-start is enabled
     */
    public boolean shouldStartAutomatically(int tunnel) {
        TunnelController tun = getController(tunnel);
        return tun != null ? tun.getStartOnLoad() : false;
    }

    /**
     *  Check whether this tunnel shares its I2CP session with other clients.
     *
     *  @param tunnel the tunnel index
     *  @return true if it is a shared client
     */
    public boolean isSharedClient(int tunnel) {
        TunnelController tun = getController(tunnel);
        return tun != null ? Boolean.parseBoolean(tun.getSharedClient()) : false;
    }

    /**
     *  Check whether the tunnel has a streaming connect delay configured.
     *
     *  @param tunnel the tunnel index
     *  @return true if connect delay is positive
     */
    public boolean shouldDelayConnect(int tunnel) {
        return getProperty(tunnel, "i2p.streaming.connectDelay", 0) > 0;
    }

    /**
     *  Check whether the tunnel is configured for interactive streaming (small window).
     *
     *  @param tunnel the tunnel index
     *  @return true if max window size is 16
     */
    public boolean isInteractive(int tunnel) {
        return getProperty(tunnel, "i2p.streaming.maxWindowSize", 128) == 16;
    }

    /**
     *  Return the inbound tunnel depth (applies to both in/out if not split).
     *
     *  @param tunnel the tunnel index
     *  @param defaultLength default value if not configured
     *  @return tunnel depth in hops
     */
    public int getTunnelDepth(int tunnel, int defaultLength) {
        return getProperty(tunnel, "inbound.length", defaultLength);
    }

    /**
     *  Return the inbound tunnel quantity (applies to both in/out if not split).
     *
     *  @param tunnel the tunnel index
     *  @param defaultQuantity default value if not configured
     *  @return number of tunnels
     */
    public int getTunnelQuantity(int tunnel, int defaultQuantity) {
        return getProperty(tunnel, "inbound.quantity", defaultQuantity);
    }

    /**
     *  Return the inbound backup tunnel quantity (applies to both in/out if not split).
     *
     *  @param tunnel the tunnel index
     *  @param defaultBackupQuantity default value if not configured
     *  @return number of backup tunnels
     */
    public int getTunnelBackupQuantity(int tunnel, int defaultBackupQuantity) {
        return getProperty(tunnel, "inbound.backupQuantity", defaultBackupQuantity);
    }

    /**
     *  Return the inbound tunnel length variance (applies to both in/out if not split).
     *
     *  @param tunnel the tunnel index
     *  @param defaultVariance default value if not configured
     *  @return length variance in hops
     */
    public int getTunnelVariance(int tunnel, int defaultVariance) {
        return getProperty(tunnel, "inbound.lengthVariance", defaultVariance);
    }

    /**
     *  Return the outbound tunnel depth.
     *
     *  @param tunnel the tunnel index
     *  @param defaultLength default value if not configured
     *  @return tunnel depth in hops
     *  @since 0.9.33
     */
    public int getTunnelDepthOut(int tunnel, int defaultLength) {
        return getProperty(tunnel, "outbound.length", defaultLength);
    }

    /**
     *  Return the outbound tunnel quantity.
     *
     *  @param tunnel the tunnel index
     *  @param defaultQuantity default value if not configured
     *  @return number of tunnels
     *  @since 0.9.33
     */
    public int getTunnelQuantityOut(int tunnel, int defaultQuantity) {
        return getProperty(tunnel, "outbound.quantity", defaultQuantity);
    }

    /**
     *  Return the outbound backup tunnel quantity.
     *
     *  @param tunnel the tunnel index
     *  @param defaultBackupQuantity default value if not configured
     *  @return number of backup tunnels
     *  @since 0.9.33
     */
    public int getTunnelBackupQuantityOut(int tunnel, int defaultBackupQuantity) {
        return getProperty(tunnel, "outbound.backupQuantity", defaultBackupQuantity);
    }

    /**
     *  Return the outbound tunnel length variance.
     *
     *  @param tunnel the tunnel index
     *  @param defaultVariance default value if not configured
     *  @return length variance in hops
     *  @since 0.9.33
     */
    public int getTunnelVarianceOut(int tunnel, int defaultVariance) {
        return getProperty(tunnel, "outbound.lengthVariance", defaultVariance);
    }

    /**
     *  Check whether I2CP session reduction on idle is enabled.
     *
     *  @param tunnel the tunnel index
     *  @param def default value if not configured
     *  @return true if reduction on idle is enabled
     */
    public boolean getReduceOnIdle(int tunnel, boolean def) {
        return getBooleanProperty(tunnel, "i2cp.reduceOnIdle", def);
    }

    /**
     *  Return the I2CP reduce quantity (target number of tunnels when idle).
     *
     *  @param tunnel the tunnel index
     *  @param def default value if not configured
     *  @return reduce quantity
     */
    public int getReduceCount(int tunnel, int def) {
        return getProperty(tunnel, "i2cp.reduceQuantity", def);
    }

    /**
     *  Return the I2CP idle reduction time threshold.
     *
     *  @param tunnel the tunnel index
     *  @param def default idle time in minutes
     *  @return idle time in minutes
     */
    public int getReduceTime(int tunnel, int def) {
        return getProperty(tunnel, "i2cp.reduceIdleTime", def*60*1000) / (60*1000);
    }

    /**
     *  Return the certificate type for the tunnel (currently unused, always 0).
     *
     *  @param tunnel the tunnel index
     *  @return always 0
     */
    public int getCert(int tunnel) {return 0;}
    /**
     *  Return the proof-of-work effort for blinded leases (currently unused, always 23).
     *
     *  @param tunnel the tunnel index
     *  @return always 23
     */
    public int getEffort(int tunnel) {return 23;}
    /**
     *  Return the signer for blinded leases (currently unused, always empty).
     *
     *  @param tunnel the tunnel index
     *  @return always &quot;&quot;
     */
    public String getSigner(int tunnel) {return "";}
    /**
     *  Check whether the lease set should be encrypted.
     *
     *  @param tunnel the tunnel index
     *  @return true if encryption is enabled
     */
    public boolean getEncrypt(int tunnel) {return getBooleanProperty(tunnel, "i2cp.encryptLeaseSet");}

    /**
     *  Determine the encryption mode for the tunnel's lease set.
     *
     *  @param tunnel the tunnel index
     *  @return encryption mode code (0=none, 1=full, 2=blinded, etc.)
     *  @since 0.9.40
     */
    public int getEncryptMode(int tunnel) {
        if (getEncrypt(tunnel)) {return 1;}
        String lstype = getProperty(tunnel, "i2cp.leaseSetType", "1");
        if (lstype.equals("5")) {
            int rv;
            String authType = getProperty(tunnel, "i2cp.leaseSetAuthType", "0");
            if (authType.equals("2")) {
                if (getProperty(tunnel, "i2cp.leaseSetClient.psk.0", null) != null) {rv = 6;} // per-client PSK key
                else {rv = 4; } // shared PSK key
            } else if (authType.equals("1")) {rv = 8;}
            else {rv = 2;}

            String pw = getBlindedPassword(tunnel);
            if (pw != null && !pw.isEmpty()) {rv++;}
            return rv;
        } else if (lstype.equals("3")) {return 10;}
        return 0;
    }

    /**
     *  Return the blinded password for the tunnel's lease set.
     *
     *  @param tunnel the tunnel index
     *  @return the decoded blinded password, or empty string
     *  @since 0.9.40
     */
    public String getBlindedPassword(int tunnel) {
        String rv = getProperty(tunnel, "i2cp.leaseSetSecret", null);
        if (rv != null) {rv = DataHelper.getUTF8(Base64.decode(rv));}
        if (rv == null) {rv = "";}
        return rv;
    }

    /**
     *  Return the list of authorized client authentications for the lease set.
     *  Each entry is a base64-encoded name:key pair.
     *
     *  @param tunnel the tunnel index
     *  @param isDH true for DH public keys, false for PSK private keys
     *  @return non-null list of auth entries
     *  @since 0.9.41
     */
    public List<String> getClientAuths(int tunnel, boolean isDH) {
        List<String> rv = new ArrayList<>(4);
        String pfx = isDH ? "i2cp.leaseSetClient.dh." : "i2cp.leaseSetClient.psk.";
        int i = 0;
        String p;
        while ((p = getProperty(tunnel, pfx + i, null)) != null) {
             rv.add(p);
             i++;
        }
        return rv;
    }

    /**
     *  Return the signature type code for the tunnel.
     *
     *  @param tunnel the tunnel index
     *  @param newTunnelType used if tunnel &lt; 0 to determine default
     *  @return the current type if a destination exists, else the default for that tunnel type
     */
    public int getSigType(int tunnel, String newTunnelType) {
        SigType type;
        String ttype;
        if (tunnel >= 0) {
            ttype = getTunnelType(tunnel);
            if (!TunnelController.isClient(ttype) ||
                getBooleanProperty(tunnel, "persistentClientKey")) {
                Destination d = getDestination(tunnel);
                if (d != null) {
                    type = d.getSigType();
                    if (type != null) {return type.getCode();}
                }
            }
            String stype = getProperty(tunnel, I2PClient.PROP_SIGTYPE, null);
            type = stype != null ? SigType.parseSigType(stype) : null;
        } else {
            type = null;
            ttype = newTunnelType;
        }
        if (type == null) {
            // same default logic as in TunnelController.setConfig()
            if (!TunnelController.isClient(ttype) ||
                TunnelController.TYPE_IRC_CLIENT.equals(ttype) ||
                TunnelController.TYPE_SOCKS_IRC.equals(ttype) ||
                TunnelController.TYPE_SOCKS.equals(ttype) ||
                TunnelController.TYPE_STREAMR_CLIENT.equals(ttype) ||
                TunnelController.TYPE_STD_CLIENT.equals(ttype) ||
                TunnelController.TYPE_CONNECT.equals(ttype) ||
                TunnelController.TYPE_HTTP_CLIENT.equals(ttype)) {
                type = TunnelController.PREFERRED_SIGTYPE;
            } else {type = SigType.EdDSA_SHA512_Ed25519;}
        }
        return type.getCode();
    }

    /**
     *  Check whether the tunnel supports a given encryption type.
     *
     *  @param tunnel the tunnel index
     *  @param encType encryption type code
     *  @return true if the encryption type is in the configured list
     *  @since 0.9.44
     */
    public boolean hasEncType(int tunnel, int encType) {
        TunnelController tun = getController(tunnel);
        if (tun == null) {return encType == 4;} // New clients and servers now default to MLKEM768+ECIES
        String dflt = "6,4";
        String senc = getProperty(tunnel, "i2cp.leaseSetEncType", dflt);
        String[] senca = DataHelper.split(senc, ",");
        String se = Integer.toString(encType);
        for (int i = 0; i < senca.length; i++) {
            if (se.equals(senca[i])) {return true;}
        }
        return false;
    }

    /**
     *  Return the inbound random key (used for testing LS encryption).
     *
     *  @param tunnel the tunnel index
     *  @return the key, or empty string if not set
     */
    public String getInboundRandomKey(int tunnel) {
        return getProperty(tunnel, "inbound.randomKey", "");
    }

    /**
     *  Return the outbound random key (used for testing LS encryption).
     *
     *  @param tunnel the tunnel index
     *  @return the key, or empty string if not set
     */
    public String getOutboundRandomKey(int tunnel) {
        return getProperty(tunnel, "outbound.randomKey", "");
    }

    /**
     *  Return the lease set signing private key.
     *
     *  @param tunnel the tunnel index
     *  @return the key, or empty string if not set
     */
    public String getLeaseSetSigningPrivateKey(int tunnel) {
        return getProperty(tunnel, "i2cp.leaseSetSigningPrivateKey", "");
    }

    /**
     *  Return the lease set private key (for encrypted LS).
     *
     *  @param tunnel the tunnel index
     *  @return the key, or empty string if not set
     */
    public String getLeaseSetPrivateKey(int tunnel) {
        return getProperty(tunnel, "i2cp.leaseSetPrivateKey", "");
    }

    /**
     *  Check whether DCC (direct client-to-client) is enabled for IRC tunnels.
     *
     *  @param tunnel the tunnel index
     *  @return true if DCC is enabled
     */
    public boolean getDCC(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelIRCClient.PROP_DCC);
    }

    /**
     *  Check whether SSL is enabled for the server tunnel.
     *
     *  @param tunnel the tunnel index
     *  @return true if SSL is enabled
     */
    public boolean isSSLEnabled(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelServer.PROP_USE_SSL);
    }

    /**
     *  Return the lease set encryption key.
     *
     *  @param tunnel the tunnel index
     *  @return the key, or empty string if not set
     */
    public String getEncryptKey(int tunnel) {
        return getProperty(tunnel, "i2cp.leaseSetKey", "");
    }

    /**
     *  Return the access control mode for the tunnel.
     *
     *  @param tunnel the tunnel index
     *  @return 0=none, 1=whitelist, 2=blacklist
     */
    public int getAccessMode(int tunnel) {
        if (getBooleanProperty(tunnel, PROP_ENABLE_ACCESS_LIST)) {return 1;}
        if (getBooleanProperty(tunnel, PROP_ENABLE_BLACKLIST)) {return 2;}
        return 0;
    }

    /**
     *  Return the access control list (comma-separated destinations), newline-delimited.
     *
     *  @param tunnel the tunnel index
     *  @return the access list entries, one per line
     */
    public String getAccessList(int tunnel) {
        return getProperty(tunnel, "i2cp.accessList", "").replace(",", "\n");
    }

    /**
     *  Return the connect filter definition for the tunnel.
     *
     *  @param tunnel the tunnel index
     *  @return the filter definition, or empty string if not set
     *  @since 0.9.40
     */
    public String getFilterDefinition(int tunnel) {
        TunnelController tunnelController = getController(tunnel);
        if (tunnelController != null) {
            String filter = tunnelController.getFilter();
            if (filter != null)
                return filter;
        }
        return "";
    }

    /**
     *  Return the list of jump servers (one per line).
     *
     *  @param tunnel the tunnel index
     *  @return the jump server list, newline-delimited
     */
    /**
     *  Return the jump server list for HTTP clients (one per line).
     *
     *  @param tunnel the tunnel index
     *  @return the jump server list, newline-delimited
     */
    public String getJumpList(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPClient.PROP_JUMP_SERVERS,
                           I2PTunnelHTTPClient.DEFAULT_JUMP_SERVERS).replace(",", "\n");
    }

    /**
     *  Check whether I2CP close-on-idle is enabled.
     *
     *  @param tunnel the tunnel index
     *  @param def default value if not configured
     *  @return true if close-on-idle is enabled
     */
    public boolean getCloseOnIdle(int tunnel, boolean def) {
        return getBooleanProperty(tunnel, "i2cp.closeOnIdle", def);
    }

    /**
     *  Return the I2CP close-on-idle timeout in minutes.
     *
     *  @param tunnel the tunnel index
     *  @param def default value in minutes
     *  @return idle timeout in minutes
     */
    public int getCloseTime(int tunnel, int def) {
        return getProperty(tunnel, "i2cp.closeIdleTime", def*60*1000) / (60*1000);
    }

    /**
     *  Check whether the tunnel should get a new destination on resume.
     *
     *  @param tunnel the tunnel index
     *  @return true if a new destination will be created on resume
     */
    public boolean getNewDest(int tunnel) {
        return getBooleanProperty(tunnel, "i2cp.newDestOnResume", true) &&
               getBooleanProperty(tunnel, "i2cp.closeOnIdle") &&
               !getBooleanProperty(tunnel, "persistentClientKey");
    }

    /**
     *  Check whether the tunnel uses a persistent client key file.
     *
     *  @param tunnel the tunnel index
     *  @return true if the key is persistent
     */
    public boolean getPersistentClientKey(int tunnel) {
        return getBooleanProperty(tunnel, "persistentClientKey");
    }

    /**
     *  Check whether I2CP delay-open is enabled.
     *
     *  @param tunnel the tunnel index
     *  @return true if delay-open is enabled
     */
    public boolean getDelayOpen(int tunnel) {
        return getBooleanProperty(tunnel, "i2cp.delayOpen");
    }

    /**
     *  Check whether the HTTP client allows custom User-Agent headers.
     *
     *  @param tunnel the tunnel index
     *  @return true if custom User-Agent is allowed
     */
    public boolean getAllowUserAgent(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelHTTPClient.PROP_USER_AGENT);
    }

    /**
     *  Check whether the HTTP client allows custom Referer headers.
     *
     *  @param tunnel the tunnel index
     *  @return true if custom Referer is allowed
     */
    public boolean getAllowReferer(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelHTTPClient.PROP_REFERER);
    }

    /**
     *  Check whether the HTTP client allows custom Accept headers.
     *
     *  @param tunnel the tunnel index
     *  @return true if custom Accept is allowed
     */
    public boolean getAllowAccept(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelHTTPClient.PROP_ACCEPT);
    }

    /**
     *  Check whether internal SSL connections are allowed through the HTTP client.
     *  As of 0.9.35, defaults to true unless explicitly disabled.
     *
     *  @param tunnel the tunnel index
     *  @return true if internal SSL is allowed
     */
    public boolean getAllowInternalSSL(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelHTTPClient.PROP_INTERNAL_SSL, true) ||
               !getBooleanProperty(tunnel, I2PTunnelHTTPClient.PROP_SSL_SET, true);
    }

    /**
     *  Check whether the tunnel should bundle reply information (multihome mode).
     *
     *  @param tunnel the tunnel index
     *  @return true if bundling is enabled
     */
    public boolean getMultihome(int tunnel) {
        return getBooleanProperty(tunnel, "shouldBundleReplyInfo");
    }

    /**
     *  Return the proxy authentication mode.
     *
     *  @param tunnel the tunnel index
     *  @return &quot;false&quot;, &quot;true&quot;, or &quot;basic&quot;
     */
    public String getProxyAuth(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPClientBase.PROP_AUTH, "false");
    }

    /**
     *  Check whether outproxy authentication is required.
     *
     *  @param tunnel the tunnel index
     *  @return true if outproxy auth is enabled
     */
    public boolean getOutproxyAuth(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelHTTPClientBase.PROP_OUTPROXY_AUTH);
    }

    /**
     *  Return the outproxy username.
     *
     *  @param tunnel the tunnel index
     *  @return the username, or empty string
     */
    public String getOutproxyUsername(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPClientBase.PROP_OUTPROXY_USER, "");
    }

    /**
     *  Return the outproxy password (only if a username is set).
     *
     *  @param tunnel the tunnel index
     *  @return the password, or empty string
     */
    public String getOutproxyPassword(int tunnel) {
        if (getOutproxyUsername(tunnel).length() <= 0)
            return "";
        return getProperty(tunnel, I2PTunnelHTTPClientBase.PROP_OUTPROXY_PW, "");
    }

    /**
     *  Return the list of SSL outproxies.
     *
     *  @param tunnel the tunnel index
     *  @return comma-separated SSL outproxy list
     */
    public String getSslProxies(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPClient.PROP_SSL_OUTPROXIES, "");
    }

    /**
     *  Check whether the outproxy plugin is enabled.
     *
     *  @param tunnel the tunnel index
     *  @return true if the outproxy plugin is used, defaults to true
     */
    public boolean getUseOutproxyPlugin(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelHTTPClientBase.PROP_USE_OUTPROXY_PLUGIN, true);
    }

    /**
     *  Return the outproxy connection type.
     *
     *  @param tunnel the tunnel index
     *  @return &quot;connect&quot; or &quot;socks&quot;, default depends on tunnel type
     *  @since 0.9.57
     */
    public String getOutproxyType(int tunnel) {
        String type = getTunnelType(tunnel);
        if (!type.equals("sockstunnel") && !type.equals("socksirctunnel")) {return "connect";}
        return getProperty(tunnel, I2PSOCKSTunnel.PROP_OUTPROXY_TYPE, "socks");
    }

    /**
     *  Return the per-minute connection limit.
     *
     *  @param tunnel the tunnel index
     *  @return max connections per minute
     *  @since 0.8.3
     */
    public int getLimitMinute(int tunnel) {
        return getProperty(tunnel, TunnelController.PROP_MAX_CONNS_MIN, TunnelController.DEFAULT_MAX_CONNS_MIN);
    }

    /**
     *  Return the per-hour connection limit.
     *
     *  @param tunnel the tunnel index
     *  @return max connections per hour
     *  @since 0.8.3
     */
    public int getLimitHour(int tunnel) {
        return getProperty(tunnel, TunnelController.PROP_MAX_CONNS_HOUR, TunnelController.DEFAULT_MAX_CONNS_HOUR);
    }

    /**
     *  Return the per-day connection limit.
     *
     *  @param tunnel the tunnel index
     *  @return max connections per day
     *  @since 0.8.3
     */
    public int getLimitDay(int tunnel) {
        return getProperty(tunnel, TunnelController.PROP_MAX_CONNS_DAY, TunnelController.DEFAULT_MAX_CONNS_DAY);
    }

    /**
     *  Return the per-minute total connection limit across all sources.
     *
     *  @param tunnel the tunnel index
     *  @return max total connections per minute
     *  @since 0.8.3
     */
    public int getTotalMinute(int tunnel) {
        return getProperty(tunnel, TunnelController.PROP_MAX_TOTAL_CONNS_MIN, TunnelController.DEFAULT_MAX_TOTAL_CONNS_MIN);
    }

    /**
     *  Return the per-hour total connection limit across all sources.
     *
     *  @param tunnel the tunnel index
     *  @return max total connections per hour
     *  @since 0.8.3
     */
    public int getTotalHour(int tunnel) {
        return getProperty(tunnel, TunnelController.PROP_MAX_TOTAL_CONNS_HOUR, 0);
    }

    /**
     *  Return the per-day total connection limit across all sources.
     *
     *  @param tunnel the tunnel index
     *  @return max total connections per day
     *  @since 0.8.3
     */
    public int getTotalDay(int tunnel) {
        return getProperty(tunnel, TunnelController.PROP_MAX_TOTAL_CONNS_DAY, 0);
    }

    /**
     *  Return the maximum concurrent streams for the tunnel.
     *
     *  @param tunnel the tunnel index
     *  @return max streams
     *  @since 0.8.3
     */
    public int getMaxStreams(int tunnel) {
        return getProperty(tunnel, TunnelController.PROP_MAX_STREAMS, TunnelController.DEFAULT_MAX_STREAMS);
    }

    /**
     *  Return the maximum POST request size in bytes.
     *
     *  @param tunnel the tunnel index
     *  @return max POST size in bytes
     *  @since 0.9.9
     */
    public int getPostMax(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPServer.OPT_POST_MAX, I2PTunnelHTTPServer.DEFAULT_POST_MAX);
    }

    /**
     *  Return the maximum total POST request size for the window.
     *
     *  @param tunnel the tunnel index
     *  @return max total POST size in bytes
     *  @since 0.9.9
     */
    public int getPostTotalMax(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPServer.OPT_POST_TOTAL_MAX, I2PTunnelHTTPServer.DEFAULT_POST_TOTAL_MAX);
    }

    /**
     *  Return the POST check window size in minutes.
     *
     *  @param tunnel the tunnel index
     *  @return check window in minutes
     *  @since 0.9.9
     */
    public int getPostCheckTime(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPServer.OPT_POST_WINDOW, I2PTunnelHTTPServer.DEFAULT_POST_WINDOW) / 60;
    }

    /**
     *  Return the POST ban duration in minutes.
     *
     *  @param tunnel the tunnel index
     *  @return ban time in minutes
     *  @since 0.9.9
     */
    public int getPostBanTime(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPServer.OPT_POST_BAN_TIME, I2PTunnelHTTPServer.DEFAULT_POST_BAN_TIME) / 60;
    }

    /**
     *  Return the total POST ban duration in minutes.
     *
     *  @param tunnel the tunnel index
     *  @return total ban time in minutes
     *  @since 0.9.9
     */
    public int getPostTotalBanTime(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPServer.OPT_POST_TOTAL_BAN_TIME, I2PTunnelHTTPServer.DEFAULT_POST_TOTAL_BAN_TIME) / 60;
    }

    /**
     *  Check whether inproxy connections are rejected.
     *
     *  @param tunnel the tunnel index
     *  @return true if inproxy connections are rejected
     *  @since 0.9.9
     */
    public boolean getRejectInproxy(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelHTTPServer.OPT_REJECT_INPROXY);
    }

    /**
     *  Check whether connections with a Referer header are rejected.
     *
     *  @param tunnel the tunnel index
     *  @return true if Referer connections are rejected
     *  @since 0.9.25
     */
    public boolean getRejectReferer(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelHTTPServer.OPT_REJECT_REFERER);
    }

    /**
     *  Check whether connections with known bad User-Agents are rejected.
     *
     *  @param tunnel the tunnel index
     *  @return true if User-Agent rejection is enabled
     *  @since 0.9.25
     */
    public boolean getRejectUserAgents(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelHTTPServer.OPT_REJECT_USER_AGENTS);
    }

    /**
     *  Return the blocked User-Agent list.
     *
     *  @param tunnel the tunnel index
     *  @return comma-separated user agents to block
     *  @since 0.9.25
     */
    public String getUserAgents(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPServer.OPT_USER_AGENTS, "");
    }

    /**
     *  Check whether the server tunnel uses unique local addresses for each client.
     *
     *  @param tunnel the tunnel index
     *  @return true if unique local addressing is enabled
     *  @since 0.9.9
     */
    public boolean getUniqueLocal(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelServer.PROP_UNIQUE_LOCAL);
    }

    /**
     *  Return the custom client options as a single URL-parameter-style string.
     *
     *  @param tunnel the tunnel index
     *  @return the options string, HTML-escaped, or empty string
     */
    public String getCustomOptionsString(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            Properties opts = tun.getClientOptionProps();
            if (opts == null) {return "";}
            boolean isMD5Proxy = TunnelController.TYPE_HTTP_CLIENT.equals(tun.getType()) ||
                                 TunnelController.TYPE_CONNECT.equals(tun.getType());
            Map<String, String> sorted = new TreeMap<>();
            for (Map.Entry<Object, Object> e : opts.entrySet()) {
                String key = (String)e.getKey();
                if (TunnelConfig._noShowSet.contains(key))
                    continue;
                // Leave in for HTTP and Connect so it can get migrated to MD5,
                // hide for SOCKS until migrated to MD5
                if (((!isMD5Proxy) && TunnelConfig._nonProxyNoShowSet.contains(key)) ||
                    key.startsWith("i2cp.leaseSetClient.")) {continue;}
                sorted.put(key, (String)e.getValue());
            }
            if (sorted.isEmpty()) {return "";}
            StringBuilder buf = new StringBuilder(64);
            boolean space = false;
            for (Map.Entry<String, String> e : sorted.entrySet()) {
                if (space) {buf.append(' ');}
                else {space = true;}
                buf.append(e.getKey()).append('=');
                String v = e.getValue();
                if (v.contains(" ") || v.contains("\t")) {buf.append('"').append(v).append('"');}
                else {buf.append(v);}
            }
            return DataHelper.escapeHTML(buf.toString());
        } else {
            return "";
        }
    }

    //
    // Internal helpers
    //

    private int getProperty(int tunnel, String prop, int def) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            Properties opts = tun.getClientOptionProps();
            if (opts != null) {
                String s = opts.getProperty(prop);
                if (s == null) return def;
                try {return Integer.parseInt(s);}
                catch (NumberFormatException nfe) { /* ignored */ }
            }
        }
        return def;
    }

    private String getProperty(int tunnel, String prop, String def) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            Properties opts = tun.getClientOptionProps();
            if (opts != null) {
                String rv = opts.getProperty(prop);
                if (rv != null) {return DataHelper.escapeHTML(rv);}
            }
        }
        return def;
    }

    /** default is false */
    private boolean getBooleanProperty(int tunnel, String prop) {
        return getBooleanProperty(tunnel, prop, false);
    }

    private boolean getBooleanProperty(int tunnel, String prop, boolean def) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            Properties opts = tun.getClientOptionProps();
            if (opts != null) {return Boolean.parseBoolean(opts.getProperty(prop));}
        }
        return def;
    }

    /**
     *  Translate a string using the given context.
     *
     *  @param key the untranslated string
     *  @param context the app context for translation
     *  @return the translated string
     */
    protected static String _t(String key, I2PAppContext context) {
        return Messages._t(key, context);
    }

}
