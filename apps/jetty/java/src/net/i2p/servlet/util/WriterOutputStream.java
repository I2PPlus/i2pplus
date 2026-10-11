package net.i2p.servlet.util;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;

/**
 * Treat a writer as an output stream. Quick 'n dirty, none
 * of that "intarnasheeonaleyzayshun" stuff.  So we can treat
 * the jsp's PrintWriter as an OutputStream
 *
 * @since 0.9.33 consolidated from routerconsole and i2psnark
 */
public class WriterOutputStream extends OutputStream {
    private final Writer _writer;

    /**
     * Wraps the writer so it can be used as the destination stream.
     * @param writer the writer this stream writes into, letting a PrintWriter serve as an OutputStream
     */
    public WriterOutputStream(Writer writer) { _writer = writer; }

    /**
     * Writes the low byte of the argument to the wrapped writer.
     */
    public void write(int b) throws IOException { _writer.write(b); }
}
