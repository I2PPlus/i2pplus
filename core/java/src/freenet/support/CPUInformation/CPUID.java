/*
 * Created on Jul 14, 2004
 * Updated on Jan 8, 2011
 *
 * free (adj.): unencumbered; not under the control of others
 * Written by Iakin in 2004 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might. Use at your own risk.
 */
package freenet.support.CPUInformation;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Locale;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.FileUtil;
import net.i2p.util.SystemVersion;

/**
 * Retrieves CPU details via the x86 CPUID instruction through a native JNI library (jcpuid).
 *
 * <p>This class provides low-level access to CPU identification and feature flags.
 * It loads a platform-specific native library ({@code libjcpuid.so} / {@code jcpuid.dll})
 * that executes the CPUID assembly instruction and returns register values.</p>
 *
 * <h3>CPUID Register Layout (Leaf 1, EAX)</h3>
 * <pre>
 * Bits 31:28  Reserved
 * Bits 27:20  Extended Family ID
 * Bits 19:16  Extended Model ID
 * Bits 15:12  Processor Type (Intel only; AMD reserved)
 * Bits 11:8   Base Family ID
 * Bits 7:4    Base Model ID
 * Bits 3:0    Stepping ID
 * </pre>
 *
 * <h3>Family/Model Computation</h3>
 * <p>The actual (displayed) family and model are computed as follows:</p>
 * <ul>
 *   <li><b>Actual Family</b> = Base Family + Extended Family
 *       (only when Base Family == 0xF for Intel; always for AMD)</li>
 *   <li><b>Actual Model</b> = Base Model + (Extended Model &lt;&lt; 4)
 *       (only when Base Family == 0x6 or 0xF for Intel; always for AMD)</li>
 * </ul>
 *
 * <h3>AMD Family/Microarchitecture Mapping</h3>
 * <table border="1" cellpadding="4">
 *   <tr><th>Family (hex)</th><th>Microarchitecture</th><th>Products</th></tr>
 *   <tr><td>15 (0xF)</td><td>K8 / Hammer</td><td>Athlon 64, Opteron</td></tr>
 *   <tr><td>16 (0x10)</td><td>K10</td><td>Phenom, Athlon II</td></tr>
 *   <tr><td>20 (0x14)</td><td>Bobcat</td><td>Ontario, Zacate</td></tr>
 *   <tr><td>21 (0x15)</td><td>Bulldozer family</td><td>FX, Opteron</td></tr>
 *   <tr><td>22 (0x16)</td><td>Jaguar</td><td>Kabini, Temash</td></tr>
 *   <tr><td>23 (0x17)</td><td>Zen / Zen+ / Zen 2</td><td>Ryzen 1000-3000, EPYC 7001-7002</td></tr>
 *   <tr><td>25 (0x19)</td><td>Zen 3 / Zen 4</td><td>Ryzen 5000-8000, EPYC 7003-9004</td></tr>
 *   <tr><td>26 (0x1A)</td><td>Zen 5</td><td>Ryzen 9000, EPYC 9005, Ryzen AI 300</td></tr>
 * </table>
 *
 * <h3>AMD Family 19h (Zen 3/4) Model Ranges</h3>
 * <pre>
 * 0x00-0x0F  Milan/Chagall      (Zen 3)   - EPYC 7003, Threadripper 5000
 * 0x10-0x1F  Stones/Storm Peak  (Zen 4)   - EPYC 9004, Threadripper 7000
 * 0x20-0x2F  Vermeer            (Zen 3)   - Ryzen 5000 desktop
 * 0x30-0x3F  Badami             (Zen 3)   - EPYC (internal)
 * 0x40-0x4F  Rembrandt          (Zen 3+)  - Ryzen 6000 mobile
 * 0x50-0x5F  Cezanne            (Zen 3)   - Ryzen 5000 APU
 * 0x60-0x6F  Raphael            (Zen 4)   - Ryzen 7000 desktop (AM5)
 * 0x70-0x77  Phoenix/HawkPoint1 (Zen 4)   - Ryzen 7040/8040 APU
 * 0x78-0x7F  Phoenix2/HawkPoint2(Zen 4)   - Ryzen 8040 APU
 * 0xA0-0xAF  Stones-Dense       (Zen 4)   - EPYC 4004/Siena
 * </pre>
 *
 * <h3>AMD Family 1Ah (Zen 5) Model Ranges</h3>
 * <p>Sources: LLVM Host.cpp, Linux kernel amd.c, InstLatx64 CPUID dumps.</p>
 * <pre>
 * 0x00-0x0F  Breithorn/Turin        (Zen 5)   - EPYC 9005 server
 * 0x10-0x1F  Breithorn-Dense        (Zen 5)   - EPYC 9005 dense
 * 0x20-0x2F  Strix Point            (Zen 5)   - Ryzen AI 300 mobile
 * 0x30-0x37  Strix Point (2nd)      (Zen 5)   - Ryzen AI 300 mobile
 * 0x38-0x3F  Strix Point (3rd)      (Zen 5)   - Ryzen AI 300 mobile
 * 0x40-0x4F  Granite Ridge          (Zen 5)   - Ryzen 9000 desktop (AM5)
 * 0x50-0x5F  Weisshorn              (Zen 6)   - NOT Zen 5!
 * 0x60-0x6F  Krackan Point          (Zen 5)   - Ryzen AI 300 mobile
 * 0x70-0x77  Sarlak/Strix Halo      (Zen 5)   - Ryzen AI Max 300
 * 0xD0-0xD7  Annapurna              (Zen 5)   - Future EPYC
 * </pre>
 *
 * <h3>CPUID Brand String (Most Reliable Identification)</h3>
 * <p>CPUID leaves 0x80000002-0x80000004 return a 48-byte ASCII brand string
 * programmed by AMD/Intel into the CPU. This is the most reliable way to
 * identify a specific CPU model, as model number ranges can overlap across
 * product lines (e.g., model 0x44 is Granite Ridge desktop, not Strix Halo).</p>
 *
 * @see <a href="http://en.wikipedia.org/wiki/Cpuid">Wikipedia: CPUID</a>
 * @see <a href="https://www.amd.com/en/search/documentation/hub.html">AMD PPR / CPUID references</a>
 * @author Iakin
 */

