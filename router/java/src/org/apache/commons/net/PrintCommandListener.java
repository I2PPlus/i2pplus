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

package org.apache.commons.net;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.Objects;

import org.apache.commons.net.io.Util;

/**
 * This is a support class for some example programs. It is a sample implementation of the ProtocolCommandListener interface which just prints out to a
 * specified stream all command/reply traffic.
 *
 * @since 2.0
 */
public class PrintCommandListener implements ProtocolCommandListener {

    private static final String DIRECTION_MARKER_RECEIVE = "< ";
    private static final String DIRECTION_MARKER_SEND = "> ";
    private static final String HIDDEN_MARKER = " *******";
    private static final String CMD_LOGIN = "LOGIN";
    private static final String CMD_USER = "USER";
    private static final String CMD_PASS = "PASS";
    private final PrintWriter writer;
    private final boolean noLogin;
    private final char eolMarker;
    private final boolean showDirection;

    /**
     * Constructs an instance which prints everything using the default Charset.
     *
     * @param printStream where to write the commands and responses e.g. System.out
     * @since 3.0
     */
    @SuppressWarnings("resource")
    public PrintCommandListener(final PrintStream printStream) {
        this(Util.newPrintWriter(printStream));
    }

    /**
     * Constructs an instance which optionally suppresses login command text and indicates where the EOL starts with the specified character.
     *
     * @param printStream        where to write the commands and responses
     * @param suppressLogin if {@code true}, only print command name for login
     * @since 3.0
     */
    @SuppressWarnings("resource")
    public PrintCommandListener(final PrintStream printStream, final boolean suppressLogin) {
        this(Util.newPrintWriter(printStream), suppressLogin);
    }

    /**
     * Constructs an instance which optionally suppresses login command text and indicates where the EOL starts with the specified character.
     *
     * @param printStream        where to write the commands and responses
     * @param suppressLogin if {@code true}, only print command name for login
     * @param eolMarker     if non-zero, add a marker just before the EOL.
     * @since 3.0
     */
    @SuppressWarnings("resource")
    public PrintCommandListener(final PrintStream printStream, final boolean suppressLogin, final char eolMarker) {
        this(Util.newPrintWriter(printStream), suppressLogin, eolMarker);
    }

    /**
     * Constructs an instance which optionally suppresses login command text and indicates where the EOL starts with the specified character.
     *
     * @param printStream        where to write the commands and responses
     * @param suppressLogin if {@code true}, only print command name for login
     * @param eolMarker     if non-zero, add a marker just before the EOL.
     * @param showDirection if {@code true}, add {@code "> "} or {@code "< "} as appropriate to the output
     * @since 3.0
     */
    @SuppressWarnings("resource")
    public PrintCommandListener(final PrintStream printStream, final boolean suppressLogin, final char eolMarker, final boolean showDirection) {
        this(Util.newPrintWriter(printStream), suppressLogin, eolMarker, showDirection);
    }

    /**
     * Constructs the default instance which prints everything.
     *
     * @param writer where to write the commands and responses
     */
    public PrintCommandListener(final PrintWriter writer) {
        this(writer, false);
    }

    /**
     * Constructs an instance which optionally suppresses login command text.
     *
     * @param writer        where to write the commands and responses
     * @param suppressLogin if {@code true}, only print command name for login
     * @since 3.0
     */
    public PrintCommandListener(final PrintWriter writer, final boolean suppressLogin) {
        this(writer, suppressLogin, (char) 0);
    }

    /**
     * Constructs an instance which optionally suppresses login command text and indicates where the EOL starts with the specified character.
     *
     * @param writer        where to write the commands and responses
     * @param suppressLogin if {@code true}, only print command name for login
     * @param eolMarker     if non-zero, add a marker just before the EOL.
     * @since 3.0
     */
    public PrintCommandListener(final PrintWriter writer, final boolean suppressLogin, final char eolMarker) {
        this(writer, suppressLogin, eolMarker, false);
    }

    /**
     * Constructs an instance which optionally suppresses login command text and indicates where the EOL starts with the specified character.
     *
     * @param writer        where to write the commands and responses, not null.
     * @param suppressLogin if {@code true}, only print command name for login
     * @param eolMarker     if non-zero, add a marker just before the EOL.
     * @param showDirection if {@code true}, add {@code ">} " or {@code "< "} as appropriate to the output
     * @since 3.0
     */
    public PrintCommandListener(final PrintWriter writer, final boolean suppressLogin, final char eolMarker, final boolean showDirection) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.noLogin = suppressLogin;
        this.eolMarker = eolMarker;
        this.showDirection = showDirection;
    }

    private String getCommand(final ProtocolCommandEvent event) {
        return Objects.toString(event.getCommand()).toUpperCase(Locale.ROOT);
    }

    private String getMessage(final ProtocolCommandEvent event) {
        return Objects.toString(event.getMessage());
    }

    private String getPrintableString(final String msg) {
        if (eolMarker == 0) {
            return msg;
        }
        final int pos = msg.indexOf(SocketClient.NETASCII_EOL);
        if (pos > 0) {
            final StringBuilder sb = new StringBuilder(msg + 1);
            sb.append(msg.substring(0, pos));
            sb.append(eolMarker);
            sb.append(msg.substring(pos));
            return sb.toString();
        }
        return msg;
    }

    @Override
    public void protocolCommandSent(final ProtocolCommandEvent event) {
        if (showDirection) {
            writer.print(DIRECTION_MARKER_SEND);
        }
        if (noLogin) {
            final String cmd = getCommand(event);
            if (CMD_PASS.equals(cmd) || CMD_USER.equals(cmd)) {
                writer.print(cmd);
                writer.println(HIDDEN_MARKER); // Don't bother with EOL marker for this!
            } else if (CMD_LOGIN.equals(cmd)) { // IMAP
                String msg = getMessage(event);
                msg = msg.substring(0, msg.indexOf(CMD_LOGIN) + CMD_LOGIN.length());
                writer.print(msg);
                writer.println(HIDDEN_MARKER); // Don't bother with EOL marker for this!
            } else {
                writer.print(getPrintableString(getMessage(event)));
            }
        } else {
            writer.print(getPrintableString(getMessage(event)));
        }
        writer.flush();
    }

    @Override
    public void protocolReplyReceived(final ProtocolCommandEvent event) {
        if (showDirection) {
            writer.print(DIRECTION_MARKER_RECEIVE);
        }
        final String message = getMessage(event);
        final char last = message.charAt(message.length() - 1);
        writer.print(message);
        if (last != '\r' && last != '\n') {
            writer.println();
        }
        writer.flush();
    }
}
