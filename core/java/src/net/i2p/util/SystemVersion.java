package net.i2p.util;

/*
 * public domain
 */

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.util.TimeZone;
import java.util.TreeSet;
import net.i2p.I2PAppContext;
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.stat.StatManager;

/**
 * Methods to find out what system we are running on
 *
 * @since 0.9.3 consolidated from various places
 */
public abstract class SystemVersion {

    /*
     *  @since 0.9.28
     */
    public static final String DAEMON_USER = "i2psvc";
    /*
     *  @since 0.9.29
     */
    public static final String GENTOO_USER = "i2p";

    private static final boolean _isWin = System.getProperty("os.name").startsWith("Win");
    private static final boolean _isMac = System.getProperty("os.name").startsWith("Mac");
    private static final boolean _isArm = System.getProperty("os.arch").startsWith("arm") ||
                                          System.getProperty("os.arch").startsWith("aarch");
    private static final boolean _isX86 = System.getProperty("os.arch").contains("86") ||
                                          System.getProperty("os.arch").equals("amd64");
    private static final boolean _isGentoo = System.getProperty("os.version").contains("gentoo") ||
                                             System.getProperty("os.version").contains("hardened");  // Funtoo
    // Could also check for java.vm.info = "interpreted mode"
    private static final boolean _isZero = System.getProperty("java.vm.name").contains("Zero");
    private static final boolean _isAndroid;
    private static final boolean _isApache;
    private static final boolean _isGNU;
    private static final boolean _isOpenJDK;
    private static final boolean _is64;
    private static final boolean _hasWrapper = System.getProperty("wrapper.version") != null;
    private static final boolean _isLinuxService;
    // found in Tanuki WrapperManager source so we don't need the WrapperManager class here
    private static final boolean _isWindowsService = _isWin && _hasWrapper && Boolean.parseBoolean(System.getProperty("wrapper.service"));
    private static final boolean _isService;
    private static final boolean _isSlow;

    private static final boolean _oneDotSix;
    private static final boolean _oneDotSeven;
    private static final boolean _oneDotEight;
    private static final boolean _oneDotNine;
    private static final boolean _oneDotTen;
    private static final boolean _oneDotEleven;
    private static final boolean _twelve;
    private static final boolean _thirteen;
    private static final boolean _fourteen;
    private static final boolean _fifteen;
    private static final boolean _sixteen;
    private static final boolean _seventeen;
    private static final boolean _eighteen;
    private static final boolean _nineteen;
    private static final boolean _twenty;
    private static final boolean _twentyOne;
    private static final boolean _twentyTwo;
    private static final boolean _twentyThree;
    private static final boolean _twentyFour;
    private static final int _androidSDK;

    /** @since 0.9.55+ */
    public static final String PROP_OVERRIDE_IS_SLOW = "router.overrideIsSlow";
    public static final boolean DEFAULT_OVERRIDE_IS_SLOW = false;
    private static final I2PAppContext _ctx = I2PAppContext.getGlobalContext();