public class CPUID {

    /** did we load the native lib correctly? */
    private static boolean _nativeOk = false;
    private static int _jcpuidVersion;

    /**
     * do we want to dump some basic success/failure info to stderr during
     * initialization?  this would otherwise use the Log component, but this makes
     * it easier for other systems to reuse this class
     *
     * Well, we really want to use Log so if you are one of those "other systems"
     * then comment out the I2PAppContext usage below.
     *
     * Set to false if not in router context, so scripts using TrustedUpdate
     * don't spew log messages. main() below overrides to true.
     */
    private static boolean _doLog = System.getProperty("jcpuid.dontLog") == null &&
                                    I2PAppContext.getGlobalContext().isRouterContext();

    private static final boolean isX86 = SystemVersion.isX86();
    private static final boolean isWindows = SystemVersion.isWindows();
    private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase(Locale.US);
    private static final boolean isLinux = OS_NAME.contains("linux");
    private static final boolean isKFreebsd = OS_NAME.contains("kfreebsd");
    private static final boolean isFreebsd = (!isKFreebsd) && OS_NAME.contains("freebsd");
    private static final boolean isNetbsd = OS_NAME.contains("netbsd");
    private static final boolean isOpenbsd = OS_NAME.contains("openbsd");
    private static final boolean isSunos = OS_NAME.contains("sunos");
    private static final boolean isMac = SystemVersion.isMac();

    /**
     *  This isn't always correct.
     *  http://stackoverflow.com/questions/807263/how-do-i-detect-which-kind-of-jre-is-installed-32bit-vs-64bit
     *  http://mark.koli.ch/2009/10/javas-osarch-system-property-is-the-bitness-of-the-jre-not-the-operating-system.html
     *  http://mark.koli.ch/2009/10/reliably-checking-os-bitness-32-or-64-bit-on-windows-with-a-tiny-c-app.html
     *  sun.arch.data.model not on all JVMs
     *  sun.arch.data.model == 64 => 64 bit processor
     *  sun.arch.data.model == 32 => A 32 bit JVM but could be either 32 or 64 bit processor or libs
     *  os.arch contains "64" could be 32 or 64 bit libs
     */
    private static final boolean is64 = SystemVersion.is64Bit();

    /**
     * CPUID leaf 1 result cache. CPUID leaf 1 (function 0x01) returns processor
     * version information (family, model, stepping) and feature bits (SSE, AVX, etc.).
     * This is called by nearly every method in this class, so caching eliminates
     * ~8 redundant native JNI round-trips per CPU detection cycle.
     */
    private static volatile CPUIDResult _leaf1Cache;

