package net.i2p.data.i2cp;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.util.ByteArrayStream;

/**
 * Defines the message a router sends to a client to request that
 * a leaseset be created and signed. The reply is a CreateLeaseSetMessage.
 *
 * @author jrandom
 */
public class RequestLeaseSetMessage extends I2CPMessageImpl {

    private static final long serialVersionUID = 1L;
    public final static int MESSAGE_TYPE = 21;
    private SessionId _sessionId;
    // ArrayList is Serializable, List is not
    private final ArrayList<TunnelEndpoint> _endpoints;
    private Date _end;

    public RequestLeaseSetMessage() {_endpoints = new ArrayList<TunnelEndpoint>(6);}

    public SessionId getSessionId() {return _sessionId;}

    /**
     * Return the SessionId for this message.
     *
     * @since 0.9.21
     */
    @Override
    public SessionId sessionId() {return _sessionId;}

    public void setSessionId(SessionId id) {_sessionId = id;}

    public int getEndpoints() {return _endpoints.size();}

    public Hash getRouter(int endpoint) {
        if ((endpoint < 0) || (_endpoints.size() <= endpoint)) {return null;}
        return _endpoints.get(endpoint).getRouter();
    }

    public TunnelId getTunnelId(int endpoint) {
        if ((endpoint < 0) || (_endpoints.size() <= endpoint)) {return null;}
        return _endpoints.get(endpoint).getTunnelId();
    }

    public void addEndpoint(Hash router, TunnelId tunnel) {
        if (router == null) {throw new IllegalArgumentException("Null Router -> [TunnelId " + tunnel + "]");}
        if (tunnel == null) {throw new IllegalArgumentException("Null Tunnel -> Router [" + router.toBase64().substring(0,6) + "]");}
        _endpoints.add(new TunnelEndpoint(router, tunnel));
    }

    public Date getEndDate() {return _end;}

    public void setEndDate(Date end) {_end = end;}

    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = new SessionId();
            _sessionId.readBytes(in);
            int numTunnels = in.read();
            // EOF will be caught below
            _endpoints.clear();
            for (int i = 0; i < numTunnels; i++) {
                Hash router = Hash.create(in);
                TunnelId tunnel = new TunnelId();
                tunnel.readBytes(in);
                _endpoints.add(new TunnelEndpoint(router, tunnel));
            }
            _end = DataHelper.readDate(in);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }

    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if (_sessionId == null) {
            throw new I2CPMessageException("Unable to write out the message as there is not enough data");
        }
        int len = 2 + 1 + (_endpoints.size() * (32 + 4)) + 8;
        ByteArrayStream os = new ByteArrayStream(len);
        try {
            _sessionId.writeBytes(os);
            os.write((byte) _endpoints.size());
            for (int i = 0; i < _endpoints.size(); i++) {
                Hash router = getRouter(i);
                router.writeBytes(os);
                TunnelId tunnel = getTunnelId(i);
                tunnel.writeBytes(os);
            }
            DataHelper.writeDate(os, _end);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error writing out the message data", dfe);
        }
        return os.toByteArray();
    }

    public int getType() {return MESSAGE_TYPE;}

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("RequestLeaseSetMessage: ")
           .append("\n* SessionId: ").append(getSessionId())
           .append("\n* Tunnels:");
        for (int i = 0; i < getEndpoints(); i++) {
            buf.append("\n* RouterIdentity: ").append(getRouter(i))
               .append("\n* TunnelId: ").append(getTunnelId(i));
        }
        buf.append("\n* EndDate: ").append(getEndDate());
        return buf.toString();
    }

    private static class TunnelEndpoint implements Serializable {
        private static final long serialVersionUID = 1L;
        private final Hash _router;
        private final TunnelId _tunnelId;

        public TunnelEndpoint(Hash router, TunnelId id) {
            _router = router;
            _tunnelId = id;
        }

        public Hash getRouter() {return _router;}

        public TunnelId getTunnelId() {return _tunnelId;}
    }

}
