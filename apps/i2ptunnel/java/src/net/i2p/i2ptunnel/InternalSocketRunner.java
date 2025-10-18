package net.i2p.i2ptunnel;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.i2p.util.I2PAppThread;
import net.i2p.util.InternalServerSocket;
import net.i2p.util.Log;

/**
 * Thread which listens for internal in-JVM socket connections on a specified port.
 * Uses a thread pool executor to asynchronously dispatch each accepted socket connection
 * to the client handler, enabling concurrent handling of multiple internal connections.
 *
 * This design avoids blocking the accept thread and supports high throughput and concurrency.
 *
 * @author zzz
 * @since 0.7.9
 */
class InternalSocketRunner extends I2PAppThread {
    private final I2PTunnelClientBase client;
    private final int port;
    private ServerSocket ss;
    private volatile boolean open;
    private final ExecutorService executor;
    private final Log log = new Log(InternalSocketRunner.class);

    /**
     * Creates the InternalSocketRunner for the given client.
     * Initializes a cached thread pool to handle multiple concurrent internal connections.
     * Does not start the thread; caller must invoke start().
     *
     * @param client the client to which accepted connections are delegated
     */
    InternalSocketRunner(I2PTunnelClientBase client) {
        super("Internal socket port " + client.getLocalPort());
        setDaemon(true);
        this.client = client;
        this.port = client.getLocalPort();
        // Cached thread pool dynamically expands and contracts with load,
        // suitable for varying number of internal connections with minimal overhead.
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("InternalSocketHandler-" + port);
            return t;
        });
    }

    /**
     * Main thread loop that accepts internal socket connections and dispatches
     * them asynchronously to the client handler using the executor.
     */
    @Override
    public final void run() {
        try {
            // Create internal server socket listening on client port
            this.ss = new InternalServerSocket(this.port);
            this.open = true;

            // Continuously accept incoming internal connections while open
            while (this.open) {
                try {
                    // Blocking accept call to wait for internal client connection
                    Socket s = this.ss.accept();

                    // Dispatch connection asynchronously to avoid blocking accept loop
                    executor.execute(() -> {
                        try {
                            client.manageConnection(s);
                        } catch (Exception e) {
                            log.error("Exception managing internal connection", e);
                            try { s.close(); } catch (IOException ignored) {}
                        }
                    });
                } catch (IOException ioe) {
                    if (this.open) {
                        log.error("Error accepting internal connection on port " + this.port, ioe);
                        stopRunning();
                    }
                }
            }
        } catch (IOException ex) {
            if (this.open) {
                log.error("Error initializing internal server socket on port " + this.port, ex);
                stopRunning();
            }
        } finally {
            shutdownExecutor();
        }
    }

    /**
     * Stops the accept loop and closes the internal server socket,
     * releasing resources and terminating the thread.
     */
    void stopRunning() {
        if (this.open) {
            this.open = false;
            try {
                if (this.ss != null) {
                    this.ss.close();
                }
            } catch (IOException ex) {
                // Ignore exceptions on close
            }
            shutdownExecutor();
        }
    }

    /**
     * Gracefully shuts down the executor, disallowing new tasks.
     * Attempts to finish all submitted tasks before terminating.
     */
    private void shutdownExecutor() {
        executor.shutdown();
    }
}
