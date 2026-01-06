package net.i2p.stat;

/**
 * Receive the state of the rate when its coalesced
 */
public interface RateSummaryListener {
    /**
      *  Called to add rate statistics for a period.
      *
      * @param totalValue sum of all event values in the most recent period
      * @param eventCount how many events occurred
      * @param totalEventTime how long the events were running for
      * @param period how long this period is
      */
    void add(double totalValue, long eventCount, double totalEventTime, long period);
}
