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
 * Simple option handler that can be used for options that don't require subnegotiation.
 */
public class SimpleOptionHandler extends TelnetOptionHandler {
    /**
     * Constructor for the SimpleOptionHandler. Initial and accept behavior flags are set to false
     *
     * @param optcode   option code.
     */
    public SimpleOptionHandler(final int optcode) {
        super(optcode, false, false, false, false);
    }

    /**
     * Constructor for the SimpleOptionHandler. Allows defining desired initial setting for local/remote activation of this option and behavior in case a
     * local/remote activation request for this option is received.
     *
     * @param optcode        option code.
     * @param initlocal      if set to true, a WILL is sent upon connection.
     * @param initremote     if set to true, a DO is sent upon connection.
     * @param acceptlocal    if set to true, any DO request is accepted.
     * @param acceptremote   if set to true, any WILL request is accepted.
     */
    public SimpleOptionHandler(final int optcode, final boolean initlocal, final boolean initremote, final boolean acceptlocal, final boolean acceptremote) {
        super(optcode, initlocal, initremote, acceptlocal, acceptremote);
    }

}
