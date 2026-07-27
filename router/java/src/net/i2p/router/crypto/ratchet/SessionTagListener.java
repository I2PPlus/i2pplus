package net.i2p.router.crypto.ratchet;

/**
 * Interface for managing session tag lifecycle in ratchet tag sets
 *
 * @since 0.9.44
 */
interface SessionTagListener {

    /**
     *  Map the tag to this tagset.
     *
     *  @param tag the session tag
     *  @param ts the tag set
     *  @return true if added, false if dup
     */
    public boolean addTag(RatchetSessionTag tag, RatchetTagSet ts);

    /**
     *  Remove the tag associated with this tagset.
     *
     *  @param tag the session tag
     *  @param ts the tag set
     */
    public void expireTag(RatchetSessionTag tag, RatchetTagSet ts);
}
