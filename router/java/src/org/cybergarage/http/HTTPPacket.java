/*
 * CyberHTTP for Java
 * Copyright (C) Satoshi Konno 2002-2004
 */

package org.cybergarage.http;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.StringTokenizer;
import java.util.Vector;
import org.cybergarage.net.HostInterface;
import org.cybergarage.util.Debug;
import org.cybergarage.util.StringUtil;

public class HTTPPacket {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    /**
     * Creates a new HTTP packet with default settings. Initializes with HTTP version and empty
     * content.
     */
    public HTTPPacket() {
        setVersion(HTTP.VERSION);
        setContentInputStream(null);
    }

    /**
     * Creates a copy of an existing HTTP packet.
     *
     * @param httpPacket HTTP packet to copy
     */
    public HTTPPacket(HTTPPacket httpPacket) {
        setVersion(HTTP.VERSION);
        set(httpPacket);
        setContentInputStream(null);
    }

    /**
     * Creates an HTTP packet by parsing from an input stream.
     *
     * @param in input stream containing the HTTP packet data
     */
    public HTTPPacket(InputStream in) {
        setVersion(HTTP.VERSION);
        set(in);
        setContentInputStream(null);
    }

    ////////////////////////////////////////////////
    //	init
    ////////////////////////////////////////////////

    /**
     * Initializes the HTTP packet to empty state. Clears first line, headers, content, and content
     * input stream.
     */
    public void init() {
        setFirstLine("");
        clearHeaders();
        setContent(new byte[0], false);
        setContentInputStream(null);
    }

    ////////////////////////////////////////////////
    //	Version
    ////////////////////////////////////////////////

    private String version;

    /**
     * Sets the HTTP version for this packet.
     *
     * @param ver HTTP version string (e.g., "1.0", "1.1")
     */
    public void setVersion(String ver) {
        version = ver;
    }

    /**
     * Gets the HTTP version of this packet.
     *
     * @return HTTP version string, or null if not set
     */
    public String getVersion() {
        return version;
    }

    ////////////////////////////////////////////////
    //	set
    ////////////////////////////////////////////////

