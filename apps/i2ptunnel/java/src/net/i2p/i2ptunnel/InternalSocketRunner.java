package net.i2p.i2ptunnel;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import net.i2p.util.I2PAppThread;
import net.i2p.util.InternalServerSocket;
import net.i2p.util.Log;

/*
 * Listens for in-JVM connections on internal socket.
 *
 * @since 0.7.9
 */
/** Listens for in-JVM connections on internal socket */
class InternalSocketRunner extends I2PAppThread {
    private final I2PTunnelClientBase client;
    private final int port;
    private final String label;
    private ServerSocket ss;
    private volatile boolean open;

    /**
     * Does not start the runner, caller must call start()
     *
     * @param client the tunnel client that handles each socket accepted internally
     */
    InternalSocketRunner(I2PTunnelClientBase client) {
        this(client, client.getClass().getSimpleName());
    }

    /**
     * Does not start the runner, caller must call start()
     *
     * @param client the tunnel client that handles each socket accepted internally
     * @param label service name shown for this port on the debug page
     */
    InternalSocketRunner(I2PTunnelClientBase client, String label) {
        super("Internal:" + client.getLocalPort());
        setDaemon(true);
        this.client = client;
        this.port = client.getLocalPort();
        this.label = label;
    }

    /**
     * Accepts internal sockets in a loop until closed, handing each to the tunnel client.
     */
    @Override
    public final void run() {
        try {
            this.ss = new InternalServerSocket(this.port, this.label);
            this.open = true;
            while (this.open) {
                Socket s = this.ss.accept();
                this.client.manageConnection(s);
            }
        } catch (IOException ex) {
            if (this.open) {
                Log log = new Log(InternalSocketRunner.class);
                log.error("Error listening for internal connections on port " + this.port, ex);
                stopRunning();
            }
        }
    }

    /** Stop the server socket and mark as closed */
    void stopRunning() {
        if (this.open) {
            this.open = false;
            try {
                this.ss.close();
            } catch (IOException ex) { /* ignored */ }
        }
    }
}