    static {loadNative();}

    /**
     * Holds the four 32-bit register values (EAX, EBX, ECX, EDX) returned
     * by a single CPUID instruction call.
     *
     * <p>For CPUID leaf 1, the EAX register contains:</p>
     * <ul>
     *   <li>Bits 3:0   - Stepping ID</li>
     *   <li>Bits 7:4   - Base Model</li>
     *   <li>Bits 11:8  - Base Family</li>
     *   <li>Bits 15:12 - Processor Type (Intel only)</li>
     *   <li>Bits 19:16 - Extended Model</li>
     *   <li>Bits 27:20 - Extended Family</li>
     * </ul>
     */
    protected static class CPUIDResult {
        final int EAX;
        final int EBX;
        final int ECX;
        final int EDX;
        CPUIDResult(int EAX,int EBX,int ECX, int EDX) {
            this.EAX = EAX;
            this.EBX = EBX;
            this.ECX = ECX;
            this.EDX = EDX;
        }
    }

    /**
     * Executes a CPUID instruction with the given function number and returns
     * the register values.
     *
     * <p>Common CPUID functions:</p>
     * <ul>
     *   <li>0x00 - Highest basic function, vendor ID string</li>
     *   <li>0x01 - Family/model/stepping, feature flags (SSE, AVX, etc.)</li>
     *   <li>0x07 - Extended feature flags (AVX2, AVX-512, SHA, etc.)</li>
     *   <li>0x80000000 - Highest extended function</li>
     *   <li>0x80000001 - Extended feature flags (LZCNT, SSE4A, etc.)</li>
     *   <li>0x80000002-4 - CPU brand string (48 bytes ASCII)</li>
     *   <li>0x80000008 - Virtual/physical address sizes</li>
     * </ul>
     *
     * @param iFunction The CPUID function number (EAX value)
     * @return Register values after execution
     * @throws UnsatisfiedLinkError if native library not loaded
     */
    private static native CPUIDResult doCPUID(int iFunction);

    /**
     *  Get the jbigi version, only available since jbigi version 3
     *  Caller must catch Throwable
     *  @since 0.9.26
     */
    private native static int nativeJcpuidVersion();

    /**
     *  Get the jcpuid version
     *  @return 0 if no jcpuid available, 2 if version not supported
     *  @since 0.9.26
     */
    private static int fetchJcpuidVersion() {
        if (!_nativeOk) {return 0;}
        try {return nativeJcpuidVersion();}
        catch (Throwable t) {return 2;}
    }

    /**
     *  Return the jcpuid version
     *  @return 0 if no jcpuid available, 2 if version not supported
     *  @since 0.9.26
     */
    public static int getJcpuidVersion() {return _jcpuidVersion;}

    /**
     * Returns cached CPUID leaf 1 result, performing the native call only once.
     */
    private static CPUIDResult getLeaf1() {
        CPUIDResult c = _leaf1Cache;
        if (c == null) {
            c = doCPUID(1);
            _leaf1Cache = c;
        }
        return c;
    }

    /**
     * Returns the 12-byte CPU vendor ID string from CPUID leaf 0.
     * The string is constructed from EBX:EDX:ECX (in that order).
     *
     * <p>Known vendor strings:</p>
     * <ul>
     *   <li>{@code "AuthenticAMD"} - AMD</li>
     *   <li>{@code "GenuineIntel"} - Intel</li>
     *   <li>{@code "CentaurHauls"} - VIA/Centaur</li>
     *   <li>{@code "HygonGenuine"} - Hygon (AMD-based Chinese JV)</li>
     * </ul>
     *
     * @return 12-character vendor string, or empty string if native library not loaded
     */
    static String getCPUVendorID() {
        if (!_nativeOk) {return "";}
        CPUIDResult c = doCPUID(0);
        StringBuilder sb = new StringBuilder(12);
        sb.append((char)(c.EBX        & 0xFF));
        sb.append((char)((c.EBX >> 8)  & 0xFF));
        sb.append((char)((c.EBX >> 16) & 0xFF));
        sb.append((char)((c.EBX >> 24) & 0xFF));

        sb.append((char)(c.EDX        & 0xFF));
        sb.append((char)((c.EDX >> 8)  & 0xFF));
        sb.append((char)((c.EDX >> 16) & 0xFF));
        sb.append((char)((c.EDX >> 24) & 0xFF));

        sb.append((char)(c.ECX        & 0xFF));
        sb.append((char)((c.ECX >> 8)  & 0xFF));
        sb.append((char)((c.ECX >> 16) & 0xFF));
        sb.append((char)((c.ECX >> 24) & 0xFF));

        return sb.toString();
    }

