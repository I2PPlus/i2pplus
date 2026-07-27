package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Certificate;
import net.i2p.data.DataHelper;
import net.i2p.data.i2np.GarlicClove;

/**
 * The unwrapped result of decrypting a GarlicMessage: an array of GarlicCloves
 * plus the outer certificate, message ID, and expiration.
 *
 * For ElGamal-encrypted messages the certificate/ID/expiration are serialized
 * inside the clove set; for ECIES-encrypted messages they are synthesized with
 * Certificate.NULL_CERT at construction time.
 *
 * @since public since 0.9.44, was package private
 */
public class CloveSet {
    private final GarlicClove[] _cloves;
    private final Certificate _cert;
    private final long _msgId;
    private final long _expiration;

    /**
     *  Create a new CloveSet.
     *
     *  @param cloves non-null, all entries non-null
     *  @param cert non-null
     *  @param msgId the message ID
     *  @param expiration the expiration time
     */
    public CloveSet(GarlicClove[] cloves, Certificate cert, long msgId, long expiration) {
   _cloves = cloves;
        _cert = cert;
   _msgId = msgId;
   _expiration = expiration;
    }

    /**
     *  Get the number of cloves.
     *  @return the clove count
     */
    public int getCloveCount() { return _cloves.length; }

    /**
     *  Get the clove at the specified index.
     *
     *  @param index the index
     *  @return the clove at the given index
     *  @throws ArrayIndexOutOfBoundsException if the index is out of range
     */
    public GarlicClove getClove(int index) { return _cloves[index]; }

    /**
     *  Get the certificate.
     *  @return the certificate
     */
    public Certificate getCertificate() { return _cert; }

    /**
     *  Get the message ID.
     *  @return the message ID
     */
    public long getMessageId() { return _msgId; }

    /**
     *  Get the expiration.
     *  @return the expiration time
     */
    public long getExpiration() { return _expiration; }

    @Override
    public String toString() {
	StringBuilder buf = new StringBuilder(128);
	buf.append("CloveSet: ID ").append(_msgId)
           .append(' ').append(_cert)
           .append(" expires " ).append(DataHelper.formatTime(_expiration))
           .append(" cloves: " ).append(_cloves.length)
	   .append(" {");
	for (int i = 0; i < _cloves.length; i++) {
	    GarlicClove clove = _cloves[i];
	    if (clove.getData() != null) {
		buf.append(clove.getData().getClass().getSimpleName());
	    } else {
		buf.append("[null clove]");
	    }
            if (i < _cloves.length - 1) {
		buf.append(", ");
	    }
	}
	buf.append('}');
	return buf.toString();
    }
}
