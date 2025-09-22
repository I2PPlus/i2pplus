/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.commons.net.smtp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import org.apache.commons.net.MalformedServerReplyException;
import org.apache.commons.net.ProtocolCommandSupport;
import org.apache.commons.net.SocketClient;
import org.apache.commons.net.io.CRLFLineReader;
import org.apache.commons.net.util.NetConstants;

/**
 * SMTP provides the basic the functionality necessary to implement your own SMTP client. To derive the full benefits of the SMTP class requires some knowledge
 * of the FTP protocol defined in RFC 821. However, there is no reason why you should have to use the SMTP class. The
 * {@link org.apache.commons.net.smtp.SMTPClient} class, derived from SMTP, implements all the functionality required of an SMTP client. The SMTP class is made
 * public to provide access to various SMTP constants and to make it easier for adventurous programmers (or those with special needs) to interact with the SMTP
 * protocol and implement their own clients. A set of methods with names corresponding to the SMTP command names are provided to facilitate this interaction.
 * <p>
 * You should keep in mind that the SMTP server may choose to prematurely close a connection for various reasons. The SMTP class will detect a premature SMTP
 * server connection closing when it receives a {@link org.apache.commons.net.smtp.SMTPReply#SERVICE_NOT_AVAILABLE SMTPReply.SERVICE_NOT_AVAILABLE} response to
 * a command. When that occurs, the SMTP class method encountering that reply will throw an {@link org.apache.commons.net.smtp.SMTPConnectionClosedException}.
 * {@code SMTPConnectionClosedException} is a subclass of {@code IOException} and therefore need not be caught separately, but if you are going to
 * catch it separately, its catch block must appear before the more general {@code IOException} catch block. When you encounter an
 * {@link org.apache.commons.net.smtp.SMTPConnectionClosedException} , you must disconnect the connection with
 * {@link org.apache.commons.net.SocketClient#disconnect disconnect()} to properly clean up the system resources used by SMTP. Before disconnecting, you may
 * check the last reply code and text with {@link #getReplyCode getReplyCode}, {@link #getReplyString getReplyString}, and {@link #getReplyStrings
 * getReplyStrings}.
 * </p>
 * <p>
 * Rather than list it separately for each method, we mention here that every method communicating with the server and throwing an IOException can also throw a
 * {@link org.apache.commons.net.MalformedServerReplyException} , which is a subclass of IOException. A MalformedServerReplyException will be thrown when the
 * reply received from the server deviates enough from the protocol specification that it cannot be interpreted in a useful manner despite attempts to be as
 * lenient as possible.
 * </p>
 *
 * @see SMTPClient
 * @see SMTPConnectionClosedException
 * @see org.apache.commons.net.MalformedServerReplyException
 */

public class SMTP extends SocketClient {
    /** The default SMTP port (25). */
    public static final int DEFAULT_PORT = 25;

    /**
     * We have to ensure that the protocol communication is in ASCII, but we use ISO-8859-1 just in case 8-bit characters cross the wire.
     */
    private static final String DEFAULT_ENCODING = StandardCharsets.ISO_8859_1.name();

    /**
     * The encoding to use (user-settable).
     *
     * @since 3.1 (changed from private to protected)
     */
    protected final String encoding;

    /**
     * A ProtocolCommandSupport object used to manage the registering of ProtocolCommandListeners and te firing of ProtocolCommandEvents.
     */
    protected ProtocolCommandSupport _commandSupport_;

    BufferedReader reader;
    BufferedWriter writer;

    private int replyCode;
    private final ArrayList<String> replyLines;
    private boolean newReplyString;
    private String replyString;

    /**
     * The default SMTP constructor. Sets the default port to {@code DEFAULT_PORT} and initializes internal data structures for saving SMTP reply
     * information.
     */
    public SMTP() {
        this(DEFAULT_ENCODING);
    }

    /**
     * Overloaded constructor where the user may specify a default encoding.
     *
     * @param encoding the encoding to use
     * @since 2.0
     */
    public SMTP(final String encoding) {
        setDefaultPort(DEFAULT_PORT);
        replyLines = new ArrayList<>();
        newReplyString = false;
        replyString = null;
        _commandSupport_ = new ProtocolCommandSupport(this);
        this.encoding = encoding;
    }

    /** Initiates control connections and gets initial reply. */
    @Override
    protected void _connectAction_() throws IOException {
        super._connectAction_();
        reader = new CRLFLineReader(new InputStreamReader(_input_, encoding));
        writer = new BufferedWriter(new OutputStreamWriter(_output_, encoding));
        getReply();
    }

