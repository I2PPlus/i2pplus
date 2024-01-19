/*
 * Released into the public domain
 * with no warranty of any kind, either expressed or implied.
 */
package org.klomp.snark;

/**
 * A structure for regex filters
 * @since 0.9.62+
 */
public class RegexFilter {

    public final String name;
    public final String regex;
    public final boolean isDefault;

    public RegexFilter(String name, String regex, boolean isDefault) {
        this.name = name;
        this.regex = regex;
        this.isDefault = isDefault;
    }
}
