package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import net.i2p.data.Certificate;
import net.i2p.data.PublicKey;
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.router.RouterInfo;

/**
 * Configuration and content for a garlic message containing one or more cloves.
 *
 * This is the top-level container for a GarlicMessage's cloves, their delivery
 * instructions, and the recipient's encryption key. Individual clove payloads
 * are represented by PayloadGarlicConfig.
 *
 * Despite the name, instances of this class hold the actual clove references,
 * not just configuration metadata.
 */
class GarlicConfig {
    private RouterInfo _recipient;
    private PublicKey _recipientPublicKey;
    private final Certificate _cert;
    private final long _id;
    private final long _expiration;
    private final List<GarlicConfig> _cloveConfigs;
    private final DeliveryInstructions _instructions;

    public GarlicConfig(Certificate cert, long id, long expiration, DeliveryInstructions di) {
	this(new ArrayList<>(4), cert, id, expiration, di);
    }

    protected GarlicConfig(List<GarlicConfig> cloveConfigs, Certificate cert, long id,
                           long expiration, DeliveryInstructions di) {
        _cert = cert;
	_id = id;
	_expiration = expiration;
        _cloveConfigs = cloveConfigs;
        _instructions = di;
    }

    /**
     * Router to receive and process this clove - the router that will open the
     * delivery instructions and decide what to do process it locally as an I2NPMessage,
     * forward it as an I2NPMessage to a router, forward it as an I2NPMessage to a Destination,
     * or forward it as an I2NPMessage to a tunnel.
     *
     * Used only if recipient public key is not set.
     *
     */
    public void setRecipient(RouterInfo info) { _recipient = info; }
    public RouterInfo getRecipient() { return _recipient; }

    /**
     * Public key of the router to receive and process this clove.  This is useful
     * for garlic routed messages encrypted to the router at the end of a tunnel,
     * as their RouterIdentity is not known, but a PublicKey they handle is exposed
     * via the LeaseSet
     *
     */
    public void setRecipientPublicKey(PublicKey recipientPublicKey) { _recipientPublicKey = recipientPublicKey; }
    public PublicKey getRecipientPublicKey() { return _recipientPublicKey; }

    /**
     * Certificate for the getRecipient() to pay for their processing
     *
     */
    public Certificate getCertificate() { return _cert; }

    /**
     * Unique ID of the clove
     *
     */
    public long getId() { return _id; }

    /**
     * Expiration of the clove, after which it should be dropped
     *
     */
    public long getExpiration() { return _expiration; }

    /**
     * Specify how the I2NPMessage in the clove should be handled.
     *
     */
    public DeliveryInstructions getDeliveryInstructions() { return _instructions; }

    /**
     * Add a clove to the current message - if any cloves are added, an I2NP message
     * cannot be specified via setPayload.  This means that the resulting GarlicClove
     * represented by this GarlicConfig must be a GarlicMessage itself
     *
     */
    public void addClove(GarlicConfig config) {
	if (config != null) {
	    _cloveConfigs.add(config);
	}
    }

    public int getCloveCount() { return _cloveConfigs.size(); }

    public GarlicConfig getClove(int index) { return _cloveConfigs.get(index); }

    public void clearCloves() { _cloveConfigs.clear(); }

    protected String getSubData() { return ""; }

    private static final String NL = System.getProperty("line.separator");

    @Override
    public String toString() {
	StringBuilder buf = new StringBuilder();
	buf.append("<garlicConfig>").append(NL);
	buf.append("<certificate>").append(getCertificate()).append("</certificate>").append(NL);
	buf.append("<instructions>").append(getDeliveryInstructions()).append("</instructions>").append(NL);
	buf.append("<expiration>").append(new Date(getExpiration())).append("</expiration>").append(NL);
	buf.append("<garlicId>").append(getId()).append("</garlicId>").append(NL);
	buf.append("<recipient>").append(getRecipient()).append("</recipient>").append(NL);
	buf.append("<recipientPublicKey>").append(getRecipientPublicKey()).append("</recipientPublicKey>").append(NL);
	buf.append(getSubData());
	buf.append("<subcloves>").append(NL);
	for (int i = 0; i < getCloveCount(); i++)
	    buf.append("<clove>").append(getClove(i)).append("</clove>").append(NL);
	buf.append("</subcloves>").append(NL);
	buf.append("</garlicConfig>").append(NL);
	return buf.toString();
    }
}
