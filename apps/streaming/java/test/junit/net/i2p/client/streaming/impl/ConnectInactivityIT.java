package net.i2p.client.streaming.impl;

import java.util.Properties;

import org.junit.Test;


import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.IncomingConnectionFilter;
import net.i2p.util.Log;

/**
 *
 */
public class ConnectInactivityIT extends StreamingITBase {
    private static final long LONG_TIME = 60 * 1000;

    private static void sleep() throws Exception {
        Thread.sleep(LONG_TIME);
    }

    private Log _log;
    private I2PSession _client;
    private I2PSession _server;

    @Test
    public void test() throws Exception {
        I2PAppContext context = I2PAppContext.getGlobalContext();
        _log = context.logManager().getLog(ConnectIT.class);
        _log.debug("Creating server session");
        _server = createSession();
        _log.debug("Running server");
        runServer(context, _server);
        _log.debug("Creating client session");
        _client = createSession();

        _log.debug("Running client");
        Thread client = runClient(context, _client);
        client.join(LONG_TIME + 1000);
    }

    @Override
    protected Runnable getClient(I2PAppContext ctx, I2PSession session) {
        return new ClientRunner(ctx,session);
    }

    @Override
    protected Runnable getServer(I2PAppContext ctx, I2PSession session) {
        return new ServerRunner(ctx,session);
    }

    private class ServerRunner extends RunnerBase {
        public ServerRunner(I2PAppContext ctx, I2PSession session) {
            super(ctx,session);
        }

        public void run() {
            try {
                Properties opts = new Properties();
                I2PSocketManager mgr = new I2PSocketManagerFull(
                    _context, _session, opts, "client", IncomingConnectionFilter.ALLOW);
                _log.debug("I2P Socket Manager created");
                I2PServerSocket ssocket = mgr.getServerSocket();
                _log.debug("Server socket created");
                I2PSocket socket = ssocket.accept();
                sleep();
                _log.debug("Socket accepted: " + socket);
                socket.close();
                ssocket.close();
                _session.destroySession();
            } catch (Exception e) {
                fail(e.getMessage());
                _log.error("Error running", e);
            }
        }

    }

    private class ClientRunner extends RunnerBase {
        public ClientRunner(I2PAppContext ctx, I2PSession session) {
            super(ctx,session);
        }

        public void run() {
            try {
                Properties opts = new Properties();
                I2PSocketManager mgr = new I2PSocketManagerFull(
                    _context, _session, opts, "client", IncomingConnectionFilter.ALLOW);
                _log.debug("I2P Socket Manager created");
                I2PSocket socket = mgr.connect(_server.getMyDestination());
                _log.debug("Socket created");
                sleep();
                socket.close();
                _log.debug("Socket closed");
                //_session.destroySession();
            } catch (Exception e) {
                fail(e.getMessage());
                _log.error("Error running", e);
            }
        }

    }

    @Override
    protected Properties getProperties() {
        Properties p = new Properties();
//        p.setProperty(I2PClient.PROP_TCP_HOST, "localhost");
//        p.setProperty(I2PClient.PROP_TCP_PORT, "10001");
        return p;
    }
}
