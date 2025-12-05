package net.i2p.router.news;

import net.i2p.data.DataHelper;

/**
 * Data structure for Certificate Revocation List (CRL) entries.
 * <p>
 * Represents individual revocation entries with identifier, data payload,
 * and timestamp information. Provides proper equals and hashCode
 * implementations for use in collections and maps.
 * <p>
 * Designed for handling certificate revocation information in I2P
 * news feeds, supporting validation and comparison operations.
 * <p>
 * All String fields may be null to accommodate optional
 * metadata elements in revocation lists.
 *
 * @since 0.9.26
 */
public class CRLEntry {
    public String data;
    public String id;
    public long updated;

    @Override
    public boolean equals(Object o) {
        if(o == null)
            return false;
        if(!(o instanceof CRLEntry))
            return false;
        CRLEntry e = (CRLEntry) o;
        return updated == e.updated &&
               DataHelper.eq(id, e.id) &&
               DataHelper.eq(data, e.data);
    }

    @Override
    public int hashCode() {
        return (int) updated;
    }
}
