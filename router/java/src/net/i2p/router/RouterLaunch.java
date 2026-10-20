package net.i2p.router;

/**
 * This is the class called by the runplain.sh script on linux
 * and the {@code i2p.exe} launcher on Windows.
 * (i.e. no wrapper)
 * <p>
 * Setup of wrapper.log file is moved to WorkingDir.java
 * Until WorkingDir is called, the existing stdout / stderr will be used.
 * <p>
 * Not recommended for embedded use. Instantiate Router() yourself.
 *
 */
public class RouterLaunch {

    /**
     * Launching is entirely {@link #main}'s job - it hands the arguments to Router - so an
     * instance starts nothing and carries no state.
     */
    public RouterLaunch() {}

    /**
     * Usage: RouterLaunch [rebuild]
     * No other options allowed, for now.
     *
     * @param args null ok
     * @throws IllegalArgumentException if the arguments are not accepted by Router.main
     */
    public static void main(String[] args) {
	Router.main(args);
    }
}
