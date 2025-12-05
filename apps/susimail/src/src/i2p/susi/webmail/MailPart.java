/*
 * This file is part of SusiMail project for I2P
 * Created on 07.11.2004
 * $Revision: 1.4 $
 * Copyright (C) 2004-2005 <susi23@mail.i2p>
 * License: GPL2 or later
 */
package i2p.susi.webmail;

import i2p.susi.util.Buffer;
import i2p.susi.util.CountingOutputStream;
import i2p.susi.util.DummyOutputStream;
import i2p.susi.util.EOFOnMatchInputStream;
import i2p.susi.util.FilenameUtil;
import i2p.susi.util.LimitInputStream;
import i2p.susi.util.ReadCounter;
import i2p.susi.util.OutputStreamBuffer;
import i2p.susi.util.MemoryBuffer;
import i2p.susi.webmail.encoding.DecodingException;
import i2p.susi.webmail.encoding.Encoding;
import i2p.susi.webmail.encoding.EncodingFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 * Represents a part of a multipart email message with headers and content.
 */
class MailPart {

    private static final OutputStream DUMMY_OUTPUT = new DummyOutputStream();
    public final String[] headerLines;
    /** encoding non-null */
    public final String type, encoding, name, description, disposition, charset, version, multipart_type, cid;
    /** begin, end, and beginBody are relative to readBuffer.getOffset().
     *  begin is before the headers
     *  beginBody is after the headers
     *  warning - end is exclusive
     */
    private final int beginBody, begin, end;
    /** fixme never set */
    public final String filename = null;
    public final List<MailPart> parts;
    public final boolean multipart, message;
    public final Buffer buffer;
    private final Log _log;

    /**
     *  the decoded length if known, else -1
     *  @since 0.9.34
     */
    public int decodedLength = -1;

    /**
     *  the UIDL of the mail, same for all parts
     *  @since 0.9.33
     */
    public final String uidl;
    private final int intID;