    /**
     * Reads a single line from the buffered input stream.
     *
     * <p>This method reads characters until a line feed (LF) is encountered. Carriage return (CR)
     * characters are ignored. The method handles interrupted IO exceptions gracefully as they are
     * used to break HTTP connections.
     *
     * @param in the buffered input stream to read from
     * @return the line as a UTF-8 string, or empty string if end of stream is reached
     */
    private String readLine(BufferedInputStream in) {
        ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
        byte readBuf[] = new byte[1];

        try {
            int readLen = in.read(readBuf);
            while (0 < readLen) {
                if (readBuf[0] == HTTP.LF) break;
                if (readBuf[0] != HTTP.CR) lineBuf.write(readBuf[0]);
                readLen = in.read(readBuf);
            }
        } catch (InterruptedIOException e) {
            // Ignoring warning because it's a way to break the HTTP connecttion
            // TODO Create a new level of Logging and log the event
        } catch (IOException e) {
            Debug.warning(e);
        }

        // Always use UTF-8 charset to avoid encoding issues
        return new String(lineBuf.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Parses HTTP packet data from an input stream.
     *
     * <p>This method reads and parses the HTTP packet including the first line, headers, and
     * optionally the content body. It handles special cases such as:
     *
     * <ul>
     *   <li>HTTP 100 Continue responses (IIS compatibility)
     *   <li>Chunked transfer encoding
     *   <li>Content length parsing
     * </ul>
     *
     * @param in the input stream containing HTTP packet data
     * @param onlyHeaders if true, only parse headers and skip content body
     * @return true if parsing was successful, false otherwise
     */
    protected boolean set(InputStream in, boolean onlyHeaders) {
        try {
            BufferedInputStream reader = new BufferedInputStream(in);

            String firstLine = readLine(reader);
            if (firstLine == null || firstLine.length() <= 0) return false;
            setFirstLine(firstLine);

            // Thanks for Giordano Sassaroli <sassarol@cefriel.it> (09/03/03)
            HTTPStatus httpStatus = new HTTPStatus(firstLine);
            int statCode = httpStatus.getStatusCode();
            if (statCode == HTTPStatus.CONTINUE) {
                // ad hoc code for managing iis non-standard behaviour
                // iis sends 100 code response and a 200 code response in the same
                // stream, so the code should check the presence of the actual
                // response in the stream.
                // skip all header lines
                String headerLine = readLine(reader);
                while ((headerLine != null) && (0 < headerLine.length())) {
                    HTTPHeader header = new HTTPHeader(headerLine);
                    if (header.hasName() == true) setHeader(header);
                    headerLine = readLine(reader);
                }
                // look forward another first line
                String actualFirstLine = readLine(reader);
                if ((actualFirstLine != null) && (0 < actualFirstLine.length())) {
                    // this is the actual first line
                    setFirstLine(actualFirstLine);
                } else {
                    return true;
                }
            }

            String headerLine = readLine(reader);
            while ((headerLine != null) && (0 < headerLine.length())) {
                HTTPHeader header = new HTTPHeader(headerLine);
                if (header.hasName() == true) setHeader(header);
                headerLine = readLine(reader);
            }

            if (onlyHeaders == true) {
                setContent("", false);
                return true;
            }

            boolean isChunkedRequest = isChunked();

            long contentLen = 0;
            if (isChunkedRequest == true) {
                try {
                    String chunkSizeLine = readLine(reader);
                    // Thanks for Lee Peik Feng <pflee@users.sourceforge.net> (07/07/05)
                    // contentLen = Long.parseLong(new String(chunkSizeLine.getBytes(), 0,
                    // chunkSizeLine.length()-2), 16);
                    contentLen =
                            (chunkSizeLine != null) ? Long.parseLong(chunkSizeLine.trim(), 16) : 0;
                } catch (Exception e) {
                }
                ;
            } else contentLen = getContentLength();

            ByteArrayOutputStream contentBuf = new ByteArrayOutputStream();

            while (0 < contentLen) {
                int chunkSize = HTTP.getChunkSize();

                /* Thanks for Stephan Mehlhase (2010-10-26) */
                byte readBuf[] = new byte[(int) (contentLen > chunkSize ? chunkSize : contentLen)];

                long readCnt = 0;
                while (readCnt < contentLen) {
                    try {
                        // Thanks for Mark Retallack (02/02/05)
                        long bufReadLen = contentLen - readCnt;
                        if (chunkSize < bufReadLen) bufReadLen = chunkSize;
                        int readLen = reader.read(readBuf, 0, (int) bufReadLen);
                        if (readLen < 0) break;
                        contentBuf.write(readBuf, 0, readLen);
                        readCnt += readLen;
                    } catch (Exception e) {
                        Debug.warning(e);
                        break;
                    }
                }
                if (isChunkedRequest == true) {
                    // skip CRLF
                    long skipLen = 0;
                    do {
                        long skipCnt = reader.skip(HTTP.CRLF.length() - skipLen);
                        if (skipCnt < 0) break;
                        skipLen += skipCnt;
                    } while (skipLen < HTTP.CRLF.length());
                    // read next chunk size
                    try {
                        String chunkSizeLine = readLine(reader);
                        // Thanks for Lee Peik Feng <pflee@users.sourceforge.net> (07/07/05)
                        contentLen =
                                Long.parseLong(
                                        new String(
                                                chunkSizeLine.getBytes(StandardCharsets.UTF_8),
                                                0,
                                                chunkSizeLine.length() - 2,
                                                StandardCharsets.UTF_8),
                                        16);
                    } catch (Exception e) {
                        contentLen = 0;
                    }
                    ;
                } else contentLen = 0;
            }

            setContent(contentBuf.toByteArray(), false);
        } catch (Exception e) {
            Debug.warning(e);
            return false;
        }

        return true;
    }

    /**
     * Parses HTTP packet data from an input stream including content.
     *
     * <p>This is a convenience method that calls {@link #set(InputStream, boolean)} with
     * onlyHeaders set to false.
     *
     * @param in the input stream containing HTTP packet data
     * @return true if parsing was successful, false otherwise
     */
    protected boolean set(InputStream in) {
        return set(in, false);
    }

    /**
     * Parses HTTP packet data from an HTTP socket.
     *
     * <p>This is a convenience method that extracts the input stream from the HTTP socket and
     * parses the complete HTTP packet including content.
     *
     * @param httpSock the HTTP socket containing the HTTP packet data
     * @return true if parsing was successful, false otherwise
     */
    protected boolean set(HTTPSocket httpSock) {
        return set(httpSock.getInputStream());
    }

    /**
     * Copies all data from another HTTP packet to this packet.
     *
     * <p>This method performs a deep copy by copying the first line, all headers, and content from
     * the source packet. The target packet is first cleared before copying.
     *
     * @param httpPacket the source HTTP packet to copy from
     */
    protected void set(HTTPPacket httpPacket) {
        setFirstLine(httpPacket.getFirstLine());

        clearHeaders();
        int nHeaders = httpPacket.getNHeaders();
        for (int n = 0; n < nHeaders; n++) {
            HTTPHeader header = httpPacket.getHeader(n);
            addHeader(header);
        }
        setContent(httpPacket.getContent());
    }

    ////////////////////////////////////////////////
    //	read
    ////////////////////////////////////////////////

    /**
     * Reads and parses HTTP packet from a socket.
     *
     * @param httpSock socket containing the HTTP packet data
     * @return true if packet was read successfully, false otherwise
     */
    public boolean read(HTTPSocket httpSock) {
        init();
        return set(httpSock);
    }

    ////////////////////////////////////////////////
    //	String
    ////////////////////////////////////////////////

    private String firstLine = "";

    /**
     * Sets the first line of the HTTP packet.
     *
     * <p>For requests, this is typically the request line (e.g., "GET /path HTTP/1.1"). For
     * responses, this is the status line (e.g., "HTTP/1.1 200 OK").
     *
     * @param value the first line string to set
     */
    private void setFirstLine(String value) {
        firstLine = value;
    }

    /**
     * Gets the first line of the HTTP packet.
     *
     * <p>For requests, this returns the request line containing method, URI, and HTTP version. For
     * responses, this returns the status line containing HTTP version, status code, and reason
     * phrase.
     *
     * @return the first line string, or empty string if not set
     */
    protected String getFirstLine() {
        return firstLine;
    }

    /**
     * Gets a specific token from the first line using HTTP request line delimiters.
     *
     * <p>This method parses the first line (request line or status line) and returns the token at
     * the specified position. Tokens are separated by spaces.
     *
     * <p>For request lines: token 0 = method, token 1 = URI, token 2 = HTTP version
     *
     * <p>For status lines: token 0 = HTTP version, token 1 = status code, token 2 = reason phrase
     *
     * @param num the zero-based token position to retrieve
     * @return the token at the specified position, or empty string if not found
     */
    protected String getFirstLineToken(int num) {
        StringTokenizer st = new StringTokenizer(firstLine, HTTP.REQEST_LINE_DELIM);
        String lastToken = "";
        for (int n = 0; n <= num; n++) {
            if (st.hasMoreTokens() == false) return "";
            lastToken = st.nextToken();
        }
        return lastToken;
    }

    public boolean hasFirstLine() {
        return (0 < firstLine.length()) ? true : false;
    }

    ////////////////////////////////////////////////
    //	Header
    ////////////////////////////////////////////////

    private Vector<HTTPHeader> httpHeaderList = new Vector<HTTPHeader>();

    public int getNHeaders() {
        return httpHeaderList.size();
    }

    public void addHeader(HTTPHeader header) {
        httpHeaderList.add(header);
    }

    public void addHeader(String name, String value) {
        HTTPHeader header = new HTTPHeader(name, value);
        httpHeaderList.add(header);
    }

    public HTTPHeader getHeader(int n) {
        return httpHeaderList.get(n);
    }

    public HTTPHeader getHeader(String name) {
        int nHeaders = getNHeaders();
        for (int n = 0; n < nHeaders; n++) {
            HTTPHeader header = getHeader(n);
            String headerName = header.getName();
            if (headerName.equalsIgnoreCase(name) == true) return header;
        }
        return null;
    }

    public void clearHeaders() {
        httpHeaderList.clear();
        httpHeaderList = new Vector<HTTPHeader>();
    }

    public boolean hasHeader(String name) {
        return (getHeader(name) != null) ? true : false;
    }

    public void setHeader(String name, String value) {
        HTTPHeader header = getHeader(name);
        if (header != null) {
            header.setValue(value);
            return;
        }
        addHeader(name, value);
    }

    public void setHeader(String name, int value) {
        setHeader(name, Integer.toString(value));
    }

    public void setHeader(String name, long value) {
        setHeader(name, Long.toString(value));
    }

    public void setHeader(HTTPHeader header) {
        setHeader(header.getName(), header.getValue());
    }

    public String getHeaderValue(String name) {
        HTTPHeader header = getHeader(name);
        if (header == null) return "";
        return header.getValue();
    }

    ////////////////////////////////////////////////
    // set*Value
    ////////////////////////////////////////////////

    public void setStringHeader(String name, String value, String startWidth, String endWidth) {
        String headerValue = value;
        if (headerValue.startsWith(startWidth) == false) headerValue = startWidth + headerValue;
        if (headerValue.endsWith(endWidth) == false) headerValue = headerValue + endWidth;
        setHeader(name, headerValue);
    }

    public void setStringHeader(String name, String value) {
        setStringHeader(name, value, "\"", "\"");
    }

    public String getStringHeaderValue(String name, String startWidth, String endWidth) {
        String headerValue = getHeaderValue(name);
        if (headerValue.startsWith(startWidth) == true)
            headerValue = headerValue.substring(1, headerValue.length());
        if (headerValue.endsWith(endWidth) == true)
            headerValue = headerValue.substring(0, headerValue.length() - 1);
        return headerValue;
    }

    public String getStringHeaderValue(String name) {
        return getStringHeaderValue(name, "\"", "\"");
    }

    public void setIntegerHeader(String name, int value) {
        setHeader(name, Integer.toString(value));
    }

    public void setLongHeader(String name, long value) {
        setHeader(name, Long.toString(value));
    }

    public int getIntegerHeaderValue(String name) {
        HTTPHeader header = getHeader(name);
        if (header == null) return 0;
        return StringUtil.toInteger(header.getValue());
    }

    public long getLongHeaderValue(String name) {
        HTTPHeader header = getHeader(name);
        if (header == null) return 0;
        return StringUtil.toLong(header.getValue());
    }

    ////////////////////////////////////////////////
    //	getHeader
    ////////////////////////////////////////////////

    public String getHeaderString() {
        StringBuffer str = new StringBuffer();

        int nHeaders = getNHeaders();
        for (int n = 0; n < nHeaders; n++) {
            HTTPHeader header = getHeader(n);
            str.append(header.getName() + ": " + header.getValue() + HTTP.CRLF);
        }

        return str.toString();
    }

    ////////////////////////////////////////////////
    //	Contents
    ////////////////////////////////////////////////

    private byte content[] = new byte[0];

    /**
     * Sets the content of this HTTP packet.
     *
     * @param data content data as byte array
     * @param updateWithContentLength whether to update Content-Length header automatically
     */
    public void setContent(byte data[], boolean updateWithContentLength) {
        content = data;
        if (updateWithContentLength == true) setContentLength(data.length);
    }

    public void setContent(byte data[]) {
        setContent(data, true);
    }

    public void setContent(String data, boolean updateWithContentLength) {
        setContent(data.getBytes(StandardCharsets.UTF_8), updateWithContentLength);
    }

    /**
     * Sets the content of this HTTP packet from a string. Automatically updates Content-Length
     * header.
     *
     * @param data content data as string
     */
    public void setContent(String data) {
        setContent(data, true);
    }

    /**
     * Gets the content of this HTTP packet.
     *
     * @return content as byte array
     */
    public byte[] getContent() {
        return content;
    }

    /**
     * Gets the content of this HTTP packet as a string. Uses the character set specified in
     * Content-Type header if available.
     *
     * @return content as string, using UTF-8 as default charset
     */
    public String getContentString() {
        String charSet = getCharSet();
        if (charSet == null || charSet.length() <= 0)
            return new String(content, StandardCharsets.UTF_8);
        try {
            return new String(content, charSet);
        } catch (Exception e) {
            Debug.warning(e);
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    /**
     * Checks if this HTTP packet has content.
     *
     * @return true if content length is greater than 0, false otherwise
     */
    public boolean hasContent() {
        return (content.length > 0) ? true : false;
    }

    ////////////////////////////////////////////////
    //	Contents (InputStream)
    ////////////////////////////////////////////////

    private InputStream contentInput = null;

    public void setContentInputStream(InputStream in) {
        contentInput = in;
    }

    public InputStream getContentInputStream() {
        return contentInput;
    }

    public boolean hasContentInputStream() {
        return (contentInput != null) ? true : false;
    }

    ////////////////////////////////////////////////
    //	ContentType
    ////////////////////////////////////////////////

    public void setContentType(String type) {
        setHeader(HTTP.CONTENT_TYPE, type);
    }

    public String getContentType() {
        return getHeaderValue(HTTP.CONTENT_TYPE);
    }

    ////////////////////////////////////////////////
    //	ContentLanguage
    ////////////////////////////////////////////////

    public void setContentLanguage(String code) {
        setHeader(HTTP.CONTENT_LANGUAGE, code);
    }

    public String getContentLanguage() {
        return getHeaderValue(HTTP.CONTENT_LANGUAGE);
    }

    ////////////////////////////////////////////////
    //	Charset
    ////////////////////////////////////////////////

    public String getCharSet() {
        String contentType = getContentType();
        if (contentType == null) return "";
        contentType = contentType.toLowerCase();
        int charSetIdx = contentType.indexOf(HTTP.CHARSET);
        if (charSetIdx < 0) return "";
        int charSetEndIdx = charSetIdx + HTTP.CHARSET.length() + 1;
        String charSet =
                new String(
                        contentType.getBytes(StandardCharsets.UTF_8),
                        charSetEndIdx,
                        (contentType.length() - charSetEndIdx),
                        StandardCharsets.UTF_8);
        if (charSet.length() < 0) return "";
        if (charSet.charAt(0) == '\"') charSet = charSet.substring(1, (charSet.length() - 1));
        if (charSet.length() < 0) return "";
        if (charSet.charAt((charSet.length() - 1)) == '\"')
            charSet = charSet.substring(0, (charSet.length() - 1));
        return charSet;
    }

    ////////////////////////////////////////////////
    //	ContentLength
    ////////////////////////////////////////////////

    public void setContentLength(long len) {
        setLongHeader(HTTP.CONTENT_LENGTH, len);
    }

    public long getContentLength() {
        return getLongHeaderValue(HTTP.CONTENT_LENGTH);
    }

    ////////////////////////////////////////////////
    //	Connection
    ////////////////////////////////////////////////

    public boolean hasConnection() {
        return hasHeader(HTTP.CONNECTION);
    }

    public void setConnection(String value) {
        setHeader(HTTP.CONNECTION, value);
    }

    public String getConnection() {
        return getHeaderValue(HTTP.CONNECTION);
    }

    public boolean isCloseConnection() {
        if (hasConnection() == false) return false;
        String connection = getConnection();
        if (connection == null) return false;
        return connection.equalsIgnoreCase(HTTP.CLOSE);
    }

    public boolean isKeepAliveConnection() {
        if (hasConnection() == false) return false;
        String connection = getConnection();
        if (connection == null) return false;
        return connection.equalsIgnoreCase(HTTP.KEEP_ALIVE);
    }

    ////////////////////////////////////////////////
    //	ContentRange
    ////////////////////////////////////////////////

    public boolean hasContentRange() {
        return (hasHeader(HTTP.CONTENT_RANGE) || hasHeader(HTTP.RANGE));
    }

    public void setContentRange(long firstPos, long lastPos, long length) {
        String rangeStr = "";
        rangeStr += HTTP.CONTENT_RANGE_BYTES + " ";
        rangeStr += Long.toString(firstPos) + "-";
        rangeStr += Long.toString(lastPos) + "/";
        rangeStr += ((0 < length) ? Long.toString(length) : "*");
        setHeader(HTTP.CONTENT_RANGE, rangeStr);
    }

    public long[] getContentRange() {
        long range[] = new long[3];
        range[0] = range[1] = range[2] = 0;
        if (hasContentRange() == false) return range;
        String rangeLine = getHeaderValue(HTTP.CONTENT_RANGE);
        // Thanks for Brent Hills (10/20/04)
        if (rangeLine.length() <= 0) rangeLine = getHeaderValue(HTTP.RANGE);
        if (rangeLine.length() <= 0) return range;
        // Thanks for Brent Hills (10/20/04)
        StringTokenizer strToken = new StringTokenizer(rangeLine, " =");
        // Skip bytes
        if (strToken.hasMoreTokens() == false) return range;
        String bytesStr = strToken.nextToken(" ");
        // Get first-byte-pos
        if (strToken.hasMoreTokens() == false) return range;
        String firstPosStr = strToken.nextToken(" -");
        try {
            range[0] = Long.parseLong(firstPosStr);
        } catch (NumberFormatException e) {
        }
        ;
        if (strToken.hasMoreTokens() == false) return range;
        String lastPosStr = strToken.nextToken("-/");
        try {
            range[1] = Long.parseLong(lastPosStr);
        } catch (NumberFormatException e) {
        }
        ;
        if (strToken.hasMoreTokens() == false) return range;
        String lengthStr = strToken.nextToken("/");
        try {
            range[2] = Long.parseLong(lengthStr);
        } catch (NumberFormatException e) {
        }
        ;
        return range;
    }

    public long getContentRangeFirstPosition() {
        long range[] = getContentRange();
        return range[0];
    }

    public long getContentRangeLastPosition() {
        long range[] = getContentRange();
        return range[1];
    }

    public long getContentRangeInstanceLength() {
        long range[] = getContentRange();
        return range[2];
    }

    ////////////////////////////////////////////////
    //	CacheControl
    ////////////////////////////////////////////////

    public void setCacheControl(String directive) {
        setHeader(HTTP.CACHE_CONTROL, directive);
    }

    public void setCacheControl(String directive, int value) {
        String strVal = directive + "=" + Integer.toString(value);
        setHeader(HTTP.CACHE_CONTROL, strVal);
    }

    public void setCacheControl(int value) {
        setCacheControl(HTTP.MAX_AGE, value);
    }

    public String getCacheControl() {
        return getHeaderValue(HTTP.CACHE_CONTROL);
    }

    ////////////////////////////////////////////////
    //	Server
    ////////////////////////////////////////////////

    public void setServer(String name) {
        setHeader(HTTP.SERVER, name);
    }

    public String getServer() {
        return getHeaderValue(HTTP.SERVER);
    }

    ////////////////////////////////////////////////
    //	Host
    ////////////////////////////////////////////////

    public void setHost(String host, int port) {
        String hostAddr = host;
        if (HostInterface.isIPv6Address(host) == true) hostAddr = "[" + host + "]";
        setHeader(HTTP.HOST, hostAddr + ":" + Integer.toString(port));
    }

    /*  I2P No - we always want port also. libupnp-based devices will reject 403 without the port
    	public void setHost(String host)
    	{
    		String hostAddr = host;
    		if (HostInterface.isIPv6Address(host) == true)
    			hostAddr = "[" + host + "]";
    		setHeader(HTTP.HOST, hostAddr);
    	}
    */

    public String getHost() {
        return getHeaderValue(HTTP.HOST);
    }

    ////////////////////////////////////////////////
    //	Date
    ////////////////////////////////////////////////

    public void setDate(Calendar cal) {
        Date date = new Date(cal);
        setHeader(HTTP.DATE, date.getDateString());
    }

    public String getDate() {
        return getHeaderValue(HTTP.DATE);
    }

    ////////////////////////////////////////////////
    //	Connection
    ////////////////////////////////////////////////

    public boolean hasTransferEncoding() {
        return hasHeader(HTTP.TRANSFER_ENCODING);
    }

    public void setTransferEncoding(String value) {
        setHeader(HTTP.TRANSFER_ENCODING, value);
    }

    public String getTransferEncoding() {
        return getHeaderValue(HTTP.TRANSFER_ENCODING);
    }

    public boolean isChunked() {
        if (hasTransferEncoding() == false) return false;
        String transEnc = getTransferEncoding();
        if (transEnc == null) return false;
        return transEnc.equalsIgnoreCase(HTTP.CHUNKED);
    }

    ////////////////////////////////////////////////
    //	set
    ////////////////////////////////////////////////

    /*
    	public final static boolean parse(HTTPPacket httpPacket, InputStream in)
    	{
     		try {
    			BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    			return parse(httpPacket, reader);
    		}
    		catch (Exception e) {
    			Debug.warning(e);
    		}
    		return false;
    	}
    */
}
