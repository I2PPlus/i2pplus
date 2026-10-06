package net.i2p.installer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import javax.swing.ImageIcon;

import com.izforge.izpack.api.exception.ResourceException;
import com.izforge.izpack.api.exception.ResourceNotFoundException;
import com.izforge.izpack.api.resource.Resources;
import com.izforge.izpack.panels.htmlinfo.HTMLInfoPanel;

/**
 * An HTML info panel whose body is chosen by the selected installer language.
 *
 * <p>IzPack's HTML panels cannot be localized through a langpack. The body is fetched with
 * {@link Resources#getURL(String)}, which resolves a single packaged resource name and never
 * consults the langpack, so one {@code welcome.html} is shown for every language. Only the
 * panel's headline is a langpack key. That is why the IzPack 4 installer, which uses the
 * plain {@code HelloPanel} and is langpack-driven throughout, can translate its welcome text
 * while this one cannot.
 *
 * <p>This panel closes the gap without giving up the markup. It resolves
 * {@code welcome_<iso3>.html} for the selected language and falls back to {@code welcome.html}
 * when that file has not been supplied yet, so a partially translated set degrades to English
 * one language at a time instead of failing. Register the per-language files in the install
 * descriptor as ordinary resources:
 *
 * <pre>
 *   &lt;res id="welcome_ces.html" src="installer/lib/izpack/resources/welcome_ces.html"/&gt;
 * </pre>
 *
 * <p>Nothing has to be added to a langpack, and a language with no file needs no entry.
 *
 * <p>The name is looked up through {@link #getResources()}, which the superclass calls
 * virtually when it loads the body, so overriding that is enough - the resource name the
 * superclass computes in its constructor is just the key we rewrite here.
 *
 * <p>That rewrite does not reach the panel's header: {@code PanelHelper} reads
 * {@code installData.getMessages()} for it. The header is blanked through
 * CustomLangPack.xml instead - see
 * {@link #LocalizedHTMLInfoPanel(Panel, InstallerFrame, GUIInstallData, Resources, Log)}.
 *
 * @since 0.9.71+
 */
public class LocalizedHTMLInfoPanel extends HTMLInfoPanel {

    private static final long serialVersionUID = 1L;

    /** Suffix of the per-language body, e.g. {@code welcome_ces.html}. */
    private static final String LOCALIZED_PREFIX = "welcome_";
    private static final String LOCALIZED_SUFFIX = ".html";

    /** The language-neutral body, used when no translation has been supplied. */
    private static final String FALLBACK = "welcome.html";

    /**
     * The superclass's simple name, which is the resource prefix IzPack would have used had
     * the descriptor named {@code HTMLInfoPanel}.
     *
     * <p>See the class comment: this prefix has to be put back, or nothing resolves.
     */
    private static final String SUPER_SIMPLE_NAME = "HTMLInfoPanel";

    /** This class's own simple name, the prefix IzPack actually asks with. */
    private static final String OWN_SIMPLE_NAME = "LocalizedHTMLInfoPanel";

    /** Built once: getResources() is called for every string the panel renders. */
    private transient Resources localized;

    /**
     * The constructor IzPack's container actually uses.
     *
     * <p>Panels are instantiated reflectively through picocontainer, which autowires by
     * parameter type and cannot satisfy a primitive {@code boolean} - there is no binding for
     * it. HTMLInfoPanel therefore declares a second constructor without the flag, and that
     * is the one the container picks; a subclass that declares only the six-argument form
     * fails at runtime with "unsatisfied dependency 'class java.lang.Boolean'". Both
     * constructors are mirrored here for that reason, and the five-argument one passes
     * {@code true}, exactly as the stock class does.
     *
     * <p>{@code writeConfig=true} builds the panel's header label, which is blanked rather than
     * removed. The label's text is {@code PanelHelper.getPanelTitleMessageKey(panel, "info",
     * installData)}, and that helper cannot be reached from here: it reads
     * {@code installData.getMessages()} directly, never through {@link #getResources()}, so the
     * rewrite in {@link LocalizedResources} does not apply to it. It derives the key
     * {@code LocalizedHTMLInfoPanel.info} from the class name it finds in the descriptor, no
     * langpack defines that key, and {@code LocaleDatabase.get} answers an unknown key with
     * the key itself - so the header rendered the literal text "LocalizedHTMLInfoPanel.info"
     * above the welcome text in every language.
     *
     * <p>The key is blanked from outside instead: CustomLangPack.xml carries
     * {@code <str id="LocalizedHTMLInfoPanel.info" txt=""/>}, and IzPack merges that pack over
     * whichever langpack the user picked with {@code LocaleDatabase.add}, a TreeMap putAll, so
     * one entry covers every language including English - see
     * installer/lib/izpack/gen-izpack-langpacks.py.
     */
    public LocalizedHTMLInfoPanel(com.izforge.izpack.api.data.Panel panel,
                                  com.izforge.izpack.installer.gui.InstallerFrame frame,
                                  com.izforge.izpack.installer.data.GUIInstallData data,
                                  Resources resources,
                                  com.izforge.izpack.gui.log.Log log) {
        this(panel, frame, data, true, resources, log);
    }

    public LocalizedHTMLInfoPanel(com.izforge.izpack.api.data.Panel panel,
                                  com.izforge.izpack.installer.gui.InstallerFrame frame,
                                  com.izforge.izpack.installer.data.GUIInstallData data,
                                  boolean writeConfig,
                                  Resources resources,
                                  com.izforge.izpack.gui.log.Log log) {
        super(panel, frame, data, writeConfig, resources, log);
    }

