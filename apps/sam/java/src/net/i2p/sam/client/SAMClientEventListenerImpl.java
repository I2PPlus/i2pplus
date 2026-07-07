package net.i2p.sam.client;

import java.util.Properties;

/**
 * Basic noop client event listener
 */
public class SAMClientEventListenerImpl implements SAMReader.SAMClientEventListener {
    public void destReplyReceived(String publicKey, String privateKey) { /* no-op */ }
    public void helloReplyReceived(boolean ok, String version) { /* no-op */ }
    public void namingReplyReceived(String name, String result, String value, String message) { /* no-op */ }
    public void sessionStatusReceived(String result, String destination, String message) { /* no-op */ }
    public void streamClosedReceived(String result, String id, String message) { /* no-op */ }
    public void streamConnectedReceived(String remoteDestination, String id) { /* no-op */ }
    public void streamDataReceived(String id, byte[] data, int offset, int length) { /* no-op */ }
    public void streamStatusReceived(String result, String id, String message) { /* no-op */ }
    public void datagramReceived(String dest, byte[] data, int offset, int length, int fromPort, int toPort) { /* no-op */ }
    public void rawReceived(byte[] data, int offset, int length, int fromPort, int toPort, int protocol) { /* no-op */ }
    public void pingReceived(String data) { /* no-op */ }
    public void pongReceived(String data) { /* no-op */ }
    public void unknownMessageReceived(String major, String minor, Properties params) { /* no-op */ }
}
