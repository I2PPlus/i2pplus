package net.i2p.i2ptunnel;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLException;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.util.ByteCache;
import net.i2p.util.Clock;
import net.i2p.util.I2PAppThread;
import net.i2p.util.InternalSocket;
import net.i2p.util.Log;

/**
 * I2PTunnelOutproxyRunner forwards data bidirectionally between two connected sockets:
 * one representing an external socket connection and the other an I2P socket connection.
 * It continuously transfers data until either side closes the connection or an error occurs.
 *
 * The class manages two internal forwarding threads to handle each direction of the data flow,
 * maintains counters of bytes sent and received, and allows optional initial data to be sent
 * on either socket upon startup.
 *
 * A user-provided callback can be invoked if no data (beyond initial data) is received before
 * the connection closes, enabling handling of timeouts or failures.
 *
 * This class is designed for internal use within the I2P tunnel infrastructure and is not
 * maintained as a stable public API.
 *
 * @since 0.9.11
 */
public class I2PTunnelOutproxyRunner extends I2PAppThread {
    protected final Log _log;

    private static final AtomicLong __runnerId = new AtomicLong();
    private final long _runnerId;

    /** Max bytes streamed in a packet - smaller packets might be filled up to this size */
    private static final int MAX_PACKET_SIZE = 4 * 1024;
    private static final int NETWORK_BUFFER_SIZE = MAX_PACKET_SIZE * 8;

    private final Socket s;
    private final Socket i2ps;
    private final Object slock;

    // Latch to await completion of forwarding threads
    private final CountDownLatch finishLatch = new CountDownLatch(2);

    private final byte[] initialI2PData;
    private final byte[] initialSocketData;

    /** When the runner started up */
    private final long startedOn;

    /**
     * Called if no data (except initial) is received and timeout occurs.
     */
    private final I2PTunnelRunner.FailCallback onTimeout;

    // Use AtomicLong to safely count totals across threads
    private final AtomicLong totalSent = new AtomicLong(0);
    private final AtomicLong totalReceived = new AtomicLong(0);

    private static final AtomicLong __forwarderId = new AtomicLong();

    /**
     * Constructs the OutproxyRunner
     *
     * Does not start itself. Caller must call start().
     *
     * @param s external socket connection
     * @param i2ps I2P socket connection
     * @param slock socket lock object, non-null
     * @param initialI2PData initial data to send to I2P side, nullable
     * @param initialSocketData initial data to send to socket side, nullable
     * @param onTimeout fail callback if no data received (except initial), nullable
     */
    public I2PTunnelOutproxyRunner(Socket s, Socket i2ps, Object slock, byte[] initialI2PData,
                                   byte[] initialSocketData, I2PTunnelRunner.FailCallback onTimeout) {
        this.s = s;
        this.i2ps = i2ps;
        this.slock = slock;
        this.initialI2PData = initialI2PData;
        this.initialSocketData = initialSocketData;
        this.onTimeout = onTimeout;
        startedOn = Clock.getInstance().now();
        _log = I2PAppContext.getGlobalContext().logManager().getLog(getClass());
        if (_log.shouldInfo()) {
            _log.info("OutproxyRunner started");
        }
        _runnerId = __runnerId.incrementAndGet();
        setName("OutproxyRunner " + _runnerId);
    }

    /**
     * Get when this runner started transferring data
     * @return timestamp in ms
     */
    public long getStartedOn() {
        return startedOn;
    }

    protected InputStream getSocketIn() throws IOException {
        return s.getInputStream();
    }

    protected OutputStream getSocketOut() throws IOException {
        return s.getOutputStream();
    }

    @Override
    public void run() {
        try (Socket autoCloseS = s; Socket autoCloseI2PS = i2ps;
             InputStream in = (s instanceof InternalSocket) ? s.getInputStream() :
                     new BufferedInputStream(s.getInputStream(), 2 * NETWORK_BUFFER_SIZE);
             OutputStream out = s.getOutputStream();
             InputStream i2pin = i2ps.getInputStream();
             OutputStream i2pout = i2ps.getOutputStream()) {

            if (initialI2PData != null) {
                i2pout.write(initialI2PData);
                i2pout.flush();
            }
            if (initialSocketData != null) {
                out.write(initialSocketData);
                // Do not increment totalReceived here for initial data
            }

            if (_log.shouldDebug()) {
                _log.debug("Initial data (" + (initialI2PData != null ? initialI2PData.length : 0)
                        + " bytes) written to the outproxy, "
                        + (initialSocketData != null ? initialSocketData.length : 0)
                        + " bytes written to the socket, starting forwarders...");
            }

            Thread t1 = new StreamForwarder(in, i2pout, true);
            Thread t2 = new StreamForwarder(i2pin, out, false);
            t1.start();
            t2.start();

            // Await both forwarder threads completing
            finishLatch.await();

            if (_log.shouldDebug()) {
                _log.debug("Forwarders completed, closing connections and evaluating timeout job");
            }

            if (onTimeout != null) {
                if (_log.shouldDebug()) {
                    _log.debug("Runner has a timeout job, totalReceived = " + totalReceived.get()
                            + " totalSent = " + totalSent.get() + " job = " + onTimeout);
                }
                if (totalReceived.get() <= 0) {
                    onTimeout.onFail(null);
                }
            }
        } catch (InterruptedException ex) {
            // Preserve interrupt status
            Thread.currentThread().interrupt();
            if (_log.shouldError())
                _log.error("Interrupted (" + ex.getMessage() + ")");
        } catch (SSLException she) {
            _log.error("SSL error", she);
        } catch (IOException ex) {
            if (_log.shouldDebug())
                _log.debug("Error forwarding (" + ex.getMessage() + ")");
        } catch (IllegalStateException ise) {
            if (_log.shouldWarn())
                _log.warn("gnu?", ise);
        } catch (RuntimeException e) {
            if (_log.shouldError())
                _log.error("Internal error", e);
        }
    }

