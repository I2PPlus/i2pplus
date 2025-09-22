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

import java.io.IOException;
import java.net.InetAddress;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;

/**
 * An SMTP Client class with authentication support (RFC4954).
 *
 * @see SMTPClient
 * @since 3.0
 */
public class AuthenticatingSMTPClient extends SMTPSClient {

    /**
     * The enumeration of currently-supported authentication methods.
     */
    public enum AUTH_METHOD {

        /** The standardized (RFC4616) PLAIN method, which sends the password unencrypted (insecure). */
        PLAIN,

        /** The standardized (RFC2195) CRAM-MD5 method, which doesn't send the password (secure). */
        CRAM_MD5,

        /** The non-standarized Microsoft LOGIN method, which sends the password unencrypted (insecure). */
        LOGIN,

        /** XOAuth method which accepts a signed and base64ed OAuth URL. */
        XOAUTH,

        /** XOAuth 2 method which accepts a signed and base64ed OAuth JSON. */
        XOAUTH2;

        /**
         * Gets the name of the given authentication method suitable for the server.
         *
         * @param method The authentication method to get the name for.
         * @return The name of the given authentication method suitable for the server.
         */
        public static String getAuthName(final AUTH_METHOD method) {
            if (method.equals(PLAIN)) {
                return "PLAIN";
            }
            if (method.equals(CRAM_MD5)) {
                return "CRAM-MD5";
            }
            if (method.equals(LOGIN)) {
                return "LOGIN";
            }
            if (method.equals(XOAUTH)) {
                return "XOAUTH";
            }
            if (method.equals(XOAUTH2)) {
                return "XOAUTH2";
            }
            return null;
        }
    }

    /** {@link Mac} algorithm. */
    private static final String MAC_ALGORITHM = "HmacMD5";

    /**
     * The default AuthenticatingSMTPClient constructor. Creates a new Authenticating SMTP Client.
     */
    public AuthenticatingSMTPClient() {
    }

    /**
     * Overloaded constructor that takes the implicit argument, and using {@link #DEFAULT_PROTOCOL} i.e. TLS
     *
     * @param implicit The security mode, {@code true} for implicit, {@code false} for explicit
     * @param ctx      A pre-configured SSL Context.
     * @since 3.3
     */
    public AuthenticatingSMTPClient(final boolean implicit, final SSLContext ctx) {
        super(implicit, ctx);
    }

    /**
     * Overloaded constructor that takes a protocol specification
     *
     * @param protocol The protocol to use
     */
    public AuthenticatingSMTPClient(final String protocol) {
        super(protocol);
    }

    /**
     * Overloaded constructor that takes a protocol specification and the implicit argument
     *
     * @param proto    the protocol.
     * @param implicit The security mode, {@code true} for implicit, {@code false} for explicit
     * @since 3.3
     */
    public AuthenticatingSMTPClient(final String proto, final boolean implicit) {
        super(proto, implicit);
    }

    /**
     * Overloaded constructor that takes the protocol specification, the implicit argument and encoding
     *
     * @param proto    the protocol.
     * @param implicit The security mode, {@code true} for implicit, {@code false} for explicit
     * @param encoding the encoding
     * @since 3.3
     */
    public AuthenticatingSMTPClient(final String proto, final boolean implicit, final String encoding) {
        super(proto, implicit, encoding);
    }

    /**
     * Overloaded constructor that takes a protocol specification and encoding
     *
     * @param protocol The protocol to use
     * @param encoding The encoding to use
     * @since 3.3
     */
    public AuthenticatingSMTPClient(final String protocol, final String encoding) {
        super(protocol, false, encoding);
    }