    /**
     * Returns the Base Family ID from CPUID leaf 1, EAX bits 11:8.
     *
     * <p><b>WARNING:</b> This is the raw Base Family, NOT the computed family.
     * For AMD CPUs with Base Family == 0xF (15), you must add
     * {@link #getCPUExtendedFamily()} to get the actual family.
     * For other AMD families (17h, 19h, 1Ah), the extended family is always
     * added to the base family to form the actual family.</p>
     *
     * @return Base Family ID (0-15)
     * @see #getCPUExtendedFamily() for the extended family component
     */
    static int getCPUFamily() {
        return (getLeaf1().EAX >> 8) & 0xf;
    }

    /**
     * Returns the Base Model ID from CPUID leaf 1, EAX bits 7:4.
     *
     * <p><b>WARNING:</b> This is the raw Base Model, NOT the computed model.
     * For CPUs with Base Family == 0x6 or 0xF, you must add
     * {@link #getCPUExtendedModel()} &lt;&lt; 4 to get the actual model.
     * For AMD CPUs, the extended model is always applied when computing
     * the actual model.</p>
     *
     * @return Base Model ID (0-15)
     * @see #getCPUExtendedModel() for the extended model component
     */
    static int getCPUModel() {
        return (getLeaf1().EAX >> 4) & 0xf;
    }

    /**
     * Returns the Extended Model ID from CPUID leaf 1, EAX bits 19:16.
     *
     * <p>To compute the actual model for CPUs where Base Family is 6 or 0xF:</p>
     * <pre>
     *   actualModel = baseModel + (extendedModel &lt;&lt; 4)
     * </pre>
     *
     * <p>For AMD CPUs, this is always added to the base model.</p>
     *
     * @return Extended Model ID (0-15)
     */
    /**
     *  Only valid if family == 15, or, for Intel only, family == 6.
     *  Left shift by 4 and then add model to get full model.
     *  @return 0-15
     */
    static int getCPUExtendedModel() {
        return (getLeaf1().EAX >> 16) & 0xf;
    }

    /** @return 0-15 */
    static int getCPUType() {
        return (getLeaf1().EAX >> 12) & 0xf;
    }

    /**
     * Returns the Extended Family ID from CPUID leaf 1, EAX bits 27:20.
     *
     * <p>To compute the actual family when Base Family == 0xF:</p>
     * <pre>
     *   actualFamily = baseFamily + extendedFamily
     * </pre>
     *
     * <p>For AMD CPUs, this is always added to the base family.</p>
     *
     * @return Extended Family ID (0-255)
     */
    /**
     *  Only valid if family == 15.
     *  Add family to get full family.
     *  @return 0-255
     */
    static int getCPUExtendedFamily() {
        return (getLeaf1().EAX >> 20) & 0xff;
    }

    /** @return 0-15 */
    static int getCPUStepping() {
        return getLeaf1().EAX & 0xf;
    }

    static int getEDXCPUFlags() {
        return getLeaf1().EDX;
    }

    static int getECXCPUFlags() {
        return getLeaf1().ECX;
    }

    static int getExtendedECXCPUFlags() {
        CPUIDResult c = doCPUID(0x80000001);
        return c.ECX;
    }

    /** @since 0.8.7 */
    static int getExtendedEDXCPUFlags() {
        CPUIDResult c = doCPUID(0x80000001);
        return c.EDX;
    }

    /**
     *  @since 0.9.26
     */
    static int getExtendedEBXFeatureFlags() {
        // Supposed to set ECX to 0 before calling?
        // But we don't have support for that in jcpuid.
        // And it works just fine without that.
        CPUIDResult c = doCPUID(7);
        return c.EBX;
    }

    /**
     *  There's almost nothing in here.
     *  @since 0.9.26
     */
    static int getExtendedECXFeatureFlags() {
        // Supposed to set ECX to 0 before calling?
        // But we don't have support for that in jcpuid.
        // And it works just fine without that.
        CPUIDResult c = doCPUID(7);
        return c.ECX;
    }

