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

package org.apache.commons.net.ntp;

import java.net.DatagramPacket;

/**
 * Interface for a NtpV3Packet with get/set methods corresponding to the fields in the NTP Data Message Header described in RFC 1305.
 */
public interface NtpV3Packet {

    /**
     * Standard NTP UDP port
     */
    int NTP_PORT = 123;

    /**
     * {@value}
     */
    int LI_NO_WARNING = 0;

    /**
     * {@value}
     */
    int LI_LAST_MINUTE_HAS_61_SECONDS = 1;

    /**
     * {@value}
     */
    int LI_LAST_MINUTE_HAS_59_SECONDS = 2;

    /**
     * {@value}
     */
    int LI_ALARM_CONDITION = 3;

    /** Mode option {@value}. */
    int MODE_RESERVED = 0;

    /** Mode option {@value}. */
    int MODE_SYMMETRIC_ACTIVE = 1;

    /** Mode option {@value}. */
    int MODE_SYMMETRIC_PASSIVE = 2;

    /** Mode option {@value}. */
    int MODE_CLIENT = 3;

    /** Mode option {@value}. */
    int MODE_SERVER = 4;

    /** Mode option {@value}. */
    int MODE_BROADCAST = 5;

    /** Mode option {@value}. */
    int MODE_CONTROL_MESSAGE = 6;

    /** Mode option {@value}. */
    int MODE_PRIVATE = 7;

    /**
     * {@value}
     */
    int NTP_MINPOLL = 4; // 16 seconds

    /**
     * {@value}
     */
    int NTP_MAXPOLL = 14; // 16284 seconds

    /**
     * {@value}
     */
    int NTP_MINCLOCK = 1;

    /**
     * {@value}
     */
    int NTP_MAXCLOCK = 10;

    /**
     * {@value}
     */
    int VERSION_3 = 3;

    /**
     * {@value}
     */
    int VERSION_4 = 4;

    //
    // possible getType values such that other time-related protocols can have its information represented as NTP packets
    //
    /**
     * {@value}
     */
    String TYPE_NTP = "NTP"; // RFC-1305/2030

    /**
     * {@value}
     */
    String TYPE_ICMP = "ICMP"; // RFC-792

    /**
     * {@value}
     */
    String TYPE_TIME = "TIME"; // RFC-868

    /**
     * {@value}
     */
    String TYPE_DAYTIME = "DAYTIME"; // RFC-867

    /**
     * Gets a datagram packet with the NTP parts already filled in.
     *
     * @return a datagram packet with the NTP parts already filled in.
     */
    DatagramPacket getDatagramPacket();

    /**
     * Gets the leap indicator as defined in RFC-1305.
     *
     * @return the leap indicator as defined in RFC-1305.
     */
    int getLeapIndicator();

    /**
     * Gets the mode as defined in RFC-1305.
     *
     * @return the mode as defined in RFC-1305.
     */
    int getMode();

    /**
     * Gets the  mode as human readable string; for example, 3=Client.
     *
     * @return the mode as human readable string; for example, 3=Client.
     */
    String getModeName();

    /**
     * Gets the {@code originate} time as defined in RFC-1305.
     *
     * @return the {@code originate} time as defined in RFC-1305.
     */
    TimeStamp getOriginateTimeStamp();

    /**
     * Gets the poll interval as defined in RFC-1305. Field range between NTP_MINPOLL and NTP_MAXPOLL.
     *
     * @return the poll interval as defined in RFC-1305. Field range between NTP_MINPOLL and NTP_MAXPOLL.
     */
    int getPoll();

    /**
     * Gets the precision as defined in RFC-1305.
     *
     * @return the precision as defined in RFC-1305.
     */
    int getPrecision();

    /**
     * Gets the {@code receive} time as defined in RFC-1305.
     *
     * @return the {@code receive} time as defined in RFC-1305.
     */
    TimeStamp getReceiveTimeStamp();

    /**
     * Gets the reference id (32-bit code) as defined in RFC-1305.
     *
     * @return the reference id (32-bit code) as defined in RFC-1305.
     */
    int getReferenceId();

