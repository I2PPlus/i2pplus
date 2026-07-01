package freenet.support.CPUInformation;

/**
 * Identifies Intel CPUs via CPUID and exposes instruction set support flags.
 *
 * <h2>Identification Strategy</h2>
 * <p>Two identification paths are used, in order of priority:</p>
 * <ol>
 *   <li><b>Brand string</b> (CPUID leaf {@code 0x80000002}–{@code 0x80000004}): Returns the exact
 *       marketing name (e.g. "Intel(R) Core(TM) i9-14900K") and is always accurate when the
 *       CPU supports extended CPUID leaves.</li>
 *   <li><b>Model number fallback</b>: For very old CPUs without brand string support, the
 *       processor identifier is computed from CPUID family/model/extended-model/extended-family
 *       values and matched against a table of known Intel microarchitectures.</li>
 * </ol>
 *
 * <h2>Instruction Set Flags</h2>
 * <p>Regardless of the identification path, the {@code switch(family)} block always runs to
 * set the instruction set compatibility flags ({@link #isSandyCompatible},
 * {@link #isHaswellCompatible}, etc.), which are queried by callers to determine feature
 * availability (AVX, AVX2, AVX-512, BMI, FMA, etc.).</p>
 *
 * <h2>Intel Family 6 Model Numbers</h2>
 * <p>Intel uses family 6 for all modern Core processors. The model number (base model +
 * extended model) distinguishes microarchitectures:</p>
 * <pre>
 *   0x0e-0x0f  Core (Merom/Conroe)       0x1a-0x1f  Nehalem
 *   0x25-0x2f  Westmere                  0x25-0x2f  Sandy Bridge
 *   0x3a-0x3f  Ivy Bridge                0x3c-0x3f  Haswell
 *   0x45-0x47  Haswell (ULT)             0x3d-0x4f  Broadwell
 *   0x4e-0x5e  Skylake                   0x55        Skylake-X / Cascade Lake / Cooper Lake
 *   0x5c-0x5f  Goldmont (Atom)           0x66-0x67  Cannon Lake
 *   0x7a        Gemini Lake               0x7c        Lakefield
 *   0x7d-0x7e  Ice Lake client           0x6a-0x6c  Ice Lake server
 *   0x82-0x9e  Kaby Lake / Coffee Lake   0xa5-0xa6  Comet Lake
 *   0x8c-0x8d  Tiger Lake                0xa7        Rocket Lake
 *   0x97-0x9a  Alder Lake                0xb7-0xbf  Raptor Lake
 *   0xaa-0xac  Meteor Lake               0xb5-0xc6  Arrow Lake
 *   0xbd        Lunar Lake                0xcc        Panther Lake
 *   0xd5        Wildcat Lake              0x8f        Sapphire Rapids
 *   0xcf        Emerald Rapids            0xad-0xae  Granite Rapids
 *   0xaf        Sierra Forest             0xdd        Clearwater Forest
 *   0xb6        Grand Ridge               0xbe        Gracemont (E-core only)
 * </pre>
 *
 * @see CPUID
 * @see IntelCPUInfo
 * @since 0.8.7
 */
class IntelInfoImpl extends CPUIDCPUInfo implements IntelCPUInfo
{
    private static boolean isPentiumCompatible;
    private static boolean isPentiumMMXCompatible;
    private static boolean isPentium2Compatible;
    private static boolean isPentium3Compatible;
    private static boolean isPentium4Compatible;
    private static boolean isPentiumMCompatible;
    private static boolean isAtomCompatible;
    private static boolean isCore2Compatible;
    private static boolean isCoreiCompatible;
    private static boolean isSandyCompatible;
    private static boolean isIvyCompatible;
    private static boolean isHaswellCompatible;
    private static boolean isBroadwellCompatible;
    private static boolean isSkylakeCompatible;

    /** Identified processor name from {@link #identifyCPU()}, or {@code null} if unrecognized. */
    private static final String smodel = identifyCPU();