    static {
        boolean is64 = "64".equals(System.getProperty("sun.arch.data.model")) ||
                       System.getProperty("os.arch").contains("64");
        if (_isWin && !is64) {
            // http://stackoverflow.com/questions/4748673/how-can-i-check-the-bitness-of-my-os-using-java-j2se-not-os-arch
            // http://blogs.msdn.com/b/david.wang/archive/2006/03/26/howto-detect-process-bitness.aspx
            String arch = System.getenv("PROCESSOR_ARCHITECTURE");
            String wow64Arch = System.getenv("PROCESSOR_ARCHITEW6432");
            is64 = (arch != null && arch.endsWith("64")) ||
                   (wow64Arch != null && wow64Arch.endsWith("64"));
        }
        _is64 = is64;

        String vendor = System.getProperty("java.vendor");
        _isAndroid = vendor.contains("Android");
        _isApache = vendor.startsWith("Apache");
        _isGNU = vendor.startsWith("GNU Classpath") || // JamVM
                 vendor.startsWith("Free Software Foundation"); // gij
        String runtime = System.getProperty("java.runtime.name");
        _isOpenJDK = runtime != null && runtime.contains("OpenJDK");
        _isLinuxService = !_isWin && !_isMac && !_isAndroid &&
                          (DAEMON_USER.equals(System.getProperty("user.name")) ||
                           (_isGentoo && GENTOO_USER.equals(System.getProperty("user.name"))));
        _isService = _isLinuxService || _isWindowsService;
        // we assume the Apple M1 is not slow, however isSlow() below will still return true until we have a jbigi
        _isSlow = _isAndroid || _isApache || (isARM() && !_isMac) || _isGNU || _isZero || getMaxMemory() < 256*1024*1024L;

        int sdk = 0;
        if (_isAndroid) {
            try {
                Class<?> ver = Class.forName("android.os.Build$VERSION", true, ClassLoader.getSystemClassLoader());
                Field field = ver.getField("SDK_INT");
                sdk = field.getInt(null);
            } catch (Exception e) {}
        }
        _androidSDK = sdk;

        if (_isAndroid) {
            _oneDotSix = _androidSDK >= 9;
            // java.nio.File not until 26
            _oneDotSeven = _androidSDK >= 19;
            // https://developer.android.com/studio/write/java8-support.html
            // some stuff in 24
            _oneDotEight = false;
            _oneDotNine = false;
            _oneDotTen = false;
            _oneDotEleven = false;
            _twelve = false;
            _thirteen = false;
            _fourteen = false;
            _fifteen = false;
            _sixteen = false;
            _seventeen = false;
            _eighteen = false;
            _nineteen = false;
            _twenty = false;
            _twentyOne = false;
            _twentyTwo = false;
            _twentyThree = false;
            _twentyFour = false;
        } else {
            String version = System.getProperty("java.version");
            // handle versions like "8-ea" or "9-internal"
            if (!version.startsWith("1.")) {version = "1." + version;}
            _oneDotSix = VersionComparator.comp(version, "1.6") >= 0;
            _oneDotSeven = _oneDotSix && VersionComparator.comp(version, "1.7") >= 0;
            _oneDotEight = _oneDotSeven && VersionComparator.comp(version, "1.8") >= 0;
            _oneDotNine = _oneDotEight && VersionComparator.comp(version, "1.9") >= 0;
            // Starting 2018, versions are YY.M, this works for that also
            _oneDotTen = _oneDotNine && VersionComparator.comp(version, "1.10") >= 0;
            _oneDotEleven = _oneDotTen && VersionComparator.comp(version, "1.11") >= 0;
            _twelve = _oneDotEleven && VersionComparator.comp(version, "12.0") >= 0;
            _thirteen = _twelve && VersionComparator.comp(version, "13.0") >= 0;
            _fourteen = _thirteen && VersionComparator.comp(version, "14.0") >= 0;
            _fifteen = _fourteen && VersionComparator.comp(version, "15.0") >= 0;
            _sixteen = _fifteen && VersionComparator.comp(version, "16.0") >= 0;
            _seventeen = _sixteen && VersionComparator.comp(version, "17.0") >= 0;
            _eighteen = _seventeen && VersionComparator.comp(version, "18.0") >= 0;
            _nineteen = _eighteen && VersionComparator.comp(version, "19.0") >= 0;
            _twenty = _nineteen && VersionComparator.comp(version, "20.0") >= 0;
            _twentyOne = _twenty && VersionComparator.comp(version, "21.0") >= 0;
            _twentyTwo = _twentyOne && VersionComparator.comp(version, "22.0") >= 0;
            _twentyThree = _twentyTwo && VersionComparator.comp(version, "23.0") >= 0;
            _twentyFour = _twentyThree && VersionComparator.comp(version, "24.0") >= 0;
        }
    }

