package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import org.junit.Test;
import org.mockito.Mockito;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;

public class TestI2PClientFactory {

    @Test
    public void testCreateClientWithNullContextUsesGlobal() {
        I2PClient client = I2PClientFactory.createClient((I2PAppContext) null);
        assertNotNull(client);
    }

    @Test
    public void testCreateClientWithMockContext() {
        I2PAppContext mockCtx = Mockito.mock(I2PAppContext.class);
        I2PClient client = I2PClientFactory.createClient(mockCtx);
        assertNotNull(client);
    }
}