    /**
     * A convenience method to send the SMTP DATA command to the server, receive the reply, and return the reply code.
     *
     * @return The reply code received from the server.
     * @throws SMTPConnectionClosedException If the SMTP server prematurely closes the connection as a result of the client being idle or some other reason
     *                                       causing the server to send SMTP reply code 421. This exception may be caught either as an IOException or
     *                                       independently as itself.
     * @throws IOException                   If an I/O error occurs while either sending the command or receiving the server reply.
     */
    public int data() throws IOException {
        return sendCommand(SMTPCommand.DATA);
    }

    /**
     * Closes the connection to the SMTP server and sets to null some internal data so that the memory may be reclaimed by the garbage collector. The reply text
     * and code information from the last command is voided so that the memory it used may be reclaimed.
     *
     * @throws IOException If an error occurs while disconnecting.
     */
    @Override
    public void disconnect() throws IOException {
        super.disconnect();
        reader = null;
        writer = null;
        replyString = null;
        replyLines.clear();
        newReplyString = false;
    }

    /**
     * A convenience method to send the SMTP VRFY command to the server, receive the reply, and return the reply code.
     *
     * @param name The name to expand.
     * @return The reply code received from the server.
     * @throws SMTPConnectionClosedException If the SMTP server prematurely closes the connection as a result of the client being idle or some other reason
     *                                       causing the server to send SMTP reply code 421. This exception may be caught either as an IOException or
     *                                       independently as itself.
     * @throws IOException                   If an I/O error occurs while either sending the command or receiving the server reply.
     */
    public int expn(final String name) throws IOException {
        return sendCommand(SMTPCommand.EXPN, name);
    }

    /**
     * Provide command support to super-class
     */
    @Override
    protected ProtocolCommandSupport getCommandSupport() {
        return _commandSupport_;
    }

    /**
     * Gets a reply from the SMTP server and returns the integer reply code. After calling this method, the actual reply text can be accessed from either
     * calling {@link #getReplyString getReplyString} or {@link #getReplyStrings getReplyStrings}. Only use this method if you are implementing your own SMTP
     * client or if you need to fetch a secondary response from the SMTP server.
     *
     * @return The integer value of the reply code of the fetched SMTP reply.
     * @throws SMTPConnectionClosedException If the SMTP server prematurely closes the connection as a result of the client being idle or some other reason
     *                                       causing the server to send SMTP reply code 421. This exception may be caught either as an IOException or
     *                                       independently as itself.
     * @throws IOException                   If an I/O error occurs while receiving the server reply.
     */
    public int getReply() throws IOException {
        final int length;

        newReplyString = true;
        replyLines.clear();

        String line = reader.readLine();

        if (line == null) {
            throw new SMTPConnectionClosedException("Connection closed without indication.");
        }

        // In case we run into an anomaly we don't want fatal index exceptions
        // to be thrown.
        length = line.length();
        if (length < 3) {
            throw new MalformedServerReplyException("Truncated server reply: " + line);
        }

        try {
            final String code = line.substring(0, 3);
            replyCode = Integer.parseInt(code);
        } catch (final NumberFormatException e) {
            throw new MalformedServerReplyException("Could not parse response code.\nServer Reply: " + line);
        }

        replyLines.add(line);

        // Get extra lines if message continues.
        if (length > 3 && line.charAt(3) == '-') {
            do {
                line = reader.readLine();

                if (line == null) {
                    throw new SMTPConnectionClosedException("Connection closed without indication.");
                }

                replyLines.add(line);

                // The length() check handles problems that could arise from readLine()
                // returning too soon after encountering a naked CR or some other
                // anomaly.
            } while (!(line.length() >= 4 && line.charAt(3) != '-' && Character.isDigit(line.charAt(0))));
            // This is too strong a condition because a non-conforming server
            // could screw things up like ftp.funet.fi does for FTP
            // line.startsWith(code)));
        }

        fireReplyReceived(replyCode, getReplyString());

        if (replyCode == SMTPReply.SERVICE_NOT_AVAILABLE) {
            throw new SMTPConnectionClosedException("SMTP response 421 received.  Server closed connection.");
        }
        return replyCode;
    }

    /**
     * Gets the integer value of the reply code of the last SMTP reply. You will usually only use this method after you connect to the SMTP server to check
     * that the connection was successful since {@code connect} is of type void.
     *
     * @return The integer value of the reply code of the last SMTP reply.
     */
    public int getReplyCode() {
        return replyCode;
    }

