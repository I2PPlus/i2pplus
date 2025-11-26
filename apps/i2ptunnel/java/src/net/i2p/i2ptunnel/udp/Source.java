package net.i2p.i2ptunnel.udp;

/**
 * Source interface for streaming data sources.
 *
 * @author welterde
 */
public interface Source {
    public void setSink(Sink sink);
    public void start();
}
