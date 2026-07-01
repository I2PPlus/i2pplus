package freenet.support.CPUInformation;

/**
 *  Moved out of CPUID.java
 *
 *  Ref: https://en.wikipedia.org/wiki/List_of_AMD_CPU_microarchitectures
 *  Ref: https://gmplib.org/repo/gmp/file/tip/config.guess
 *
 *  @since 0.8.7
 */
class AMDInfoImpl extends CPUIDCPUInfo implements AMDCPUInfo {
    private static boolean isK6Compatible;
    private static boolean isK6_2_Compatible;
    private static boolean isK6_3_Compatible;
    private static boolean isGeodeCompatible;
    private static boolean isAthlonCompatible;
    private static boolean isAthlon64Compatible;
    private static boolean isK10Compatible;
    private static boolean isBobcatCompatible;
    private static boolean isJaguarCompatible;
    private static boolean isBulldozerCompatible;
    private static boolean isPiledriverCompatible;
    private static boolean isSteamrollerCompatible;
    private static boolean isExcavatorCompatible;
    private static boolean isZenCompatible;
    private static boolean isZen2Compatible;
    private static boolean isZen3Compatible;
    private static boolean isZen4Compatible;
    private static boolean isZen5Compatible;

    /**
     * Default constructor.
     */
    public AMDInfoImpl() {}

     /**
      * @return true if the CPU present in the machine is at least a 'k6' CPU
      */
    public boolean IsK6Compatible() {return isK6Compatible;}

    public boolean IsK6_2_Compatible() {return isK6_2_Compatible;}
    public boolean IsK6_3_Compatible() {return isK6_3_Compatible;}
    public boolean IsGeodeCompatible() {return isGeodeCompatible;}
    public boolean IsAthlonCompatible() {return isAthlonCompatible;}
    public boolean IsAthlon64Compatible() {return isAthlon64Compatible;}
    public boolean IsK10Compatible() {return isK10Compatible;}
    public boolean IsBobcatCompatible() {return isBobcatCompatible;}
    public boolean IsJaguarCompatible() {return isJaguarCompatible;}
    public boolean IsBulldozerCompatible() {return isBulldozerCompatible;}
    public boolean IsPiledriverCompatible() {return isPiledriverCompatible;}
    public boolean IsSteamrollerCompatible() {return isSteamrollerCompatible;}
    public boolean IsExcavatorCompatible() {return isExcavatorCompatible;}

    /**
     * @return true if the CPU present in the machine is at least a Zen family CPU
     * @since 0.9.48
     */
    public boolean IsZenCompatible() {return isZenCompatible;}

    /**
     * @return true if the CPU present in the machine is at least a Zen2 family CPU
     * @since 0.9.48
     */
    public boolean IsZen2Compatible() {return isZen2Compatible;}

    /**
     * @return true if the CPU present in the machine is at least a Zen3 family CPU
     * @since 0.9.69+
     */
    public boolean IsZen3Compatible() {return isZen3Compatible;}

    /**
     * @return true if the CPU present in the machine is at least a Zen4 family CPU
     * @since 0.9.69+
     */
    public boolean IsZen4Compatible() {return isZen4Compatible;}

    /**
     * @return true if the CPU present in the machine is at least a Zen5 family CPU
     * @since 0.9.69+
     */
    public boolean IsZen5Compatible() {return isZen5Compatible;}

    public String getCPUModelString() throws UnknownCPUException {
        String smodel = identifyCPU();
        if (smodel != null) {return smodel;}
        throw new UnknownCPUException("Unknown AMD CPU; Family=" +
                                      CPUID.getCPUFamily() + '/' +
                                      CPUID.getCPUExtendedFamily() +
                                      ", Model=" + CPUID.getCPUModel() + '/' +
                                      CPUID.getCPUExtendedModel());
    }

    private String identifyCPU() {
        // Use the CPUID brand string (Fn8000_0002-4) as primary identifier.
        // This is what AMD programmed into the CPU and is always correct.
        // Model number ranges are unreliable — AMD reuses them across product lines
        // (e.g. model 0x44 is both Granite Ridge and appears in Strix ranges).
        String brand = CPUID.getCPUModelName();
        if (brand != null && brand.length() > 0) {
            return brand;
        }
        // Fallback: model-based detection when brand string is unavailable
        return identifyCPUByModel();
    }