    /**
     * Gets the entire text of the last SMTP server response exactly as it was received, including all end of line markers in NETASCII format.
     *
     * @return The entire text from the last SMTP response as a String.
     */
    public String getReplyString() {
        final StringBuilder buffer;

        if (!newReplyString) {
            return replyString;
        }

        buffer = new StringBuilder();

        for (final String line : replyLines) {
            buffer.append(line);
            buffer.append(NETASCII_EOL);
        }

        newReplyString = false;

        replyString = buffer.toString();
        return replyString;
    }

    /**
     * Gets the lines of text from the last SMTP server response as an array of strings, one entry per line. The end of line markers of each are stripped
     * from each line.
     *
     * @return The lines of text from the last SMTP response as an array.
     */
    public String[] getReplyStrings() {
        return replyLines.toArray(NetConstants.EMPTY_STRING_ARRAY);
    }

    /**
     * A convenience method to send the SMTP HELO command to the server, receive the reply, and return the reply code.
     *
     * @param hostname The hostname of the sender.
     * @return The reply code received from the server.
     * @throws SMTPConnectionClosedException If the SMTP server prematurely closes the connection as a result of the client being idle or some other reason
     *                                       causing the server to send SMTP reply code 421. This exception may be caught either as an IOException or
     *                                       independently as itself.
     * @throws IOException                   If an I/O error occurs while either sending the command or receiving the server reply.
     */
    public int helo(final String hostname) throws IOException {
        return sendCommand(SMTPCommand.HELO, hostname);
    }

    /**
     * A convenience method to send the SMTP HELP command to the server, receive the reply, and return the reply code.
     *
     * @return The reply code received from the server.
     * @throws SMTPConnectionClosedException If the SMTP server prematurely closes the connection as a result of the client being idle or some other reason
     *                                       causing the server to send SMTP reply code 421. This exception may be caught either as an IOException or
     *                                       independently as itself.
     * @throws IOException                   If an I/O error occurs while either sending the command or receiving the server reply.
     */
    public int help() throws IOException {
        return sendCommand(SMTPCommand.HELP);
    }

    /**
     * A convenience method to send the SMTP HELP command to the server, receive the reply, and return the reply code.
     *
     * @param command The command name on which to request help.
     * @return The reply code received from the server.
     * @throws SMTPConnectionClosedException If the SMTP server prematurely closes the connection as a result of the client being idle or some other reason
     *                                       causing the server to send SMTP reply code 421. This exception may be caught either as an IOException or
     *                                       independently as itself.
     * @throws IOException                   If an I/O error occurs while either sending the command or receiving the server reply.
     */
    public int help(final String command) throws IOException {
        return sendCommand(SMTPCommand.HELP, command);
    }

    /**
     * A convenience method to send the SMTP MAIL command to the server, receive the reply, and return the reply code.
     *
     * @param reversePath The reverse path.
     * @return The reply code received from the server.
     * @throws SMTPConnectionClosedException If the SMTP server prematurely closes the connection as a result of the client being idle or some other reason
     *                                       causing the server to send SMTP reply code 421. This exception may be caught either as an IOException or
     *                                       independently as itself.
     * @throws IOException                   If an I/O error occurs while either sending the command or receiving the server reply.
     */
    public int mail(final String reversePath) throws IOException {
        return sendCommand(SMTPCommand.MAIL, reversePath, false);
    }

    /**
     * A convenience method to send the SMTP NOOP command to the server, receive the reply, and return the reply code.
     *
     * @return The reply code received from the server.
     * @throws SMTPConnectionClosedException If the SMTP server prematurely closes the connection as a result of the client being idle or some other reason
     *                                       causing the server to send SMTP reply code 421. This exception may be caught either as an IOException or
     *                                       independently as itself.
     * @throws IOException                   If an I/O error occurs while either sending the command or receiving the server reply.
     */
    public int noop() throws IOException {
        return sendCommand(SMTPCommand.NOOP);
    }

    /**
     * A convenience method to send the SMTP QUIT command to the server, receive the reply, and return the reply code.
     *
     * @return The reply code received from the server.
     * @throws SMTPConnectionClosedException If the SMTP server prematurely closes the connection as a result of the client being idle or some other reason
     *                                       causing the server to send SMTP reply code 421. This exception may be caught either as an IOException or
     *                                       independently as itself.
     * @throws IOException                   If an I/O error occurs while either sending the command or receiving the server reply.
     */
    public int quit() throws IOException {
        return sendCommand(SMTPCommand.QUIT);
    }