    /** {@code true} if the CPU supports Pentium (P5) instructions. */
    public boolean IsPentiumCompatible(){ return isPentiumCompatible; }
    /** {@code true} if the CPU supports Pentium MMX instructions. */
    public boolean IsPentiumMMXCompatible(){ return isPentiumMMXCompatible; }
    /** {@code true} if the CPU supports Pentium II (P6) instructions. */
    public boolean IsPentium2Compatible(){ return isPentium2Compatible; }
    /** {@code true} if the CPU supports Pentium III instructions (SSE). */
    public boolean IsPentium3Compatible(){ return isPentium3Compatible; }
    /** {@code true} if the CPU supports Pentium 4 instructions (SSE2). */
    public boolean IsPentium4Compatible(){ return isPentium4Compatible; }
    /** {@code true} if the CPU supports Pentium M (Dothan/Core) instructions. */
    public boolean IsPentiumMCompatible(){ return isPentiumMCompatible; }
    /** {@code true} if the CPU is an Intel Atom (Silvermont/Goldmont/etc.). */
    public boolean IsAtomCompatible(){ return isAtomCompatible; }
    /** {@code true} if the CPU supports Core 2 (SSSE3) instructions. */
    public boolean IsCore2Compatible(){ return isCore2Compatible; }
    /** {@code true} if the CPU supports Core i-series (SSE4.2) instructions. */
    public boolean IsCoreiCompatible(){ return isCoreiCompatible; }
    /** {@code true} if the CPU supports Sandy Bridge (AVX) instructions. */
    public boolean IsSandyCompatible(){ return isSandyCompatible; }
    /** {@code true} if the CPU supports Ivy Bridge (AVX, PCMPGTQ) instructions. */
    public boolean IsIvyCompatible(){ return isIvyCompatible; }
    /** {@code true} if the CPU supports Haswell (AVX2, BMI1/2, FMA) instructions. */
    public boolean IsHaswellCompatible(){ return isHaswellCompatible; }
    /** {@code true} if the CPU supports Broadwell (ADX, AVX-512PF/ER) instructions. */
    public boolean IsBroadwellCompatible(){ return isBroadwellCompatible; }
    /**
     * {@code true} if the CPU supports Skylake (AVX-512F/DQ/BW/VL) instructions.
     * @since 0.9.41
     */
    public boolean IsSkylakeCompatible() { return isSkylakeCompatible; }

    /**
     * Returns the identified processor name, or throws if unrecognized.
     *
     * @return the CPU model string (e.g. "Intel(R) Core(TM) i9-14900K" or "Skylake Core i3/i5/i7")
     * @throws UnknownCPUException if the CPU could not be identified
     */
    public String getCPUModelString() throws UnknownCPUException
    {
        if (smodel != null)
            return smodel;
        throw new UnknownCPUException("Unknown Intel CPU; Family="+CPUID.getCPUFamily() + '/' + CPUID.getCPUExtendedFamily()+
                                      ", Model="+CPUID.getCPUModel() + '/' + CPUID.getCPUExtendedModel());
    }

