package net.i2p.i2pcontrol.servlets.jsonrpc2handlers;

import com.thetransactioncompany.jsonrpc2.JSONRPC2Error;
import com.thetransactioncompany.jsonrpc2.JSONRPC2Request;
import com.thetransactioncompany.jsonrpc2.JSONRPC2Response;
import com.thetransactioncompany.jsonrpc2.server.MessageContext;
import com.thetransactioncompany.jsonrpc2.server.RequestHandler;
import java.util.HashMap;
import java.util.Map;
import net.i2p.i2pcontrol.security.SecurityManager;

/*
 *  Copyright 2011 hottuna (dev@robertfoss.se)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

/**
 * JSON-RPC2 handler for the I2PControl method.
 * Manages I2PControl service settings, primarily handling password changes for the control interface.
 */
public class I2PControlHandler implements RequestHandler {

    private final SecurityManager _secMan;
    private final JSONRPC2Helper _helper;

    public I2PControlHandler(JSONRPC2Helper helper, SecurityManager secMan) {
        _helper = helper;
        _secMan = secMan;
    }


    /** @return method names handled by this handler */
    @Override
    public String[] handledRequests() {
        return new String[] {"I2PControl"};
    }

    /** Process an I2PControl request */
    @Override
    public JSONRPC2Response process(JSONRPC2Request req, MessageContext ctx) {
        if (req.getMethod().equals("I2PControl")) {
            return process(req);
        } else {
            // Method name not supported
            return new JSONRPC2Response(JSONRPC2Error.METHOD_NOT_FOUND, req.getID());
        }
    }


    private JSONRPC2Response process(JSONRPC2Request req) {
        JSONRPC2Error err = _helper.validateParams(null, req);
        if (err != null)
            return new JSONRPC2Response(err, req.getID());

        Map<String, Object> inParams = req.getNamedParams();
        Map<String, Object> outParams = new HashMap<>(4);

        boolean settingsSaved = false;
        String inParam;

        if (inParams.containsKey("i2pcontrol.password") &&
            (inParam = (String) inParams.get("i2pcontrol.password")) != null &&
            _secMan.setPasswd(inParam)) {
            outParams.put("i2pcontrol.password", null);
            settingsSaved = true;
        }

        outParams.put("SettingsSaved", settingsSaved);
        return new JSONRPC2Response(outParams, req.getID());
    }
}
