package net.i2p.kademlia;

import net.i2p.data.SimpleDataStructure;

/**
 * Visit kbuckets, gathering matches
 * @since 0.9.2 in i2psnark, moved to core in 0.9.10
 * @param <T> type of SimpleDataStructure objects being collected
 */
public interface SelectionCollector<T extends SimpleDataStructure> {
    public void add(T entry);
}
