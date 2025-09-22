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

package org.apache.commons.net.pop3;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A POP3 Client class with protocol and authentication extensions support (RFC2449 and RFC2195).
 *
 * @see POP3Client
 * @since 3.0
 */
public class ExtendedPOP3Client extends POP3SClient {

    /**
     * The enumeration of currently-supported authentication methods.
     */
    public enum AUTH_METHOD {

        /** The standardized (RFC4616) PLAIN method, which sends the password unencrypted (insecure). */
        PLAIN("PLAIN"),

        /** The standardized (RFC2195) CRAM-MD5 method, which doesn't send the password (secure). */
        CRAM_MD5("CRAM-MD5");

        private final String methodName;

        AUTH_METHOD(final String methodName) {
            this.methodName = methodName;
        }

        /**
         * Gets the name of the given authentication method suitable for the server.
         *
         * @return The name of the given authentication method suitable for the server.
         */
        public String getAuthName() {
            return methodName;
        }
    }

    /** {@link Mac} algorithm. */
    private static final String MAC_ALGORITHM = "HmacMD5";

    /**
     * The default ExtendedPOP3Client constructor. Creates a new Extended POP3 Client.
     *
     * @throws NoSuchAlgorithmException Never thrown here.
     */
    public ExtendedPOP3Client() throws NoSuchAlgorithmException {
    }

    /**
     * Authenticate to the POP3 server by sending the AUTH command with the selected mechanism, using the given user and the given password.
     *
     * @param method   the {@link AUTH_METHOD} to use
     * @param user the user name
     * @param password the password
     * @return True if successfully completed, false if not.
     * @throws IOException              If an I/O error occurs while either sending a command to the server or receiving a reply from the server.
     * @throws NoSuchAlgorithmException If the CRAM hash algorithm cannot be instantiated by the Java runtime system.
     * @throws InvalidKeyException      If the CRAM hash algorithm failed to use the given password.
     */
    public boolean auth(final AUTH_METHOD method, final String user, final String password)
            throws IOException, NoSuchAlgorithmException, InvalidKeyException {
        if (sendCommand(POP3Command.AUTH, method.getAuthName()) != POP3Reply.OK_INT) {
            return false;
        }

        switch (method) {
        case PLAIN:
            // the server sends an empty response ("+ "), so we don't have to read it.
            return sendCommand(
                    new String(Base64.getEncoder().encode(("\000" + user + "\000" + password).getBytes(getCharset())), getCharset())) == POP3Reply.OK;
        case CRAM_MD5:
            // get the CRAM challenge
            final byte[] serverChallenge = Base64.getDecoder().decode(getReplyString().substring(2).trim());
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
            return sendCommand(Base64.getEncoder().encodeToString(toEncode)) == POP3Reply.OK;
        default:
            return false;
        }
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
}
