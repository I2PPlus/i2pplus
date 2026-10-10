/*
 * Copyright 1997-2006 Mort Bay Consulting Pty. Ltd.
 * ------------------------------------------------------------------------
 * Licensed under the Apache License, Version 2.0 (the "License");
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package net.i2p.jetty;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Locale;
import java.util.TimeZone;
import javax.servlet.http.Cookie;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.DateCache;
import org.eclipse.jetty.util.RolloverFileOutputStream;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;

import java.nio.charset.StandardCharsets;
import java.io.Writer;
/**
 * This {@link RequestLog} implementation outputs logs in the pseudo-standard NCSA common log format.
 * Configuration options allow a choice between the standard Common Log Format (as used in the 3 log format)
 * and the Combined Log Format (single log format).
 *
 * This log format can be output by most web servers, and almost all web log analysis software can understand
 * these formats.
 *
 * ** I2P Mods **
 *
 * For Jetty 5, this extended NCSARequestLog to override log() to put in the requestor's destination hash,
 * instead of 127.0.0.1, which is placed in the X-I2P-DestHash field in the request headers by I2PTunnelHTTPServer.
 * But we also had to modify NCSARequestLog to do so, to change private fields to protected.
 *
 * So that we will work with system Jetty 6 packages, we just copy the whole thing and modify log() as required.
 *
 * @author Greg Wilkins
 * @author Nigel Canonizado
 *
 */
public class I2PRequestLog extends AbstractLifeCycle implements RequestLog
{
    private String _filename;
    private boolean _extended;
    private boolean _append;
    private int _retainDays;
    private boolean _closeOut;
    private boolean _preferProxiedForAddress;
    private String _logDateFormat="dd/MMM/yyyy:HH:mm:ss Z";
    private String _filenameDateFormat = null;
    private Locale _logLocale = Locale.getDefault();
    private String _logTimeZone = "GMT";
    private String[] _ignorePaths;
    private boolean _logLatency = false;
    private boolean _logCookies = false;
    private boolean _logServer = false;
    private boolean _b64;

    private transient OutputStream _out;
    private transient OutputStream _fileOut;
    private transient DateCache _logDateCache;
    private transient PathMappings<String> _ignorePathMap;
    private transient Writer _writer;
    private transient ArrayList<Utf8StringBuilder> _buffers;
    private transient char[] _copy;

    /**
     * I2PRequestLog.
     */
    public I2PRequestLog() {
        _extended = true;
        _append = true;
        _retainDays = 31;
    }

    /**
     * Create a request log writing to the named file.
     *
     * @param filename the file to write to, in any format {@link RolloverFileOutputStream} accepts
     */
    public I2PRequestLog(String filename) {
        _extended = true;
        _append = true;
        _retainDays = 31;
        setFilename(filename);
    }

    /**
     * Redirect the log to another file.
     *
     * @param filename the file to write to, in any format {@link RolloverFileOutputStream} accepts; null is ignored
     */
    public void setFilename(String filename) {
        if (filename != null) {
            filename = filename.trim();
            if (filename.isEmpty()) {filename = null;}
        }
        _filename = filename;
    }

    /**
     * The file the log is currently writing to.
     *
     * @return the log filename
     */
    public String getFilename() {return _filename;}

    /**
     * The log filename with any date tokens expanded.
     *
     * @return the filename in use right now, or the undated name when the log is
     *         not rolling over
     */
    public String getDatedFilename() {
        if (_fileOut instanceof RolloverFileOutputStream) {
            return ((RolloverFileOutputStream)_fileOut).getDatedFilename();
        }
        return null;
    }