    /**
     * A convenience method to send the SMTP RCPT command to the server, receive the reply, and return the reply code.
     *
     * @param forwardPath The forward path.
     * @return The reply code received from the server.
     * @throws SMTPConnectionClosedException If the SMTP server prematurely closes the connection as a result of the client being idle or some other reason
     *                                       causing the server to send SMTP reply code 421. This exception may be caught either as an IOException or
     *                                       independently as itself.
     * @throws IOException                   If an I/O error occurs while either sending the command or receiving the server reply.
     */
    public int rcpt(final String forwardPath) throws IOException {
        return sendCommand(SMTPCommand.RCPT, forwardPath, false);
    }

    /**
     * Removes a ProtocolCommandListener.
     *
     * Delegates this incorrectly named method - removeProtocolCommandistener (note the missing "L")- to the correct method
     * {@link SocketClient#removeProtocolCommandListener}
     *
     * @param listener The ProtocolCommandListener to remove
     */
    public void removeProtocolCommandistener(final org.apache.commons.net.ProtocolCommandListener listener) {
        removeProtocolCommandListener(listener);
    }

    /**
     * A convenience method to send the SMTP RSET command to the server, receive the reply, and return the reply code.
     *
     * @return The reply code received from the server.
     * @throws SMTPConnectionClosedException If the SMTP server prematurely closes the connection as a result of the client being idle or some other reason
     *                                       causing the server to send SMTP reply code 421. This exception may be caught either as an IOException or
     *                                       independently as itself.
     * @throws IOException                   If an I/O error occurs while either sending the command or receiving the server reply.
     */
    public int rset() throws IOException {
        return sendCommand(SMTPCommand.RSET);
    }

    /**
     * A convenience method to send the SMTP SAML command to the server, receive the reply, and return the reply code.
     *
     * @param reversePath The reverse path.
     * @return The reply code received from the server.
     * @throws SMTPConnectionClosedException If the SMTP server prematurely closes the connection as a result of the client being idle or some other reason
     *                                       causing the server to send SMTP reply code 421. This exception may be caught either as an IOException or
     *                                       independently as itself.
     * @throws IOException                   If an I/O error occurs while either sending the command or receiving the server reply.
     */
    public int saml(final String reversePath) throws IOException {
        return sendCommand(SMTPCommand.SAML, reversePath);
    }

    /**
     * A convenience method to send the SMTP SEND command to the server, receive the reply, and return the reply code.
     *
     * @param reversePath The reverse path.
     * @return The reply code received from the server.
     * @throws SMTPConnectionClosedException If the SMTP server prematurely closes the connection as a result of the client being idle or some other reason
     *                                       causing the server to send SMTP reply code 421. This exception may be caught either as an IOException or
     *                                       independently as itself.
     * @throws IOException                   If an I/O error occurs while either sending the command or receiving the server reply.
     */
    public int send(final String reversePath) throws IOException {
        return sendCommand(SMTPCommand.SEND, reversePath);
    }

    /**
     * Sends an SMTP command with no arguments to the server, waits for a reply and returns the numerical response code. After invocation, for more detailed
     * information, the actual reply text can be accessed by calling {@link #getReplyString getReplyString} or {@link #getReplyStrings getReplyStrings}.
     *
     * @param command The SMTPCommand constant corresponding to the SMTP command to send.
     * @return The integer value of the SMTP reply code returned by the server in response to the command.
     * @throws SMTPConnectionClosedException If the SMTP server prematurely closes the connection as a result of the client being idle or some other reason
     *                                       causing the server to send SMTP reply code 421. This exception may be caught either as an IOException or
     *                                       independently as itself.
     * @throws IOException                   If an I/O error occurs while either sending the command or receiving the server reply.
     */
    public int sendCommand(final int command) throws IOException {
        return sendCommand(command, null);
    }

    /**
     * Sends an SMTP command to the server, waits for a reply and returns the numerical response code. After invocation, for more detailed information, the
     * actual reply text can be accessed by calling {@link #getReplyString getReplyString} or {@link #getReplyStrings getReplyStrings}.
     *
     * @param command The SMTPCommand constant corresponding to the SMTP command to send.
     * @param args    The arguments to the SMTP command. If this parameter is set to null, then the command is sent with no argument.
     * @return The integer value of the SMTP reply code returned by the server in response to the command.
     * @throws SMTPConnectionClosedException If the SMTP server prematurely closes the connection as a result of the client being idle or some other reason
     *                                       causing the server to send SMTP reply code 421. This exception may be caught either as an IOException or
     *                                       independently as itself.
     * @throws IOException                   If an I/O error occurs while either sending the command or receiving the server reply.
     */
    public int sendCommand(final int command, final String args) throws IOException {
        return sendCommand(SMTPCommand.getCommand(command), args);
    }

