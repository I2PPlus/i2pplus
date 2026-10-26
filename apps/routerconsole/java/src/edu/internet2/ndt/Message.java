package edu.internet2.ndt;

/**
 * A message exchanged with the NDT server, composed of a type and a body.
 * Message types include COMM_FAILURE, SRV_QUEUE, MSG_LOGIN and TEST_PREPARE.
 * A message also carries a length; currently two bytes of the body array are
 * often used to store it (for example, the second and third array positions).
 * <p>
 * TODO: the message types could be merged into this class rather than kept in
 * {@link NDTConstants}, and Message/Type could become an enumeration so the
 * current MessageType assignment can be checked.
 *
 * @see MessageType for more Message Types.
 */
public class Message {

    /** Creates a new Message with default type and body. */
    public Message() {}

    /** Message type byte. */
    byte _yType;
    /**  Ya body. */
    byte[] _yaBody;

    /**
     * Get Message Type.
     *
     * @return byte indicating Message Type
     */
    public byte getType() {return _yType;}

    /**
     * Set Message Type.
     *
     * @param bParamType byte indicating Message Type
     */
    public void setType(byte bParamType) {this._yType = bParamType;}

    /**
     * Get Message body as array.
     *
     * @return byte array message body
     */
    public byte[] getBody() {return _yaBody;}

    /**
     * Set Message body, given a byte array input.
     *
     * @param baParamBody message body byte array
     */
    public void setBody(byte[] baParamBody) {
        int iParamSize = 0;
        if (baParamBody != null) {iParamSize = baParamBody.length;}
        _yaBody = new byte[iParamSize];
        System.arraycopy(baParamBody, 0, _yaBody, 0, iParamSize);
    }

    /**
     * Set Message body, given a byte array and a size parameter. This may be
     * useful if user wants to initialize the message, and then continue to
     * populate it later. This method is unused currently.
     *
     * @param baParamBody message body byte array
     * @param iParamSize byte array size
     */
    public void setBody(byte[] baParamBody, int iParamSize) {
        _yaBody = new byte[iParamSize];
        System.arraycopy(baParamBody, 0, _yaBody, 0, iParamSize);
    }

    /**
     * Utility method to initialize Message body.
     *
     * @param iParamSize byte array size
     */
    public void initBodySize(int iParamSize) {this._yaBody = new byte[iParamSize];}

}
