/*
 *  Test that "Made with IzPack" is never rendered, for any language.
 *
 *  IzPack 5 ships a langpack for every language it knows, and each one carries its
 *  own translation of installer.madewith - the title of the etched border around
 *  the installer's log window. Those packs are compiled into the installer from the
 *  compiler's own jar and cannot be replaced: staging a blanked copy in the working
 *  directory has no effect, because the compiler reads the jar in preference. So the
 *  string is still present in the shipped bytes.
 *
 *  What matters is whether it is rendered, and that is a property of the runtime
 *  merge rather than of the file. This test performs the same two steps the
 *  installer performs:
 *
 *    1. load the langpack the user selected, through LocaleDatabase
 *    2. add CustomLangPack over it, the way
 *       AbstractInstallDataProvider.addCustomLangpack does
 *
 *  and then asks for installer.madewith. LocaleDatabase extends TreeMap and add is
 *  putAll, so the CustomLangPack wins. Asserting that here means the claim is
 *  checked rather than reasoned about, and it fails loudly if a future IzPack
 *  changes the merge order.
 *
 *  Run by "ant izpack-branding-check" against the built installer jar.
 */

package net.i2p.installer;

import com.izforge.izpack.api.data.LocaleDatabase;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class BrandingSuppressedTest {

    /** Languages whose pack the IzPack distribution supplies, i.e. not ours. */
    private static final String[] UPSTREAM = {
        "cat", "ces", "dan", "deu", "ell", "eng", "fin", "fra", "hun", "ita",
        "jpn", "kor", "nld", "nor", "pol", "ron", "rus", "slk", "spa", "swe",
        "tur", "ukr",
    };

    public static void main(String[] args) {
        ClassLoader loader = BrandingSuppressedTest.class.getClassLoader();
        int checked = 0;
        List<String> failures = new ArrayList<>();

        for (String iso3 : UPSTREAM) {
            InputStream pack = loader.getResourceAsStream(
                    "resources/langpacks/" + iso3 + ".xml");
            if (pack == null) {
                System.out.println(iso3 + ": not packaged, skipped");
                continue;
            }
            // One parse per pack: LocaleDatabase consumes and closes the stream, so
            // reading "before" through a second LocaleDatabase would fail.
            LocaleDatabase db = new LocaleDatabase(pack, null);
            String before = db.get("installer.madewith");
            if (before == null || before.trim().isEmpty()) {
                System.out.println(iso3 + ": langpack already blank");
                continue;
            }

            InputStream custom = loader.getResourceAsStream(
                    "resources/CustomLangPack.xml");
            if (custom == null) {
                failures.add(iso3 + ": no CustomLangPack.xml to override the langpack");
                continue;
            }
            db.add(new LocaleDatabase(custom, null));

            String after = db.get("installer.madewith");
            checked++;
            if (after == null || after.trim().isEmpty()) {
                System.out.println(iso3 + ": suppressed (langpack said \""
                                   + clip(before) + "\")");
            } else {
                failures.add(iso3 + ": still renders " + after);
            }
        }

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("PASS: \"Made with IzPack\" is not rendered for any of the "
                               + checked + " upstream langpack(s)");
            return;
        }
        for (String f : failures) {
            System.out.println("FAIL: " + f);
        }
        System.out.println(failures.size() + " language(s) still render the branding");
        System.exit(1);
    }

    private static String clip(String s) {
        String flat = s.replace('\n', ' ').trim();
        return flat.length() > 32 ? flat.substring(0, 32) + "..." : flat;
    }
}