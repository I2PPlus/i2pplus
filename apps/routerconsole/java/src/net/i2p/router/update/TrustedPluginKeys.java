package net.i2p.router.update;

import java.util.HashMap;
import java.util.Map;

/**
 *  Plugin keys we know about.
 *  Contact I2P devs to be added to this list.
 *
 *  @since 0.9.14.1
 */
class TrustedPluginKeys {

    private static final String[] KEYS = {
        "zzz-plugin@mail.i2p",
        "Z3xbCcZiIA44W65~q4u5Rm9ZZWvBIv1bCvTx8DrbsKefu0PZ1134xzkI~vyXuRmmujvSwTVTfgEnxL81hwmpuB4aXMBLDlBmckspFnGKte~HefYI-6WcK79rnZPvNQCffdgi~EgWnUMYDR20PBWQKaGwajkSb-LOK~l2Z69G6aI="
    };

    /**
     *  @return map of B64 DSA keys to signer names
     */
    public static Map<String, String> getKeys() {
        Map<String, String> rv = new HashMap<String, String>(KEYS.length / 2);
        for (int i = 0; i < KEYS.length; i += 2) {
            rv.put(KEYS[i+1], KEYS[i]);
        }
        return rv;
    }
}