    private String identifyCPUByModel() {
        // http://en.wikipedia.org/wiki/Cpuid
        // #include "llvm/Support/Host.h", http://llvm.org/docs/doxygen/html/Host_8cpp_source.html
        String modelString = null;
        int family = CPUID.getCPUFamily();
        int model = CPUID.getCPUModel();
        if (family == 15) {
            family += CPUID.getCPUExtendedFamily();
            model += CPUID.getCPUExtendedModel() << 4;
        }

        switch (family) {
            // i486 class (Am486, 5x86)
            case 4: {
                switch (model) {
                    case 3:
                        modelString = "486 DX/2";
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
                    case 14:
                        modelString = "Am5x86-WT";
                        break;
                    case 15:
                        modelString = "Am5x86-WB";
                        break;
                    default:
                        modelString = "AMD 486/586 model " + model;
                        break;
                }
            }
            break;

            // i586 class (K5/K6/K6-2/K6-III)
            // ref: http://support.amd.com/TechDocs/20734.pdf
            case 5: {
                isK6Compatible = true;
                switch (model) {
                    case 0:
                        modelString = "K5/SSA5";
                        break;
                    case 1:
                    case 2:
                    case 3:
                        modelString = "K5";
                        break;
                    case 4:
                        isK6Compatible = false;
                        isGeodeCompatible = true;
                        modelString = "Geode GX1/GXLV/GXm";
                        break;
                    case 5:
                        isK6Compatible = false;
                        isGeodeCompatible = true;
                        modelString = "Geode GX2/LX";
                        break;
                    case 6:
                    case 7:
                        modelString = "K6";
                        break;
                    case 8:
                        isK6_2_Compatible = true;
                        modelString = "K6-2";
                        break;
                    case 9:
                        isK6_2_Compatible = true;
                        isK6_3_Compatible = true;
                        modelString = "K6-3";
                        break;
                    case 13:
                        isK6_2_Compatible = true;
                        modelString = "K6-2+ or K6-III+";
                        break;
                    default:
                        modelString = "AMD K5/K6 model " + model;
                        break;
                }
            }
            break;

            // i686 class (Athlon/Athlon XP/Duron/K7 Sempron)
            // ref: http://support.amd.com/TechDocs/20734.pdf
            case 6: {
                isK6Compatible = true;
                isK6_2_Compatible = true;
                isK6_3_Compatible = true;
                isAthlonCompatible = true;
                switch (model) {
                    case 0:
                    case 1:
                        modelString = "Athlon (250 nm)";
                        break;
                    case 2:
                        modelString = "Athlon (180 nm)";
                        break;
                    case 3:
                        modelString = "Duron";
                        break;
                    case 4:
                        modelString = "Athlon (Thunderbird)";
                        break;
                    case 6:
                        modelString = "Athlon (Palamino)";
                        break;
                    case 7:
                        modelString = "Duron (Morgan)";
                        break;
                    case 8:
                        modelString = "Athlon (Thoroughbred)";
                        break;
                    case 10:
                        modelString = "Athlon (Barton)";
                        break;
                    default:
                        modelString = "AMD Athlon/Duron model " + model;
                        break;
                }
            }
            break;

            // AMD64 class (A64/Opteron/A64 X2/K8 Sempron/Turion/Second-Generation Opteron/Athlon Neo)
            // ref: http://support.amd.com/TechDocs/33610.PDF
            case 15: {
                isK6Compatible = true;
                isK6_2_Compatible = true;
                isK6_3_Compatible = true;
                isAthlonCompatible = true;
                isAthlon64Compatible = true;
                switch (model) {
                    case 4:
                        modelString = "Athlon 64/Mobile XP-M";
                        break;
                    case 5:
                        modelString = "Athlon 64 FX Opteron";
                        break;
                    case 7:
                        modelString = "Athlon 64 FX (Sledgehammer S939, 130 nm)";
                        break;
                    case 8:
                        modelString = "Mobile A64/Sempron/XP-M";
                        break;
                    case 11:
                        modelString = "Athlon 64 (Clawhammer S939, 130 nm)";
                        break;
                    case 12:
                    case 14:
                        modelString = "Athlon 64/Sempron (Newcastle S754, 130 nm)";
                        break;
                    case 15:
                        modelString = "Athlon 64/Sempron (Clawhammer S939, 130 nm)";
                        break;
                        // everything below here was broken prior to 0.9.16
                    case 18:
                        modelString = "Sempron (Palermo, 90 nm)";
                        break;
                    case 20:
                        modelString = "Athlon 64 (Winchester S754, 90 nm)";
                        break;
                    case 23:
                        modelString = "Athlon 64 (Winchester S939, 90 nm)";
                        break;
                    case 24:
                        modelString = "Mobile A64/Sempron/XP-M (Winchester S754, 90 nm)";
                        break;
                    case 26:
                        modelString = "Athlon 64 (Winchester S939, 90 nm)";
                        break;
                    case 27:
                        modelString = "Athlon 64/Sempron (Winchester/Palermo 90 nm)";
                        break;
                    case 28:
                        modelString = "Sempron (Palermo, 90 nm)";
                        break;
                    case 31:
                        modelString = "Athlon 64/Sempron (Winchester/Palermo, 90 nm)";
                        break;
                    case 33:
                        modelString = "Dual-Core Opteron (Italy-Egypt S940, 90 nm)";
                        break;
                    case 35:
                        modelString = "Athlon 64 X2/A64 FX/Opteron (Toledo/Denmark S939, 90 nm)";
                        break;
                    case 36:
                        modelString = "Mobile A64/Turion (Lancaster/Richmond/Newark, 90 nm)";
                        break;
                    case 37:
                        modelString = "Opteron (Troy/Athens S940, 90 nm)";
                        break;
                    case 39:
                        modelString = "Athlon 64 (San Diego, 90 nm)";
                        break;
                    case 43:
                        modelString = "Athlon 64 X2 (Manchester, 90 nm)";
                        break;
                    case 44:
                        modelString = "Sempron/mobile Sempron (Palermo/Albany/Roma S754, 90 nm)";
                        break;
                    case 47:
                        modelString = "Athlon 64/Sempron (Venice/Palermo S939, 90 nm)";
                        break;
                    case 65:
                        modelString = "Second-Generaton Opteron (Santa Rosa S1207, 90 nm)";
                        break;
                    case 67:
                        modelString = "Athlon 64 X2/2nd-gen Opteron (Windsor/Santa Rosa, 90 nm)";
                        break;
                    case 72:
                        modelString = "Athlon 64 X2/Turion 64 X2 (Windsor/Taylor/Trinidad, 90 nm)";
                        break;
                    case 75:
                        modelString = "Athlon 64 X2 (Windsor, 90 nm)";
                        break;
                    case 76:
                        modelString = "Mobile A64/mobile Sempron/Turion (Keene/Trinidad/Taylor, 90 nm)";
                        break;
                    case 79:
                        modelString = "Athlon 64/Sempron (Orleans/Manila AM2, 90 nm)";
                        break;
                    case 93:
                        modelString = "Opteron Gen 2 (Santa Rosa, 90 nm)";
                        break;
                    case 95:
                        modelString = "A64/Sempron/mobile Sempron (Orleans/Manila/Keene, 90 nm)";
                        break;
                    case 104:
                        modelString = "Turion 64 X2 (Tyler S1, 65 nm)";
                        break;
                    case 107:
                        modelString = "Athlon 64 X2/Sempron X2/Athlon Neo X2 (Brisbane/Huron, 65 nm)";
                        break;
                    case 108:
                        modelString = "A64/Athlon Neo/Sempron/Mobile Sempron (Lima/Huron/Sparta/Sherman, 65 nm)";
                        break;
                    case 111:
                        modelString = "Neo/Sempron/mobile Sempron (Huron/Sparta/Sherman, 65 nm)";
                        break;
                    case 124:
                        modelString = "Athlon/Sempron/mobile Sempron (Lima/Sparta/Sherman, 65 nm)";
                        break;
                    case 127:
                        modelString = "A64/Athlon Neo/Sempron/mobile Sempron (Lima/Huron/Sparta/Sherman, 65 nm)";
                        break;
                    case 193:
                        modelString = "Athlon 64 FX (Windsor S1207 90 nm)";
                        break;
                    default:
                        modelString = "AMD Athlon/Duron/Sempron model " + model;
                        break;
                }
            }
            break;

            // Stars (Phenom II/Athlon II/Third-Generation Opteron/Opteron 4100 & 6100/Sempron 1xx)
            case 16: {
                isK6Compatible = true;
                isK6_2_Compatible = true;
                isK6_3_Compatible = true;
                isAthlonCompatible = true;
                isAthlon64Compatible = true;
                isK10Compatible = true;
                switch (model) {
                    case 2:
                        modelString = "Phenom / Athlon / Opteron Gen 3 (Barcelona/Agena/Toliman/Kuma, 65 nm)";
                        break;
                    case 4:
                        modelString = "Phenom II / Opteron Gen 3 (Shanghai/Deneb/Heka/Callisto, 45 nm)";
                        break;
                    case 5:
                        modelString = "Athlon II X2/X3/X4 (Regor/Rana/Propus AM3, 45 nm)";
                        break;
                    case 6:
                        modelString = "Mobile Athlon II / Turion II / Phenom II / Sempron/V-series (Regor/Caspian/Champlain, 45 nm)";
                        break;
                    case 8:
                        modelString = "Six-Core Opteron / Opteron 4100 series (Istanbul/Lisbon, 45 nm)";
                        break;
                    case 9:
                        modelString = "Opteron 6100 series (Magny-Cours G34, 45 nm)";
                        break;
                    case 10:
                        modelString = "Phenom II X4/X6 (Zosma/Thuban AM3, 45 nm)";
                        break;
                    default:
                        modelString = "AMD Athlon/Opteron model " + model;
                        break;
                }
            }
            break;

            // K8 mobile+HT3 (Turion X2/Athlon X2/Sempron)
            case 17: {
                isK6Compatible = true;
                isK6_2_Compatible = true;
                isK6_3_Compatible = true;
                isAthlonCompatible = true;
                isAthlon64Compatible = true;
                switch (model) {
                    case 3:
                        modelString = "AMD Turion X2 / Athlon X2 / Sempron (Lion/Sable, 65 nm)";
                        break;
                    default:
                        modelString = "AMD Athlon / Turion / Sempron model " + model;
                        break;
                }
            }
            break;

            // APUs
            // http://en.wikipedia.org/wiki/List_of_AMD_Accelerated_Processing_Unit_microprocessors
            // 1st gen Llano high perf / Brazos low power
            // 2nd gen Trinity high perf / Brazos 2 low power
            // 3rd gen Kaveri high perf / Kabini/Temash low power
            case 18: {
                isK6Compatible = true;
                isK6_2_Compatible = true;
                isK6_3_Compatible = true;
                isAthlonCompatible = true;
                isAthlon64Compatible = true;
                modelString = "AMD APU model " + model;
            }
            break;

            // Bobcat
            case 20: {
                isK6Compatible = true;
                isK6_2_Compatible = true;
                isK6_3_Compatible = true;
                isAthlonCompatible = true;
                isAthlon64Compatible = true;
                isBobcatCompatible = true;
                switch (model) {
                    case 1:
                        // Case 3 is uncertain but most likely a Bobcat APU
                    case 3:
                        modelString = "AMD Bobcat APU";
                        break;
                    default:
                        modelString = "AMD Bobcat APU model " + model;
                        break;
                }
            }
            break;

            // Bulldozer
            case 21: {
                isK6Compatible = true;
                isK6_2_Compatible = true;
                isK6_3_Compatible = true;
                isAthlonCompatible = true;
                isAthlon64Compatible = true;
                isBulldozerCompatible = true;
                if (!this.hasAVX()) {
                    modelString = "Bulldozer";
                    break;
                }
                if (model >= 0x50 && model <= 0x5F) {
                    isPiledriverCompatible = true;
                    isSteamrollerCompatible = true;
                    isExcavatorCompatible = true;
                    modelString = "Excavator";
                } else if (model >= 0x30 && model <= 0x3F) {
                    isPiledriverCompatible = true;
                    isSteamrollerCompatible = true;
                    modelString = "Steamroller";
                } else if ((model >= 0x10 && model <= 0x1F) || hasTBM()) {
                    isPiledriverCompatible = true;
                    modelString = "Piledriver";
                } else {
                    modelString = "Bulldozer";
                }
            }
            break;

            //  Jaguar
            case 22: {
                isK6Compatible = true;
                isK6_2_Compatible = true;
                isK6_3_Compatible = true;
                isAthlonCompatible = true;
                isAthlon64Compatible = true;
                isBobcatCompatible = true;
                isJaguarCompatible = true;
                modelString = "Jaguar";
            }
            break;

            // Family 17h (0x17) = Zen / Zen+ / Zen2
            case 23: {
                isK6Compatible = true;
                isK6_2_Compatible = true;
                isK6_3_Compatible = true;
                isAthlonCompatible = true;
                isAthlon64Compatible = true;
                isPiledriverCompatible = true;
                isSteamrollerCompatible = true;
                isExcavatorCompatible = true;
                isBulldozerCompatible = true;
                isZenCompatible = true;
                isZen2Compatible = true;
                // https://en.wikichip.org/wiki/amd/codenames
                // https://wikiidevi.wi-cat.ru/AMD/CPU
                if (model <= 0x0F) {
                    modelString = "AMD Epyc 7001 / Ryzen (Naples/Summit Ridge/Whitehaven, Zen)";
                } else if (model <= 0x1F) {
                    modelString = "AMD Ryzen 2000 APU (Raven Ridge, Zen)";
                } else if (model <= 0x2F) {
                    modelString = "AMD Ryzen (Bristol Ridge/Picasso, Zen/Zen+)";
                } else if (model <= 0x3F) {
                    modelString = "AMD Epyc 7002 / Threadripper 3000 (Rome/Castle Peak, Zen2)";
                } else if (model <= 0x4F) {
                    modelString = "AMD Ryzen (Cardinal, Zen2)";
                } else if (model <= 0x6F) {
                    modelString = "AMD Ryzen 4000 APU (Renoir, Zen2)";
                } else if (model <= 0x7F) {
                    modelString = "AMD Ryzen 3000 (Matisse, Zen2)";
                } else if (model <= 0x8F) {
                    modelString = "AMD Ryzen (Project X, Zen2)";
                } else if (model <= 0x9F) {
                    modelString = "AMD Ryzen (Van Gogh, Zen2)";
                } else if (model <= 0xAF) {
                    modelString = "AMD Ryzen (Mendocino, Zen2)";
                } else {
                    modelString = "AMD Ryzen / Epyc Zen 1/2 model " + model;
                }
            }
                break;

            // Family 19h (0x19) = Zen3 / Zen4
            // https://en.wikichip.org/wiki/amd/codenames
            // https://github.com/llvm/llvm-project/blob/main/llvm/lib/Support/Host.cpp
            case 25: {
                isK6Compatible = true;
                isK6_2_Compatible = true;
                isK6_3_Compatible = true;
                isAthlonCompatible = true;
                isAthlon64Compatible = true;
                isPiledriverCompatible = true;
                isSteamrollerCompatible = true;
                isExcavatorCompatible = true;
                isBulldozerCompatible = true;
                isZenCompatible = true;
                isZen2Compatible = true;
                isZen3Compatible = true;
                if (model <= 0x0F) {
                    modelString = "AMD Epyc 7003 / Threadripper 5000 (Milan/Chagall, Zen3)";
                } else if (model <= 0x1F) {
                    isZen4Compatible = true;
                    modelString = "AMD Epyc 9004 / Threadripper 7000 (Genoa/Storm Peak, Zen4)";
                } else if (model <= 0x2F) {
                    modelString = "AMD Ryzen 5000 (Vermeer, Zen3)";
                } else if (model <= 0x3F) {
                    modelString = "AMD Epyc (Badami, Zen3)";
                } else if (model <= 0x4F) {
                    modelString = "AMD Ryzen 6000 APU (Rembrandt, Zen3+)";
                } else if (model <= 0x5F) {
                    modelString = "AMD Ryzen 5000 APU (Cezanne, Zen3)";
                } else if (model <= 0x6F) {
                    isZen4Compatible = true;
                    modelString = "AMD Ryzen 7000 (Raphael, Zen4)";
                } else if (model <= 0x77) {
                    isZen4Compatible = true;
                    modelString = "AMD Ryzen 7040/8040 APU (Phoenix, Zen4)";
                } else if (model <= 0x7F) {
                    isZen4Compatible = true;
                    modelString = "AMD Ryzen 8040 APU (Hawk Point, Zen4)";
                } else if (model <= 0x9F) {
                    modelString = "AMD Epyc (unknown model " + model + ", Zen3/4)";
                } else if (model <= 0xAF) {
                    isZen4Compatible = true;
                    modelString = "AMD Epyc 4004 / Siena (Genoa-X, Zen4)";
                } else {
                    modelString = "AMD Ryzen / Epyc Zen 3/4 model " + model;
                }
            }
                break;

            // Hygon Dhyana (untested)
            // http://lkml.iu.edu/hypermail/linux/kernel/1806.1/00730.html
            case 24: {
                isK6Compatible = true;
                isK6_2_Compatible = true;
                isK6_3_Compatible = true;
                isAthlonCompatible = true;
                isAthlon64Compatible = true;
                // Pending testing of the bulldozer jbigi
                //isPiledriverCompatible = true;
                //isSteamrollerCompatible = true;
                //isExcavatorCompatible = true;
                //isBulldozerCompatible = true;
                modelString = "Hygon Dhyana model " + model;
            }
            break;

            // Family 1Ah (0x1A) = Zen5 / Zen5c
            // Brand string (CPUID 0x80000002-4) is the primary identifier in identifyCPU().
            // This fallback only runs when the brand string is unavailable (very rare).
            // Model ranges from LLVM Host.cpp + Linux kernel amd.c + InstLatx64 CPUID dumps.
            // Only show specific codenames for ranges confirmed by multiple sources;
            // use generic microarchitecture name for uncertain ranges.
            case 26: {
                isK6Compatible = true;
                isK6_2_Compatible = true;
                isK6_3_Compatible = true;
                isAthlonCompatible = true;
                isAthlon64Compatible = true;
                isPiledriverCompatible = true;
                isSteamrollerCompatible = true;
                isExcavatorCompatible = true;
                isBulldozerCompatible = true;
                isZenCompatible = true;
                isZen2Compatible = true;
                isZen3Compatible = true;
                isZen4Compatible = true;
                isZen5Compatible = true;
                // Confirmed by LLVM + InstLatx64 B40F00 + Linux kernel + user's 9900X
                if (model <= 0x0F) {
                    modelString = "AMD Epyc 9005 (Turin, Zen5)";
                } else if (model <= 0x1F) {
                    modelString = "AMD Epyc 9005 (Turin Dense, Zen5)";
                } else if (model <= 0x2F) {
                    modelString = "AMD Ryzen AI 300 (Strix Point, Zen5)";
                } else if (model <= 0x3F) {
                    // LLVM: Strix 2/3; Linux kernel omits this range from Zen 5
                    modelString = "AMD Ryzen AI 300 (Zen5)";
                } else if (model <= 0x4F) {
                    modelString = "AMD Ryzen 9000 (Granite Ridge, Zen5)";
                } else if (model <= 0x5F) {
                    // Confirmed by InstLatx64 B50F00 — this is Zen 6, NOT Zen 5
                    modelString = "AMD Ryzen (Zen6)";
                } else if (model <= 0x6F) {
                    modelString = "AMD Ryzen AI 300 (Krackan Point, Zen5)";
                } else if (model <= 0x77) {
                    modelString = "AMD Ryzen AI Max 300 (Strix Halo, Zen5)";
                } else if (model <= 0x7F) {
                    // Linux kernel includes 0x70-0x7F as Zen 5; LLVM only 0x70-0x77
                    modelString = "AMD Ryzen (Zen5)";
                } else if (model <= 0xCF) {
                    // LLVM: Zen 6 range (Weisshorn and beyond)
                    modelString = "AMD Ryzen (Zen6)";
                } else if (model <= 0xD7) {
                    // LLVM: Annapurna; Linux kernel doesn't mention this range
                    modelString = "AMD Epyc (Zen5)";
                } else if (model <= 0xE7) {
                    modelString = "AMD Ryzen (Zen6)";
                } else {
                    modelString = "AMD Ryzen / Epyc Zen5 model " + model;
                }
            }
                break;
        }
        return modelString;
    }

}