    /**
     * Returns the CPU brand string from CPUID extended leaves 0x80000002-0x80000004.
     *
     * <p>This is the most reliable way to identify a specific CPU model. The brand
     * string is a 48-byte ASCII string programmed by AMD/Intel into the CPU.
     * Examples:</p>
     * <ul>
     *   <li>{@code "AMD Ryzen 9 9900X 12-Core Processor"}</li>
     *   <li>{@code "Intel(R) Core(TM) i9-14900K"}</li>
     *   <li>{@code "AMD EPYC 9754 128-Core Processor"}</li>
     * </ul>
     *
     * <p><b>IMPORTANT:</b> This is preferred over model-based identification.
     * AMD model number ranges overlap across product lines (e.g., model 0x44
     * is Granite Ridge desktop, but model ranges can be ambiguous). The brand
     * string is always correct when available.</p>
     *
     * @return CPU brand string, or null if unsupported or native library not loaded
     * @since 0.9.16
     */
    static String getCPUModelName() {
        if (!_nativeOk) {return null;}
        CPUIDResult c = doCPUID(0x80000000);
        long maxSupported = c.EAX & 0xFFFFFFFFL;
        if (maxSupported < 0x80000004L) {return null;}
        StringBuilder buf = new StringBuilder(48);
        for (int fn = 0x80000002; fn <= 0x80000004; fn++) {
            c = doCPUID(fn);
            extractReg(c.EAX, buf);
            extractReg(c.EBX, buf);
            extractReg(c.ECX, buf);
            extractReg(c.EDX, buf);
        }
        return buf.toString().trim();
    }

    /**
     * Extracts up to 4 bytes from a CPUID register into a StringBuilder.
     * Stops at the first null byte (CPUID strings are null-terminated).
     *
     * @param reg 32-bit register value (little-endian byte order)
     * @param buf destination buffer
     */
    private static void extractReg(int reg, StringBuilder buf) {
        for (int j = 0; j < 4; j++) {
            char ch = (char) (reg & 0xff);
            if (ch == 0) {return;}
            buf.append(ch);
            reg >>= 8;
        }
    }

    /**
     * Returns a {@link CPUInfo} instance for the current CPU.
     *
     * <p>Detects the CPU vendor via CPUID leaf 0, then returns the appropriate
     * implementation:</p>
     * <ul>
     *   <li>{@code "AuthenticAMD"} / {@code "HygonGenuine"} → {@link AMDInfoImpl}</li>
     *   <li>{@code "GenuineIntel"} → {@link IntelInfoImpl}</li>
     *   <li>{@code "CentaurHauls"} → {@link VIAInfoImpl}</li>
     * </ul>
     *
     * @return CPUInfo for the detected CPU type
     * @throws UnknownCPUException if native library not loaded, not x86, or unknown vendor
     */
    public static CPUInfo getInfo() throws UnknownCPUException {
        if (!_nativeOk) {
            throw new UnknownCPUException("Failed to read CPU information from the system. Please verify the existence of the " +
                                          getLibraryPrefix() + "jcpuid " + getLibrarySuffix() + " file.");
        }
        String id = getCPUVendorID();
        if (id.equals("CentaurHauls")) {return new VIAInfoImpl();}
        if (!isX86) {
            throw new UnknownCPUException("Failed to read CPU information from the system. The CPUID instruction exists on x86 CPUs only.");
        }
        // http://lkml.iu.edu/hypermail/linux/kernel/1806.1/00730.html
        if (id.equals("AuthenticAMD") || id.equals("HygonGenuine")) {return new AMDInfoImpl();}
        if (id.equals("GenuineIntel")) {return new IntelInfoImpl();}
        throw new UnknownCPUException("Unknown CPU type: '" + id + '\'');
    }

