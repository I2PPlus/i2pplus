package net.i2p.data.i2cp;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;

import net.i2p.I2PAppContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * The I2CPMessageReader reads an InputStream (using
 * {@link I2CPMessageHandler I2CPMessageHandler}) and passes out events to a registered
 * listener, where events are either messages being received, exceptions being
 * thrown, or the connection being closed.  Applications should use this rather
 * than read from the stream themselves.
 *
 * @author jrandom
 */
public class I2CPMessageReader {
    private InputStream _stream;
    protected I2CPMessageEventListener _listener;
    protected I2CPMessageReaderRunner _reader;
    protected Thread _readerThread;

    protected static final AtomicLong __readerId = new AtomicLong();

    public I2CPMessageReader(InputStream stream, I2CPMessageEventListener lsnr) {
        _stream = stream;
        setListener(lsnr);
        _reader = new I2CPMessageReaderRunner();
        _readerThread = new I2PThread(_reader);
        _readerThread.setDaemon(true);
        _readerThread.setName("I2CP Reader " + __readerId.incrementAndGet());
    }

    /**
     * For internal extension only. No stream.
     * @since 0.8.3
     */
    protected I2CPMessageReader(I2CPMessageEventListener lsnr) {
        setListener(lsnr);
    }

    public void setListener(I2CPMessageEventListener lsnr) {
        _listener = lsnr;
    }

    public I2CPMessageEventListener getListener() {
        return _listener;
    }

    /**
     * Instruct the reader to begin reading messages off the stream
     *
     */
    public void startReading() {
        _readerThread.start();
    }

    /**
     * Have the already started reader pause its reading indefinitely
     * @deprecated unused
     */
    @Deprecated
    public void pauseReading() {
        _reader.pauseRunner();
    }

    /**
     * Resume reading after a pause
     * @deprecated unused
     */
    @Deprecated
    public void resumeReading() {
        _reader.resumeRunner();
    }

    /**
     * Cancel reading.
     *
     */
    public void stopReading() {
        _reader.cancelRunner();
    }

    /**
     * Defines the different events the reader produces while reading the stream
     *
     */
    public static interface I2CPMessageEventListener {
        /**
         * Notify the listener that a message has been received from the given
         * reader
         *
	 * @param reader I2CPMessageReader to notify
	 * @param message the I2CPMessage
	 */
        public void messageReceived(I2CPMessageReader reader, I2CPMessage message);

        /**
         * Notify the listener that an exception was thrown while reading from the given
         * reader. For most errors, disconnected() will also be called, as of 0.9.41.
         *
	 * @param reader I2CPMessageReader to notify
	 * @param error Exception that was thrown, non-null
	 */
        public void readError(I2CPMessageReader reader, Exception error);

        /**
         * Notify the listener that the stream this reader was reading was
         * closed. For most errors, readError() will be called first, as of 0.9.41
         *
	 * @param reader I2CPMessageReader to notify
	 */
        public void disconnected(I2CPMessageReader reader);
    }

    protected class I2CPMessageReaderRunner implements Runnable {
        protected volatile boolean _doRun;
        protected volatile boolean _stayAlive;
        private final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(I2CPMessageReader.class);

        public I2CPMessageReaderRunner() {
            _doRun = true;
            _stayAlive = true;
        }

        /** deprecated unused */
        public void pauseRunner() {
            _doRun = false;
        }

        /** deprecated unused */
        public void resumeRunner() {
            _doRun = true;
        }

        public void cancelRunner() {
            _doRun = false;
            _stayAlive = false;
            // prevent race NPE
            InputStream in = _stream;
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ioe) {
                    _log.error("Error closing the stream \n* IO Error: " + ioe.getMessage());
                }
            }
        }

        public void run() {
            try {
                run2();
            } catch (RuntimeException e) {
                _log.log(Log.CRIT, "Uncaught I2CP error \n* Error: " + e.getMessage());
                _listener.readError(I2CPMessageReader.this, e);
                _listener.disconnected(I2CPMessageReader.this);
                cancelRunner();
            }
        }

        /**
         * Called by run()
         * @since 0.9.21
         */
        protected void run2() {
            while (_stayAlive) {
                while (_doRun) {
                    // do read
                    try {
                        I2CPMessage msg = I2CPMessageHandler.readMessage(_stream);
                        if (msg != null) {
                            //_log.debug("Before handling the newly received message");
                            _listener.messageReceived(I2CPMessageReader.this, msg);
                            //_log.debug("After handling the newly received message");
                        }
                    } catch (I2CPMessageException ime) {
                        _log.warn("Error handling message", ime);
                        _listener.readError(I2CPMessageReader.this, ime);
                        cancelRunner();
                    } catch (IOException ioe) {
                        _log.warn("IO Error handling message \n* Error:" + ioe.getMessage());
                        _listener.readError(I2CPMessageReader.this, ioe);
                        _listener.disconnected(I2CPMessageReader.this);
                        cancelRunner();
                    } catch (OutOfMemoryError oom) {
                        // ooms seen here... maybe log and keep going?
                        throw oom;
                    } catch (RuntimeException e) {
                        _log.log(Log.CRIT, "Unhandled error reading I2CP stream \n* Error: " + e.getMessage());
                        _listener.readError(I2CPMessageReader.this, e);
                        _listener.disconnected(I2CPMessageReader.this);
                        cancelRunner();
                    }
                }
                // ??? unused
                if (_stayAlive && !_doRun) {
                    // pause .5 secs when we're paused
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        // we should break away here.
                        _log.warn("Breaking away stream \n* Interrupted Exception: " + ie.getMessage());
                        _listener.disconnected(I2CPMessageReader.this);
                        cancelRunner();
                    }
                }
            }
            _stream = null;
        }
    }
}
