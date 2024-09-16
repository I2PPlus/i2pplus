package net.i2p.data;

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
import java.io.OutputStream;
import java.util.Date;

import net.i2p.util.Clock;

/**
 * Defines the proof that a particular router / tunnel is allowed to receive
 * messages for a particular Destination during some period of time.
 *
 * @author jrandom
 */
public class Lease extends DataStructureImpl {
    protected Hash _gateway;
    protected TunnelId _tunnelId;
    protected long _end;

    public Lease() {}

    /** Retrieve the router at which the destination can be contacted
     * @return identity of the router acting as a gateway
     */
    public Hash getGateway() {return _gateway;}

    /** Configure the router at which the destination can be contacted
     * @param ident router acting as the gateway
     */
    public void setGateway(Hash ident) {_gateway = ident;}

    /** Tunnel on the gateway to communicate with
     * @return tunnel ID
     */
    public TunnelId getTunnelId() {return _tunnelId;}

    /** Configure the tunnel on the gateway to communicate with
     * @param id tunnel ID
     */
    public void setTunnelId(TunnelId id) {_tunnelId = id;}

    /**
     * @deprecated use getEndTime()
     */
    @Deprecated
    public Date getEndDate() {return new Date(_end);}

    /**
     * @deprecated use setEndDate(long)
     */
    @Deprecated
    public void setEndDate(Date date) {_end = date.getTime();}

    /**
     * @since 0.9.48
     */
    public long getEndTime() {return _end;}

    /**
     * @since 0.9.48
     */
    public void setEndDate(long date) {_end = date;}

    /** has this lease already expired? */
    public boolean isExpired() {return isExpired(0);}

    /** has this lease already expired (giving allowing up the fudgeFactor milliseconds for clock skew)? */
    public boolean isExpired(long fudgeFactor) {
        return _end < Clock.getInstance().now() - fudgeFactor;
    }

    public void readBytes(InputStream in) throws DataFormatException, IOException {
        //_gateway = new Hash();
        //_gateway.readBytes(in);
        _gateway = Hash.create(in);
        _tunnelId = new TunnelId();
        _tunnelId.readBytes(in);
        _end = DataHelper.readLong(in, 8);
    }

    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if ((_gateway == null) || (_tunnelId == null)) {
            throw new DataFormatException("Not enough data to write out a Lease");
        }

        _gateway.writeBytes(out);
        _tunnelId.writeBytes(out);
        DataHelper.writeLong(out, 8, _end);
    }

    @Override
    public boolean equals(Object object) {
        if (object == this) {return true;}
        if ((object == null) || !(object instanceof Lease)) {return false;}
        Lease lse = (Lease) object;
        return _end == lse.getEndTime() &&
                       DataHelper.eq(_tunnelId, lse.getTunnelId()) &&
                       DataHelper.eq(_gateway, lse.getGateway());

    }

    @Override
    public int hashCode() {
        return (int) _end ^ DataHelper.hashCode(_gateway) ^ DataHelper.hashCode(_tunnelId);
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(128);
        buf.append("\n* Lease:");
        buf.append(" [TunnelID ").append(_tunnelId).append("]");
        buf.append(" -> Gateway: [").append(_gateway.toBase64().substring(0,6) + "]");
        buf.append(" -> Expires: ").append(DataHelper.formatTime(_end));
        return buf.toString();
    }
}