    /**
     * If not set, the pre-formated request timestamp is used.
     *
     * @param format Format for the timestamps in the log file.
     */
    public void setLogDateFormat(String format) {_logDateFormat = format;}
    /**
     * The format used to timestamp each entry.
     *
     * @return the date format, or null to use the timestamp jetty already
     *         formatted
     */
    public String getLogDateFormat() {return _logDateFormat;}
    /**
     * Set the locale timestamps are rendered in.
     *
     * @param logLocale the locale, or null for the platform default
     */
    public void setLogLocale(Locale logLocale) {_logLocale = logLocale;}
    /**
     * The locale timestamps are rendered in.
     *
     * @return the locale, or null for the platform default
     */
    public Locale getLogLocale() {return _logLocale;}
    /**
     * Set the time zone timestamps are rendered in.
     *
     * @param tz the time zone ID, or null for the platform default
     */
    public void setLogTimeZone(String tz) {_logTimeZone = tz;}
    /**
     * The time zone timestamps are rendered in.
     *
     * @return the time zone ID, or null for the platform default
     */
    public String getLogTimeZone() {return _logTimeZone;}
    /**
     * Set how many days of rotated logs are kept.
     *
     * @param retainDays the retention period in days
     */
    public void setRetainDays(int retainDays) {_retainDays = retainDays;}
    /**
     * How many days of rotated logs are kept.
     *
     * @return the retention period in days
     */
    public int getRetainDays() {return _retainDays;}
    /**
     * Select the extended, combined log format.
     *
     * @param extended true for the extended format
     */
    public void setExtended(boolean extended) {_extended = extended;}
    /**
     * Whether the extended, combined log format is in use.
     *
     * @return true when the extended format is selected
     */
    public boolean isExtended() {return _extended;}
    /**
     * Choose whether output appends to or replaces an existing file.
     *
     * @param append true to append
     */
    public void setAppend(boolean append) {_append = append;}
    /**
     * Whether output is appended to an existing file rather than replacing it.
     *
     * @return true when appending
     */
    public boolean isAppend() {return _append;}
    /**
     * Set the request paths to leave out of the log.
     *
     * @param ignorePaths the paths to exclude, or null to log everything
     */
    public void setIgnorePaths(String[] ignorePaths) {_ignorePaths = ignorePaths;}
    /**
     * The request paths left out of the log.
     *
     * @return the ignored paths, or null when nothing is excluded
     */
    public String[] getIgnorePaths() {return _ignorePaths;}
    /**
     * Record whether cookie headers are logged.
     *
     * @param logCookies true to log cookies
     */
    public void setLogCookies(boolean logCookies) {_logCookies = logCookies;}
    /**
     * Whether cookie headers are recorded.
     *
     * @return true when cookies are logged
     */
    public boolean getLogCookies() {return _logCookies;}
    /**
     * Whether the server identity is recorded in each entry.
     *
     * @return true when the server field is logged
     */
    public boolean getLogServer() {return _logServer;}
    /**
     * Record whether the server field is logged.
     *
     * @param logServer true to log the server identity
     */
    public void setLogServer(boolean logServer) {_logServer=logServer;}
    /**
     * Record whether request latency is logged.
     *
     * @param logLatency true to log latency
     */
    public void setLogLatency(boolean logLatency) {_logLatency = logLatency;}
    /**
     * Whether request latency is recorded.
     *
     * @return true when latency is logged
     */
    public boolean getLogLatency() {return _logLatency;}
    /**
     * Record whether proxied destinations are preferred for an address.
     *
     * @param preferProxiedForAddress true to prefer the proxied destination
     */
    public void setPreferProxiedForAddress(boolean preferProxiedForAddress) {_preferProxiedForAddress = preferProxiedForAddress;}

    /**
     * Choose the base used to encode destination hashes in the log.
     *
     * @param b64 true to log in base 64, false for base 32
     * @since 0.9.24
     */
    public void setB64(boolean b64) {_b64 = b64;}