    public static void main(String[] args) {
        _doLog = true; // this is too late to log anything from above
        String path = System.getProperty("java.library.path");
        String name = getLibraryPrefix() + "jcpuid" + getLibrarySuffix();
        System.out.println("Native library search path: " + path);
        if (_nativeOk) {
            String sep = System.getProperty("path.separator");
            String[] paths = DataHelper.split(path, sep);
            for (String p : paths) {
                File f = new File(p, name);
                if (f.exists()) {
                    System.out.println("Found native library: " + f);
                    break;
                }
            }
        } else {
            System.out.println("Failed to retrieve CPUInfo. Please verify the existence of the " +
                               name + " file in the library path, or set -Djava.library.path=. in the command line");
        }
        System.out.println("JCPUID Version: " + _jcpuidVersion);
        System.out.println("** CPUInfo **");
        String mname = getCPUModelName();
        if (mname != null) {System.out.println("CPU Name: " + mname);}
        String vendor = getCPUVendorID();
        System.out.println("CPU Vendor: " + vendor);
        // http://en.wikipedia.org/wiki/Cpuid
        // http://web.archive.org/web/20110307080258/http://www.intel.com/Assets/PDF/appnote/241618.pdf
        // http://www.intel.com/content/dam/www/public/us/en/documents/manuals/64-ia-32-architectures-software-developer-vol-2a-manual.pdf
        int family = getCPUFamily();
        int model = getCPUModel();
        if (family == 15 || (family == 6 && "GenuineIntel".equals(vendor))) {model += getCPUExtendedModel() << 4;}
        if (family == 15) {family += getCPUExtendedFamily();}
        System.out.println("CPU Family: " + family);
        System.out.println("CPU Model: " + model + " (0x" + Integer.toHexString(model) + ')');
        System.out.println("CPU Stepping: " + getCPUStepping());
        System.out.println("CPU Flags (EDX): 0x" + Integer.toHexString(getEDXCPUFlags()));
        System.out.println("CPU Flags (ECX): 0x" + Integer.toHexString(getECXCPUFlags()));
        System.out.println("CPU Ext. Info. (EDX): 0x" + Integer.toHexString(getExtendedEDXCPUFlags()));
        System.out.println("CPU Ext. Info. (ECX): 0x" + Integer.toHexString(getExtendedECXCPUFlags()));
        System.out.println("CPU Ext. Feat. (EBX): 0x" + Integer.toHexString(getExtendedEBXFeatureFlags()));
        System.out.println("CPU Ext. Feat. (ECX): 0x" + Integer.toHexString(getExtendedECXFeatureFlags()));

        CPUInfo c = getInfo();
        System.out.println("\n** More CPUInfo **");
        System.out.println("CPU model name: " + c.getCPUModelString());
        System.out.println("CPU has MMX: " + c.hasMMX());
        System.out.println("CPU has SSE: " + c.hasSSE());
        System.out.println("CPU has SSE2: " + c.hasSSE2());
        System.out.println("CPU has SSE3: " + c.hasSSE3());
        System.out.println("CPU has SSE4.1: " + c.hasSSE41());
        System.out.println("CPU has SSE4.2: " + c.hasSSE42());
        System.out.println("CPU has SSE4A: " + c.hasSSE4A());
        System.out.println("CPU has AES-NI: " + c.hasAES());
        System.out.println("CPU has AVX: " + c.hasAVX());
        System.out.println("CPU has AVX2: " + c.hasAVX2());
        System.out.println("CPU has AVX512: " + c.hasAVX512());
        System.out.println("CPU has ADX: " + c.hasADX());
        System.out.println("CPU has TBM: " + c.hasTBM());
        System.out.println("CPU has BMI1: " + c.hasBMI1());
        System.out.println("CPU has BMI2: " + c.hasBMI2());
        System.out.println("CPU has FMA3: " + c.hasFMA3());
        System.out.println("CPU has MOVBE: " + c.hasMOVBE());
        System.out.println("CPU has ABM: " + c.hasABM());
        if (c instanceof IntelCPUInfo) {
            IntelCPUInfo cc = (IntelCPUInfo) c;
            System.out.println("\n** Intel Info **");
            System.out.println("Is PII-compatible: " + cc.IsPentium2Compatible());
            System.out.println("Is PIII-compatible: " + cc.IsPentium3Compatible());
            System.out.println("Is PIV-compatible: " + cc.IsPentium4Compatible());
            System.out.println("Is Atom-compatible: " + cc.IsAtomCompatible());
            System.out.println("Is Pentium M compatible: " + cc.IsPentiumMCompatible());
            System.out.println("Is Core2-compatible: " + cc.IsCore2Compatible());
            System.out.println("Is Corei-compatible: " + cc.IsCoreiCompatible());
            System.out.println("Is Sandy-compatible: " + cc.IsSandyCompatible());
            System.out.println("Is Ivy-compatible: " + cc.IsIvyCompatible());
            System.out.println("Is Haswell-compatible: " + cc.IsHaswellCompatible());
            System.out.println("Is Broadwell-compatible: " + cc.IsBroadwellCompatible());
            System.out.println("Is Skylake-compatible: " + cc.IsSkylakeCompatible());
        } else if (c instanceof AMDCPUInfo) {
            AMDCPUInfo cc = (AMDCPUInfo) c;
            System.out.println("\n** AMD Info **");
            System.out.println("Is K6-compatible: " + cc.IsK6Compatible());
            System.out.println("Is K6_2-compatible: " + cc.IsK6_2_Compatible());
            System.out.println("Is K6_3-compatible: " + cc.IsK6_3_Compatible());
            System.out.println("Is Geode-compatible: " + cc.IsGeodeCompatible());
            System.out.println("Is Athlon-compatible: " + cc.IsAthlonCompatible());
            System.out.println("Is Athlon64-compatible: " + cc.IsAthlon64Compatible());
            System.out.println("Is Bobcat-compatible: " + cc.IsBobcatCompatible());
            System.out.println("Is K10-compatible: " + cc.IsK10Compatible());
            System.out.println("Is Jaguar-compatible: " + cc.IsJaguarCompatible());
            System.out.println("Is Bulldozer-compatible: " + cc.IsBulldozerCompatible());
            System.out.println("Is Piledriver-compatible: " + cc.IsPiledriverCompatible());
            System.out.println("Is Steamroller-compatible: " + cc.IsSteamrollerCompatible());
            System.out.println("Is Excavator-compatible: " + cc.IsExcavatorCompatible());
            System.out.println("Is Zen-compatible: " + cc.IsZenCompatible());
            System.out.println("Is Zen2-compatible: " + cc.IsZen2Compatible());
            System.out.println("Is Zen3-compatible: " + cc.IsZen3Compatible());
            System.out.println("Is Zen4-compatible: " + cc.IsZen4Compatible());
            System.out.println("Is Zen5-compatible: " + cc.IsZen5Compatible());
        }
    }

