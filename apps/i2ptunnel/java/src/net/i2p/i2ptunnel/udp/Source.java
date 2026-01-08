package net.i2p.i2ptunnel.udp;

/**
 *  Source interface for streaming data sources.
 *
 *  @author welterde
 *  @since 0.9.53
 */
public interface Source {
    /**
     *  Sets the sink for received data.
     *
     *  @param sink the sink to receive data
     */
    public void setSink(Sink sink);
    /**
     *  Starts the source.
     */
    public void start();
}