    /**
     * returns the OS of the system running I2P as a lower-case string
     * for reference in clients.config and plugin.config files.
     *
     * matches the conventions of the Go cross compiler
     *
     * @return the OS of the system running I2P as a lower-case string
     * @since 0.9.53
     */
    public static String getOS() {
        if (isWindows()) {return "Windows";}
        if (isMac()) {return "Apple";}
        if (isGNU()) {return "Linux";} /* actually... */
        if (isLinuxService()) {return "Linux (Service)";}
        if (isAndroid()) {return "Android";}
        /** Everybody else knows if they're on a Windows machine or a
         *  Mac, so for now, assume linux here.
         */
        return "Linux";
    }

    /**
     * returns the architecture of the system running I2P as a string
     * for reference in clients.config and plugin.config files.
     *
     * matches the conventions of the Go cross compiler
     *
     * @return the architecture of the system running I2P as a string
     * @since 0.9.53
     */
    public static String getArch() {
        if (is64Bit()) {
            if (isARM()) {return "ARM64";}
            if (isX86()) {return "AMD64";}
        }
        if (isARM()) {return "ARM";}
        if (isX86()) {return "386";}
        return "unknown";
    }


    public static boolean isWindows() {return _isWin;}

    public static boolean isMac() {return _isMac;}

    public static boolean isAndroid() {return _isAndroid;}

    /**
     *  Apache Harmony JVM, or Android
     */
    public static boolean isApache() {return _isApache || _isAndroid;}

    /**
     *  gij or JamVM with GNU Classpath
     */
    public static boolean isGNU() {return _isGNU;}

    /**
     *  @since 0.9.23
     */
    public static boolean isGentoo() {return _isGentoo;}

    /**
     *  @since 0.9.26
     */
    public static boolean isOpenJDK() {return _isOpenJDK;}

    /**
     *  @since 0.9.8
     * speed: do not cripple down for powerful ARM
     */
    public static boolean isARM() {
        return _isArm && (getCores() < 4 || getCores() == 4 && System.getProperty("os.arch").startsWith("arm"));
    }

    /**
     *  @since 0.9.14
     */
    public static boolean isX86() {return _isX86;}

    /**
     *  Is this a very slow interpreted mode VM?
     *  @since 0.9.38
     */
    public static boolean isZeroVM() {return _isZero;}

    /**
     *  Our best guess on whether this is a slow architecture / OS / JVM,
     *  using some simple heuristics.
     *
     *  @since 0.9.30
     */
    public static boolean isSlow() {
        // we don't put the NBI call in the static field,
        // to prevent a circular initialization with NBI.
        return _isSlow || !NativeBigInteger.isNative() || (getCores() < 4 && !is64Bit()) ||
               (getCores() == 1 && getMaxMemory() < 384*1024*1024L) &&
               !_ctx.getBooleanProperty(PROP_OVERRIDE_IS_SLOW);
    }

    /**
     *  Better than (new VersionComparator()).compare(System.getProperty("java.version"), "1.6") &gt;= 0
     *  as it handles Android also, where java.version = "0".
     *
     *  @return true if Java 1.6 or higher, or Android API 9 or higher
     */
    public static boolean isJava6() {return _oneDotSix;}

    /**
     *  Better than (new VersionComparator()).compare(System.getProperty("java.version"), "1.7") &gt;= 0
     *  as it handles Android also, where java.version = "0".
     *
     *  @return true if Java 1.7 or higher, or Android API 19 or higher
     *  @since 0.9.14
     */
    public static boolean isJava7() {return _oneDotSeven;}

    /**
     *
     *  @return true if Java 1.8 or higher, false for Android.
     *  @since 0.9.15
     */
    public static boolean isJava8() {return _oneDotEight;}

    /**
     *
     *  @return true if Java 9 or higher, false for Android.
     *  @since 0.9.23
     */
    public static boolean isJava9() {return _oneDotNine;}

    /**
     *
     *  @return true if Java 10 or higher, false for Android.
     *  @since 0.9.33
     */
    public static boolean isJava10() {return _oneDotTen;}

    /**
     *
     *  @return true if Java 11 or higher, false for Android.
     *  @since 0.9.35
     */
    public static boolean isJava11() {return _oneDotEleven;}

