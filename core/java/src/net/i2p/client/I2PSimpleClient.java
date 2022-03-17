package net.i2p.client;

/*
 * Released into the public domain
 * with no warranty of any kind, either expressed or implied.
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.impl.I2PSimpleSession;
import net.i2p.crypto.SigType;
import net.i2p.data.Certificate;
import net.i2p.data.Destination;

/**
 * Simple client implementation with no Destination,
 * just used to talk to the router.
 */
public class I2PSimpleClient implements I2PClient {

    /**
     *  @deprecated Don't do this
     *  @throws UnsupportedOperationException always
     */
    @Deprecated
    public Destination createDestination(OutputStream destKeyStream) throws I2PException, IOException {
        throw new UnsupportedOperationException();
    }

    /**
     *  @deprecated Don't do this
     *  @throws UnsupportedOperationException always
     *  @since 0.9.12
     */
    @Deprecated
    public Destination createDestination(OutputStream destKeyStream, SigType type) throws I2PException, IOException {
        throw new UnsupportedOperationException();
    }

    /**
     *  @deprecated Don't do this
     *  @throws UnsupportedOperationException always
     */
    @Deprecated
    public Destination createDestination(OutputStream destKeyStream, Certificate cert) throws I2PException, IOException {
        throw new UnsupportedOperationException();
    }

    /**
     * Create a new session (though do not connect it yet)
     *
     */
    public I2PSession createSession(InputStream destKeyStream, Properties options) throws I2PSessionException {
        return createSession(I2PAppContext.getGlobalContext(), options);
    }

    /**
     * Create a new session (though do not connect it yet)
     *
     */
    public I2PSession createSession(I2PAppContext context, Properties options) throws I2PSessionException {
        return new I2PSimpleSession(context, options);
    }
}
