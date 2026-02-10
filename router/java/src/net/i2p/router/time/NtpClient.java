package net.i2p.router.time;
/*
 * Copyright (c) 2004, Adam Buckley
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 * - Neither the name of Adam Buckley nor the names of its contributors may be
 *   used to endorse or promote products derived from this software without
 *   specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 */
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.DNSOverHTTPS;
import net.i2p.util.HexDump;
import net.i2p.util.Log;

/**
 * NtpClient - an NTP client for Java.  This program connects to an NTP server
 * and prints the response to the console.
 *
 * The local clock offset calculation is implemented according to the SNTP
 * algorithm specified in RFC 2030.
 *
 * Note that on windows platforms, the curent time-of-day timestamp is limited
 * to an resolution of 10ms and adversely affects the accuracy of the results.
 *
 * Public only for main(), not a public API, not for external use.
 *
 * 2036-compliant as of 0.9.50, see NtpMessage.
 *
 * @author Adam Buckley
 * (minor refactoring by jrandom)
 * @since 0.9.1 moved from net.i2p.time
 */
public class NtpClient {
    /** difference between the unix epoch and jan 1 1900 (NTP uses that) */
    static final double SECONDS_1900_TO_EPOCH = 2208988800.0;
    private static final int NTP_PORT = 123;
    private static final int DEFAULT_TIMEOUT = 10 * 1000;
    private static final int OFF_ORIGTIME = 24;
    private static final int OFF_TXTIME = 40;
    private static final int MIN_PKT_LEN = 48;

    /**
     * Map of server IPs to reasons for receiving a "Kiss of Death" (KoD) message.
     * KoD is a special NTP stratum 0 response indicating the server requests the client to stop querying temporarily.
     */
    private static final Map<String, String> kisses = new ConcurrentHashMap<>(2);
    private static final String PROP_USE_DNS_OVER_HTTPS = "time.useDNSOverHTTPS";
    private static final boolean DEFAULT_USE_DNS_OVER_HTTPS = false;

    /**
     * Query the ntp servers, returning the current time from first one we find
     * Hack to return time and stratum
     *
     * @param log may be null
     * @return time in rv[0] and stratum in rv[1]
     * @throws IllegalArgumentException if none of the servers are reachable
     * @since 0.7.12
     */
    static long[] currentTimeAndStratum(String serverNames[], int perServerTimeout, boolean preferIPv6, Log log) {
        if (serverNames == null) {
            throw new IllegalArgumentException("No NTP servers specified");
        }
        ArrayList<String> names = new ArrayList<>(serverNames.length);
        Collections.addAll(names, serverNames);
        Collections.shuffle(names);
        for (String server : names) {
            long[] rv = currentTimeAndStratum(server, perServerTimeout, preferIPv6, log);
            if (rv != null && rv[0] > 0) {
                return rv;
            }
        }
        throw new IllegalArgumentException("No reachable NTP servers specified");
    }

