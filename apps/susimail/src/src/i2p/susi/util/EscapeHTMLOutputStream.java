package i2p.susi.util;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import net.i2p.data.DataHelper;

/**
 * OutputStream that escapes HTML characters during streaming.
 * Streaming version of DataHelper.escapeHTML(), also escapes '--' for
 * comment safety since '--' is disallowed inside HTML comments.
 *
 * @since 0.9.34
 */
public class EscapeHTMLOutputStream extends FilterOutputStream {

    private static final byte[] AMP = DataHelper.getASCII("&amp;");
    private static final byte[] QUOT = DataHelper.getASCII("&quot;");
    private static final byte[] LT = DataHelper.getASCII("&lt;");
    private static final byte[] GT = DataHelper.getASCII("&gt;");
    private static final byte[] APOS = DataHelper.getASCII("&apos;");
    private static final byte[] MDASH = DataHelper.getASCII("&#45;");
    private static final byte[] BR = DataHelper.getASCII("<br>\n");

    /** Track state: 0=normal, 1=saw '<', 2=saw '<-', 3=prev was '-' */
    private int _commentState;

    public EscapeHTMLOutputStream(OutputStream out) {
        super(out);
        _commentState = 0;
    }

    @Override
    public void write(int val) throws IOException {
        switch (val) {
            case '&':
                out.write(AMP);
                _commentState = 0;
                break;
            case '"':
                out.write(QUOT);
                _commentState = 0;
                break;
            case '<':
                out.write(LT);
                _commentState = 1;
                break;
            case '>':
                if (_commentState == 3) {
                    out.write(MDASH);
                    out.write(GT);
                } else {
                    out.write(GT);
                }
                _commentState = 0;
                break;
            case '\'':
                out.write(APOS);
                _commentState = 0;
                break;
            case '-':
                if (_commentState == 2) {
                    out.write(MDASH);
                    _commentState = 0;
                } else if (_commentState == 1) {
                    out.write('-');
                    _commentState = 2;
                } else if (_commentState == 3) {
                    out.write('-');
                    out.write('-');
                    _commentState = 3;
                } else {
                    out.write('-');
                    _commentState = 3;
                }
                break;
            case '\t':
                out.write(' ');
                _commentState = 0;
                break;
            case '\r':
                _commentState = 0;
                break;
            case '\n':
                out.write(BR);
                _commentState = 0;
                break;
            default:
                out.write(val);
                _commentState = 0;
        }
    }

    /**
     *  Does nothing. Does not close the underlying stream.
     */
    @Override
    public void close() {}
}
