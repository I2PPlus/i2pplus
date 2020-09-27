/*
 * Created on Jul 17, 2004
 *
 * free (adj.): unencumbered; not under the control of others
 * Written by Iakin in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might. Use at your own risk.
 */
package freenet.support.CPUInformation;

/**
 * An interface for classes that provide lowlevel information about AMD CPU's
 *
 * @author Iakin
 */
public interface AMDCPUInfo extends CPUInfo {
    /**
     * @return true if the CPU present in the machine is at least an 'k6' CPU
     */
    public boolean IsK6Compatible();
    /**
     * @return true if the CPU present in the machine is at least an 'k6-2' CPU
     */
    public boolean IsK6_2_Compatible();
    /**
     * @return true if the CPU present in the machine is at least an 'k6-3' CPU
     */
    public boolean IsK6_3_Compatible();
    /**
     * @return true if the CPU present in the machine is at least an 'geode' CPU
     */
    boolean IsGeodeCompatible();
    /**
     * @return true if the CPU present in the machine is at least an 'k7' CPU (Atlhon, Duron etc. and better)
     */
    public boolean IsAthlonCompatible();
    /**
     * @return true if the CPU present in the machine is at least an 'k8' CPU (Atlhon 64, Opteron etc. and better)
     */
    public boolean IsAthlon64Compatible();
    /** 
     * @return true if the CPU present in the machine is at least an 'k10' CPU
     * @since 0.9.26
     */
    public boolean IsK10Compatible();
    /**
     * @return true if the CPU present in the machine is at least an 'bobcat' CPU
     */
	public boolean IsBobcatCompatible();
    /**
     * @return true if the CPU present in the machine is at least an 'jaguar' CPU
     * @since 0.9.26
     */
	public boolean IsJaguarCompatible();
    /**
     * @return true if the CPU present in the machine is at least a 'bulldozer' CPU
     */
	public boolean IsBulldozerCompatible();
    /**
     * @return true if the CPU present in the machine is at least a 'piledriver' CPU
     * @since 0.9.26
     */
	public boolean IsPiledriverCompatible();
    /**
     * @return true if the CPU present in the machine is at least a 'steamroller' CPU
     * @since 0.9.26
     */
	public boolean IsSteamrollerCompatible();
    /**
     * @return true if the CPU present in the machine is at least a 'excavator' CPU
     * @since 0.9.26
     */
	public boolean IsExcavatorCompatible();

    /**
     * @return true if the CPU present in the machine is at least a Zen family CPU
     * @since 0.9.48
     */
	public boolean IsZenCompatible();

    /**
     * @return true if the CPU present in the machine is at least a Zen2 family CPU
     * @since 0.9.48
     */
	public boolean IsZen2Compatible();

}
