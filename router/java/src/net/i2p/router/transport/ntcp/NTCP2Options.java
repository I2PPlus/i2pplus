package net.i2p.router.transport.ntcp;

import net.i2p.data.DataHelper;

/**
 *
 *  NTCP2 Padding/Dummy/Delay configuration for data phase.
 *  Any other options TBD.
 *
 *  @since 0.9.36
 */
class NTCP2Options {

    private final float _sendMin;
    private final float _sendMax;
    private final float _recvMin;
    private final float _recvMax;
    private final int _sendDummy;
    private final int _recvDummy;
    private final int _sendDelay;
    private final int _recvDelay;

    /** All params are as named. */
    public NTCP2Options(float sendMin, float sendMax, float recvMin, float recvMax,
                        int sendDummy, int recvDummy, int sendDelay, int recvDelay) {
        _sendMin = sendMin;
        _sendMax = sendMax;
        _recvMin = recvMin;
        _recvMax = recvMax;
        _sendDummy = sendDummy;
        _recvDummy = recvDummy;
        _sendDelay = sendDelay;
        _recvDelay = recvDelay;
    }

    /**
     * The send padding min.
     * @return the send min
     */
    public float getSendMin() { return _sendMin; }
    /**
     * The send padding max.
     * @return the send max
     */
    public float getSendMax() { return _sendMax; }
    /**
     * The receive padding min.
     * @return the recv min
     */
    public float getRecvMin() { return _recvMin; }
    /**
     * The receive padding max.
     * @return the recv max
     */
    public float getRecvMax() { return _recvMax; }
    /**
     * The send dummy data rate in bytes per second.
     * @return the send dummy
     */
    public int getSendDummy() { return _sendDummy; }
    /**
     * The receive dummy data rate in bytes per second.
     * @return the recv dummy
     */
    public int getRecvDummy() { return _recvDummy; }
    /**
     * The send delay in milliseconds.
     * @return the send delay
     */
    public int getSendDelay() { return _sendDelay; }
    /**
     * The receive delay in milliseconds.
     * @return the recv delay
     */
    public int getRecvDelay() { return _recvDelay; }

    /**
     *  Merge our options with the peer's options.
     *  @return new merged options
     */
    public NTCP2Options merge(NTCP2Options his) {
        float xsMin = Math.max(_sendMin, his.getRecvMin());
        float xsMax = Math.min(_sendMax, his.getRecvMax());
        if (xsMin > xsMax)
            xsMin = xsMax;

        float xrMin = Math.max(_recvMin, his.getSendMin());
        float xrMax = Math.min(_recvMax, his.getSendMax());
        if (xrMin > xrMax)
            xrMin = xrMax;

        int xsDummy = Math.min(_sendDummy, his.getRecvDummy());
        int xrDummy = Math.min(_recvDummy, his.getSendDummy());
        int xsDelay = Math.min(_sendDelay, his.getRecvDelay());
        int xrDelay = Math.min(_recvDelay, his.getSendDelay());

        return new NTCP2Options(xsMin, xsMax, xrMin, xrMax,
                                xsDummy, xrDummy, xsDelay, xrDelay);
    }

    /**
     *  @return null on error
     *  @since 0.9.37 consolidated from two places
     */
    public static NTCP2Options fromByteArray(byte[] options) {
        if (options.length < 12)
            return null;
        float tmin = (options[0] & 0xff) / 16.0f;
        float tmax = (options[1] & 0xff) / 16.0f;
        float rmin = (options[2] & 0xff) / 16.0f;
        float rmax = (options[3] & 0xff) / 16.0f;
        int tdummy = (int) DataHelper.fromLong(options, 4, 2);
        int rdummy = (int) DataHelper.fromLong(options, 6, 2);
        int tdelay = (int) DataHelper.fromLong(options, 8, 2);
        int rdelay = (int) DataHelper.fromLong(options, 10, 2);
        return new NTCP2Options(tmin, tmax, rmin, rmax,
                                tdummy, rdummy, tdelay, rdelay);
    }

    /**
     * String representation of these padding options.
     */
    @Override
    public String toString() {
        return "Padding options: send min/max %: (" + (_sendMin * 100) + ", " + (_sendMax * 100) +
               ") recv min/max %: (" + (_recvMin * 100) + ", " + (_recvMax * 100) +
               ") dummy send/recv B/s: (" + _sendDummy + ", " + _recvDummy +
               ") delay send/recv ms: (" + _sendDelay + ", " + _recvDelay + ')';
    }
}
