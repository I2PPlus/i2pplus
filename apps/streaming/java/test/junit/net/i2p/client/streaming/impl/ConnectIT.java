package net.i2p.client.streaming.impl;

import java.util.Properties;

import org.junit.Before;
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
public class ConnectIT extends StreamingITBase {
    private Log _log;
    private I2PSession _server;

    @Before
    public void setUp() throws Exception {
//        System.setProperty(I2PClient.PROP_TCP_HOST, "localhost");
//        System.setProperty(I2PClient.PROP_TCP_PORT, "11001");
    }

    @Test
    public void test() throws Exception {
        I2PAppContext context = I2PAppContext.getGlobalContext();
        _log = context.logManager().getLog(ConnectIT.class);
        _log.debug("Creating server session");
        _server = createSession();
        _log.debug("Running server");
        Thread server = runServer(context, _server);
        Thread [] clients = new Thread[5];
        for (int i = 0; i < 5; i++) {
            _log.debug("Running client");
            clients[i] = runClient(context, createSession());
        }
        for (Thread c : clients)
            c.join();
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
                while (true) {
                    I2PSocket socket = ssocket.accept();
                    _log.debug("Socket accepted: " + socket);
                    Thread.sleep(5*1000);
                    socket.close();
                }
            } catch (Exception e) {
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
                Thread.sleep(5*1000);
                socket.close();
                _log.debug("Socket closed");
                mgr.destroySocketManager();
                mgr = null;
                socket = null;
            } catch (Exception e) {
                _log.error("Error running", e);
            }
        }

    }

    @Override
    protected Properties getProperties() {
        return System.getProperties();
    }
}
