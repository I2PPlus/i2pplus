package edu.internet2.ndt;

/** Class to define the NDTP control message types **/
public class MessageType {
    /** default constructor */
    public MessageType() {}

    /** Communication failure */
    public static final byte COMM_FAILURE = 0;
    /** Server queue */
    public static final byte SRV_QUEUE = 1;
    /** Login message */
    public static final byte MSG_LOGIN = 2;
    /** Test prepare */
    public static final byte TEST_PREPARE = 3;
    /** Test start */
    public static final byte TEST_START = 4;
    /** Test message */
    public static final byte TEST_MSG = 5;
    /** Test finalize */
    public static final byte TEST_FINALIZE = 6;
    /** Error message */
    public static final byte MSG_ERROR = 7;
    /** Results message */
    public static final byte MSG_RESULTS = 8;
    /** Logout message */
    public static final byte MSG_LOGOUT = 9;
    /** Waiting message */
    public static final byte MSG_WAITING = 10;
    /** Extended login message */
    public static final byte MSG_EXTENDED_LOGIN = 11;
}