    /**
     *  @param readBuffer has zero offset for top-level MailPart.
     *  @param in used for reading (NOT readBuffer.getInputStream())
     *  @param counter used for counting how much we have read.
     *                 Probably the same as InputStream but a different interface.
     *  @param hdrlines non-null for top-level MailPart, where they
     *         were already parsed in Mail. Null otherwise
     */
    public MailPart(String uidl, AtomicInteger id, Buffer readBuffer, InputStream in,
                    ReadCounter counter, String[] hdrlines) throws IOException {
        _log = I2PAppContext.getGlobalContext().logManager().getLog(MailPart.class);
        this.uidl = uidl;
        intID = id.getAndIncrement();
        buffer = readBuffer;

        parts = new ArrayList<MailPart>(4);

        if (hdrlines != null) {
            // from Mail headers
            headerLines = hdrlines;
            begin = 0;
        } else {
            begin = (int) counter.getRead();
            // parse header lines
            // We don't do \r\n\r\n because then we can miss the \r\n-- of the multipart boundary.
            // So we do \r\n\r here, and \n-- below. If it's not multipart, we will swallow the \n below.
            EOFOnMatchInputStream eofin = new EOFOnMatchInputStream(in, Mail.HEADER_MATCH);
            MemoryBuffer decodedHeaders = new MemoryBuffer(4096);
            EncodingFactory.getEncoding("HEADERLINE").decode(eofin, decodedHeaders);
            if (!eofin.wasFound()) {if (_log.shouldDebug()) _log.debug("EOF hit before \\r\\n\\r\\n in MailPart");}
            // Fixme UTF-8 to bytes to UTF-8
            headerLines = DataHelper.split(new String(decodedHeaders.getContent(),
                                                      decodedHeaders.getOffset(),
                                                      decodedHeaders.getLength(), StandardCharsets.UTF_8), "\r\n");
        }

        String boundary = null;
        String x_encoding = null;
        String x_disposition = null;
        String x_type = null;
        String x_multipart_type = null;
        String x_cid = null;
        boolean x_multipart = false;
        boolean x_message = false;
        String x_name = null;
        String x_charset = null;
        String x_description = null;
        String x_version = null;

        for( int i = 0; i < headerLines.length; i++) {
            String hlc = headerLines[i].toLowerCase(Locale.US);
            if (hlc.startsWith( "content-transfer-encoding: ")) {
                x_encoding = getFirstAttribute( headerLines[i]).toLowerCase(Locale.US);
            }
            else if (hlc.startsWith( "content-disposition: ")) {
                x_disposition = getFirstAttribute( headerLines[i]).toLowerCase(Locale.US);
                String str;
                str = getHeaderLineAttribute(headerLines[i], "filename*");
                if (str != null) {x_name = FilenameUtil.decodeFilenameRFC5987(str);}
                else {
                    str = getHeaderLineAttribute(headerLines[i], "filename");
                    if (str != null) {x_name = str;}
                }
            }
            else if (hlc.startsWith( "content-type: ")) {
                x_type = getFirstAttribute( headerLines[i]).toLowerCase(Locale.US);
                /*
                 * extract boundary, name and charset from content type
                 */
                String str;
                str = getHeaderLineAttribute( headerLines[i], "boundary");
                if (str != null) {boundary = str;}
                if (x_type.startsWith( "multipart") && boundary != null) {
                    x_multipart = true;
                    str = getHeaderLineAttribute( headerLines[i], "type");
                    if (str != null) {x_multipart_type = str;}
                } else if (x_type.startsWith("message")) {x_message = true;}
                str = getHeaderLineAttribute( headerLines[i], "name");
                if (str != null) {x_name = str;}
                str = getHeaderLineAttribute( headerLines[i], "charset");
                if (str != null) {x_charset = str.toUpperCase(Locale.US);}
            }
            else if (hlc.startsWith( "content-description: ")) {x_description = getFirstAttribute( headerLines[i]);}
            else if (hlc.startsWith( "mime-version: ")) {x_version = getFirstAttribute( headerLines[i]);}
            else if (hlc.startsWith( "content-id: ")) {
                x_cid = getFirstAttribute( headerLines[i]);
                if (x_cid.startsWith("<")) {x_cid = x_cid.substring(1);}
                if (x_cid.endsWith(">")) {x_cid = x_cid.substring(0, x_cid.length() - 1);}
            }
        }

        // RFC 2045 Sec. 6.1: 7bit is the default
        if (x_encoding == null) {x_encoding = "7bit";}
        encoding = x_encoding;
        disposition = x_disposition;
        type = x_type;
        multipart = x_multipart;
        multipart_type = x_multipart_type;
        cid = x_cid;
        message = x_message;
        name = x_name;
        charset = x_charset;
        description = x_description;
        version = x_version;

        // See above re: \n
        if (multipart) {beginBody = (int) counter.getRead() + 1;} // EOFOnMatch will eat the \n
        else {
            int c = in.read(); // swallow the \n
            if (c != '\n' && _log.shouldDebug()) {_log.debug("wasn't a \\n, it was " + c);}
            beginBody = (int) counter.getRead();
        }

        int tmpEnd = 0;
        /* parse body */
        if (multipart) {
            // See above for why we don't include the \r
            byte[] match = DataHelper.getASCII("\n--" + boundary);
            for (int i = 0; ; i++) {
                EOFOnMatchInputStream eofin = new EOFOnMatchInputStream(in, counter, match);
                if (i == 0) {
                    // Read through first boundary line, not including "\r\n" or "--\r\n"
                    OutputStream dummy = new DummyOutputStream();
                    DataHelper.copy(eofin, dummy);
                    if (!eofin.wasFound())
                        if (_log.shouldDebug()) _log.debug("EOF hit before first boundary " + boundary + " UIDL: " + uidl);
                    if (readBoundaryTrailer(in)) {
                        if (!eofin.wasFound()) {
                            if (_log.shouldDebug()) _log.debug("EOF hit before first part body " + boundary + " UIDL: " + uidl);
                        }
                        tmpEnd = (int) eofin.getRead();
                        break;
                    }
                    // From here on we do include the \r
                    match = DataHelper.getASCII("\r\n--" + boundary);
                    eofin = new EOFOnMatchInputStream(in, counter, match);
                }
                MailPart newPart = new MailPart(uidl, id, buffer, eofin, eofin, null);
                parts.add( newPart);
                tmpEnd = (int) eofin.getRead();
                if (!eofin.wasFound()) {
                    // if MailPart contains a MailPart, we may not have drained to the end
                    DataHelper.copy(eofin, DUMMY_OUTPUT);
                    if (!eofin.wasFound()) {
                        if (_log.shouldDebug()) _log.debug("EOF hit before end of body " + i + " boundary: " + boundary + " UIDL: " + uidl);
                    }
                }
                if (readBoundaryTrailer(in)) {break;}
            }
        }
        else if (message) {
            MailPart newPart = new MailPart(uidl, id, buffer, in, counter, null);
            // TODO newPart doesn't save message headers we might like to display,
            // like From, To, and Subject
            parts.add( newPart);
            tmpEnd = (int) counter.getRead();
        } else {
            // read through to the end
            DataHelper.copy(in, DUMMY_OUTPUT);
            tmpEnd = (int) counter.getRead();
        }
        end = tmpEnd;
        if (encoding.equals("7bit") || encoding.equals("8bit") || encoding.equals("binary")) {
            decodedLength = end - beginBody;
        }
    }