    /**
     * Called to signal an error occurred and notify waiting threads.
     */
    public void errorOccurred() {
        while (finishLatch.getCount() > 0) {
            finishLatch.countDown();
        }
    }

    /**
     * Forwards data from InputStream to OutputStream.
     * Flushes are batched by checking available bytes without forced sleeps.
     */
    private class StreamForwarder extends I2PAppThread {
        private final InputStream in;
        private final OutputStream out;
        private final String direction;
        private final boolean _toI2P;
        private final ByteCache _cache;

        /**
         * Constructs a forwarder thread for one data direction.
         * @param in InputStream to read from
         * @param out OutputStream to write to
         * @param toI2P true if forwarding toward I2P side
         */
        private StreamForwarder(InputStream in, OutputStream out, boolean toI2P) {
            this.in = in;
            this.out = out;
            this._toI2P = toI2P;
            this.direction = toI2P ? "[To outproxy]" : "[From outproxy]";
            this._cache = ByteCache.getInstance(32, NETWORK_BUFFER_SIZE);
            setName("OutproxyForwarder " + _runnerId + '.' + __forwarderId.incrementAndGet());
        }

        @Override
        public void run() {
            String from = "todo";  // Can be improved with socket info
            String to = "todo";

            if (_log.shouldDebug()) {
                _log.debug(direction + " Forwarding between [" + from + "] and [" + to + "]");
            }

            ByteArray ba = _cache.acquire();
            byte[] buffer = ba.getData();
            try {
                int len;
                // Batch flush every 64KB or when no more data available
                int flushedSinceLast = 0;
                final int FLUSH_THRESHOLD = 64 * 1024;

                while ((len = in.read(buffer)) != -1) {
                    if (len > 0) {
                        out.write(buffer, 0, len);
                        if (_toI2P)
                            totalSent.addAndGet(len);
                        else
                            totalReceived.addAndGet(len);
                        flushedSinceLast += len;
                    }

                    // Flush if no more data available or flushed enough
                    if (in.available() == 0 || flushedSinceLast >= FLUSH_THRESHOLD) {
                        out.flush();
                        flushedSinceLast = 0;
                    }
                }
            } catch (SocketException ex) {
                if (_log.shouldDebug()) {
                    _log.debug(direction + " Socket closed - error reading/writing (" + ex.getMessage() + ")");
                }
            } catch (InterruptedIOException ex) {
                if (_log.shouldWarn())
                    _log.warn(direction + " Closing connection due to timeout (" + ex.getMessage() + ")");
                // Restore interrupt state
                Thread.currentThread().interrupt();
            } catch (IOException ex) {
                if (_log.shouldWarn() && !Thread.currentThread().isInterrupted()) {
                    _log.warn(direction + " Error forwarding (" + ex.getMessage() + ")");
                }
            } finally {
                _cache.release(ba);

                try {
                    in.close();
                } catch (IOException ex) {
                    if (_log.shouldWarn())
                        _log.warn(direction + " Error closing input stream (" + ex.getMessage() + ")");
                }
                try {
                    // If onTimeout is set, don't close output stream from socket side if no data received
                    if (!(onTimeout != null && !_toI2P && totalReceived.get() <= 0)) {
                        out.close();
                    } else if (_log.shouldInfo()) {
                        _log.info(direction + " Not closing stream to write the error message...");
                    }
                } catch (IOException ioe) {
                    if (_log.shouldWarn())
                        _log.warn(direction + " Error closing output stream (" + ioe.getMessage() + ")");
                }

                finishLatch.countDown();

                if (_log.shouldInfo()) {
                    _log.info(direction + " Done forwarding between [" + from + "] and [" + to + "]");
                }
            }
        }
    }
}