    /**
     *
     *  @return true if Java 12 or higher, false for Android.
     *  @since 0.9.59+
     */
    public static boolean isJava12() {return _twelve;}

    /**
     *
     *  @return true if Java 13 or higher, false for Android.
     *  @since 0.9.59+
     */
    public static boolean isJava13() {return _thirteen;}

    /**
     *
     *  @return true if Java 14 or higher, false for Android.
     *  @since 0.9.59+
     */
    public static boolean isJava14() {return _fourteen;}

    /**
     *
     *  @return true if Java 15 or higher, false for Android.
     *  @since 0.9.59+
     */
    public static boolean isJava15() {return _fifteen;}

    /**
     *
     *  @return true if Java 16 or higher, false for Android.
     *  @since 0.9.59+
     */
    public static boolean isJava16() {return _sixteen;}

    /**
     *
     *  @return true if Java 17 or higher, false for Android.
     *  @since 0.9.59+
     */
    public static boolean isJava17() {return _seventeen;}

    /**
     *
     *  @return true if Java 18 or higher, false for Android.
     *  @since 0.9.59+
     */
    public static boolean isJava18() {return _eighteen;}

    /**
     *
     *  @return true if Java 19 or higher, false for Android.
     *  @since 0.9.59+
     */
    public static boolean isJava19() {return _nineteen;}

    /**
     *
     *  @return true if Java 20 or higher, false for Android.
     *  @since 0.9.59+
     */
    public static boolean isJava20() {return _twenty;}

    /**
     *
     *  @return true if Java 21 or higher, false for Android.
     *  @since 0.9.59+
     */
    public static boolean isJava21() {return _twentyOne;}

    /**
     *
     *  @return true if Java 21 or higher, false for Android.
     *  @since 0.9.59+
     */
    public static boolean isJava22() {return _twentyTwo;}

    /**
     *  Handles Android also
     *
     *  @param minVersion e.g. 11
     *  @return true if greater than or equal to minVersion
     *  @since 0.9.41
     */
    public static boolean isJava(int minVersion) {return isJava("1." + minVersion);}

    /**
     *  Handles Android, and minVersions in both forms (e.g. 11 or 1.11)
     *
     *  @param minVersion either 1.x or x form works
     *  @return true if greater than or equal to minVersion
     *  @since 0.9.41
     */
    public static boolean isJava(String minVersion) {
        String version = System.getProperty("java.version");
        if (!version.startsWith("1.")) {version = "1." + version;}
        if (!minVersion.startsWith("1.")) {minVersion = "1." + minVersion;}
        if (_isAndroid) {
            if (minVersion.startsWith("1.6")) {return _oneDotSix;}
            if (minVersion.startsWith("1.7")) {return _oneDotSeven;}
            if (minVersion.startsWith("1.8")) {return _oneDotEight;}
            if (minVersion.startsWith("1.9")) {return _oneDotNine;}
            if (minVersion.startsWith("1.10")) {return _oneDotTen;}
            if (minVersion.startsWith("1.11")) {return _oneDotEleven;}
            if (minVersion.startsWith("1.17")) {return _androidSDK >= 34;}
            return false;
        }
        return VersionComparator.comp(version, minVersion) >= 0;
    }

    /**
     * This isn't always correct.
     * http://stackoverflow.com/questions/807263/how-do-i-detect-which-kind-of-jre-is-installed-32bit-vs-64bit
     * http://mark.koli.ch/2009/10/javas-osarch-system-property-is-the-bitness-of-the-jre-not-the-operating-system.html
     * http://mark.koli.ch/2009/10/reliably-checking-os-bitness-32-or-64-bit-on-windows-with-a-tiny-c-app.html
     * sun.arch.data.model not on all JVMs
     * sun.arch.data.model == 64 =&gt; 64 bit processor
     * sun.arch.data.model == 32 =&gt; A 32 bit JVM but could be either 32 or 64 bit processor or libs
     * os.arch contains "64" could be 32 or 64 bit libs
     */
    public static boolean is64Bit() {return _is64;}