    /**
     * Write one request out in the configured format.
     *
     * @param request the request being logged
     * @param response the response being logged
     */
    public void log(Request request, Response response) {
        if (!isStarted()) {return;}

        try {
            if (_ignorePathMap != null && _ignorePathMap.getMatch(request.getRequestURI()) != null) {return;}
            if (_fileOut == null) {return;}

            Utf8StringBuilder u8buf;
            StringBuilder buf;
            synchronized(_writer) {
                int size=_buffers.size();
                u8buf = size==0?new Utf8StringBuilder(160):_buffers.remove(size-1);
                buf = u8buf.getStringBuilder();
            }

            synchronized(buf) { // for efficiency until we can use StringBuilder
                if (_logServer) {
                    buf.append(request.getServerName());
                    buf.append(' ');
                }

                String addr = null;
                if (_preferProxiedForAddress) {addr = request.getHeader("X-Forwarded-For");}

                if (addr == null) {
                    if (_b64) {
                        addr = request.getHeader("X-I2P-DestHash");
                        if (addr != null) {addr += ".i2p";}
                    } else {addr = request.getHeader("X-I2P-DestB32");} // 52chars.b32.i2p
                    if (addr == null) {addr = request.getRemoteAddr();}
                }

                buf.append(addr);
                buf.append(" - ");
                String user = request.getRemoteUser();
                buf.append((user == null)? " - " : user);
                buf.append(" [");
                if (_logDateCache!=null) {buf.append(_logDateCache.format(request.getTimeStamp()));}
                else {
                    buf.append(request.getTimeStamp());
                }

                buf.append("] \"").append(request.getMethod()).append(' ');

                u8buf.append(request.getRequestURI());

                buf.append(' ').append(request.getProtocol()).append("\" ");
                int status = response.getStatus();
                if (status<=0) {status = 404;}
                buf.append((char)('0'+((status/100)%10)));
                buf.append((char)('0'+((status/10)%10)));
                buf.append((char)('0'+(status%10)));

                long responseLength=response.getContentCount();
                /*
                 * The above is what Jetty used before 9, but now it often (for large content?)
                 * returns 0 for non-cgi responses.
                 *
                 * Now, Jetty uses getLongContentLength(), but according to these threads it returns 0
                 * for streaming (cgi) responses. So we take whichever one is nonzero, if the result was 200.
                 * See: https://dev.eclipse.org/mhonarc/lists/jetty-dev/msg02261.html and followups including this workaround:
                 * https://dev.eclipse.org/mhonarc/lists/jetty-dev/msg02267.html
                 */
                if (responseLength == 0 && status == 200 && !"HEAD".equals(request.getMethod())) {
                    responseLength = response.getLongContentLength();
                }
                if (responseLength >=0) {
                    buf.append(' ');
                    if (responseLength > 99999) {buf.append(Long.toString(responseLength));}
                    else {
                        if (responseLength > 9999) {
                            buf.append((char)('0' + ((responseLength / 10000)%10)));
                        }
                        if (responseLength > 999) {
                            buf.append((char)('0' + ((responseLength /1000)%10)));
                        }
                        if (responseLength > 99) {
                            buf.append((char)('0' + ((responseLength / 100)%10)));
                        }
                        if (responseLength > 9) {
                            buf.append((char)('0' + ((responseLength / 10)%10)));
                        }
                        buf.append((char)('0' + (responseLength)%10));
                    }
                    buf.append(' ');
                } else {buf.append(" - ");}
            }

            if (!_extended && !_logCookies && !_logLatency) {
                synchronized(_writer) {
                    buf.append(System.getProperty("line.separator", "\n"));
                    int l=buf.length();
                    if (l>_copy.length) {l=_copy.length;}
                    buf.getChars(0,l,_copy,0);
                    _writer.write(_copy,0,l);
                    _writer.flush();
                    u8buf.reset();
                    _buffers.add(u8buf);
                }
            } else {
                // The optional sections append to the same shared _writer, so
                // they have to run inside the lock that serializes writes to
                // it. The lock is therefore held for them as well; that is
                // inherent to writing to the shared writer, and the work done
                // under it is only request attribute formatting.
                synchronized(_writer) {
                    int l=buf.length();
                    if (l>_copy.length) {l=_copy.length;}
                    buf.getChars(0,l,_copy,0);
                    _writer.write(_copy,0,l);
                    u8buf.reset();
                    _buffers.add(u8buf);

                    if (_extended) {logExtended(request, _writer);}

                    if (_logCookies) {
                        Cookie[] cookies = request.getCookies();
                        if (cookies == null || cookies.length == 0) {_writer.write(" -");}
                        else {
                            _writer.write(" \"");
                            for (int i = 0; i < cookies.length; i++) {
                                if (i != 0) {_writer.write(';');}
                                _writer.write(cookies[i].getName());
                                _writer.write('=');
                                _writer.write(cookies[i].getValue());
                            }
                            _writer.write('\"');
                        }
                    }

                    if (_logLatency) {
                        _writer.write(' ');
                        _writer.write(Long.toString(System.currentTimeMillis() - request.getTimeStamp()));
                    }

                    _writer.write(System.getProperty("line.separator", "\n"));
                    _writer.flush();
                }
            }
        }
        catch (IOException e) {Log.getLogger((String)null).warn(e);}

    }