    /**
     * Gets the reference ID string.
     *
     * @return the reference ID string.
     */
    String getReferenceIdString();

    /**
     * Gets the reference time as defined in RFC-1305.
     *
     * @return the reference time as defined in RFC-1305.
     */
    TimeStamp getReferenceTimeStamp();

    /**
     * Gets the root delay as defined in RFC-1305.
     *
     * @return the root delay as defined in RFC-1305.
     */
    int getRootDelay();

    /**
     * Gets root delay in milliseconds.
     *
     * @return root delay in milliseconds.
     */
    double getRootDelayInMillisDouble();

    /**
     * Gets the root dispersion as defined in RFC-1305.
     *
     * @return the root dispersion as defined in RFC-1305.
     */
    int getRootDispersion();

    /**
     * Gets the the root dispersion in milliseconds.
     *
     * @return the root dispersion in milliseconds.
     */
    long getRootDispersionInMillis();

    /**
     * Gets the root dispersion in milliseconds.
     *
     * @return the root dispersion in milliseconds.
     */
    double getRootDispersionInMillisDouble();

    /**
     * Gets the stratum as defined in RFC-1305.
     *
     * @return the stratum as defined in RFC-1305.
     */
    int getStratum();

    /**
     * Gets the {@code transmit} timestamp as defined in RFC-1305.
     *
     * @return the {@code transmit} timestamp as defined in RFC-1305.
     */
    TimeStamp getTransmitTimeStamp();

    /**
     * Gets the type of time packet. The values (e.g. NTP, TIME, ICMP, ...) correspond to the protocol used to obtain the timing information.
     *
     * @return packet type string identifier
     */
    String getType();

    /**
     * Gets version as defined in RFC-1305.
     *
     * @return version as defined in RFC-1305.
     */
    int getVersion();

    /**
     * Sets the contents of this object from the datagram packet
     *
     * @param dp the packet
     */
    void setDatagramPacket(DatagramPacket dp);

    /**
     * Sets leap indicator.
     *
     * @param li   leap indicator code
     */
    void setLeapIndicator(int li);

    /**
     * Sets mode as defined in RFC-1305
     *
     * @param mode the mode to set
     */
    void setMode(int mode);

    /**
     * Sets originate timestamp given NTP TimeStamp object.
     *
     * @param ts   timestamp
     */
    void setOriginateTimeStamp(TimeStamp ts);

    /**
     * Sets poll interval as defined in RFC-1305. Field range between NTP_MINPOLL and NTP_MAXPOLL.
     *
     * @param poll the interval to set
     */
    void setPoll(int poll);

    /**
     * Sets precision as defined in RFC-1305
     *
     * @param precision Precision
     * @since 3.4
     */
    void setPrecision(int precision);

    /**
     * Sets receive timestamp given NTP TimeStamp object.
     *
     * @param ts   timestamp
     */
    void setReceiveTimeStamp(TimeStamp ts);

    /**
     * Sets reference clock identifier field.
     *
     * @param refId the clock id field to set
     */
    void setReferenceId(int refId);

    /**
     * Sets the reference timestamp given NTP TimeStamp object.
     *
     * @param ts   timestamp
     */
    void setReferenceTime(TimeStamp ts);

    /**
     * Sets root delay as defined in RFC-1305
     *
     * @param delay the delay to set
     * @since 3.4
     */
    void setRootDelay(int delay);

    /**
     * Sets the dispersion value.
     *
     * @param dispersion the value.
     * @since 3.4
     */
    void setRootDispersion(int dispersion);

    /**
     * Sets stratum as defined in RFC-1305
     *
     * @param stratum the stratum to set
     */
    void setStratum(int stratum);

    /**
     * Sets the {@code transmit} timestamp given NTP TimeStamp object.
     *
     * @param ts   timestamp
     */
    void setTransmitTime(TimeStamp ts);

    /**
     * Sets version as defined in RFC-1305
     *
     * @param version the version to set
     */
    void setVersion(int version);

}