    /**
     *  A value unique across all the parts of this Mail,
     *  and constant across restarts, so it may be part of a bookmark.
     *
     *  @since 0.9.34
     */
    public int getID() {return intID;}


    /**
     *  Swallow "\r\n" or "--\r\n".
     *  We don't have any pushback if this goes wrong.
     *
     *  @return true if end of input
     */
    private boolean readBoundaryTrailer(InputStream in) throws IOException {
        int c = in.read();
        if (c == '-') {
            // end of parts with this boundary
            c = in.read();
            if (c != '-') {
                if (_log.shouldDebug()) {_log.debug("Unexpected char after boundary-: " + c);}
                return true;
            }
            c = in.read();
            if (c == -1) {return true;}
            if (c != '\r') {
                if (_log.shouldDebug()) {_log.debug("Unexpected char after boundary--: " + c);}
                return true;
            }
            c = in.read();
            if (c != '\n') {
                if (_log.shouldDebug()) {_log.debug("Unexpected char after boundary--\\r: " + c);}
            }
            return true;
        } else if (c == '\r') {
            c = in.read();
            if (c != '\n') {
                if (_log.shouldDebug()) {_log.debug("Unexpected char after boundary\\r: " + c);}
            }
        } else {
            if (_log.shouldDebug()) {_log.debug("Unexpected char after boundary: " + c);}
        }
        return c == -1;
    }

    /**
     *  Synched because FileBuffer keeps stream open
     *
     *  @param offset 2 for sendAttachment, 0 otherwise, probably for \r\n
     *  @since 0.9.13
     */
    public synchronized void decode(int offset, Buffer out) throws IOException {
        Encoding enc = EncodingFactory.getEncoding(encoding);
        if (enc == null) {
            throw new DecodingException(_t("No encoder found for encoding \\''{0}\\''.", WebMail.quoteHTML(encoding)));
        }
        InputStream in = null;
        LimitInputStream lin = null;
        CountingOutputStream cos = null;
        Buffer dout = null;
        try {
            lin = getRawInputStream(offset);
            if (decodedLength < 0) {
                cos = new CountingOutputStream(out.getOutputStream());
                dout = new OutputStreamBuffer(cos);
            } else {dout = out;}
            enc.decode(lin, dout);
        } catch (IOException ioe) {
            if (_log.shouldDebug()) {
                if (lin != null) {_log.debug("Decode IOE at in position " + lin.getRead() + " offset " + offset, ioe);}
                if (cos != null) {_log.debug("Decode IOE at out position " + cos.getWritten() + " offset " + offset, ioe);}
                else {_log.debug("Decode IOE", ioe);}
            }
            throw ioe;
        } finally {
            if (lin != null) {
                try {lin.close();}
                catch (IOException ioe) {};
            }
            buffer.readComplete(true);
            if (dout != null) {dout.getOutputStream().flush();}
        }
        if (cos != null)
            decodedLength = (int) cos.getWritten();
    }

    /**
     *  Synched because FileBuffer keeps stream open
     *  Caller must close out
     *
     *  @since 0.9.35
     */
    public synchronized void outputRaw(OutputStream out) throws IOException {
        LimitInputStream lin = null;
        try {
            lin = getRawInputStream(0);
            DataHelper.copy(lin, out);
        } catch (IOException ioe) {
            if (_log.shouldDebug()) {_log.debug("Decode IOE", ioe);}
            throw ioe;
        } finally {
            if (lin != null) {
                try {lin.close();}
                catch (IOException ioe) {};
            }
            buffer.readComplete(true);
        }
    }

