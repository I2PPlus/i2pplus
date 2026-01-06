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
      * Checks if the CPU is at least a 'k6' CPU.
      * @return true if the CPU present in the machine is at least an 'k6' CPU
      */
     public boolean IsK6Compatible();
     /**
      * Checks if the CPU is at least a 'k6-2' CPU.
      * @return true if the CPU present in the machine is at least an 'k6-2' CPU
      */
    public boolean IsK6_2_Compatible();
     /**
      * Checks if the CPU is at least a 'k6-3' CPU.
      * @return true if the CPU present in the machine is at least an 'k6-3' CPU
      */
     public boolean IsK6_3_Compatible();
     /**
      * Checks if the CPU is at least a 'geode' CPU.
      * @return true if the CPU present in the machine is at least an 'geode' CPU
      */
    boolean IsGeodeCompatible();
     /**
      * Checks if the CPU is at least a 'k7' CPU (Athlon, Duron etc. and better).
      * @return true if the CPU present in the machine is at least an 'k7' CPU (Atlhon, Duron etc. and better)
      */
     public boolean IsAthlonCompatible();
     /**
      * Checks if the CPU is at least a 'k8' CPU (Athlon 64, Opteron etc. and better).
      * @return true if the CPU present in the machine is at least an 'k8' CPU (Atlhon 64, Opteron etc. and better)
      */
    public boolean IsAthlon64Compatible();
     /**
      * Checks if the CPU is at least a 'k10' CPU.
      * @return true if the CPU present in the machine is at least an 'k10' CPU
      * @since 0.9.26
      */
     public boolean IsK10Compatible();
     /**
      * Checks if the CPU is at least a 'bobcat' CPU.
      * @return true if the CPU present in the machine is at least an 'bobcat' CPU
      */
	public boolean IsBobcatCompatible();
     /**
      * Checks if the CPU is at least a 'jaguar' CPU.
      * @return true if the CPU present in the machine is at least an 'jaguar' CPU
      * @since 0.9.26
      */
 	public boolean IsJaguarCompatible();
     /**
      * Checks if the CPU is at least a 'bulldozer' CPU.
      * @return true if the CPU present in the machine is at least a 'bulldozer' CPU
      */
	public boolean IsBulldozerCompatible();
     /**
      * Checks if the CPU is at least a 'piledriver' CPU.
      * @return true if the CPU present in the machine is at least a 'piledriver' CPU
      * @since 0.9.26
      */
 	public boolean IsPiledriverCompatible();
     /**
      * Checks if the CPU is at least a 'steamroller' CPU.
      * @return true if the CPU present in the machine is at least a 'steamroller' CPU
      * @since 0.9.26
      */
	public boolean IsSteamrollerCompatible();
     /**
      * Checks if the CPU is at least an 'excavator' CPU.
      * @return true if the CPU present in the machine is at least a 'excavator' CPU
      * @since 0.9.26
      */
 	public boolean IsExcavatorCompatible();

     /**
      * Checks if the CPU is at least a Zen family CPU.
      * @return true if the CPU present in the machine is at least a Zen family CPU
      * @since 0.9.48
      */
	public boolean IsZenCompatible();

     /**
      * Checks if the CPU is at least a Zen2 family CPU.
      * @return true if the CPU present in the machine is at least a Zen2 family CPU
      * @since 0.9.48
      */
	public boolean IsZen2Compatible();

}