    /**
     *
     * @param command      the command to send (as an int defined in {@link SMTPCommand})
     * @param args         the command arguments, may be {@code null}
     * @param includeSpace if {@code true}, add a space between the command and its arguments
     * @return the reply code
     * @throws IOException
     */
    private int sendCommand(final int command, final String args, final boolean includeSpace) throws IOException {
        return sendCommand(SMTPCommand.getCommand(command), args, includeSpace);
    }

    /**
     * Sends an SMTP command with no arguments to the server, waits for a reply and returns the numerical response code. After invocation, for more detailed
     * information, the actual reply text can be accessed by calling {@link #getReplyString getReplyString} or {@link #getReplyStrings getReplyStrings}.
     *
     * @param command The text representation of the SMTP command to send.
     * @return The integer value of the SMTP reply code returned by the server in response to the command.
     * @throws SMTPConnectionClosedException If the SMTP server prematurely closes the connection as a result of the client being idle or some other reason
     *                                       causing the server to send SMTP reply code 421. This exception may be caught either as an IOException or
     *                                       independently as itself.
     * @throws IOException                   If an I/O error occurs while either sending the command or receiving the server reply.
     */
    public int sendCommand(final String command) throws IOException {
        return sendCommand(command, null);
    }

    /**
     * Sends an SMTP command to the server, waits for a reply and returns the numerical response code. After invocation, for more detailed information, the
     * actual reply text can be accessed by calling {@link #getReplyString getReplyString} or {@link #getReplyStrings getReplyStrings}.
     *
     * @param command The text representation of the SMTP command to send.
     * @param args    The arguments to the SMTP command. If this parameter is set to null, then the command is sent with no argument.
     * @return The integer value of the SMTP reply code returned by the server in response to the command.
     * @throws SMTPConnectionClosedException If the SMTP server prematurely closes the connection as a result of the client being idle or some other reason
     *                                       causing the server to send SMTP reply code 421. This exception may be caught either as an IOException or
     *                                       independently as itself.
     * @throws IOException                   If an I/O error occurs while either sending the command or receiving the server reply.
     */
    public int sendCommand(final String command, final String args) throws IOException {
        return sendCommand(command, args, true);
    }

    /**
     * Send a command to the server. May also be used to send text data.
     *
     * @param command      the command to send (as a plain String)
     * @param args         the command arguments, may be {@code null}
     * @param includeSpace if {@code true}, add a space between the command and its arguments
     * @return the reply code
     * @throws IOException
     */
    private int sendCommand(final String command, final String args, final boolean includeSpace) throws IOException {
        final StringBuilder builder = new StringBuilder(command);
        if (args != null) {
            if (includeSpace) {
                builder.append(' ');
            }
            builder.append(args);
        }
        builder.append(NETASCII_EOL);
        final String message = builder.toString();
        writer.write(message);
        writer.flush();
        fireCommandSent(command, message);
        return getReply();
    }

    /**
     * A convenience method to send the SMTP SOML command to the server, receive the reply, and return the reply code.
     *
     * @param reversePath The reverse path.
     * @return The reply code received from the server.
     * @throws SMTPConnectionClosedException If the SMTP server prematurely closes the connection as a result of the client being idle or some other reason
     *                                       causing the server to send SMTP reply code 421. This exception may be caught either as an IOException or
     *                                       independently as itself.
     * @throws IOException                   If an I/O error occurs while either sending the command or receiving the server reply.
     */
    public int soml(final String reversePath) throws IOException {
        return sendCommand(SMTPCommand.SOML, reversePath);
    }

    /**
     * A convenience method to send the SMTP TURN command to the server, receive the reply, and return the reply code.
     *
     * @return The reply code received from the server.
     * @throws SMTPConnectionClosedException If the SMTP server prematurely closes the connection as a result of the client being idle or some other reason
     *                                       causing the server to send SMTP reply code 421. This exception may be caught either as an IOException or
     *                                       independently as itself.
     * @throws IOException                   If an I/O error occurs while either sending the command or receiving the server reply.
     */
    public int turn() throws IOException {
        return sendCommand(SMTPCommand.TURN);
    }

    /**
     * A convenience method to send the SMTP VRFY command to the server, receive the reply, and return the reply code.
     *
     * @param user The user address to verify.
     * @return The reply code received from the server.
     * @throws SMTPConnectionClosedException If the SMTP server prematurely closes the connection as a result of the client being idle or some other reason
     *                                       causing the server to send SMTP reply code 421. This exception may be caught either as an IOException or
     *                                       independently as itself.
     * @throws IOException                   If an I/O error occurs while either sending the command or receiving the server reply.
     */
    public int vrfy(final String user) throws IOException {
        return sendCommand(SMTPCommand.VRFY, user);
    }
}
