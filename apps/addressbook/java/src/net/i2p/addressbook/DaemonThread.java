// License: MIT. See docs/LICENSES.md
package net.i2p.addressbook;

import java.util.Properties;
import net.i2p.I2PAppContext;
import net.i2p.client.naming.NamingServiceUpdater;
import net.i2p.util.I2PAppThread;

/**
 * A thread that waits five minutes, then runs the addressbook daemon.
 *
 * @author Ragnarok
 *
 */
public class DaemonThread extends I2PAppThread implements NamingServiceUpdater {

    private final String[] args;
    private final Daemon daemon;

    /**
     * Construct a DaemonThread with the command line arguments args.
     * @param args
     * A String array to pass to Daemon.main().
     */
    public DaemonThread(String[] args) {
        this.args = args;
        daemon = new Daemon();
    }

    @Override
    public void run() {
        I2PAppContext.getGlobalContext().namingService().registerUpdater(this);
        try {
            if (args != null && args.length > 0 && args[0].equals("test"))
                daemon.test();
            else
                daemon.run(args);
        } finally {
            I2PAppContext.getGlobalContext().namingService().unregisterUpdater(this);
        }
    }

    public void halt() {
        daemon.stop();
        interrupt();
    }

    /**
     *  The NamingServiceUpdater interface.
     *  While this may be called directly, the recommended way
     *  is to call I2PAppContext.namingService().requestUpdate(Properties)
     *  which will call this.
     *
     *  @param options ignored, may be null
     *  @since 0.8.7
     */
    public void update(Properties options) {
        interrupt();
    }
}