    /**
     *  Synched because FileBuffer keeps stream open
     *  Caller must call readComplete() on buffer
     *
     *  @param offset 2 for sendAttachment, 0 otherwise, probably for \r\n
     *  @since 0.9.35
     */
    private synchronized LimitInputStream getRawInputStream(int offset) throws IOException {
        InputStream in = buffer.getInputStream();
        DataHelper.skip(in, buffer.getOffset() + beginBody + offset);
        return new LimitInputStream(in, end - beginBody - offset);
    }

    private static String getFirstAttribute( String line) {
        String result = null;
        int i = line.indexOf( ": ");
        if (i != - 1) {
            int j = line.indexOf(';', i + 2);
            if (j == -1) {result = line.substring( i + 2);}
            else {result = line.substring( i + 2, j);}
            result = result.trim();
        }
        return result;
    }

    /**
     *  @param attributeName must be lower case, will be matched case-insensitively
     *  @return as found, not necessarily lower case
     */
    private static String getHeaderLineAttribute( String line, String attributeName)
    {
        String lineLC = line.toLowerCase(Locale.US);
        String result = null;
        int h = 0;
        int l = attributeName.length();
        while (result == null) {
            int i = lineLC.indexOf(attributeName, h);
            if (i == -1) {break;}
            h = i + l;
            int j = line.indexOf('=', i + l);
            if (j != -1) {
                int k = line.indexOf('"', j + 1);
                int m = line.indexOf(';', j + 1);
                if (k != -1 && ( m == -1 || k < m)) {
                    /* We found a " before a possible ; - now we look for the 2nd (not quoted) " */
                    m = -1;
                    int k2 = k + 1;
                    while (true) {
                        m = line.indexOf('"', k2);
                        if (m == -1) {break;}
                        else {
                            /* Found one! */
                            if (line.charAt( m - 1) != '\\') {
                                /* It's not quoted, so it is the one we look for */
                                result = line.substring( k + 1, m);
                                break;
                            } else {
                                /* This is quoted, so we extract the quote and continue the search */
                                line = line.substring( 0, m - 1) + line.substring( m);
                                k2 = m;
                            }
                        }
                    }
                } else if (m != -1) {result = line.substring( j + 1, m).trim();} /* No " found, but a ; */
                else {result = line.substring( j + 1).trim();} /* No " found and no ; */
            }
        }
        return result;
    }

    /** translate */
    private static String _t(String s, Object o) {return Messages.getString(s, o);}

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("MailPart:")
           .append("\n\tuidl:\t").append(uidl)
           .append("\n\tbuffer:\t").append(buffer)
           .append("\n\tbuffer offset:\t").append( buffer.getOffset())
           .append("\n\tbegin:\t").append( begin)
           .append("\n\theader lines:\t").append(headerLines.length).append("\n");
        for (int i = 0; i < headerLines.length; i++) {buf.append("\t\t\"").append(headerLines[i]).append("\"\n");}
        buf.append("\tmultipart?\t").append(multipart)
           .append("\n\tmessage?\t").append(message)
           .append("\n\ttype:\t").append(type)
           .append("\n\tmultipart type:\t").append(multipart_type)
           .append("\n\tcid:\t").append(cid)
           .append("\n\tencoding:\t").append(encoding)
           .append("\n\tname:\t").append(name)
           .append("\n\tdescription:\t").append(description)
           .append("\n\tdisposition:\t").append(disposition)
           .append("\n\tcharset:\t").append(charset)
           .append("\n\tversion:\t").append(version)
           .append("\n\tsubparts:\t").append(parts.size())
           .append("\n\tbeginbody:\t").append(beginBody)
           .append("\n\tbody len:\t").append((end - beginBody))
           .append("\n\tdecoded len:\t").append(decodedLength)
           .append("\n\tend:\t").append((end - 1))
           .append("\n\ttotal len:\t").append((end - begin))
           .append("\n\tbuffer len:\t").append(buffer.getLength());
        return  buf.toString();
    }

}
