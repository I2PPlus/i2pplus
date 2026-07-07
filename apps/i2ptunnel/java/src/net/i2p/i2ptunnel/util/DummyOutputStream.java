package net.i2p.i2ptunnel.util;

import java.io.OutputStream;

/**
 * Write to nowhere
 *
 * @since 0.9.62 copied from susimail
 */
public class DummyOutputStream extends OutputStream {

    @Override
    public void write(int val) { /* no-op */ }

    @Override
    public void write(byte[] src) { /* no-op */ }

    @Override
    public void write(byte[] src, int off, int len) { /* no-op */ }

    @Override
    public void flush() { /* no-op */ }

    @Override
    public void close() { /* no-op */ }
}
