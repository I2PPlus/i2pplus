package net.i2p.i2ptunnel.util;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Abstract output stream that limits writes and provides completion callback.
 *
 * <p>This class extends FilterOutputStream to provide a framework for
 * implementing output streams with write limitations or size restrictions.
 * It tracks completion state and calls a callback when the stream
 * is finished, preventing multiple completion attempts.</p>
 *
 * <p>Subclasses implement specific limiting behavior while inheriting
 * callback functionality and completion state management. Useful for
 * implementing streams that need to enforce size limits or provide
 * notification when writing is complete.</p>
 * @since 0.9.62
 */
public abstract class LimitOutputStream extends FilterOutputStream {

    private final byte _buf1[];
    protected final DoneCallback _callback;
    protected boolean _isDone;

    /** Callback interface for notification when a limited output stream completes */
    public interface DoneCallback { public void streamDone(); }

    /**
     *  @param done non-null
     */
    public LimitOutputStream(OutputStream out, DoneCallback done) {
        super(out);
        _callback = done;
        _buf1 = new byte[1];
    }

    @Override
    public void write(int c) throws IOException {
        _buf1[0] = (byte)c;
        write(_buf1, 0, 1);
    }

    /**
     * Subclasses MUST override the following method
     * such that it calls done() when finished
     * and throws EOFException if called again
     */
    @Override
    public void write(byte buf[], int off, int len) throws IOException {
        out.write(buf, off, len);
    }


    protected boolean isDone() { return _isDone; }

    /**
     *  flush(), call the callback, and set _isDone
     */
    protected void setDone() throws IOException {
        if (_isDone)
            throw new IllegalStateException("already done");
        flush();
        _callback.streamDone();
        _isDone = true;
    }
}
