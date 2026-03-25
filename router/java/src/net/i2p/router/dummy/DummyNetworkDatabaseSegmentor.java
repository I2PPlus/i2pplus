package net.i2p.router.dummy;

import net.i2p.data.Hash;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.kademlia.SegmentedNetworkDatabaseFacade;

/**
 *  Dummy network database segmentor for testing.
 *  @since 0.9.61
 */
public class DummyNetworkDatabaseSegmentor extends SegmentedNetworkDatabaseFacade {
    private final NetworkDatabaseFacade _fndb;

    public DummyNetworkDatabaseSegmentor(RouterContext ctx) {
        _fndb = new DummyNetworkDatabaseFacade(ctx);
    }

    @Override
    public void shutdown() {
        _fndb.shutdown();
    }

    @Override
    public void startup() {
        _fndb.startup();
    }

    @Override
    public NetworkDatabaseFacade mainNetDB() {
        return _fndb;
    }

    @Override
    public NetworkDatabaseFacade clientNetDB(Hash id) {
        return _fndb;
    }
}
