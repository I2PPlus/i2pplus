package i2p.susi.util;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * OutputStream that converts LF to CRLF during streaming.
 * Used when importing .eml files.
 *
 * @since 0.9.34
 */
public class FixCRLFOutputStream extends FilterOutputStream {

    private int previous = -1;

    public FixCRLFOutputStream(OutputStream out) {
        super(out);
    }

    @Override
    public void write(int val) throws IOException {
        if (val == '\n' && previous != '\r')
            out.write('\r');
        out.write(val);
        previous = val;
    }
}
