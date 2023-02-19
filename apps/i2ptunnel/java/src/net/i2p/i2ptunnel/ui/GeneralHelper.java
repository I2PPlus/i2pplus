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
    public static final int RUNNING = 1;
    public static final int STARTING = 2;
    public static final int NOT_RUNNING = 3;
    public static final int STANDBY = 4;

    protected static final String PROP_ENABLE_ACCESS_LIST = "i2cp.enableAccessList";
    protected static final String PROP_ENABLE_BLACKLIST = "i2cp.enableBlackList";

    private static final String OPT = TunnelController.PFX_OPTION;

    private final I2PAppContext _context;
    protected final TunnelControllerGroup _group;

    /**
     *  @param tcg may be null ???
     */
    public GeneralHelper(TunnelControllerGroup tcg) {
        this(I2PAppContext.getGlobalContext(), tcg);
    }

    /**
     *  @param tcg may be null ???
     */
    public GeneralHelper(I2PAppContext context, TunnelControllerGroup tcg) {
        _context = context;
        _group = tcg;
    }

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
        if (controllers.size() > tunnel)
            return controllers.get(tunnel);
        else
            return null;
    }

    /**
     *  Save the configuration for a new or existing tunnel to disk.
     *  For new tunnels, adds to controller and (if configured) starts it.
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
        List<String> msgs = new ArrayList<String>();
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
        List<String> msgs = new ArrayList<String>();
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
        if (TunnelController.TYPE_STD_CLIENT.equals(type) || TunnelController.TYPE_IRC_CLIENT.equals(type)) {
            //
            // If we switch to SSL, create the keystore here, so we can store the new properties.
            // Down in I2PTunnelClientBase it's very hard to save the config.
            //
            if (Boolean.parseBoolean(props.getProperty(OPT + I2PTunnelClientBase.PROP_USE_SSL))) {
                // add the local interface and all targets to the cert
                String intfc = props.getProperty(TunnelController.PROP_INTFC);
                Set<String> altNames = new HashSet<String>(4);
                if (intfc != null && !intfc.equals("0.0.0.0") && !intfc.equals("::") &&
                    !intfc.equals("0:0:0:0:0:0:0:0"))
                    altNames.add(intfc);
                String tgts = props.getProperty(TunnelController.PROP_DEST);
                if (tgts != null) {
                    altNames.add(intfc);
                    String[] hosts = DataHelper.split(tgts, "[ ,]");
                    for (String h : hosts) {
                        int colon = h.indexOf(':');
                        if (colon >= 0)
                            h = h.substring(0, colon);
                        altNames.add(h);
                        if (!h.endsWith(".b32.i2p")) {
                            Hash hash = ConvertToHash.getHash(h);
                            if (hash != null)
                                altNames.add(hash.toBase32());
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
        }
        if (cur == null) {
            // creating new
            cur = new TunnelController(props, "", true);
            tcg.addController(cur);
            if (cur.getStartOnLoad())
                cur.startTunnelBackground();
        } else {
            cur.setConfig(props, "");
        }
        // Only modify other shared tunnels
        // if the current tunnel is shared, and of supported type
        if (Boolean.parseBoolean(cur.getSharedClient()) && TunnelController.isClient(cur.getType())) {
            // all clients use the same I2CP session, and as such, use the same I2CP options
            List<TunnelController> controllers = tcg.getControllers();

            for (int i = 0; i < controllers.size(); i++) {
                TunnelController c = controllers.get(i);

                // Current tunnel modified by user, skip
                if (c == cur) continue;

                // Only modify this non-current tunnel
                // if it belongs to a shared destination, and is of supported type
                if (Boolean.parseBoolean(c.getSharedClient()) && TunnelController.isClient(c.getType())) {
                    copySharedOptions(config, props, c);
                    try {
                        tcg.saveConfig(c);
                    } catch (IOException ioe) {
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
            if (v != null)
                cOpt.setProperty(k, v);
            else
                cOpt.remove(k);
        }
        // persistent client key, not prefixed with "option."
        String v = from.getProperty(TunnelController.PROP_FILE);
        if (v != null)
            cOpt.setProperty(TunnelController.PROP_FILE, v);
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
            rv.add("Invalid tunnel number");
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
            rv.add(0, _t("Configuration changes saved", context));
        } catch (IOException ioe) {
            Log log = context.logManager().getLog(GeneralHelper.class);
            log.error("Failed to save config file", ioe);
            rv.add(0, _t("Failed to save configuration", context) + ": " + ioe.toString());
        }
        return rv;
    }

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
    public static List<String> deleteTunnel(
            I2PAppContext context, TunnelControllerGroup tcg, int tunnel, String privKeyFile) {
        List<String> msgs;
        TunnelController cur = getController(tcg, tunnel);
        if (cur == null) {
            msgs = new ArrayList<String>();
            msgs.add("Invalid tunnel number");
            return msgs;
        }

        msgs = tcg.removeController(cur);
        try {
            tcg.removeConfig(cur);
        }catch (IOException ioe){
            msgs.add(ioe.toString());
        }

        // Rename private key file if it was a default name in
        // the default directory, so it doesn't get reused when a new
        // tunnel is created.
        // Use configured file name if available, not the one from the form.
        String pk = cur.getPrivKeyFile();
        if (pk == null)
            pk = privKeyFile;
        if (pk != null && pk.startsWith("i2ptunnel") && pk.endsWith("-privKeys.dat") &&
            ((!TunnelController.isClient(cur.getType())) || cur.getPersistentClientKey())) {
            File pkf = new File(context.getConfigDir(), pk);
            if (pkf.exists()) {
                String name = cur.getName();
                if (name == null) {
                    name = cur.getDescription();
                    if (name == null) {
                        name = cur.getType();
                        if (name == null)
                            name = Long.toString(context.clock().now());
                    }
                }
                name = name.replace(' ', '_').replace(':', '_').replace("..", "_").replace('/', '_').replace('\\', '_');
                name = "i2ptunnel-deleted-" + name + '-' + context.clock().now() + "-privkeys.dat";
                File backupDir = new SecureFile(context.getConfigDir(), TunnelController.KEY_BACKUP_DIR);
                File to;
                if (backupDir.isDirectory() || backupDir.mkdir())
                    to = new File(backupDir, name);
                else
                    to = new File(context.getConfigDir(), name);
                boolean success = FileUtil.rename(pkf, to);
                if (success)
                    msgs.add("Private key file " + pkf.getAbsolutePath() + " renamed to " + to.getAbsolutePath());
            }
        }
        return msgs;
    }

    //
    // Accessors
    //

    public String getTunnelType(int tunnel) {
        TunnelController tun = getController(tunnel);
        return (tun != null && tun.getType() != null) ? tun.getType() : "";
    }

    /**
     *  @return null if unset
     */
    public String getTunnelName(int tunnel) {
        return getTunnelName(_group, tunnel);
    }

    /**
     *  @return null if unset
     */
    public static String getTunnelName(TunnelControllerGroup tcg, int tunnel) {
        TunnelController tun = getController(tcg, tunnel);
        return tun != null ? tun.getName() : null;
    }

    public String getTunnelDescription(int tunnel) {
        TunnelController tun = getController(tunnel);
        return (tun != null && tun.getDescription() != null) ? tun.getDescription() : "";
    }

    public String getTargetHost(int tunnel) {
        TunnelController tun = getController(tunnel);
        return (tun != null && tun.getTargetHost() != null) ? tun.getTargetHost() : "127.0.0.1";
    }

    /**
     * @param tunnel
     * @return -1 if unset or invalid
     */
    public int getTargetPort(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null && tun.getTargetPort() != null) {
            try {
                return Integer.parseInt(tun.getTargetPort());
            } catch (NumberFormatException e) {
                return -1;
            }
        } else
            return -1;
    }

    public String getSpoofedHost(int tunnel) {
        TunnelController tun = getController(tunnel);
        return (tun != null && tun.getSpoofedHost() != null) ? tun.getSpoofedHost() : "";
    }

    /**
     *  @return path, non-null, non-empty
     */
    public String getPrivateKeyFile(int tunnel) {
        return getPrivateKeyFile(_group, tunnel);
    }

    /**
     *  @return path, non-null, non-empty
     */
    public String getPrivateKeyFile(TunnelControllerGroup tcg, int tunnel) {
        TunnelController tun = getController(tcg, tunnel);
        if (tun != null) {
            String rv = tun.getPrivKeyFile();
            if (rv != null)
                return rv;
        }
        if (tunnel < 0)
            tunnel = tcg == null ? 999 : tcg.getControllers().size();
        String rv = "i2ptunnel" + tunnel + "-privKeys.dat";
        // Don't default to a file that already exists,
        // which could happen after other tunnels are deleted.
        int i = 0;
        while ((new File(_context.getConfigDir(), rv)).exists()) {
            rv = "i2ptunnel" + tunnel + '.' + (++i) + "-privKeys.dat";
        }
        return rv;
    }

    /**
     *  @return path or ""
     *  @since 0.9.30
     */
    public String getAltPrivateKeyFile(int tunnel) {
        return getAltPrivateKeyFile(_group, tunnel);
    }

    /**
     *  @return path or ""
     *  @since 0.9.30
     */
    public String getAltPrivateKeyFile(TunnelControllerGroup tcg, int tunnel) {
        TunnelController tun = getController(tcg, tunnel);
        if (tun != null) {
            File f = tun.getAlternatePrivateKeyFile();
            if (f != null)
                return f.getAbsolutePath();
        }
        return "";
    }

    public String getClientInterface(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            if ("streamrclient".equals(tun.getType()))
                return tun.getTargetHost();
            else
                return tun.getListenOnInterface();
        } else
            return "127.0.0.1";
    }

    public int getClientPort(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null && tun.getListenPort() != null) {
            try {
                return Integer.parseInt(tun.getListenPort());
            } catch (NumberFormatException e) {
                return -1;
            }
        } else
            return -1;
    }

    public int getTunnelStatus(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun == null) return NOT_RUNNING;
        if (tun.getIsRunning()) {
            if (tun.isClient() && tun.getIsStandby())
                return STANDBY;
            else
                return RUNNING;
        } else if (tun.getIsStarting()) return STARTING;
        else return NOT_RUNNING;
    }

    public String getClientDestination(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun == null) return "";
        String rv;
        if (TunnelController.TYPE_STD_CLIENT.equals(tun.getType()) ||
            TunnelController.TYPE_IRC_CLIENT.equals(tun.getType()) ||
            TunnelController.TYPE_STREAMR_CLIENT.equals(tun.getType()))
            rv = tun.getTargetDestination();
        else
            rv = tun.getProxyList();
        return rv != null ? rv : "";
    }

    /**
     *  Works even if tunnel is not running.
     *  @return Destination or null
     */
    public Destination getDestination(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            Destination rv = tun.getDestination();
            if (rv != null)
                return rv;
            // if not running, do this the hard way
            File keyFile = tun.getPrivateKeyFile();
            if (keyFile != null) {
                PrivateKeyFile pkf = new PrivateKeyFile(keyFile);
                try {
                    rv = pkf.getDestination();
                    if (rv != null)
                        return rv;
                } catch (I2PException e) {
                } catch (IOException e) {}
            }
        }
        return null;
    }

    /**
     *  Works even if tunnel is not running.
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
                    if (rv != null)
                        return rv;
                } catch (I2PException e) {
                } catch (IOException e) {}
            }
        }
        return null;
    }

    /**
     *  Works even if tunnel is not running.
     *  @return true if offline keys
     *  @since 0.9.40
     */
    public boolean isOfflineKeys(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            if (tun.getIsRunning())
                return tun.getIsOfflineKeys();
            // do this the hard way
            File keyFile = tun.getPrivateKeyFile();
            if (keyFile != null) {
                PrivateKeyFile pkf = new PrivateKeyFile(keyFile);
                return pkf.isOffline();
            }
        }
        return false;
    }

    public boolean shouldStartAutomatically(int tunnel) {
        TunnelController tun = getController(tunnel);
        return tun != null ? tun.getStartOnLoad() : false;
    }

    public boolean isSharedClient(int tunnel) {
        TunnelController tun = getController(tunnel);
        return tun != null ? Boolean.parseBoolean(tun.getSharedClient()) : false;
    }

    public boolean shouldDelayConnect(int tunnel) {
        return getProperty(tunnel, "i2p.streaming.connectDelay", 0) > 0;
    }

    public boolean isInteractive(int tunnel) {
        return getProperty(tunnel, "i2p.streaming.maxWindowSize", 128) == 16;
    }

    /** Inbound or both in/out */
    public int getTunnelDepth(int tunnel, int defaultLength) {
        return getProperty(tunnel, "inbound.length", defaultLength);
    }

    /** Inbound or both in/out */
    public int getTunnelQuantity(int tunnel, int defaultQuantity) {
        return getProperty(tunnel, "inbound.quantity", defaultQuantity);
    }

    /** Inbound or both in/out */
    public int getTunnelBackupQuantity(int tunnel, int defaultBackupQuantity) {
        return getProperty(tunnel, "inbound.backupQuantity", defaultBackupQuantity);
    }

    /** Inbound or both in/out */
    public int getTunnelVariance(int tunnel, int defaultVariance) {
        return getProperty(tunnel, "inbound.lengthVariance", defaultVariance);
    }

    /** @since 0.9.33 */
    public int getTunnelDepthOut(int tunnel, int defaultLength) {
        return getProperty(tunnel, "outbound.length", defaultLength);
    }

    /** @since 0.9.33 */
    public int getTunnelQuantityOut(int tunnel, int defaultQuantity) {
        return getProperty(tunnel, "outbound.quantity", defaultQuantity);
    }

    /** @since 0.9.33 */
    public int getTunnelBackupQuantityOut(int tunnel, int defaultBackupQuantity) {
        return getProperty(tunnel, "outbound.backupQuantity", defaultBackupQuantity);
    }

    /** @since 0.9.33 */
    public int getTunnelVarianceOut(int tunnel, int defaultVariance) {
        return getProperty(tunnel, "outbound.lengthVariance", defaultVariance);
    }

    public boolean getReduceOnIdle(int tunnel, boolean def) {
        return getBooleanProperty(tunnel, "i2cp.reduceOnIdle", def);
    }

    public int getReduceCount(int tunnel, int def) {
        return getProperty(tunnel, "i2cp.reduceQuantity", def);
    }

    /**
     * @param tunnel
     * @param def in minutes
     * @return time in minutes
     */
    public int getReduceTime(int tunnel, int def) {
        return getProperty(tunnel, "i2cp.reduceIdleTime", def*60*1000) / (60*1000);
    }

    public int getCert(int tunnel) {
        return 0;
    }

    public int getEffort(int tunnel) {
        return 23;
    }

    public String getSigner(int tunnel) {
        return "";
    }

    public boolean getEncrypt(int tunnel) {
        return getBooleanProperty(tunnel, "i2cp.encryptLeaseSet");
    }

    /**
     *  @since 0.9.40
     */
    public int getEncryptMode(int tunnel) {
        if (getEncrypt(tunnel))
            return 1;
        String lstype = getProperty(tunnel, "i2cp.leaseSetType", "1");
        if (lstype.equals("5")) {
            int rv;
            String authType = getProperty(tunnel, "i2cp.leaseSetAuthType", "0");
            if (authType.equals("2")) {
                if (getProperty(tunnel, "i2cp.leaseSetClient.psk.0", null) != null) {
                    // per-client PSK key
                    rv = 6;
                } else {
                    // shared PSK key
                    rv = 4;
                }
            } else if (authType.equals("1")) {
                rv = 8;
            } else {
                rv = 2;
            }

            String pw = getBlindedPassword(tunnel);
            if (pw != null && pw.length() > 0)
                rv++;
            return rv;
        } else if (lstype.equals("3")) {
            return 10;
        }
        return 0;
    }

    /**
     *  @since 0.9.40
     */
    public String getBlindedPassword(int tunnel) {
        String rv = getProperty(tunnel, "i2cp.leaseSetSecret", null);
        if (rv != null)
            rv = DataHelper.getUTF8(Base64.decode(rv));
        if (rv == null)
            rv = "";
        return rv;
    }

    /**
     *  List of b64 name : b64key
     *  Pubkeys for DH, privkeys for PSK
     *  @param isDH true for DH, false for PSK
     *  @return non-null
     *  @since 0.9.41
     */
    public List<String> getClientAuths(int tunnel, boolean isDH) {
        List<String> rv = new ArrayList<String>(4);
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
     *  @param newTunnelType used if tunnel &lt; 0
     *  @return the current type if we have a destination already,
     *          else the default for that type of tunnel
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
                    if (type != null)
                        return type.getCode();
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
                TunnelController.TYPE_HTTP_CLIENT.equals(ttype))
                type = TunnelController.PREFERRED_SIGTYPE;
            else
                type = SigType.DSA_SHA1;
        }
        return type.getCode();
    }

    /**
     *  @param encType code
     *  @since 0.9.44
     */
    public boolean hasEncType(int tunnel, int encType) {
        TunnelController tun = getController(tunnel);
        if (tun == null) {
            // New clients and servers now default to ECIES-only
            return encType == 4;
        }
        // migration: HTTP proxy and shared clients default to both
        String type = tun.getType();
        String dflt;
        if (tun.isClient() &&
            (TunnelController.TYPE_HTTP_CLIENT.equals(type) ||
             TunnelController.TYPE_IRC_CLIENT.equals(type) ||
             TunnelController.TYPE_SOCKS_IRC.equals(type) ||
             TunnelController.TYPE_STREAMR_CLIENT.equals(type) ||
             Boolean.parseBoolean(tun.getSharedClient()))) {
            dflt = "4,0";
        } else if (TunnelController.TYPE_HTTP_SERVER.equals(type) ||
                   TunnelController.TYPE_IRC_SERVER.equals(type) ||
                   TunnelController.TYPE_STREAMR_SERVER.equals(type)) {
            dflt = "4";
        } else {
            dflt = "4,0";
        }
        String senc = getProperty(tunnel, "i2cp.leaseSetEncType", dflt);
        String[] senca = DataHelper.split(senc, ",");
        String se = Integer.toString(encType);
        for (int i = 0; i < senca.length; i++) {
            if (se.equals(senca[i]))
                return true;
        }
        return false;
    }

    /**
     *  Random keys
     */
    public String getInboundRandomKey(int tunnel) {
        return getProperty(tunnel, "inbound.randomKey", "");
    }

    public String getOutboundRandomKey(int tunnel) {
        return getProperty(tunnel, "outbound.randomKey", "");
    }

    public String getLeaseSetSigningPrivateKey(int tunnel) {
        return getProperty(tunnel, "i2cp.leaseSetSigningPrivateKey", "");
    }

    public String getLeaseSetPrivateKey(int tunnel) {
        return getProperty(tunnel, "i2cp.leaseSetPrivateKey", "");
    }

    public boolean getDCC(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelIRCClient.PROP_DCC);
    }

    public boolean isSSLEnabled(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelServer.PROP_USE_SSL);
    }

    public String getEncryptKey(int tunnel) {
        return getProperty(tunnel, "i2cp.leaseSetKey", "");
    }

    public int getAccessMode(int tunnel) {
        if (getBooleanProperty(tunnel, PROP_ENABLE_ACCESS_LIST))
            return 1;
        if (getBooleanProperty(tunnel, PROP_ENABLE_BLACKLIST))
            return 2;
        return 0;
    }

    public String getAccessList(int tunnel) {
        return getProperty(tunnel, "i2cp.accessList", "").replace(",", "\n");
    }

    /**
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

    public String getJumpList(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPClient.PROP_JUMP_SERVERS,
                           I2PTunnelHTTPClient.DEFAULT_JUMP_SERVERS).replace(",", "\n");
    }

    public boolean getCloseOnIdle(int tunnel, boolean def) {
        return getBooleanProperty(tunnel, "i2cp.closeOnIdle", def);
    }

    public int getCloseTime(int tunnel, int def) {
        return getProperty(tunnel, "i2cp.closeIdleTime", def*60*1000) / (60*1000);
    }

    public boolean getNewDest(int tunnel) {
        return getBooleanProperty(tunnel, "i2cp.newDestOnResume", true) &&
               getBooleanProperty(tunnel, "i2cp.closeOnIdle") &&
               !getBooleanProperty(tunnel, "persistentClientKey");
    }

    public boolean getPersistentClientKey(int tunnel) {
        return getBooleanProperty(tunnel, "persistentClientKey");
    }

    public boolean getDelayOpen(int tunnel) {
        return getBooleanProperty(tunnel, "i2cp.delayOpen");
    }

    public boolean getAllowUserAgent(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelHTTPClient.PROP_USER_AGENT);
    }

    public boolean getAllowReferer(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelHTTPClient.PROP_REFERER);
    }

    public boolean getAllowAccept(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelHTTPClient.PROP_ACCEPT);
    }

    /**
     *  As of 0.9.35, default true, and overridden to true unless
     *  PROP_SSL_SET is set
     */
    public boolean getAllowInternalSSL(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelHTTPClient.PROP_INTERNAL_SSL, true) ||
               !getBooleanProperty(tunnel, I2PTunnelHTTPClient.PROP_SSL_SET, true);
    }

    public boolean getMultihome(int tunnel) {
        return getBooleanProperty(tunnel, "shouldBundleReplyInfo");
    }

    public String getProxyAuth(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPClientBase.PROP_AUTH, "false");
    }

    public boolean getOutproxyAuth(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelHTTPClientBase.PROP_OUTPROXY_AUTH);
    }

    public String getOutproxyUsername(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPClientBase.PROP_OUTPROXY_USER, "");
    }

    public String getOutproxyPassword(int tunnel) {
        if (getOutproxyUsername(tunnel).length() <= 0)
            return "";
        return getProperty(tunnel, I2PTunnelHTTPClientBase.PROP_OUTPROXY_PW, "");
    }

    public String getSslProxies(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPClient.PROP_SSL_OUTPROXIES, "");
    }

    /**
     *  Default true
     */
    public boolean getUseOutproxyPlugin(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelHTTPClientBase.PROP_USE_OUTPROXY_PLUGIN, true);
    }

    /**
     *  @return "connect" or "socks", default depends on tunnel type
     *  @since 0.9.57
     */
    public String getOutproxyType(int tunnel) {
        String type = getTunnelType(tunnel);
        if (!type.equals("sockstunnel") && !type.equals("socksirctunnel"))
            return "connect";
        return getProperty(tunnel, I2PSOCKSTunnel.PROP_OUTPROXY_TYPE, "socks");
    }

    /** all of these are @since 0.8.3 */
    public int getLimitMinute(int tunnel) {
        return getProperty(tunnel, TunnelController.PROP_MAX_CONNS_MIN, TunnelController.DEFAULT_MAX_CONNS_MIN);
    }

    public int getLimitHour(int tunnel) {
        return getProperty(tunnel, TunnelController.PROP_MAX_CONNS_HOUR, TunnelController.DEFAULT_MAX_CONNS_HOUR);
    }

    public int getLimitDay(int tunnel) {
        return getProperty(tunnel, TunnelController.PROP_MAX_CONNS_DAY, TunnelController.DEFAULT_MAX_CONNS_DAY);
    }

    public int getTotalMinute(int tunnel) {
        return getProperty(tunnel, TunnelController.PROP_MAX_TOTAL_CONNS_MIN, TunnelController.DEFAULT_MAX_TOTAL_CONNS_MIN);
    }

    public int getTotalHour(int tunnel) {
        return getProperty(tunnel, TunnelController.PROP_MAX_TOTAL_CONNS_HOUR, 0);
    }

    public int getTotalDay(int tunnel) {
        return getProperty(tunnel, TunnelController.PROP_MAX_TOTAL_CONNS_DAY, 0);
    }

    public int getMaxStreams(int tunnel) {
        return getProperty(tunnel, TunnelController.PROP_MAX_STREAMS, TunnelController.DEFAULT_MAX_STREAMS);
    }

    /**
     * POST limits
     * @since 0.9.9
     */
    public int getPostMax(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPServer.OPT_POST_MAX, I2PTunnelHTTPServer.DEFAULT_POST_MAX);
    }

    public int getPostTotalMax(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPServer.OPT_POST_TOTAL_MAX, I2PTunnelHTTPServer.DEFAULT_POST_TOTAL_MAX);
    }

    public int getPostCheckTime(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPServer.OPT_POST_WINDOW, I2PTunnelHTTPServer.DEFAULT_POST_WINDOW) / 60;
    }

    public int getPostBanTime(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPServer.OPT_POST_BAN_TIME, I2PTunnelHTTPServer.DEFAULT_POST_BAN_TIME) / 60;
    }

    public int getPostTotalBanTime(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPServer.OPT_POST_TOTAL_BAN_TIME, I2PTunnelHTTPServer.DEFAULT_POST_TOTAL_BAN_TIME) / 60;
    }

    public boolean getRejectInproxy(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelHTTPServer.OPT_REJECT_INPROXY);
    }

    /** @since 0.9.25 */
    public boolean getRejectReferer(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelHTTPServer.OPT_REJECT_REFERER);
    }

    /** @since 0.9.25 */
    public boolean getRejectUserAgents(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelHTTPServer.OPT_REJECT_USER_AGENTS);
    }

    /** @since 0.9.25 */
    public String getUserAgents(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPServer.OPT_USER_AGENTS, "");
    }

    public boolean getUniqueLocal(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelServer.PROP_UNIQUE_LOCAL);
    }

    public String getCustomOptionsString(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            Properties opts = tun.getClientOptionProps();
            if (opts == null) return "";
            boolean isMD5Proxy = TunnelController.TYPE_HTTP_CLIENT.equals(tun.getType()) ||
                                 TunnelController.TYPE_CONNECT.equals(tun.getType());
            Map<String, String> sorted = new TreeMap<String, String>();
            for (Map.Entry<Object, Object> e : opts.entrySet()) {
                String key = (String)e.getKey();
                if (TunnelConfig._noShowSet.contains(key))
                    continue;
                // leave in for HTTP and Connect so it can get migrated to MD5
                // hide for SOCKS until migrated to MD5
                if ((!isMD5Proxy) &&
                    TunnelConfig._nonProxyNoShowSet.contains(key))
                    continue;
                if (key.startsWith("i2cp.leaseSetClient."))
                    continue;
                sorted.put(key, (String)e.getValue());
            }
            if (sorted.isEmpty())
                return "";
            StringBuilder buf = new StringBuilder(64);
            boolean space = false;
            for (Map.Entry<String, String> e : sorted.entrySet()) {
                if (space)
                    buf.append(' ');
                else
                    space = true;
                buf.append(e.getKey()).append('=');
                String v = e.getValue();
                if (v.contains(" ") || v.contains("\t")) {
                    buf.append('"').append(v).append('"');
                } else {
                    buf.append(v);
                }
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
                try {
                    return Integer.parseInt(s);
                } catch (NumberFormatException nfe) {}
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
                if (rv != null)
                    return DataHelper.escapeHTML(rv);
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
            if (opts != null)
                return Boolean.parseBoolean(opts.getProperty(prop));
        }
        return def;
    }

    protected static String _t(String key, I2PAppContext context) {
        return Messages._t(key, context);
    }
}
