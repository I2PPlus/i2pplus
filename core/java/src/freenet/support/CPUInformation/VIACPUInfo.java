package freenet.support.CPUInformation;

/**
 *  Moved out of CPUID.java
 *  @since 0.8.7
 */
public interface VIACPUInfo extends CPUInfo{

    /**
     * Test whether the installed CPU is at least an 'c3'.
     *
     * @return true if the CPU present in the machine is at least an 'c3' CPU
     */
    public boolean IsC3Compatible();
    /**
     * Test whether the installed CPU is at least a 'nano'.
     *
     * @return true if the CPU present in the machine is at least an 'nano' CPU
     */
    public boolean IsNanoCompatible();



}
