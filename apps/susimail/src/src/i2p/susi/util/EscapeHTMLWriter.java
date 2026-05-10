package i2p.susi.util;

import java.io.FilterWriter;
import java.io.IOException;
import java.io.Writer;

/**
 * Writer that escapes HTML characters during streaming.
 * Streaming version of DataHelper.escapeHTML(), also escapes '--' for
 * comment safety since '--' is disallowed inside HTML comments.
 *
 * @since 0.9.34
 */
public class EscapeHTMLWriter extends FilterWriter {

    private static final String AMP = "&amp;";
    private static final String QUOT = "&quot;";
    private static final String LT = "&lt;";
    private static final String GT = "&gt;";
    private static final String APOS = "&apos;";
    private static final String MDASH = "&#45;";
    private static final String BR = "<br>\n";

    /** Track state: 0=normal, 1=saw '<', 2=saw '<-', 3=prev was '-' */
    private int _commentState;

    public EscapeHTMLWriter(Writer out) {super(out); _commentState = 0;}

    @Override
    public void write(int c) throws IOException {
        switch (c) {
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
                    // ->  - escape the previous hyphen
                    out.write(MDASH);
                    out.write('>');
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
                    // <--  - escape this hyphen
                    out.write(MDASH);
                    _commentState = 0;
                } else if (_commentState == 1) {
                    // <- - first hyphen after '<'
                    out.write('-');
                    _commentState = 2;
                } else if (_commentState == 3) {
                    // --  - keep both, next char will determine if we escape
                    out.write('-');
                    out.write('-');
                    _commentState = 3;
                } else {
                    // normal -
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
                out.write(c);
                _commentState = 0;
        }
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        for (int i = off; i < off + len; i++) {write(cbuf[i]);}
    }

    @Override
    public void write(String str, int off, int len) throws IOException {
        for (int i = off; i < off + len; i++) {write(str.charAt(i));}
    }

    /**
     *  Does nothing. Does not close the underlying writer.
     */
    @Override
    public void close() {}
}
