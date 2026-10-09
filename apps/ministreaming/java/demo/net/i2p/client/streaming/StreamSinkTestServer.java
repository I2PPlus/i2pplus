package net.i2p.client.streaming;

/**
 * Manual throughput probe for a streaming sender: accepts one connection,
 * streams fixed-size chunks and reports bytes per second to the peer.
 */
public class StreamSinkTestServer {
    public static void main(String[] args) {
        //System.setProperty(I2PClient.PROP_TCP_HOST, "dev.i2p.net");
        //System.setProperty(I2PClient.PROP_TCP_PORT, "4101");
        System.setProperty("tunnels.depthInbound", "0");

        new Thread(new Runnable() {
            public void run() {
                StreamSinkServer.main(new String[] { "streamSinkTestLiveDir", "streamSinkTestLiveServer.key" });
            }
        }, "server").start();
    }
}