    /**
     * Write the extended fields: the referrer and the user agent.
     *
     * @param request the request being logged
     * @param writer where the log entry is written
     * @throws IOException if the writer fails
     */
    protected void logExtended(Request request,
                               Writer writer) throws IOException {
        String referer = request.getHeader("Referer");
        if (referer == null) {writer.write("\"-\" ");}
        else {
            writer.write('"');
            writer.write(referer);
            writer.write("\" ");
        }

        String agent = request.getHeader("User-Agent");
        if (agent == null) {writer.write("\"-\" ");}
        else {
            writer.write('"');
            writer.write(agent);
            writer.write('"');
        }
    }

    /**
     * Open the log file and build the timestamp cache.
     *
     * @throws Exception if the log file cannot be opened
     */
    protected void doStart() throws Exception {
        if (_logDateFormat!=null) {
            _logDateCache = new DateCache(_logDateFormat, _logLocale, _logTimeZone);
        }

        if (_filename != null) {
            _fileOut = new RolloverFileOutputStream(_filename,_append,_retainDays,TimeZone.getTimeZone(_logTimeZone),_filenameDateFormat,null);
            _closeOut = true;
            Log.getLogger((String)null).info("Opened "+getDatedFilename());
        } else {_fileOut = System.err;}

        _out = _fileOut;

        if (_ignorePaths != null && _ignorePaths.length > 0) {
            _ignorePathMap = new PathMappings<>();
            for (int i = 0; i < _ignorePaths.length; i++) {
                _ignorePathMap.put(new ServletPathSpec(_ignorePaths[i]), _ignorePaths[i]);
            }
        } else {_ignorePathMap = null;}

        _writer = new OutputStreamWriter(_out, StandardCharsets.UTF_8);
        _buffers = new ArrayList<>();
        _copy = new char[1024];
        super.doStart();
    }

    /**
     * Flush and close the log file.
     *
     * @throws Exception if the log file cannot be closed
     */
    protected void doStop() throws Exception {
        super.doStop();
        try {
            if (_writer != null) _writer.flush();
        } catch (IOException e) {
            Log.getLogger((String)null).ignore(e);
        }
        if (_out != null && _closeOut) {
            try {_out.close();}
            catch (IOException e) {
                Log.getLogger((String)null).ignore(e);
            }
        }

        _out = null;
        _fileOut = null;
        _closeOut = false;
        _logDateCache = null;
        _writer = null;
        _buffers = null;
        _copy = null;
    }

    /**
     * The date pattern used to name rotated files.
     *
     * @return the filename date format
     */
    public String getFilenameDateFormat() {return _filenameDateFormat;}

    /**
     * Set the log file date format.
     * See RolloverFileOutputStream(String, boolean, int, TimeZone, String, String)
     *
     * Set the date pattern used to name rotated files.
     *
     * @param logFileDateFormat the pattern passed to RolloverFileOutputStream
     */
    public void setFilenameDateFormat(String logFileDateFormat) {_filenameDateFormat=logFileDateFormat;}

}
