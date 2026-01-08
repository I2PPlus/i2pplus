package net.i2p.i2ptunnel.udp;

/**
 *  Stream interface for managing streaming data flow.
 *
 *  @author welterde
 *  @since 0.9.53
 */
public interface Stream {
    /**
     *  Starts the stream.
     */
    public void start();
    /**
     *  Stops the stream.
     */
    public void stop();
}
