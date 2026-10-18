package net.i2p.router.networkdb.kademlia;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import net.i2p.router.RouterContext;

/**
 * Tests {@link KademliaNetworkDatabaseFacade#bucketSize}: the property
 * default is 64 and values below the KBucketSet minimum are clamped up.
 *
 * @since 0.9.71
 */
public class BucketSizeDecisionTest {

    @Test
    public void defaultsTo64() {
        RouterContext ctx = mock(RouterContext.class);
        when(ctx.getProperty(eq(KademliaNetworkDatabaseFacade.PROP_BUCKET_SIZE), anyLong()))
                .thenAnswer(i -> i.getArgument(1));
        assertEquals(64, KademliaNetworkDatabaseFacade.bucketSize(ctx));
    }

    @Test
    public void clampsBelowMinimum() {
        RouterContext ctx = mock(RouterContext.class);
        when(ctx.getProperty(eq(KademliaNetworkDatabaseFacade.PROP_BUCKET_SIZE), anyLong()))
                .thenReturn(1L);
        assertEquals(5, KademliaNetworkDatabaseFacade.bucketSize(ctx));
    }

    @Test
    public void honorsLargerProperty() {
        RouterContext ctx = mock(RouterContext.class);
        when(ctx.getProperty(eq(KademliaNetworkDatabaseFacade.PROP_BUCKET_SIZE), anyLong()))
                .thenReturn(100L);
        assertEquals(100, KademliaNetworkDatabaseFacade.bucketSize(ctx));
    }
}