    /**
     * Query the given NTP server, returning the current internet time and stratum
     *
     * @param log may be null
     * @return time in rv[0] and stratum in rv[1], or null for error
     * @since 0.7.12
     */
    private static long[] currentTimeAndStratum(String serverName, int timeout, boolean preferIPv6, Log log) {
        DatagramSocket socket = null;
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        boolean useDNSOverHTTPS = ctx.getProperty(PROP_USE_DNS_OVER_HTTPS, DEFAULT_USE_DNS_OVER_HTTPS);
        try {
            InetAddress address;
            if (preferIPv6) {
                String ip = null;
                if (useDNSOverHTTPS) {
                    DNSOverHTTPS doh = new DNSOverHTTPS(ctx);
                    ip = doh.lookup(serverName, DNSOverHTTPS.Type.V6_PREFERRED);
                }
                if (ip != null) {
                    address = InetAddress.getByName(ip);
                } else {
                    InetAddress[] addrs = InetAddress.getAllByName(serverName);
                    if (addrs == null || addrs.length == 0) {
                        throw new UnknownHostException("No IP addresses found for " + serverName);
                    }
                    address = null;
                    for (InetAddress inet : addrs) {
                        if (inet instanceof Inet6Address) {
                            address = inet;
                            break;
                        }
                        if (address == null) {
                            address = inet;
                        }
                    }
                }
            } else {
                if (useDNSOverHTTPS) {
                    DNSOverHTTPS doh = new DNSOverHTTPS(ctx);
                    String ip = doh.lookup(serverName, DNSOverHTTPS.Type.V4_ONLY);
                    if (ip != null) {
                        serverName = ip;
                    }
                }
                address = InetAddress.getByName(serverName);
            }
            String ipAddress = address.getHostAddress();
            String koDReason = kisses.get(ipAddress);
            if (koDReason != null) {
                if (log != null) {
                    log.warn("Skipping NTP query to " + serverName + " (" + ipAddress + ") due to previous Kiss of Death (KoD) response: " + koDReason);
                }
                return null;
            }
            byte[] buf = new NtpMessage().toByteArray();
            DatagramPacket packet = new DatagramPacket(buf, buf.length, address, NTP_PORT);
            byte[] transmitTimestampBytes = new byte[8];
            socket = new DatagramSocket();
            // Set transmit timestamp just before sending for accuracy
            NtpMessage.encodeTimestamp(packet.getData(), OFF_TXTIME,
                    (System.currentTimeMillis() / 1000.0) + SECONDS_1900_TO_EPOCH);
            socket.send(packet);
            System.arraycopy(packet.getData(), OFF_TXTIME, transmitTimestampBytes, 0, 8);
            if (log != null && log.shouldDebug()) {
                log.debug("Sent NTP request to " + serverName + " (" + ipAddress + ")\n" + HexDump.dump(buf));
            }
            packet = new DatagramPacket(buf, buf.length);
            socket.setSoTimeout(timeout);
            socket.receive(packet);
            double destinationTimestamp = (System.currentTimeMillis() / 1000.0) + SECONDS_1900_TO_EPOCH;
            if (packet.getLength() < MIN_PKT_LEN) {
                if (log != null && log.shouldWarn()) {
                    log.warn("Received NTP response from " + serverName + " (" + ipAddress + ") with insufficient length: " + packet.getLength() + " bytes");
                }
                return null;
            }
            NtpMessage msg = new NtpMessage(packet.getData());
            String fromIP = packet.getAddress().getHostAddress();
            int fromPort = packet.getPort();
            if (log != null && log.shouldDebug()) {
                log.debug("Received NTP response from " + fromIP + ":" + fromPort + "\n" +
                          msg + "\n" + HexDump.dump(packet.getData()));
            }
            // Spoofing check: confirm response from expected host and port
            if (fromPort != NTP_PORT || !ipAddress.equals(fromIP)) {
                if (log != null && log.shouldWarn()) {
                    log.warn("Potential spoof detected: Sent request to " + ipAddress + ":" + NTP_PORT +
                             " but received response from " + packet.getSocketAddress());
                }
                return null;
            }
            // Stratum check: valid strata are between 1 and 15 for servers, 0 for KoD special message
            if (msg.stratum > 15) {
                if (log != null && log.shouldWarn()) {
                    log.warn("Received NTP response from " + serverName + " (" + ipAddress + ") with invalid stratum: " + msg.stratum);
                }
                return null;
            }
            // Origin timestamp check for spoofing protection
            if (!DataHelper.eq(transmitTimestampBytes, 0, packet.getData(), OFF_ORIGTIME, 8)) {
                if (log != null && log.shouldWarn()) {
                    log.warn("Origin timestamp mismatch between sent and received NTP packets:\nSent:\n" +
                             HexDump.dump(transmitTimestampBytes) + "Received:\n" + HexDump.dump(packet.getData(), OFF_ORIGTIME, 8));
                }
                return null;
            }
            // Sanity checks - leapIndicator, version, mode, timestamps, delays
            if (msg.leapIndicator == 3 || msg.version < 3 || msg.mode != 4 ||
                msg.transmitTimestamp <= 0 || Math.abs(msg.rootDelay) > 1.0d ||
                Math.abs(msg.rootDispersion) > 1.0d) {
                if (log != null && log.shouldWarn()) {
                    log.warn("Sanity check failed for NTP response from " + serverName + " (" + ipAddress + ") \n* " + msg);
                }
                return null;
            }
            // KoD (Kiss of Death) check - server telling client to stop requests temporarily
            if (msg.stratum == 0) {
                String koDDetails = msg.referenceIdentifierToString();
                kisses.put(ipAddress, koDDetails);
                if (log != null) {
                    log.logAlways(Log.WARN, "Received Kiss of Death (KoD) from NTP server " + serverName + " (" + ipAddress + ") \n* Reason: " + koDDetails);
                }
                return null;
            }
            // Calculate local clock offset relative to received NTP time
            double localClockOffset = ((msg.receiveTimestamp - msg.originateTimestamp) +
                                       (msg.transmitTimestamp - destinationTimestamp)) / 2;
            long[] result = new long[2];
            result[0] = (long)(System.currentTimeMillis() + localClockOffset * 1000);
            result[1] = msg.stratum;
            if (log != null && log.shouldInfo()) {
                double roundTripDelay = (destinationTimestamp - msg.originateTimestamp) -
                                        (msg.receiveTimestamp - msg.transmitTimestamp);
                log.info(String.format("NTP time synchronization info from %s - RTT: %.3f sec, Local clock offset: %.6f sec",
                        packet.getAddress().getHostAddress(), roundTripDelay, localClockOffset));
            }
            return result;
        } catch (IOException ioe) {
            if (log != null && log.shouldWarn()) {
                log.warn("Failed connection to NTP server " + serverName + "\n* Reason: " + ioe);
            }
            return null;
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    /**
     * Main entry point for the NtpClient program.
     *
     * Usage:
     *   java -jar i2p/lib/router.jar NtpClient [-6] [servers...]
     *
     * The optional "-6" flag forces IPv6 DNS lookups.
     * If no servers are specified, the default "pool.ntp.org" is used.
     *
     * This program queries the specified NTP servers and prints the current time along with stratum and offset information.
     *
     * @param args command-line arguments; optionally "-6" followed by list of NTP servers
     * @throws IOException if a network error occurs
     */
    public static void main(String[] args) throws IOException {
        boolean ipv6 = false;
        if (args.length > 0 && args[0].equals("-6")) {
            ipv6 = true;
            if (args.length == 1) {
                args = new String[0];
            } else {
                args = Arrays.copyOfRange(args, 1, args.length);
            }
        }
        if (args.length <= 0) {
            args = new String[] { "pool.ntp.org" };
        }
        System.out.println("Querying " + Arrays.toString(args));
        Log log = new Log(NtpClient.class);
        try {
            long[] rv = currentTimeAndStratum(args, DEFAULT_TIMEOUT, ipv6, log);
            System.out.println("Current time: " + new java.util.Date(rv[0]) + " (stratum " + rv[1] +
                               ") offset " + (rv[0] - System.currentTimeMillis()) + "ms");
        } catch (IllegalArgumentException iae) {
            System.out.println("Failed: " + iae.getMessage());
        }
    }

}