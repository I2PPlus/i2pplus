package net.i2p.kademlia;

import net.i2p.data.SimpleDataStructure;

/**
 * Visit kbuckets, gathering matches
 *
 *
 * @param <T> type of SimpleDataStructure objects being collected
 * @since 0.9.2 in i2psnark, moved to core in 0.9.10
 */
public interface SelectionCollector<T extends SimpleDataStructure> {
    /**
     *  Add an entry to this collector.
     */
    public void add(T entry);
}
