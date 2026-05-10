package net.i2p.router.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import net.i2p.router.RouterContext;
import net.i2p.router.util.RouterPasswordManager;
import net.i2p.data.DataHelper;

//import org.eclipse.jetty.util.security.UnixCrypt;

/**
 *  Manage both plaintext and salted/hashed password storage in
 *  router.config.
 *
 *  @since 0.9.4
 */
public class ConsolePasswordManager extends RouterPasswordManager {

    private static final String PROP_MIGRATED = "routerconsole.passwordManager.migrated";
    // migrate these to hash
    private static final String PROP_CONSOLE_OLD = "consolePassword";
    private static final String CONSOLE_USER = "admin";
    private static final String PROP_PBKDF2 = ".pbkdf2";
    private static final int PBKDF2_ITERATIONS = 1000000;

    public ConsolePasswordManager(RouterContext ctx) {
        super(ctx);
        migrateConsole();
    }

    /**
     *  The username is the salt
     *
     *  @param realm e.g. i2cp, routerconsole, etc.
     *  @param user null or "" for no user, already trimmed
     *  @param pw plain text, already trimmed
     *  @return if pw verified
     */
/****
    public boolean checkCrypt(String realm, String user, String pw) {
        String pfx = realm;
        if (user != null && user.length() > 0)
            pfx += '.' + user;
        cr = _context.getProperty(pfx + ".crypt");
        if (cr == null)
            return false;
        return cr.equals(UnixCrypt.crypt(pw, cr));
    }
****/

/**
     *  Straight MD5. Compatible with Jetty.
     *
     *  @param realm e.g. i2cp, routerconsole, etc.
     *  @param user null or "" for no user, already trimmed
     *  @param pw plain text, already trimmed
     *  @return if pw verified
     */
 public boolean checkMD5(String realm, String subrealm, String user, String pw) {
     // Check PBKDF2 first (new format)
     if (checkPBKDF2(realm, user, pw))
         return true;
     // Fall back to MD5 for backward compatibility
     String pfx = realm;
     if (user != null && user.length() > 0)
         pfx += '.' + user;
     String hex = _context.getProperty(pfx + PROP_MD5);
     if (hex == null)
         return false;
     return DataHelper.eqCT(md5Hex(subrealm, user, pw), hex);
 }

    /**
     *  PBKDF2 hash for password storage.
     *  Backward compatible - checks PBKDF2 first, falls back to MD5.
     *
     *  @param realm e.g. i2cp, routerconsole, etc.
     *  @param user null or "" for no user, already trimmed
     *  @param pw plain text, already trimmed
     *  @return if pw verified
     *  @since 0.9.70+
     */
    private boolean checkPBKDF2(String realm, String user, String pw) {
        String pfx = realm;
        if (user != null && user.length() > 0)
            pfx += '.' + user;
        String stored = _context.getProperty(pfx + PROP_PBKDF2);
        if (stored == null)
            return false;
        try {
            String[] parts = stored.split(":");
            if (parts.length != 3)
                return false;
            int iterations = Integer.parseInt(parts[0]);
            byte[] salt = HexDecode(parts[1]);
            byte[] hash = HexDecode(parts[2]);
            PBEKeySpec spec = new PBEKeySpec(pw.toCharArray(), salt, iterations, hash.length * 8);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] computed = skf.generateSecret(spec).getEncoded();
            return MessageDigest.isEqual(hash, computed);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            return false;
        }
    }

    private static byte[] HexDecode(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }

    /**
     *  Get all MD5 usernames and passwords. Compatible with Jetty.
     *  Any "null" user is NOT included..
     *
     *  @param realm e.g. i2cp, routerconsole, etc.
     *  @return Map of usernames to passwords (hex with leading zeros, 32 characters)
     */
    public Map<String, String> getMD5(String realm) {
        String pfx = realm + '.';
        Map<String, String> rv = new HashMap<String, String>(4);
        for (Map.Entry<String, String> e : _context.router().getConfigMap().entrySet()) {
            String prop = e.getKey();
            if (prop.startsWith(pfx) && prop.endsWith(PROP_MD5)) {
                String user = prop.substring(0, prop.length() - PROP_MD5.length()).substring(pfx.length());
                String hex = e.getValue();
                if (user.length() > 0 && hex.length() == 32)
                    rv.put(user, hex);
            }
        }
        return rv;
    }

    /**
     *  Get all users with PBKDF2 passwords.
     *
     *  @param realm e.g. routerconsole.auth.i2prouter
     *  @return Map of usernames to PBKDF2 hashes
     */
    public Map<String, String> getPBKDF2(String realm) {
        String pfx = realm + '.';
        Map<String, String> rv = new HashMap<String, String>(4);
        for (Map.Entry<String, String> e : _context.router().getConfigMap().entrySet()) {
            String prop = e.getKey();
            if (prop.startsWith(pfx) && prop.endsWith(PROP_PBKDF2)) {
                String user = prop.substring(0, prop.length() - PROP_PBKDF2.length()).substring(pfx.length());
                String hash = e.getValue();
                if (user.length() > 0 && hash != null && !hash.isEmpty())
                    rv.put(user, hash);
            }
        }
        return rv;
    }

    /**
     *  Migrate from plaintext to MD5 hash
     *  Ref: RFC 2617
     *
     *  @return success or nothing to migrate
     */
    private boolean migrateConsole() {
        synchronized(ConsolePasswordManager.class) {
            if (_context.getBooleanProperty(PROP_MIGRATED))
                return true;
            // consolePassword
            String pw = _context.getProperty(PROP_CONSOLE_OLD);
            if (pw != null) {
                Map<String, String> toAdd = new HashMap<String, String>(2);
                if (pw.length() > 0) {
                    saveMD5(RouterConsoleRunner.PROP_CONSOLE_PW, RouterConsoleRunner.JETTY_REALM,
                            CONSOLE_USER, pw);
                    toAdd.put(RouterConsoleRunner.PROP_PW_ENABLE, "true");
                }
                toAdd.put(PROP_MIGRATED, "true");
                List<String> toDel = Collections.singletonList(PROP_CONSOLE_OLD);
                return _context.router().saveConfig(toAdd, toDel);
            }
            return true;
        }
    }

    /**
     *  This will fail if
     *  user contains '#' or '=' or starts with '!'
     *  The user is the salt.
     *
     *  @param realm e.g. i2cp, routerconsole, etc.
     *  @param user null or "" for no user, already trimmed
     *  @param pw plain text, already trimmed
     *  @return success
     */
