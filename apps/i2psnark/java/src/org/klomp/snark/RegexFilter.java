/*
 * Released into the public domain
 * with no warranty of any kind, either expressed or implied.
 */
package org.klomp.snark;

/**
 * A structure for regex filters
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
