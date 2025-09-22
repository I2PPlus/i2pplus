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

package org.apache.commons.net.telnet;

/**
 * The TelnetOptionHandler class is the base class to be used for implementing handlers for Telnet options.
 * <p>
 * TelnetOptionHandler implements basic option handling functionality and defines abstract methods that must be implemented to define subnegotiation behavior.
 * </p>
 */
public abstract class TelnetOptionHandler {
    /**
     * Option code
     */
    private int optionCode = -1;

    /**
     * true if the option should be activated on the local side
     */
    private boolean initialLocal;

    /**
     * true if the option should be activated on the remote side
     */
    private boolean initialRemote;

    /**
     * true if the option should be accepted on the local side
     */
    private boolean acceptLocal;

    /**
     * true if the option should be accepted on the remote side
     */
    private boolean acceptRemote;

    /**
     * true if the option is active on the local side
     */
    private boolean doFlag;

    /**
     * true if the option is active on the remote side
     */
    private boolean willFlag;

    /**
     * Constructor for the TelnetOptionHandler. Allows defining desired initial setting for local/remote activation of this option and behavior in case a
     * local/remote activation request for this option is received.
     *
     * @param optcode        Option code.
     * @param initlocal      if set to true, a {@code WILL} is sent upon connection.
     * @param initremote     if set to true, a {@code DO} is sent upon connection.
     * @param acceptlocal    if set to true, any {@code DO} request is accepted.
     * @param acceptremote   if set to true, any {@code WILL} request is accepted.
     */
    public TelnetOptionHandler(final int optcode, final boolean initlocal, final boolean initremote, final boolean acceptlocal, final boolean acceptremote) {
        optionCode = optcode;
        initialLocal = initlocal;
        initialRemote = initremote;
        acceptLocal = acceptlocal;
        acceptRemote = acceptremote;
    }

    /**
     * Method called upon reception of a subnegotiation for this option coming from the other end.
     * <p>
     * This implementation returns null, and must be overridden by the actual TelnetOptionHandler to specify which response must be sent for the subnegotiation
     * request.
     * </p>
     *
     * @param suboptionData     the sequence received, without IAC SB &amp; IAC SE
     * @param suboptionLength   the length of data in suboption_data
     * @return response to be sent to the subnegotiation sequence. TelnetClient will add IAC SB &amp; IAC SE. null means no response
     */
    public int[] answerSubnegotiation(final int suboptionData[], final int suboptionLength) {
        return null;
    }

    /**
     * Gets a boolean indicating whether to accept a DO request coming from the other end.
     *
     * @return true if a {@code DO} request shall be accepted.
     */
    public boolean getAcceptLocal() {
        return acceptLocal;
    }

    /**
     * Gets a boolean indicating whether to accept a WILL request coming from the other end.
     *
     * @return true if a {@code WILL} request shall be accepted.
     */
    public boolean getAcceptRemote() {
        return acceptRemote;
    }

    /**
     * Gets a boolean indicating whether a {@code DO} request sent to the other side has been acknowledged.
     *
     * @return true if a {@code DO} sent to the other side has been acknowledged.
     */
    boolean getDo() {
        return doFlag;
    }

    /**
     * Gets a boolean indicating whether to send a WILL request to the other end upon connection.
     *
     * @return true if a {@code WILL} request shall be sent upon connection.
     */
    public boolean getInitLocal() {
        return initialLocal;
    }

    /**
     * Gets a boolean indicating whether to send a DO request to the other end upon connection.
     *
     * @return true if a {@code DO} request shall be sent upon connection.
     */
    public boolean getInitRemote() {
        return initialRemote;
    }

    /**
     * Gets the option code for this option.
     *
     * @return Option code.
     */
    public int getOptionCode() {
        return optionCode;
    }

    /**
     * Gets a boolean indicating whether a {@code WILL} request sent to the other side has been acknowledged.
     *
     * @return true if a {@code WILL} sent to the other side has been acknowledged.
     */
    boolean getWill() {
        return willFlag;
    }

    /**
     * Sets behavior of the option for DO requests coming from the other end.
     *
     * @param accept   if true, subsequent DO requests will be accepted.
     */
    public void setAcceptLocal(final boolean accept) {
        acceptLocal = accept;
    }

    /**
     * Sets behavior of the option for {@code WILL} requests coming from the other end.
     *
     * @param accept   if true, subsequent {@code WILL} requests will be accepted.
     */
    public void setAcceptRemote(final boolean accept) {
        acceptRemote = accept;
    }

    /**
     * Sets this option whether a {@code DO} request sent to the other side has been acknowledged (invoked by TelnetClient).
     *
     * @param state   if true, a {@code DO} request has been acknowledged.
     */
    void setDo(final boolean state) {
        doFlag = state;
    }

    /**
     * Sets this option whether to send a {@code WILL} request upon connection.
     *
     * @param init   if true, a {@code WILL} request will be sent upon subsequent connections.
     */
    public void setInitLocal(final boolean init) {
        initialLocal = init;
    }

    /**
     * Sets this option whether to send a {@code DO} request upon connection.
     *
     * @param init   if true, a {@code DO} request will be sent upon subsequent connections.
     */
    public void setInitRemote(final boolean init) {
        initialRemote = init;
    }

    /**
     * Sets this option whether a {@code WILL} request sent to the other side has been acknowledged (invoked by TelnetClient).
     *
     * @param state   if true, a {@code WILL} request has been acknowledged.
     */
    void setWill(final boolean state) {
        willFlag = state;
    }

    /**
     * This method is invoked whenever this option is acknowledged active on the local end (TelnetClient sent a WILL, remote side sent a DO). The method is used
     * to specify a subnegotiation sequence that will be sent by TelnetClient when the option is activated.
     * <p>
     * This implementation returns null, and must be overriden by the actual TelnetOptionHandler to specify which response must be sent for the subnegotiation
     * request.
     * </p>
     *
     * @return subnegotiation sequence to be sent by TelnetClient. TelnetClient will add IAC SB &amp; IAC SE. null means no subnegotiation.
     */
    public int[] startSubnegotiationLocal() {
        return null;
    }

    /**
     * This method is invoked whenever this option is acknowledged active on the remote end (TelnetClient sent a DO, remote side sent a WILL). The method is
     * used to specify a subnegotiation sequence that will be sent by TelnetClient when the option is activated.
     * <p>
     * This implementation returns null, and must be overridden by the actual TelnetOptionHandler to specify which response must be sent for the subnegotiation
     * request.
     * </p>
     *
     * @return subnegotiation sequence to be sent by TelnetClient. TelnetClient will add IAC SB &amp; IAC SE. null means no subnegotiation.
     */
    public int[] startSubnegotiationRemote() {
        return null;
    }
}