    /**
     * <p>Do whatever we can to load up the native library.
     * If it can find a custom built jcpuid.dll / libjcpuid.so, it'll use that.  Otherwise
     * it'll try to look in the classpath for the correct library (see loadFromResource).
     * If the user specifies -Djcpuid.enable=false it'll skip all of this.</p>
     *
     */
    private static final void loadNative() {
        try {
            String wantedProp = System.getProperty("jcpuid.enable", "true");
            boolean wantNative = Boolean.parseBoolean(wantedProp);
            boolean loaded = loadGeneric();
            if (!loaded) {
                loaded = loadFromResource();
            }
            _nativeOk = loaded;
            if (loaded) {
                if (_doLog) {
                    System.err.println("INFO: Native CPUID library " + getLibraryMiddlePart() + " loaded");
                }
            } else {
                if (_doLog) {
                    System.err.println("WARNING: Native CPUID library jcpuid not loaded - will not be able to read CPU information using CPUID");
                }
            }
            _jcpuidVersion = fetchJcpuidVersion();
        } catch (Exception e) {
            if (_doLog) {
                System.err.println("INFO: Native CPUID library jcpuid not loaded\n* Reason: '" + e.getMessage() +
                                   "' - will not be able to read CPU information using CPUID");
            }
        }
    }

    /**
     * <p>Try loading it from an explicitly built jcpuid.dll / libjcpuid.so</p>
     * The file name must be (e.g. on linux) either libjcpuid.so or libjcpuid-x86-linux.so.
     *
     * @return true if it was loaded successfully, else false
     *
     */
    private static final boolean loadGeneric() {
        try {
            System.loadLibrary("jcpuid");
            return true;
        } catch (UnsatisfiedLinkError ule) { /* ignored */ }
        return false;
    }

    /**
     * <p>Check all of the jars in the classpath for the jcpuid dll/so.
     * This file should be stored in the resource in the same package as this class.
     *
     * <p>This is a pretty ugly hack, using the general technique illustrated by the
     * onion FEC libraries.  It works by pulling the resource, writing out the
     * byte stream to a temporary file, loading the native library from that file.
     * We then attempt to copy the file from the temporary dir to the base install dir,
     * so we don't have to do this next time - but we don't complain if it fails,
     * so we transparently support read-only base dirs.
     * </p>
     *
     * This tries the 64 bit version first if we think we may be 64 bit.
     * Then it tries the 32 bit version.
     *
     * @return true if it was loaded successfully, else false
     *
     */
    private static final boolean loadFromResource() {
        // Through 0.9.25, we had separate 32-bit and 64-bit osx jnilib files.
        // As of 0.9.26, we have a single libjcpuid-x86_64-osx.jnilib fat binary with both.
        // getResourceName64() returns non-null for 64-bit OR for 32-bit Mac.

        // try 64 bit first, if getResourceName64() returns non-null
        String resourceName = getResourceName64();
        if (resourceName != null) {
            boolean success = extractLoadAndCopy(resourceName);
            if (success) {return true;}
            if (_doLog) {System.err.println("WARNING: Resource name [" + resourceName + "] not found");}
        }

        // now try 32 bit
        resourceName = getResourceName();
        boolean success = extractLoadAndCopy(resourceName);
        if (success) {return true;}
        if (_doLog) {System.err.println("WARNING: Resource name [" + resourceName + "] not found");}
        return false;
    }