    /*
     *  @since 0.9.28
     */
    public static boolean isLinuxService() {return _isLinuxService;}

    /*
     *  @since 0.9.46
     */
    public static boolean isWindowsService() {return _isWindowsService;}

    /*
     *  @since 0.9.46
     */
    public static boolean isService() {return _isService;}

    /**
     *  Identical to android.os.Build.VERSION.SDK_INT.
     *  For use outside of Android code.
     *  @return The SDK (API) version, e.g. 8 for Froyo, 0 if unknown
     */
    public static int getAndroidVersion() {return _androidSDK;}

    /**
     *  Is the wrapper present?
     *  Same as I2PAppContext.hasWrapper()
     */
    public static boolean hasWrapper() {return _hasWrapper;}

    /**
     *  Runtime.getRuntime().maxMemory() but check for bogus values
     *  @since 0.9.8
     */
    public static long getMaxMemory() {
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory >= Long.MAX_VALUE / 2) {maxMemory = 8192*1024*1024l;}
        return maxMemory;
    }

    /**
     *  Runtime.getRuntime().availableProcssors()
     *  @return never smaller than 1
     *  @since 0.9.34
     */
    public static int getCores() {return Runtime.getRuntime().availableProcessors();}

    /** calculate how many (virtual) cores should be actually used by a thread pool */
    public static int usableCores() {
        /** On these OSes we want our threads to occupy the CPU as long as possible
         * instead of being put to sleep by the power saving features
         */
        if (_isWin || _isMac) {return 1;}

        int dividend = 1;
        int divisor = 1;

        /** reflect CPU unavailable through hyperthreading
        * 4 virtual cores = 3 threads
        * 8 virtual cores = 6 threads
        * 12 virtual cores = 9 threads
        */
        if (_isX86) {
            dividend *= 3;
            divisor *= 4;
        }

        return (getCores() * dividend + divisor / 2) / divisor;
    }

    /**
     *  The system's time zone, which is probably different from the
     *  JVM time zone, because Router changes the JVM default to GMT.
     *  It saves the old default in the context properties where we can get it.
     *  Use this to format a time in local time zone with DateFormat.setTimeZone().
     *
     *  @return non-null
     *  @since 0.9.24
     */
    public static TimeZone getSystemTimeZone() {
        return getSystemTimeZone(_ctx);
    }

    /**
     *  The system's time zone, which is probably different from the
     *  JVM time zone, because Router changes the JVM default to GMT.
     *  It saves the old default in the context properties where we can get it.
     *  Use this to format a time in local time zone with DateFormat.setTimeZone().
     *
     *  @return non-null
     *  @since 0.9.24
     */
    public static TimeZone getSystemTimeZone(I2PAppContext ctx) {
        String systemTimeZone = ctx.getProperty("i2p.systemTimeZone");
        if (systemTimeZone != null) {return TimeZone.getTimeZone(systemTimeZone);}
        return TimeZone.getDefault();
    }

    /**
     *  @since 0.9.24
     */
    public static void main(String[] args) {
        String jvm = isOpenJDK() ? "(OpenJDK)" : isZeroVM() ? "(Zero JVM)" : isApache() ? "(Apache)" : "";
        if (isJava8()) {System.out.println("Java 8 " + jvm);}
        else if (isJava9()) {System.out.println("Java 9 " + jvm);}
        else if (isJava10()) {System.out.println("Java 10 " + jvm);}
        else if (isJava11()) {System.out.println("Java 11 " + jvm);}
        for (int i = 12; i <= 25; i++) {System.out.println("Java " + i + ' ' + jvm);}
        System.out.println("Platform: " + getOS() + (is64Bit() ? " (64Bit)" : " (32Bit)"));
        System.out.println("Cores / Max memory: " + getCores() + " / " + (getMaxMemory() / 1024 / 1024) + "KB");
        System.out.println("");
        System.out.println("System Properties:");
        TreeSet<String> keys = new TreeSet<String>(System.getProperties().stringPropertyNames());
        for (String k : keys) {
            String v = System.getProperty(k);
            if (k.equals("line.separator")) {
                if ("\n".equals(v)) {v = "\\n";}
                else if ("\r\n".equals(v)) {v = "\\r\\n";}
            } else if (k.contains("user") || k.contains("vendor.url") || k.contains("printer")) {continue;}
            System.out.println("  " + k + " = " + v);
        }
    }

    /**
     * Retrieve CPU Load of the JVM.
     * @since 0.9.57+
     */
    public static int getCPULoad() {
        OperatingSystemMXBean osmxb = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        int cpuLoad = (int)(osmxb.getProcessCpuLoad() * 100);
        return Math.min(cpuLoad, 100);
    }

    /**
     * Retrieve CPU Load Average of the JVM.
     * @since 0.9.57+
     */
    public static int getCPULoadAvg() {
        if (_ctx == null || _ctx.statManager() == null || _ctx.statManager().getRate("router.cpuLoad") == null) {
            return 0;
        } else {
            int max = 100;
            Rate stat = _ctx.statManager().getRate("router.cpuLoad").getRate(RateConstants.ONE_MINUTE);
            long loadAvg;
            long count = (1 + stat.getCurrentEventCount() + stat.getLastEventCount());
            if (count > 1) {
                loadAvg = (long) (getCPULoad() + (stat.getCurrentTotalValue() + stat.getLastTotalValue()) / count);
            } else {loadAvg = getCPULoad();}
            int load = Math.toIntExact(loadAvg);
            if (load > max) {load = max;}
            return load;
        }
    }

    /**
     * Retrieve System Load as percentage (100% equals full system load)
     * @since 0.9.57+
     */
    public static int getSystemLoad() {
        if (_ctx == null || _ctx.statManager() == null) {return 0;}
        else {
            int cores = SystemVersion.getCores();
            OperatingSystemMXBean osmxb = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double systemLoadAvg = (osmxb.getSystemLoadAverage() / cores) * 100;
            int sysLoad = (int) systemLoadAvg;
            if (sysLoad < 0) {return 0;}
            else {return sysLoad;}
        }
    }

    /**
     * Returns the current number of active (live) threads in the JVM.
     * This includes both daemon and non-daemon threads.
     * @return the estimated number of active threads, or 0 if context or stat manager is unavailable
     * @since 0.9.68+
     */
    public static int getActiveThreads() {
        if (_ctx == null || _ctx.statManager() == null) {return 0;}
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        return threadMXBean.getThreadCount();
    }

    /**
     * Retrieve Tunnel build success as a percentage.
     * @since 0.9.58+
     */
    public static int getTunnelBuildSuccess() {
        I2PAppContext context = I2PAppContext.getGlobalContext();
        StatManager sm = context.statManager();
        if (context == null || sm == null) {return 0;}
        int RATE = 10*60*1000;
        Rate explSuccess = sm.getRate("tunnel.buildExploratorySuccess").getRate(RATE);
        Rate explReject = sm.getRate("tunnel.buildExploratoryReject").getRate(RATE);
        Rate explExpire = sm.getRate("tunnel.buildExploratoryExpire").getRate(RATE);
        Rate clientSuccess = sm.getRate("tunnel.buildClientSuccess").getRate(RATE);
        Rate clientReject = sm.getRate("tunnel.buildClientReject").getRate(RATE);
        Rate clientExpire = sm.getRate("tunnel.buildClientExpire").getRate(RATE);
        int success = (int)explSuccess.getLastEventCount() + (int)clientSuccess.getLastEventCount();
        int reject = (int)explReject.getLastEventCount() + (int)clientReject.getLastEventCount();
        int expire = (int)explExpire.getLastEventCount() + (int)clientExpire.getLastEventCount();
        int percentage;
        if (success < 1) {success = 1;}
        percentage = (100 * success) / (success + reject + expire);
        if (percentage == 100 || percentage == 0) {return 0;}
        else {return percentage;}
    }

}
