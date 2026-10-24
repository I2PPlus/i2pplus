package freenet.support.CPUInformation;

/**
 * Moved out of CPUID.java
 * @since 0.8.7
 */
class VIAInfoImpl extends CPUIDCPUInfo implements VIACPUInfo {

    /**
     * The model string and the C3 and Nano flags are computed once by the static
     * initialiser, so an instance adds nothing to them.
     */
    VIAInfoImpl() {}

    /** True if the CPU takes C3 instructions. */
    private static boolean isC3Compatible;
    /** True if the CPU takes Nano instructions. */
    private static boolean isNanoCompatible;

    /**
     * The CPU's model string, null if it could not be read; a non-null model
     * means the CPU was correctly identified as a VIA.
     */
    private static final String smodel = identifyCPU();

    public boolean IsC3Compatible(){ return isC3Compatible; }

    public boolean IsNanoCompatible(){ return isNanoCompatible; }

    public String getCPUModelString()
    {
        if (smodel != null)
            return smodel;
        throw new UnknownCPUException("Unknown VIA CPU; Family="+CPUID.getCPUFamily() + '/' + CPUID.getCPUExtendedFamily()+
                                      ", Model="+CPUID.getCPUModel() + '/' + CPUID.getCPUExtendedModel());
    }


    public boolean hasX64()
    {
        return false;
    }


    /**
     * Reads the CPU's model string from /proc.
     *
     * @return the model, null if it cannot be read
     */
    private static String identifyCPU()
    {
        // http://en.wikipedia.org/wiki/Cpuid
        String modelString = null;
        int family = CPUID.getCPUFamily();
        int model = CPUID.getCPUModel();
        if (family == 15) {
            family += CPUID.getCPUExtendedFamily();
            model += CPUID.getCPUExtendedModel() << 4;
        }

        if (family == 6) {
            isC3Compatible = true; // Possibly not optimal
            switch (model) {
                case 5:
                    modelString = "Cyrix M2";
                    break;
                case 6:
                    modelString = "C5 A/B";
                    break;
                case 7:
                    modelString = "C5 C";
                    break;
                case 8:
                    modelString = "C5 N";
                    break;
                case 9:
                    modelString = "C5 XL/P";
                    break;
                case 10:
                    modelString = "C5 J";
                    break;
                case 15:
                    isNanoCompatible = true;
                    modelString = "Nano";
                    break;
                default:
                    modelString = "Via model " + model;
                    break;
            }
        }
        return modelString;
    }
}
