package net.i2p.i2pcontrol.servlets.jsonrpc2handlers;

import com.thetransactioncompany.jsonrpc2.JSONRPC2Error;
import com.thetransactioncompany.jsonrpc2.JSONRPC2ParamsType;
import com.thetransactioncompany.jsonrpc2.JSONRPC2Request;
import java.util.Map;
import net.i2p.i2pcontrol.security.ExpiredAuthTokenException;
import net.i2p.i2pcontrol.security.InvalidAuthTokenException;
import net.i2p.i2pcontrol.security.SecurityManager;

/*
 *  Copyright 2011 hottuna (dev@robertfoss.se)
 *
 *  Licensed under: Apache v2.0 (see docs/LICENSES.md)
 *
 */

/**
 * Helper utility class for I2PControl JSON-RPC2 handlers.
 * Provides common functionality for parameter validation and authentication token verification.
 */
public class JSONRPC2Helper {
    public final static Boolean USE_NO_AUTH = false;
    public final static Boolean USE_AUTH = true;

    private final SecurityManager _secMan;

    public JSONRPC2Helper(SecurityManager secMan) {
        _secMan = secMan;
    }

    /**
     * Check incoming request for required arguments, to make sure they are valid.
     * @param requiredArgs - Array of names of required arguments. If null don't check for any parameters.
     * @param req - Incoming JSONRPC2 request
     * @param useAuth - If true, will validate authentication token.
     * @return - null if no errors were found. Corresponding JSONRPC2Error if error is found.
     */
    public JSONRPC2Error validateParams(String[] requiredArgs, JSONRPC2Request req, Boolean useAuth) {

        // Error on unnamed parameters
        if (req.getParamsType() != JSONRPC2ParamsType.OBJECT) {
            return JSONRPC2Error.INVALID_PARAMS;
        }
        Map<String, Object> params = req.getNamedParams();

        // Validate authentication token.
        if (useAuth) {
            JSONRPC2Error err = validateToken(params);
            if (err != null) {
                return err;
            }
        }

        // If there exist any required arguments.
        if (requiredArgs != null && requiredArgs.length > 0) {
            String missingArgs = "";
            for (int i = 0; i < requiredArgs.length; i++) {
                if (!params.containsKey(requiredArgs[i])) {
                    missingArgs = missingArgs.concat(requiredArgs[i] + ",");
                }
            }
            if (!missingArgs.isEmpty()) {
                missingArgs = missingArgs.substring(0, missingArgs.length() - 1);
                return new JSONRPC2Error(JSONRPC2Error.INVALID_PARAMS.getCode(), "Missing parameter(s): " + missingArgs);
            }
        }
        return null;
    }

    /**
     * Check incoming request for required arguments, to make sure they are valid. Will authenticate req.
     * @param requiredArgs - Array of names of required arguments. If null don't check for any parameters.
     * @param req - Incoming JSONRPC2 request
     * @return - null if no errors were found. Corresponding JSONRPC2Error if error is found.
     */
    public JSONRPC2Error validateParams(String[] requiredArgs, JSONRPC2Request req) {
        return validateParams(requiredArgs, req, JSONRPC2Helper.USE_AUTH);
    }


    /**
     * Will check incoming parameters to make sure they contain a valid token.
     * @param req - Parameters of incoming request
     * @return null if everything is fine, JSONRPC2Error for any corresponding error.
     */
    private JSONRPC2Error validateToken(Map<String, Object> params) {
        String tokenID = (String) params.get("Token");
        if (tokenID == null) {
            return JSONRPC2ExtendedError.NO_TOKEN;
        }
        try {
            _secMan.verifyToken(tokenID);
        } catch (InvalidAuthTokenException e) {
            return JSONRPC2ExtendedError.INVALID_TOKEN;
        } catch (ExpiredAuthTokenException e) {
            JSONRPC2Error err = new JSONRPC2ExtendedError(JSONRPC2ExtendedError.TOKEN_EXPIRED.getCode(),
                    "Provided authentication token expired " + e.getExpirytime() + ", will be removed.");
            return err;
        }
        return null;
    }
}
