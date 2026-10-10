package net.i2p.client.streaming;

/**
 * Manual throughput probe for a streaming receiver: connects to a host and port,
 * opens one stream and reports bytes per second until the server closes it.
 */
public class StreamSinkTestClient {
    /**
     * The probe is driven entirely from main(); the field overrides it sets there are
     * process-wide, so an instance starts nothing.
     */
    public StreamSinkTestClient() {}

    /**
     * Sends each named file through a stream and waits for the receiver to finish
     *
     * @param args the files to send; when empty a hard-coded library file is sent instead
     */
    public static void main(String[] args) {
        //System.setProperty(I2PClient.PROP_TCP_HOST, "dev.i2p.net");
        //System.setProperty(I2PClient.PROP_TCP_PORT, "4501");
        System.setProperty("tunnels.depthInbound", "0");

        if (args.length <= 0) {
            send("/home/jrandom/libjbigi.so");
        } else {
            for (int i = 0; i < args.length; i++)
                send(args[i]);
        }
    }

    private static void send(final String filename) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                StreamSinkSend.main(new String[] { filename, "0", "streamSinkTestLiveServer.key" });
            }
        }, "client " + filename);
        t.start();
        try { t.join(); } catch (Exception e) {}
        System.err.println("Done sending");
        try { Thread.sleep(120*1000); } catch (Exception e) {}
        //System.exit(0);
    }
}
