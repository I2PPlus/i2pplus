/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002-2004
 */

package org.cybergarage.upnp;

import org.cybergarage.http.HTTPStatus;

/**
 * Represents the status of UPnP operations and provides standard UPnP error codes.
 *
 * <p>This class encapsulates UPnP status codes and their corresponding descriptions for error
 * handling in UPnP operations. It includes standard UPnP error codes defined by the UPnP Device
 * Architecture specification.
 *
 * <p>The class provides both static utility methods for converting error codes to human-readable
 * strings and instance methods for storing and retrieving status information.
 *
 * @since 1.0
 * @author Satoshi Konno
 */
public class UPnPStatus {
    ////////////////////////////////////////////////
    //	Code
    ////////////////////////////////////////////////

    /** Error code indicating the requested action is not supported by the device. */
    public static final int INVALID_ACTION = 401;

    /** Error code indicating the action arguments are invalid or missing. */
    public static final int INVALID_ARGS = 402;

    /** Error code indicating the current state values are out of sync. */
    public static final int OUT_OF_SYNC = 403;

    /** Error code indicating the requested state variable does not exist. */
    public static final int INVALID_VAR = 404;

    /** Error code indicating required preconditions are not met. */
    public static final int PRECONDITION_FAILED = 412;

    /** Error code indicating the action execution failed for other reasons. */
    public static final int ACTION_FAILED = 501;

    /**
     * Converts a UPnP status code to its corresponding human-readable string.
     *
     * @param code the UPnP status code to convert
     * @return the human-readable description of the status code
     */
    public static final String code2String(int code) {
        switch (code) {
            case INVALID_ACTION:
                return "Invalid Action";
            case INVALID_ARGS:
                return "Invalid Args";
            case OUT_OF_SYNC:
                return "Out of Sync";
            case INVALID_VAR:
                return "Invalid Var";
            case PRECONDITION_FAILED:
                return "Precondition Failed";
            case ACTION_FAILED:
                return "Action Failed";
            default:
                return HTTPStatus.code2String(code);
        }
    }

    ////////////////////////////////////////////////
    //	Member
    ////////////////////////////////////////////////

    private int code;
    private String description;

    /** Creates a new UPnPStatus with default values (code 0, empty description). */
    public UPnPStatus() {
        setCode(0);
        setDescription("");
    }

    /**
     * Creates a new UPnPStatus with the specified code and description.
     *
     * @param code the status code
     * @param desc the status description
     */
    public UPnPStatus(int code, String desc) {
        setCode(code);
        setDescription(desc);
    }

    /**
     * Gets the status code.
     *
     * @return the status code
     */
    public int getCode() {
        return code;
    }

    /**
     * Sets the status code.
     *
     * @param code the status code to set
     */
    public void setCode(int code) {
        this.code = code;
    }

    /**
     * Gets the status description.
     *
     * @return the status description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the status description.
     *
     * @param description the status description to set
     */
    public void setDescription(String description) {
        this.description = description;
    }
}
