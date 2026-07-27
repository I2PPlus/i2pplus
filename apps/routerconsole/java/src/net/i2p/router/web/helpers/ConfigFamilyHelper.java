package net.i2p.router.web.helpers;

import net.i2p.router.crypto.FamilyKeyCrypto;
import net.i2p.router.web.HelperBase;

/**
 * Helper for family configuration page rendering and form processing.
 * @since 0.9.33
 */
public class ConfigFamilyHelper extends HelperBase {

    /**
     * Creates a new instance of the family configuration helper.
     */
    public ConfigFamilyHelper() {
        super();
    }

    /**
     * Get the family name.
     * @return the family name
     */
    public String getFamily() {
        return _context.getProperty(FamilyKeyCrypto.PROP_FAMILY_NAME, "");
    }

    /**
     * Get the key password.
     * @return the key password
     */
    public String getKeyPW() {
        return _context.getProperty(FamilyKeyCrypto.PROP_KEY_PASSWORD, "");
    }
}
