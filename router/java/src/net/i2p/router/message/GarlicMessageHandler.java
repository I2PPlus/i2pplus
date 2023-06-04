package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Random;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.HandlerJobBuilder;
import net.i2p.router.Job;
import net.i2p.router.RouterContext;

/**
 * HandlerJobBuilder to build jobs to handle GarlicMessages
 *
 * This is the handler for garlic message not received down a tunnel, which is the
 * case for floodfills receiving netdb messages.
 * It is not the handler for garlic messages received down a tunnel,
 * as InNetMessagePool short circuits tunnel messages,
 * and those garlic messages are handled in InboundMessageDistributor.
 */
public class GarlicMessageHandler implements HandlerJobBuilder {
    private final RouterContext _context;
    private final long _msgIDBloomXorLocal;
    private final long _msgIDBloomXorRouter;
    private final long _msgIDBloomXorTunnel;

    public GarlicMessageHandler(RouterContext context) {
        _context = context;
        _msgIDBloomXorLocal = new Random().nextLong();
        _msgIDBloomXorRouter = new Random().nextLong();
        _msgIDBloomXorTunnel = new Random().nextLong();
    }

    public GarlicMessageHandler(RouterContext context, long msgIDBloomXorLocal, long msgIDBloomXorRouter, long msgIDBloomXorTunnel) {
        _context = context;
        _msgIDBloomXorLocal = msgIDBloomXorLocal;
        _msgIDBloomXorRouter = msgIDBloomXorRouter;
        _msgIDBloomXorTunnel = msgIDBloomXorTunnel;
    }
    
    public Job createJob(I2NPMessage receivedMessage, RouterIdentity from, Hash fromHash) {
        HandleGarlicMessageJob job = new HandleGarlicMessageJob(_context, (GarlicMessage)receivedMessage, from, fromHash, _msgIDBloomXorLocal, _msgIDBloomXorRouter, _msgIDBloomXorTunnel);
        return job;
    }

}