    /**
     * Extract a single resource, copy it to a temp location in the file system,
     * and attempt to load it. If the load succeeds, copy it to the installation
     * directory. Return value reflects only load success - copy will fail silently.
     *
     * @return true if it was loaded successfully, else false.
     * @since 0.8.7
     */
    private static final boolean extractLoadAndCopy(String resourceName) {
        URL resource = CPUID.class.getClassLoader().getResource(resourceName);
        if (resource == null) {return false;}
        String filename = getLibraryPrefix() + "jcpuid" + getLibrarySuffix();
        File outFile = new File(I2PAppContext.getGlobalContext().getTempDir(), filename);
        try (InputStream libStream = resource.openStream();
             FileOutputStream fos = new FileOutputStream(outFile)) {
            DataHelper.copy(libStream, fos);
            System.load(outFile.getAbsolutePath());
        } catch (UnsatisfiedLinkError ule) {
            if (_doLog) {
                System.err.println("WARNING: The resource " + resourceName +
                                   " was not a valid library for this platform " + ule);
            }
            outFile.delete();
            return false;
        } catch (IOException ioe) {
            if (_doLog) {
                System.err.println("ERROR: Problem writing out the temporary native library data");
                ioe.printStackTrace();
            }
            outFile.delete();
            return false;
        }
        // copy to install dir, ignore failure
        File newFile = new File(I2PAppContext.getGlobalContext().getBaseDir(), filename);
        FileUtil.copy(outFile, newFile, false, true);
        return true;
    }

    /** @return non-null */
    private static final String getResourceName() {
        return getLibraryPrefix() + getLibraryMiddlePart() + getLibrarySuffix();
    }

    /**
     * @return null if not on a 64 bit platform (except Mac)
     * @since 0.8.7
     */
    private static final String getResourceName64() {
        // libjcpuid-x86_64-osx.jnilib is a fat binary containing both 64- and 32-bit
        if (!is64 && !isMac) {return null;}
        return getLibraryPrefix() + get64LibraryMiddlePart() + getLibrarySuffix();
    }

    private static final String getLibraryPrefix() {
        if (isWindows) {return "";}
        else {return "lib";}
    }

    private static final String getLibraryMiddlePart() {
        if (isWindows) {return "jcpuid-x86-windows";}
        if (isMac) {
            if (isX86) {
                return "jcpuid-x86_64-osx";
            }
            return "jcpuid-ppc-osx";
        }
        if (isKFreebsd) {return "jcpuid-x86-kfreebsd";}
        if (isFreebsd) {return "jcpuid-x86-freebsd";}
        if (isNetbsd) {return "jcpuid-x86-netbsd";}
        if (isOpenbsd) {return "jcpuid-x86-openbsd";}
        if (isSunos) {return "jcpuid-x86-solaris";}
        return "jcpuid-x86-linux";
    }

    /** @since 0.8.7 */
    private static final String get64LibraryMiddlePart() {
        if (isWindows) {return "jcpuid-x86_64-windows";}
        if (isKFreebsd) {return "jcpuid-x86_64-kfreebsd";}
        if (isFreebsd) {return "jcpuid-x86_64-freebsd";}
        if (isNetbsd) {return "jcpuid-x86_64-netbsd";}
        if (isOpenbsd) {return "jcpuid-x86_64-openbsd";}
        if (isMac) {
            if (isX86){return "jcpuid-x86_64-osx";}
            return "jcpuid-ppc_64-osx";
        }
        if (isSunos) {return "jcpuid-x86_64-solaris";}
        return "jcpuid-x86_64-linux";
    }

    private static final String getLibrarySuffix() {
        if (isWindows) {return ".dll";}
        if (isMac) {return ".jnilib";}
        else {return ".so";}
    }

}