    /**
     * Authenticate to the SMTP server by sending the AUTH command with the selected mechanism, using the given user and the given password.
     *
     * @param method   the method to use, one of the {@link AuthenticatingSMTPClient.AUTH_METHOD} enum values
     * @param user the user name. If the method is XOAUTH/XOAUTH2, then this is used as the plain text oauth protocol parameter string which is
     *                 Base64-encoded for transmission.
     * @param password the password for the username. Ignored for XOAUTH/XOAUTH2.
     * @return True if successfully completed, false if not.
     * @throws SMTPConnectionClosedException If the SMTP server prematurely closes the connection as a result of the client being idle or some other reason
     *                                       causing the server to send SMTP reply code 421. This exception may be caught either as an IOException or
     *                                       independently as itself.
     * @throws IOException                   If an I/O error occurs while either sending a command to the server or receiving a reply from the server.
     * @throws NoSuchAlgorithmException      If the CRAM hash algorithm cannot be instantiated by the Java runtime system.
     * @throws InvalidKeyException           If the CRAM hash algorithm failed to use the given password.
     */
    public boolean auth(final AuthenticatingSMTPClient.AUTH_METHOD method, final String user, final String password)
            throws IOException, NoSuchAlgorithmException, InvalidKeyException {
        if (!SMTPReply.isPositiveIntermediate(sendCommand(SMTPCommand.AUTH, AUTH_METHOD.getAuthName(method)))) {
            return false;
        }

        if (method.equals(AUTH_METHOD.PLAIN)) {
            // the server sends an empty response ("334 "), so we don't have to read it.
            return SMTPReply
                    .isPositiveCompletion(sendCommand(Base64.getEncoder().encodeToString(("\000" + user + "\000" + password).getBytes(getCharset()))));
        }
        if (method.equals(AUTH_METHOD.CRAM_MD5)) {
            // get the CRAM challenge
            final byte[] serverChallenge = Base64.getDecoder().decode(getReplyString().substring(4).trim());
            // get the Mac instance
            final Mac hmacMd5 = Mac.getInstance(MAC_ALGORITHM);
            hmacMd5.init(new SecretKeySpec(password.getBytes(getCharset()), MAC_ALGORITHM));
            // compute the result:
            final byte[] hmacResult = convertToHexString(hmacMd5.doFinal(serverChallenge)).getBytes(getCharset());
            // join the byte arrays to form the reply
            final byte[] userNameBytes = user.getBytes(getCharset());
            final byte[] toEncode = new byte[userNameBytes.length + 1 /* the space */ + hmacResult.length];
            System.arraycopy(userNameBytes, 0, toEncode, 0, userNameBytes.length);
            toEncode[userNameBytes.length] = ' ';
            System.arraycopy(hmacResult, 0, toEncode, userNameBytes.length + 1, hmacResult.length);
            // send the reply and read the server code:
            return SMTPReply.isPositiveCompletion(sendCommand(Base64.getEncoder().encodeToString(toEncode)));
        }
        if (method.equals(AUTH_METHOD.LOGIN)) {
            // the server sends fixed responses (base64("UserName") and
            // base64("Password")), so we don't have to read them.
            if (!SMTPReply.isPositiveIntermediate(sendCommand(Base64.getEncoder().encodeToString(user.getBytes(getCharset()))))) {
                return false;
            }
            return SMTPReply.isPositiveCompletion(sendCommand(Base64.getEncoder().encodeToString(password.getBytes(getCharset()))));
        }
        if (method.equals(AUTH_METHOD.XOAUTH) || method.equals(AUTH_METHOD.XOAUTH2)) {
            return SMTPReply.isPositiveIntermediate(sendCommand(Base64.getEncoder().encodeToString(user.getBytes(getCharset()))));
        }
        return false; // safety check
    }

    /**
     * Converts the given byte array to a String containing the hexadecimal values of the bytes. For example, the byte 'A' will be converted to '41', because
     * this is the ASCII code (and the byte value) of the capital letter 'A'.
     *
     * @param a The byte array to convert.
     * @return The resulting String of hexadecimal codes.
     */
    private String convertToHexString(final byte[] a) {
        final StringBuilder result = new StringBuilder(a.length * 2);
        for (final byte element : a) {
            if ((element & 0x0FF) <= 15) {
                result.append("0");
            }
            result.append(Integer.toHexString(element & 0x0FF));
        }
        return result.toString();
    }

    /**
     * A convenience method to send the ESMTP EHLO command to the server, receive the reply, and return the reply code.
     *
     * @param hostname The hostname of the sender.
     * @return The reply code received from the server.
     * @throws SMTPConnectionClosedException If the SMTP server prematurely closes the connection as a result of the client being idle or some other reason
     *                                       causing the server to send SMTP reply code 421. This exception may be caught either as an IOException or
     *                                       independently as itself.
     * @throws IOException                   If an I/O error occurs while either sending the command or receiving the server reply.
     */
    public int ehlo(final String hostname) throws IOException {
        return sendCommand(SMTPCommand.EHLO, hostname);
    }

    /**
     * Login to the ESMTP server by sending the EHLO command with the client hostname as an argument. Before performing any mail commands, you must first login.
     *
     * @return True if successfully completed, false if not.
     * @throws SMTPConnectionClosedException If the SMTP server prematurely closes the connection as a result of the client being idle or some other reason
     *                                       causing the server to send SMTP reply code 421. This exception may be caught either as an IOException or
     *                                       independently as itself.
     * @throws IOException                   If an I/O error occurs while either sending a command to the server or receiving a reply from the server.
     */
    public boolean elogin() throws IOException {
        final String name;
        final InetAddress host;

        host = getLocalAddress();
        name = host.getHostName();

        if (name == null) {
            return false;
        }

        return SMTPReply.isPositiveCompletion(ehlo(name));
    }

    /**
     * Login to the ESMTP server by sending the EHLO command with the given hostname as an argument. Before performing any mail commands, you must first login.
     *
     * @param hostname The hostname with which to greet the SMTP server.
     * @return True if successfully completed, false if not.
     * @throws SMTPConnectionClosedException If the SMTP server prematurely closes the connection as a result of the client being idle or some other reason
     *                                       causing the server to send SMTP reply code 421. This exception may be caught either as an IOException or
     *                                       independently as itself.
     * @throws IOException                   If an I/O error occurs while either sending a command to the server or receiving a reply from the server.
     */
    public boolean elogin(final String hostname) throws IOException {
        return SMTPReply.isPositiveCompletion(ehlo(hostname));
    }

    /**
     * Gets the integer values of the enhanced reply code of the last SMTP reply.
     *
     * @return The integer values of the enhanced reply code of the last SMTP reply. First digit is in the first array element.
     */
    public int[] getEnhancedReplyCode() {
        final String reply = getReplyString().substring(4);
        final String[] parts = reply.substring(0, reply.indexOf(' ')).split("\\.");
        final int[] res = new int[parts.length];
        Arrays.setAll(res, i -> Integer.parseInt(parts[i]));
        return res;
    }
}
