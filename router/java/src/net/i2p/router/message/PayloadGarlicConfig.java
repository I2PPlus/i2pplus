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
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.i2np.I2NPMessage;

/**
 * A GarlicConfig that holds a single I2NPMessage payload with no sub-cloves.
 *
 * This is the leaf node in the garlic clove tree. Every clove in an outbound
 * GarlicMessage is a PayloadGarlicConfig wrapping one I2NPMessage (DataMessage,
 * DeliveryStatusMessage, DatabaseStoreMessage, etc.) along with its delivery
 * instructions.
 *
 * Also used directly by netdb MessageWrapper and tunnel TestJob for single-clove
 * garlic wrapping.
 */
public class PayloadGarlicConfig extends GarlicConfig {
    private final I2NPMessage _payload;

    public PayloadGarlicConfig(Certificate cert, long id, long expiration,
                               DeliveryInstructions di, I2NPMessage message) {
	super(null, cert, id, expiration, di);
	_payload = message;
    }

    /**
     * Specify the I2NP message to be sent - if this is set, no other cloves can be included
     * in this block
     */
    public I2NPMessage getPayload() { return _payload; }

    @Override
    protected String getSubData() {
	return "<payloadMessage>" + _payload + "</payloadMessage>";
    }

    /**
     *  Not supported for single-clove configs.
     *
     *  @since 0.9.12
     *  @throws UnsupportedOperationException always
     */
    @Override
    public void addClove(GarlicConfig config) {
        throw new UnsupportedOperationException();
    }

    /**
     *  Return zero, as this is a single-clove config.
     *
     *  @return zero
     *  @since 0.9.12
     */
    @Override
    public int getCloveCount() { return 0; }

    /**
     *  Not supported for single-clove configs.
     *
     *  @since 0.9.12
     *  @throws UnsupportedOperationException always
     */
    @Override
    public GarlicConfig getClove(int index) {
        throw new UnsupportedOperationException();
    }

    /**
     *  No-op for single-clove configs.
     *
     *  @since 0.9.12
     */
    @Override
    public void clearCloves() { /* Intentionally empty - single-clove config */ }
}