/****
    public boolean saveCrypt(String realm, String user, String pw) {
        String pfx = realm;
        if (user != null && user.length() > 0)
            pfx += '.' + user;
        String salt = user != null ? user : "";
        String crypt = UnixCrypt.crypt(pw, salt);
        Map<String, String> toAdd = Collections.singletonMap(pfx + ".crypt", crypt);
        List<String> toDel = new ArrayList(4);
        toDel.add(pfx + PROP_PW);
        toDel.add(pfx + PROP_B64);
        toDel.add(pfx + PROP_MD5);
        toDel.add(pfx + PROP_SHASH);
        return _context.router().saveConfig(toAdd, toDel);
    }
****/

    /**
     *  Straight MD5, no salt
     *  Compatible with Jetty and RFC 2617.
     *
     *  @param realm The full realm, e.g. routerconsole.auth.i2prouter, etc.
     *  @param subrealm to be used in creating the checksum
     *  @param user non-null, non-empty, already trimmed
     *  @param pw plain text
     *  @return if pw verified
     */
    public boolean saveMD5(String realm, String subrealm, String user, String pw) {
        // Upgrade to PBKDF2 on save
        return savePBKDF2(realm, subrealm, user, pw);
    }

    /**
     *  Save password as PBKDF2 hash.
     *  Backward compatible - saves new PBKDF2, keeps old MD5 for migration.
     *
     *  @param realm The full realm
     *  @param subrealm unused for PBKDF2
     *  @param user non-null, non-empty
     *  @param pw plain text
     *  @return if saved
     *  @since 0.9.70+
     */
    private boolean savePBKDF2(String realm, String subrealm, String user, String pw) {
        String pfx = realm;
        if (user != null && user.length() > 0)
            pfx += '.' + user;
        try {
            byte[] salt = new byte[16];
            java.security.SecureRandom sr = new java.security.SecureRandom();
            sr.nextBytes(salt);
            PBEKeySpec spec = new PBEKeySpec(pw.toCharArray(), salt, PBKDF2_ITERATIONS, 256);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = skf.generateSecret(spec).getEncoded();
            String stored = PBKDF2_ITERATIONS + ":" + HexEncode(salt) + ":" + HexEncode(hash);
            Map<String, String> toAdd = Collections.singletonMap(pfx + PROP_PBKDF2, stored);
            List<String> toDel = new ArrayList<String>(4);
            toDel.add(pfx + PROP_PW);
            toDel.add(pfx + PROP_B64);
            toDel.add(pfx + ".crypt");
            toDel.add(pfx + PROP_SHASH);
            return _context.router().saveConfig(toAdd, toDel);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            return false;
        }
    }

    private static String HexEncode(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

/****
    public static void main(String args[]) {
        RouterContext ctx = (new Router()).getContext();
        ConsolePasswordManager pm = new ConsolePasswordManager(ctx);
        if (!pm.migrate())
            System.out.println("Fail 1");
        if (!pm.migrateConsole())
            System.out.println("Fail 1a");

        System.out.println("Test plain");
        if (!pm.savePlain("type1", "user1", "pw1"))
            System.out.println("Fail 2");
        if (!pm.checkPlain("type1", "user1", "pw1"))
            System.out.println("Fail 3");

        System.out.println("Test B64");
        if (!pm.saveB64("type2", "user2", "pw2"))
            System.out.println("Fail 4");
        if (!pm.checkB64("type2", "user2", "pw2"))
            System.out.println("Fail 5");

        System.out.println("Test MD5");
        if (!pm.saveMD5("type3", "realm", "user3", "pw3"))
            System.out.println("Fail 6");
        if (!pm.checkMD5("type3", "realm", "user3", "pw3"))
            System.out.println("Fail 7");

        //System.out.println("Test crypt");
        //if (!pm.saveCrypt("type4", "user4", "pw4"))
        //    System.out.println("Fail 8");
        //if (!pm.checkCrypt("type4", "user4", "pw4"))
        //    System.out.println("Fail 9");

        System.out.println("Test hash");
        if (!pm.saveHash("type5", "user5", "pw5"))
            System.out.println("Fail 10");
        if (!pm.checkHash("type5", "user5", "pw5"))
            System.out.println("Fail 11");
    }
****/
}