    /**
     * Identifies the Intel CPU and sets instruction set compatibility flags.
     *
     * <p>Strategy:</p>
     * <ol>
     *   <li>Capture the CPUID brand string (leaf {@code 0x80000002}–{@code 0x80000004}),
     *       which gives the exact marketing name when available.</li>
     *   <li>Compute the processor family and model from CPUID leaf 1, handling Intel's
     *       extended model and extended family fields.</li>
     *   <li>Walk a {@code switch(family)} block that matches known model numbers to
     *       microarchitecture codenames and sets the appropriate instruction set
     *       compatibility flags ({@code isSandyCompatible}, {@code isHaswellCompatible},
     *       etc.).</li>
     *   <li>Override the model-based name with the brand string if one was captured,
     *       providing a more accurate and user-friendly display string.</li>
     * </ol>
     *
     * <p>The brand string override at the end ensures that modern CPUs (family 6) always
     * display the correct marketing name, while the model-based switch still runs to set
     * the instruction set flags needed by callers.</p>
     *
     * @return the identified processor name, or {@code null} if unrecognized
     */
    private static String identifyCPU()
    {
        // http://en.wikipedia.org/wiki/Cpuid
        // http://web.archive.org/web/20110307080258/http://www.intel.com/Assets/PDF/appnote/241618.pdf
        // http://www.intel.com/content/dam/www/public/us/en/documents/manuals/64-ia-32-architectures-software-developer-manual-325462.pdf
    	// #include "llvm/Support/Host.h", http://llvm.org/docs/doxygen/html/Host_8cpp_source.html
        String modelString = null;

        // Capture brand string (CPUID 0x80000002-4) — used as primary identifier when available
        String brand = CPUID.getCPUModelName();

        int family = CPUID.getCPUFamily();
        int model = CPUID.getCPUModel();
        if (family == 15 || family == 6) {
            // Intel uses extended model for family = 15 or family = 6,
            // which is not what wikipedia says
            // we construct the model from EAX as follows:
            // ext. model is 5th byte, base model is 2nd byte
            // So e.g. for a published CPUID value of "306C1"
            // the model here would be 0x3c, it's a Haswell.
            model += CPUID.getCPUExtendedModel() << 4;
        }
        if (family == 15) {
            family += CPUID.getCPUExtendedFamily();
        }

        switch (family) {
            case 4: {
                switch (model) {
                    case 0:
                        modelString = "486 DX-25/33";
                        break;
                    case 1:
                        modelString = "486 DX-50";
                        break;
                    case 2:
                        modelString = "486 SX";
                        break;
                    case 3:
                        modelString = "486 DX/2";
                        break;
                    case 4:
                        modelString = "486 SL";
                        break;
                    case 5:
                        modelString = "486 SX/2";
                        break;
                    case 7:
                        modelString = "486 DX/2-WB";
                        break;
                    case 8:
                        modelString = "486 DX/4";
                        break;
                    case 9:
                        modelString = "486 DX/4-WB";
                        break;
                    default:
                        modelString = "Intel 486/586 model " + model;
                        break;
                }
            }
            break;

            // P5
            case 5: {
                isPentiumCompatible = true;
                switch (model) {
                    case 0:
                        modelString = "Pentium 60/66 A-step";
                        break;
                    case 1:
                        modelString = "Pentium 60/66";
                        break;
                    case 2:
                        modelString = "Pentium 75 - 200";
                        break;
                    case 3:
                        modelString = "OverDrive PODP5V83";
                        break;
                    case 4:
                        isPentiumMMXCompatible = true;
                        modelString = "Pentium MMX";
                        break;
                    case 7:
                        modelString = "Mobile Pentium 75 - 200";
                        break;
                    case 8:
                        isPentiumMMXCompatible = true;
                        modelString = "Mobile Pentium MMX";
                        break;
                    default:
                        modelString = "Intel Pentium model " + model;
                        break;
                }
            }
            break;

            // P6
            case 6: {
                isPentiumCompatible = true;
                isPentiumMMXCompatible = true;
                int extmodel = model >> 4;
                if (extmodel >= 1) {
                    isPentium2Compatible = true;
                    isPentium3Compatible = true;
                    isPentium4Compatible = true;
                    isPentiumMCompatible = true;
                    isCore2Compatible = true;
                    if (extmodel >= 2)
                        isCoreiCompatible = true;
                }
                switch (model) {
                    case 0:
                        modelString = "Pentium Pro A-step";
                        break;
                    case 1:
                        modelString = "Pentium Pro";
                        break;
                    // Spoofed Nehalem by qemu-kvm
                    // Not in any CPUID charts
                    // KVM bug?
                    // # cat /usr/share/kvm/cpus-x86_64.conf | grep 'name = "Nehalem"' -B 1 -A 12
                    // [cpudef]
                    //    name = "Nehalem"
                    //    level = "2"
                    //    vendor = "GenuineIntel"
                    //    family = "6"
                    //    model = "2"
                    //    stepping = "3"
                    //    feature_edx = "sse2 sse fxsr mmx clflush pse36 pat cmov mca pge mtrr sep apic cx8 mce pae msr tsc pse de fpu"
                    //    feature_ecx = "popcnt sse4.2 sse4.1 cx16 ssse3 sse3"
                    //    extfeature_edx = "i64 syscall xd"
                    //    extfeature_ecx = "lahf_lm"
                    //    xlevel = "0x8000000A"
                    //    model_id = "Intel Core i7 9xx (Nehalem Class Core i7)"
                    //case 2:
                        // ...
                    case 3:
                        isPentium2Compatible = true;
                        modelString = "Pentium II (Klamath)";
                        break;
                    case 5:
                        isPentium2Compatible = true;
                        modelString = "Pentium II (Deschutes), Celeron (Covington), Mobile Pentium II (Dixon)";
                        break;
                    case 6:
                        isPentium2Compatible = true;
                        modelString = "Mobile Pentium II, Celeron (Mendocino)";
                        break;
                    case 7:
                        isPentium2Compatible = true;
                        isPentium3Compatible = true;
                        modelString = "Pentium III (Katmai)";
                        break;
                    case 8:
                        isPentium2Compatible = true;
                        isPentium3Compatible = true;
                        modelString = "Pentium III (Coppermine), Celeron w/SSE";
                        break;
                    case 9:
                        isPentium2Compatible = true;
                        isPentium3Compatible = true;
                        isPentiumMCompatible = true;
                        modelString = "Pentium M (Banias)";
                        break;
                    case 10:
                        isPentium2Compatible = true;
                        isPentium3Compatible = true;
                        modelString = "Pentium III Xeon (Cascades)";
                        break;
                    case 11:
                        isPentium2Compatible = true;
                        isPentium3Compatible = true;
                        modelString = "Pentium III (130 nm)";
                        break;
                    case 13:
                        isPentium2Compatible = true;
                        isPentium3Compatible = true;
                        isPentiumMCompatible = true;
                        modelString = "Core (Yonah)";
                        break;
                    case 14:
                    case 15:
                        isPentium2Compatible = true;
                        isPentium3Compatible = true;
                        isPentiumMCompatible = true;
                        isCore2Compatible = true;
                        modelString = "Penryn";
                        break;

                // following are for extended model == 1
                // most flags are set above

                    // Celeron 65 nm
                    case 0x16:
                        modelString = "Merom";
                        break;
                    // Penryn 45 nm
                    case 0x17:
                        modelString = "Penryn";
                        break;
                    // Nehalem 45 nm
                    case 0x1a:
                        isCoreiCompatible = true;
                        modelString = "Nehalem";
                        break;
                    // Atom Pineview / Silverthorne 45 nm
                    case 0x1c:
                        isAtomCompatible = true;
                        // Some support SSE3? true for Pineview? TBD...
                        isCore2Compatible = false;
                        isPentium4Compatible = false;
                        modelString = "Atom";
                        break;
                    // Penryn 45 nm
                    case 0x1d:
                        isCoreiCompatible = true;
                        modelString = "Penryn";
                        break;
                    // Nehalem 45 nm
                    case 0x1e:
                        isCoreiCompatible = true;
                        modelString = "Nehalem";
                        break;

                // following are for extended model == 2
                // most flags are set above
                // isCoreiCompatible = true is the default

                    // Westmere 32 nm
                    case 0x25:
                        modelString = "Westmere";
                        break;
                    // Atom Lincroft 45 nm
                    case 0x26:
                        isAtomCompatible = true;
                        // Supports SSE 3
                        isCoreiCompatible = false;
                        modelString = "Atom";
                        break;
                    // Sandy bridge 32 nm
                    // 1, 2, or 4 cores
                    // ref: https://en.wikipedia.org/wiki/Sandy_Bridge_%28microarchitecture%29
                    case 0x2a:
                        isSandyCompatible = true;
                        modelString = "Sandy Bridge";
                        break;
                    // Details unknown, please add a proper model string if 0x2B model is found
                    case 0x2b:
                        modelString = "Core i7/i5 (32nm)";
                        break;
                    // Westmere
                    case 0x2c:
                        modelString = "Westmere";
                        break;
                    // Sandy Bridge 32 nm
                    // Sandy Bridge-E up to 8 cores
                    // ref: https://en.wikipedia.org/wiki/Sandy_Bridge_%28microarchitecture%29
                    case 0x2d:
                        isSandyCompatible = true;
                        modelString = "Sandy Bridge";
                        break;
                    // Nehalem 45 nm
                    case 0x2e:
                        modelString = "Nehalem";
                        break;
                    // Westmere 32 nm
                    case 0x2f:
                        modelString = "Westmere";
                        break;

                // following are for extended model == 3
                // most flags are set above
                // isCoreiCompatible = true is the default

                    // Atom Cedarview 32 nm
                    case 0x36:
                        isAtomCompatible = true;
                        // Supports SSE 3
                        isCore2Compatible = false;
                        isCoreiCompatible = false;
                        modelString = "Atom";
                        break;
                    // Silvermont 22 nm Celeron
                    case 0x37:
                        isAtomCompatible = true;
                        isCore2Compatible = false;
                        isCoreiCompatible = false;
                        modelString = "Atom";
                        break;
                    // Ivy Bridge 22 nm
                    // ref: https://en.wikipedia.org/wiki/Sandy_Bridge_%28microarchitecture%29
                    case 0x3a:
                        isSandyCompatible = true;
                        isIvyCompatible = true;
                        modelString = "Ivy Bridge";
                        break;

                    // case 0x3c: See below

                    // Broadwell 14 nm
                    // ref: https://en.wikipedia.org/wiki/Broadwell_(microarchitecture)
                    case 0x3d:
                    case 0x47:
                    case 0x4f: // Broadwell-EP/EX
                    case 0x56: // Broadwell-DE
                    {
                        CPUIDCPUInfo c = new CPUIDCPUInfo();
                        if (c.hasAVX2() && c.hasBMI1()  && c.hasBMI2() &&
                            c.hasFMA3() && c.hasMOVBE() && c.hasABM()) {
                            isSandyCompatible = true;
                            isIvyCompatible = true;
                            isHaswellCompatible = true;
                            if (c.hasADX())
                                isBroadwellCompatible = true;
                            modelString = "Broadwell Core i3/i5/i7";
                        } else {
                            // This processor is "corei" compatible, as we define it,
                            // i.e. SSE4.2 but not necessarily AVX.
                            if (c.hasAVX()) {
                                isSandyCompatible = true;
                                isIvyCompatible = true;
                                modelString = "Broadwell Celeron/Pentium w/ AVX";
                            } else {
                                modelString = "Broadwell Celeron/Pentium";
                            }
                        }
                        break;
                    }

                    // Ivy Bridge 22 nm
                    case 0x3e:
                        isSandyCompatible = true;
                        isIvyCompatible = true;
                        modelString = "Ivy Bridge";
                        break;

                    // case 0x3f: See below

                // following are for extended model == 4
                // most flags are set above
                // isCoreiCompatible = true is the default

                    // Haswell 22 nm
                    // Pentium and Celeron Haswells do not support new Haswell instructions,
                    // only Corei ones do, but we can't tell that from the model alone.
                    //
                    // We know for sure that GMP coreihwl uses the MULX instruction from BMI2,
                    // unsure about the others, but let's be safe and check all 6 feature bits, as
                    // the Intel app note suggests.
                    //
                    // ref: https://en.wikipedia.org/wiki/Haswell_%28microarchitecture%29
                    // ref: https://software.intel.com/en-us/articles/how-to-detect-new-instruction-support-in-the-4th-generation-intel-core-processor-family
                    case 0x3c:
                    case 0x3f:
                    case 0x45:
                    case 0x46:
                    {
                        CPUIDCPUInfo c = new CPUIDCPUInfo();
                        if (c.hasAVX2() && c.hasBMI1()  && c.hasBMI2() &&
                            c.hasFMA3() && c.hasMOVBE() && c.hasABM()) {
                            isSandyCompatible = true;
                            isIvyCompatible = true;
                            isHaswellCompatible = true;
                            modelString = "Haswell Core i3/i5/i7 model " + model;
                        } else {
                            // This processor is "corei" compatible, as we define it,
                            // i.e. SSE4.2 but not necessarily AVX.
                            if (c.hasAVX()) {
                                isSandyCompatible = true;
                                isIvyCompatible = true;
                                modelString = "Haswell Celeron/Pentium w/ AVX model " + model;
                            } else {
                                modelString = "Haswell Celeron/Pentium model " + model;
                            }
                        }
                        break;
                    }

                    // Quark 32nm
                    case 0x4a:
                        isCore2Compatible = false;
                        isCoreiCompatible = false;
                        modelString = "Quark";
                        break;
                    // Silvermont 22 nm
                    // Supports SSE 4.2
                    case 0x4d:
                        isAtomCompatible = true;
                        modelString = "Atom";
                        break;

                // following are for extended model == 5
                // most flags are set above
                // isCoreiCompatible = true is the default

                    // Skylake 14 nm
                    // ref: http://www.intel.com/content/dam/www/public/us/en/documents/specification-updates/desktop-6th-gen-core-family-spec-update.pdf
                    // See Haswell notes above
                    case 0x4e: // Skylake mobile
                    case 0x5e: // Skylake desktop
                    {
                        CPUIDCPUInfo c = new CPUIDCPUInfo();
                        if (c.hasAVX2() && c.hasBMI1()  && c.hasBMI2() &&
                            c.hasFMA3() && c.hasMOVBE() && c.hasABM()) {
                            isSandyCompatible = true;
                            isIvyCompatible = true;
                            isHaswellCompatible = true;
                            if (c.hasADX()) {
                                isBroadwellCompatible = true;
                                if (c.hasAVX512())
                                    isSkylakeCompatible = true;
                            }
                            modelString = "Skylake Core i3/i5/i7";
                        } else {
                            if (c.hasAVX()) {
                                isSandyCompatible = true;
                                isIvyCompatible = true;
                                modelString = "Skylake Celeron/Pentium w/ AVX";
                            } else {
                                modelString = "Skylake Celeron/Pentium";
                            }
                        }
                        break;
                    }

                    // Skylake-X / Cascade Lake / Cooper Lake 14 nm
                    // ref: https://en.wikipedia.org/wiki/Cascade_Lake_(microprocessor)
                    // AVX-512 support distinguishes this from mainstream Skylake
                    case 0x55:
                    {
                        CPUIDCPUInfo c = new CPUIDCPUInfo();
                        if (c.hasAVX2() && c.hasBMI1() && c.hasBMI2() &&
                            c.hasFMA3() && c.hasMOVBE() && c.hasABM()) {
                            isSandyCompatible = true;
                            isIvyCompatible = true;
                            isHaswellCompatible = true;
                            if (c.hasADX()) {
                                isBroadwellCompatible = true;
                                if (c.hasAVX512()) {
                                    isSkylakeCompatible = true;
                                    modelString = "Skylake-X / Cascade Lake / Cooper Lake";
                                } else {
                                    modelString = "Skylake-X Core i9";
                                }
                            } else {
                                modelString = "Skylake-X";
                            }
                        } else {
                            modelString = "Intel model " + model;
                        }
                        break;
                    }

                    // Cannon Lake 14 nm (mobile, limited release)
                    // ref: https://en.wikipedia.org/wiki/Cannon_Lake_(microarchitecture)
                    case 0x66:
                    case 0x67:
                    {
                        CPUIDCPUInfo c = new CPUIDCPUInfo();
                        if (c.hasAVX2()) {
                            isSandyCompatible = true;
                            isIvyCompatible = true;
                            isHaswellCompatible = true;
                            isBroadwellCompatible = true;
                            modelString = "Cannon Lake";
                        } else {
                            modelString = "Cannon Lake";
                        }
                        break;
                    }

                    // Ice Lake 10 nm (client)
                    // ref: https://en.wikipedia.org/wiki/Ice_Lake_(microprocessor)
                    case 0x7d:
                    case 0x7e:
                    {
                        CPUIDCPUInfo c = new CPUIDCPUInfo();
                        if (c.hasAVX2()) {
                            isSandyCompatible = true;
                            isIvyCompatible = true;
                            isHaswellCompatible = true;
                            isBroadwellCompatible = true;
                            isSkylakeCompatible = true;
                            modelString = "Ice Lake Core i3/i5/i7";
                        } else {
                            modelString = "Ice Lake";
                        }
                        break;
                    }

                    // Ice Lake 10 nm (server)
                    case 0x6a:
                    case 0x6c:
                    {
                        CPUIDCPUInfo c = new CPUIDCPUInfo();
                        if (c.hasAVX512()) {
                            isSandyCompatible = true;
                            isIvyCompatible = true;
                            isHaswellCompatible = true;
                            isBroadwellCompatible = true;
                            isSkylakeCompatible = true;
                            modelString = "Ice Lake Xeon";
                        } else if (c.hasAVX2()) {
                            isSandyCompatible = true;
                            isIvyCompatible = true;
                            isHaswellCompatible = true;
                            isBroadwellCompatible = true;
                            isSkylakeCompatible = true;
                            modelString = "Ice Lake";
                        } else {
                            modelString = "Ice Lake";
                        }
                        break;
                    }


                    case 0x5c: // Apollo Lake (Goldmont)
                    case 0x5f: // Goldmont / Atom
                    case 0x7a: // Gemini Lake (Goldmont Plus)
                    case 0x7c: // Lakefield (Tremont + Sunny Cove hybrid)
                    case 0x9c: // Jasper Lake (Tremont)
                    {
                        // This processor is "corei" compatible, as we define it,
                        // i.e. SSE4.2 but not necessarily AVX.
                        CPUIDCPUInfo c = new CPUIDCPUInfo();
                        if (c.hasAVX()) {
                            isSandyCompatible = true;
                            isIvyCompatible = true;
                            modelString = "Goldmont w/ AVX";
                        } else {
                            modelString = "Goldmont";
                        }
                        break;
                    }


                // following are for extended model == 8 or higher
                // most flags are set above
                // isCoreiCompatible = true is the default

                    // Kaby Lake / Coffee Lake / Comet Lake 14 nm
                    // ref: https://en.wikipedia.org/wiki/Kaby_Lake
                    // ref: https://en.wikipedia.org/wiki/Coffee_Lake
                    // ref: https://en.wikipedia.org/wiki/Comet_Lake_(microprocessor)
                    case 0x82: // Kaby Lake mobile (early)
                    case 0x8e: // Kaby Lake mobile (also Whiskey Lake, Coffee Lake mobile)
                    case 0x9e: // Kaby Lake desktop (also Coffee Lake)
                    case 0xa5: // Comet Lake
                    case 0xa6: // Comet Lake
                    {
                        CPUIDCPUInfo c = new CPUIDCPUInfo();
                        String suffix = "";
                        if (c.hasAVX2() && c.hasBMI1()  && c.hasBMI2() &&
                            c.hasFMA3() && c.hasMOVBE() && c.hasABM()) {
                            isSandyCompatible = true;
                            isIvyCompatible = true;
                            isHaswellCompatible = true;
                            if (c.hasADX()) {
                                isBroadwellCompatible = true;
                                if (c.hasAVX512())
                                    isSkylakeCompatible = true;
                            }
                            suffix = "Core i3/i5/i7";
                        } else {
                            if (c.hasAVX()) {
                                isSandyCompatible = true;
                                isIvyCompatible = true;
                                suffix = "Celeron/Pentium w/ AVX";
                            } else {
                                suffix = "Celeron/Pentium";
                            }
                        }
                        if (model == 0xa5 || model == 0xa6)
                            modelString = "Comet Lake " + suffix;
                        else
                            modelString = "Kaby Lake " + suffix;
                        break;
                    }

                    // Tiger Lake 10 nm
                    // ref: https://en.wikipedia.org/wiki/Tiger_Lake_(microprocessor)
                    case 0x8c:
                    case 0x8d:
                    {
                        CPUIDCPUInfo c = new CPUIDCPUInfo();
                        if (c.hasAVX2()) {
                            isSandyCompatible = true;
                            isIvyCompatible = true;
                            isHaswellCompatible = true;
                            isBroadwellCompatible = true;
                            isSkylakeCompatible = true;
                            modelString = "Tiger Lake Core i3/i5/i7";
                        } else {
                            modelString = "Tiger Lake";
                        }
                        break;
                    }

                    // Rocket Lake 14 nm
                    // ref: https://en.wikipedia.org/wiki/Rocket_Lake
                    case 0xa7:
                    {
                        CPUIDCPUInfo c = new CPUIDCPUInfo();
                        if (c.hasAVX512()) {
                            isSandyCompatible = true;
                            isIvyCompatible = true;
                            isHaswellCompatible = true;
                            isBroadwellCompatible = true;
                            isSkylakeCompatible = true;
                            modelString = "Rocket Lake Core i5/i7/i9";
                        } else if (c.hasAVX2()) {
                            isSandyCompatible = true;
                            isIvyCompatible = true;
                            isHaswellCompatible = true;
                            isBroadwellCompatible = true;
                            modelString = "Rocket Lake";
                        } else {
                            modelString = "Rocket Lake";
                        }
                        break;
                    }

                    // Alder Lake 10 nm (Intel 7)
                    // ref: https://en.wikipedia.org/wiki/Alder_Lake
                    case 0x97:
                    case 0x9a:
                    {
                        CPUIDCPUInfo c = new CPUIDCPUInfo();
                        if (c.hasAVX2()) {
                            isSandyCompatible = true;
                            isIvyCompatible = true;
                            isHaswellCompatible = true;
                            isBroadwellCompatible = true;
                            isSkylakeCompatible = true;
                            modelString = "Alder Lake Core i5/i7/i9";
                        } else {
                            modelString = "Alder Lake";
                        }
                        break;
                    }

                    // Raptor Lake 10 nm (Intel 7)
                    // ref: https://en.wikipedia.org/wiki/Raptor_Lake
                    case 0xb7: // Raptor Lake
                    case 0xba: // Raptor Lake
                    case 0xbf: // Raptor Lake refresh
                    {
                        CPUIDCPUInfo c = new CPUIDCPUInfo();
                        if (c.hasAVX2()) {
                            isSandyCompatible = true;
                            isIvyCompatible = true;
                            isHaswellCompatible = true;
                            isBroadwellCompatible = true;
                            isSkylakeCompatible = true;
                            modelString = "Raptor Lake Core i5/i7/i9";
                        } else {
                            modelString = "Raptor Lake";
                        }
                        break;
                    }

                    // Meteor Lake 7 nm (Intel 4)
                    // ref: https://en.wikipedia.org/wiki/Meteor_Lake
                    case 0xaa: // Meteor Lake mobile
                    case 0xac: // Meteor Lake desktop
                    {
                        CPUIDCPUInfo c = new CPUIDCPUInfo();
                        if (c.hasAVX2()) {
                            isSandyCompatible = true;
                            isIvyCompatible = true;
                            isHaswellCompatible = true;
                            isBroadwellCompatible = true;
                            isSkylakeCompatible = true;
                            modelString = "Meteor Lake Core Ultra";
                        } else {
                            modelString = "Meteor Lake";
                        }
                        break;
                    }

                    // Arrow Lake 7 nm (Intel 20A / TSMC N3B)
                    // ref: https://en.wikipedia.org/wiki/Arrow_Lake_(microprocessor)
                    case 0xb5: // Arrow Lake mobile
                    case 0xc5: // Arrow Lake
                    case 0xc6: // Arrow Lake-S
                    {
                        CPUIDCPUInfo c = new CPUIDCPUInfo();
                        if (c.hasAVX2()) {
                            isSandyCompatible = true;
                            isIvyCompatible = true;
                            isHaswellCompatible = true;
                            isBroadwellCompatible = true;
                            isSkylakeCompatible = true;
                            modelString = "Arrow Lake Core Ultra";
                        } else {
                            modelString = "Arrow Lake";
                        }
                        break;
                    }

                    // Lunar Lake 3 nm
                    // ref: https://en.wikipedia.org/wiki/Lunar_Lake
                    case 0xbd:
                    {
                        CPUIDCPUInfo c = new CPUIDCPUInfo();
                        if (c.hasAVX2()) {
                            isSandyCompatible = true;
                            isIvyCompatible = true;
                            isHaswellCompatible = true;
                            isBroadwellCompatible = true;
                            isSkylakeCompatible = true;
                            modelString = "Lunar Lake Core Ultra";
                        } else {
                            modelString = "Lunar Lake";
                        }
                        break;
                    }

                    // Panther Lake
                    case 0xcc:
                    {
                        CPUIDCPUInfo c = new CPUIDCPUInfo();
                        if (c.hasAVX2()) {
                            isSandyCompatible = true;
                            isIvyCompatible = true;
                            isHaswellCompatible = true;
                            isBroadwellCompatible = true;
                            isSkylakeCompatible = true;
                            modelString = "Panther Lake";
                        } else {
                            modelString = "Panther Lake";
                        }
                        break;
                    }

                    // Wildcat Lake
                    case 0xd5:
                    {
                        CPUIDCPUInfo c = new CPUIDCPUInfo();
                        if (c.hasAVX2()) {
                            isSandyCompatible = true;
                            isIvyCompatible = true;
                            isHaswellCompatible = true;
                            isBroadwellCompatible = true;
                            modelString = "Wildcat Lake";
                        } else {
                            modelString = "Wildcat Lake";
                        }
                        break;
                    }

                    // Granite Rapids 5 nm
                    // ref: https://en.wikipedia.org/wiki/Granite_Rapids
                    case 0xad: // Granite Rapids-D
                    case 0xae: // Granite Rapids
                    {
                        CPUIDCPUInfo c = new CPUIDCPUInfo();
                        if (c.hasAVX512()) {
                            isSandyCompatible = true;
                            isIvyCompatible = true;
                            isHaswellCompatible = true;
                            isBroadwellCompatible = true;
                            isSkylakeCompatible = true;
                            modelString = "Granite Rapids Xeon";
                        } else {
                            modelString = "Granite Rapids";
                        }
                        break;
                    }

                    // Sierra Forest
                    // ref: https://en.wikipedia.org/wiki/Sierra_Forest
                    case 0xaf:
                    {
                        CPUIDCPUInfo c = new CPUIDCPUInfo();
                        if (c.hasAVX2()) {
                            isSandyCompatible = true;
                            isIvyCompatible = true;
                            isHaswellCompatible = true;
                            isBroadwellCompatible = true;
                            modelString = "Sierra Forest Xeon";
                        } else {
                            modelString = "Sierra Forest";
                        }
                        break;
                    }

                    // Clearwater Forest
                    case 0xdd:
                    {
                        CPUIDCPUInfo c = new CPUIDCPUInfo();
                        if (c.hasAVX2()) {
                            isSandyCompatible = true;
                            isIvyCompatible = true;
                            isHaswellCompatible = true;
                            isBroadwellCompatible = true;
                            isSkylakeCompatible = true;
                            modelString = "Clearwater Forest Xeon";
                        } else {
                            modelString = "Clearwater Forest";
                        }
                        break;
                    }

                    // Grand Ridge
                    case 0xb6:
                    {
                        CPUIDCPUInfo c = new CPUIDCPUInfo();
                        if (c.hasAVX2()) {
                            isSandyCompatible = true;
                            isIvyCompatible = true;
                            isHaswellCompatible = true;
                            isBroadwellCompatible = true;
                            isSkylakeCompatible = true;
                            modelString = "Grand Ridge";
                        } else {
                            modelString = "Grand Ridge";
                        }
                        break;
                    }

                    // Emerald Rapids
                    case 0xcf:
                    {
                        CPUIDCPUInfo c = new CPUIDCPUInfo();
                        if (c.hasAVX512()) {
                            isSandyCompatible = true;
                            isIvyCompatible = true;
                            isHaswellCompatible = true;
                            isBroadwellCompatible = true;
                            isSkylakeCompatible = true;
                            modelString = "Emerald Rapids Xeon";
                        } else {
                            modelString = "Emerald Rapids";
                        }
                        break;
                    }

                    // Sapphire Rapids
                    case 0x8f:
                    {
                        CPUIDCPUInfo c = new CPUIDCPUInfo();
                        if (c.hasAVX512()) {
                            isSandyCompatible = true;
                            isIvyCompatible = true;
                            isHaswellCompatible = true;
                            isBroadwellCompatible = true;
                            isSkylakeCompatible = true;
                            modelString = "Sapphire Rapids Xeon";
                        } else {
                            modelString = "Sapphire Rapids";
                        }
                        break;
                    }

                    // Gracemont (Atom, E-core only)
                    case 0xbe:
                    {
                        CPUIDCPUInfo c = new CPUIDCPUInfo();
                        if (c.hasAVX2()) {
                            isSandyCompatible = true;
                            isIvyCompatible = true;
                            isHaswellCompatible = true;
                            isBroadwellCompatible = true;
                            modelString = "Gracemont";
                        } else {
                            modelString = "Gracemont";
                        }
                        break;
                    }

                    // others
                    default:
                        modelString = "Intel model " + model;
                        break;

                } // switch model
            } // case 6
            break;

            case 7: {
                modelString = "Intel Itanium model " + model;
            }
            break;

            case 15: {
                isPentiumCompatible = true;
                isPentiumMMXCompatible = true;
                isPentium2Compatible = true;
                isPentium3Compatible = true;
                isPentium4Compatible = true;
                switch (model) {
                    case 0:
                    case 1:
                        modelString = "Pentium IV (180 nm)";
                        break;
                    case 2:
                        modelString = "Pentium IV (130 nm)";
                        break;
                    case 3:
                        modelString = "Pentium IV (90 nm)";
                        break;
                    case 4:
                        modelString = "Pentium IV (90 nm)";
                        break;
                    case 6:
                        modelString = "Pentium IV (65 nm)";
                        break;
                    default:
                        modelString = "Intel Pentium IV model " + model;
                        break;
                }
            }
            break;

            case 16: {
                modelString = "Intel Itanium II model " + model;
            }
        }

        // Override model-based string with brand string if available
        if (brand != null && !brand.isEmpty()) {
            modelString = brand;
        }

        return modelString;
    }
}