    @Override
    protected Resources getResources() {
        if (localized == null) {
            localized = new LocalizedResources(super.getResources(), iso3());
        }
        return localized;
    }

    /**
     * The ISO3 code of the selected language, or null when it cannot be determined.
     *
     * <p>Empty rather than guessed: a wrong code would silently show the wrong translation,
     * whereas no code simply shows English.
     */
    private String iso3() {
        try {
            String code = installData.getLocaleISO3();
            return (code == null || code.isEmpty()) ? null : code;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Puts the superclass's resource prefix back on a name IzPack derived from our class.
     *
     * <p>IzPack names a panel's resources {@code <PanelClass>.<suffix>}, taking the class name
     * from the object it instantiated, so subclassing renames every key the panel asks for:
     * {@code HTMLInfoPanel.info} becomes {@code LocalizedHTMLInfoPanel.info}, which no
     * {@code <res>} entry provides. Both of our panels need this - the GUI one through
     * {@link LocalizedResources}, the console one directly, since picocontainer injects the
     * installer-wide Resources and there is no proxy to intercept it.
     *
     * <p>Only the leading prefix is rewritten, and only when it is ours, so a per-language body
     * name, a second suffix such as {@code .winservice}, and any unrelated name pass through.
     *
     * @param name a resource name as IzPack computed it, or null
     * @return the name with the superclass's prefix, or null if {@code name} is null
     * @since 0.9.71+
     */
    static String rebaseResourceName(String name) {
        if (name == null || !name.startsWith(OWN_SIMPLE_NAME + ".")) {
            return name;
        }
        return SUPER_SIMPLE_NAME + name.substring(OWN_SIMPLE_NAME.length());
    }

    /**
     * Delegates everything, and rewrites the panel's body resource to the language-specific
     * one when it exists.
     *
     * <p>Only {@code getURL} is rewritten. The panel loads its body through it, and the other
     * six methods must keep resolving exactly as before, or every image and string the panel
     * draws would break.
     */
    static final class LocalizedResources implements Resources {

        private final Resources delegate;
        private final String iso3;

        LocalizedResources(Resources delegate, String iso3) {
            this.delegate = delegate;
            this.iso3 = iso3;
        }

        @Override
        public URL getURL(String name) throws ResourceNotFoundException {
            String rebased = rebase(name);
            if (iso3 != null && rebased != null && rebased.endsWith(".info")) {
                // Resources.getURL throws rather than returning null when the name is
                // absent, and "no translation yet" is the normal case, so the miss is
                // expected here rather than exceptional.
                try {
                    return delegate.getURL(bodyResource(iso3));
                } catch (ResourceNotFoundException e) {
                    // No translation for this language yet; fall through to the
                    // language-neutral body.
                }
            }
            return delegate.getURL(rebased == null ? name : rebased);
        }

        /**
         * Puts the superclass's resource prefix back on a name derived from this class.
         *
         * <p>IzPack names a panel's resources {@code <PanelClass>.<suffix>}, and it takes the
         * class name from the object it instantiated. Subclassing therefore renames every
         * resource and langpack key the panel asks for: {@code HTMLInfoPanel.info} becomes
         * {@code LocalizedHTMLInfoPanel.info}, which no {@code <res>} entry provides, and the
         * panel dies with "Cannot find named resource". Substituting the class is what a
         * subclass has to do to keep the descriptor's own ids working.
         *
         * <p>{@link #getURL} and all three {@code getString} overloads need this, and they
         * fail differently. {@code getURL} throws on a missing name, so the body lookup dies
         * loudly. The string lookups fail silently: {@code LocaleDatabase.get} answers an
         * unknown key with the key itself rather than null, so an unrewritten
         * {@code ...info} or {@code ...headline} lookup hands the caller the literal key name.
         *
         * <p>The panel's own header was never one of those four: it resolves in
         * {@code installData.getMessages()}, past this proxy, so it needs the blank entry in
         * CustomLangPack rather than this rewrite. The rewrite is kept so a panel-prefixed
         * key resolves the same way whichever path asks for it, and
         * {@link LocalizedHTMLInfoConsolePanel} needs it for the same reason.
         *
         * <p>Only the leading prefix is rewritten, and only when it is ours, so the per-language
         * and fallback names below are unaffected and any unrelated name passes through.
         *
         * @param name the resource name as IzPack computed it, or null
         * @return the name with the superclass prefix, or null if {@code name} is null
         */
        private String rebase(String name) {
            return rebaseResourceName(name);
        }

        @Override
        public InputStream getInputStream(String name) throws ResourceException {
            return delegate.getInputStream(name);
        }

        @Override
        public String getString(String name) {
            return delegate.getString(rebase(name));
        }

        @Override
        public String getString(String name, String defaultValue) {
            return delegate.getString(rebase(name), defaultValue);
        }

        @Override
        public String getString(String name, String param, String defaultValue) {
            return delegate.getString(rebase(name), param, defaultValue);
        }

        @Override
        public Object getObject(String name) throws ResourceException {
            return delegate.getObject(name);
        }

        @Override
        public ImageIcon getImageIcon(String name, String... params) {
            return delegate.getImageIcon(name, params);
        }
    }

    /**
     * The body this panel would load, for a given language. Package-private so a test can
     * assert the lookup without a running installer.
     *
     * @param iso3 the ISO3 language code, or null for the language-neutral body
     * @return the resource name to request
     */
    static String bodyResource(String iso3) {
        return (iso3 == null || iso3.isEmpty())
                ? FALLBACK
                : LOCALIZED_PREFIX + iso3 + LOCALIZED_SUFFIX;
    }
}
