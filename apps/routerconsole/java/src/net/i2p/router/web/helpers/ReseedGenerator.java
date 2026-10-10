package net.i2p.router.web.helpers;

import java.io.File;
import java.io.IOException;
import net.i2p.router.networkdb.reseed.ReseedBundler;
import net.i2p.router.web.HelperBase;

/**
 *  Handler to create a i2preseed.zip file
 *  @since 0.9.19
 */
public class ReseedGenerator extends HelperBase {

    /**
     * A stateless handler; createZip() builds a fresh ReseedBundler per call, so a
     * bare instance needs no further setup.
     */
    public ReseedGenerator() {}

    /**
     * createZip.
     *
     * @return the newly written i2preseed.zip file
     * @throws IOException if the reseed directory or the zip cannot be written
     */
    public File createZip() throws IOException {
        ReseedBundler rb = new ReseedBundler(_context);
        return rb.createZip(200);
    }
}
