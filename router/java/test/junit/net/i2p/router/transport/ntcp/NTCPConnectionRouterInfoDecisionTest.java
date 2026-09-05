package net.i2p.router.transport.ntcp;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;

import org.junit.Test;

/**
 *  Unit tests for the RouterInfo-aging decisions extracted from
 *  {@link NTCPConnection#gotRI}.
 *
 *  <p>Covers the peer/self discriminations (used to decide whether to store the
 *  received RouterInfo in the network database) and the strictly-newer store
 *  gate that decides whether a second store replaces the first.
 *
 *  @since 0.9.71+
 */
public class NTCPConnectionRouterInfoDecisionTest {

    @Test
    public void testSamePeerIdentical() {
        Hash h = new Hash(new byte[Hash.HASH_LENGTH]);
        assertTrue(NTCPConnection.isSamePeer(h, h));
    }

    @Test
    public void testSamePeerDistinct() {
        Hash a = new Hash(new byte[Hash.HASH_LENGTH]);
        Hash b = new Hash(new byte[Hash.HASH_LENGTH]);
        b.getData()[0] = 1;
        assertFalse(NTCPConnection.isSamePeer(a, b));
    }

    @Test
    public void testOwnRouterInfoMatch() {
        Hash h = new Hash(new byte[Hash.HASH_LENGTH]);
        assertTrue(NTCPConnection.isOwnRouterInfo(h, h));
    }

    @Test
    public void testOwnRouterInfoDistinct() {
        Hash h = new Hash(new byte[Hash.HASH_LENGTH]);
        Hash other = new Hash(new byte[Hash.HASH_LENGTH]);
        other.getData()[0] = 1;
        assertFalse(NTCPConnection.isOwnRouterInfo(h, other));
    }

    @Test
    public void testNewerWhenNoPreviousCopy() {
        RouterInfo ri = mock(RouterInfo.class);
        when(ri.getPublished()).thenReturn(100L);
        assertTrue(NTCPConnection.isNewerOrNew(null, ri));
    }

    @Test
    public void testNewerStrictly() {
        RouterInfo old = mock(RouterInfo.class);
        when(old.getPublished()).thenReturn(50L);
        RouterInfo ri = mock(RouterInfo.class);
        when(ri.getPublished()).thenReturn(100L);
        assertTrue(NTCPConnection.isNewerOrNew(old, ri));
    }

    @Test
    public void testNotNewerOnEqualTimestamp() {
        RouterInfo old = mock(RouterInfo.class);
        when(old.getPublished()).thenReturn(100L);
        RouterInfo ri = mock(RouterInfo.class);
        when(ri.getPublished()).thenReturn(100L);
        assertFalse(NTCPConnection.isNewerOrNew(old, ri));
    }

    @Test
    public void testNotNewerWhenOlder() {
        RouterInfo old = mock(RouterInfo.class);
        when(old.getPublished()).thenReturn(100L);
        RouterInfo ri = mock(RouterInfo.class);
        when(ri.getPublished()).thenReturn(50L);
        assertFalse(NTCPConnection.isNewerOrNew(old, ri));
    }
}