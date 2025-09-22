/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.commons.net.ftp.parser;

import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.commons.net.ftp.FTPFileEntryParserImpl;

/**
 * This abstract class implements both the older FTPFileListParser and newer FTPFileEntryParser interfaces with default functionality. All the classes in the
 * parser subpackage inherit from this.
 * <p>
 * This is the base class for all regular expression based FTPFileEntryParser classes
 * </p>
 */
public abstract class RegexFTPFileEntryParserImpl extends FTPFileEntryParserImpl {
    /**
     * Internal pattern the matcher tries to match, representing a file entry
     */
    private Pattern pattern;

    /**
     * Internal match result used by the parser
     */
    private MatchResult result;

    /**
     * Internal PatternMatcher object used by the parser. It has protected scope in case subclasses want to make use of it for their own purposes.
     */
    protected Matcher _matcher_;

    /**
     * The constructor for a RegexFTPFileEntryParserImpl object. The expression is compiled with flags = 0.
     *
     * @param regex The regular expression with which this object is initialized.
     * @throws IllegalArgumentException Thrown if the regular expression is unparseable. Should not be seen in normal conditions. If it is seen, this is a sign
     *                                  that a subclass has been created with a bad regular expression. Since the parser must be created before use, this means
     *                                  that any bad parser subclasses created from this will bomb very quickly, leading to easy detection.
     */

    public RegexFTPFileEntryParserImpl(final String regex) {
        compileRegex(regex, 0);
    }

    /**
     * The constructor for a RegexFTPFileEntryParserImpl object.
     *
     * @param regex The regular expression with which this object is initialized.
     * @param flags the flags to apply, see {@link Pattern#compile(String, int)}. Use 0 for none.
     * @throws IllegalArgumentException Thrown if the regular expression is unparseable. Should not be seen in normal conditions. If it is seen, this is a sign
     *                                  that a subclass has been created with a bad regular expression. Since the parser must be created before use, this means
     *                                  that any bad parser subclasses created from this will bomb very quickly, leading to easy detection.
     * @since 3.4
     */
    public RegexFTPFileEntryParserImpl(final String regex, final int flags) {
        compileRegex(regex, flags);
    }

    /**
     * Compile the regex and store the {@link Pattern}.
     *
     * This is an internal method to do the work so the constructor does not have to call an overrideable method.
     *
     * @param regex the expression to compile
     * @param flags the flags to apply, see {@link Pattern#compile(String, int)}. Use 0 for none.
     * @throws IllegalArgumentException if the regex cannot be compiled
     */
    private void compileRegex(final String regex, final int flags) {
        try {
            pattern = Pattern.compile(regex, flags);
        } catch (final PatternSyntaxException pse) {
            throw new IllegalArgumentException("Unparseable regex supplied: " + regex);
        }
    }

    /**
     * Convenience method
     *
     * @return the number of groups() in the internal MatchResult.
     */
    public int getGroupCnt() {
        if (result == null) {
            return 0;
        }
        return result.groupCount();
    }

    /**
     * Gets a string shows each match group by number.
     * <p>
     * For debugging purposes.
     * </p>
     *
     * @return a string shows each match group by number.
     */
    public String getGroupsAsString() {
        final StringBuilder b = new StringBuilder();
        for (int i = 1; i <= result.groupCount(); i++) {
            b.append(i).append(") ").append(result.group(i)).append(System.lineSeparator());
        }
        return b.toString();
    }

    /**
     * Convenience method delegates to the internal MatchResult's group() method.
     *
     * @param matchNum match group number to be retrieved
     * @return the content of the {@code matchnum'th} group of the internal match or null if this method is called without a match having been made.
     */
    public String group(final int matchNum) {
        if (result == null) {
            return null;
        }
        return result.group(matchNum);
    }

    /**
     * Convenience method delegates to the internal MatchResult's matches() method.
     *
     * @param s the String to be matched
     * @return true if s matches this object's regular expression.
     */
    public boolean matches(final String s) {
        result = null;
        _matcher_ = pattern.matcher(s);
        if (_matcher_.matches()) {
            result = _matcher_.toMatchResult();
        }
        return null != result;
    }

    /**
     * Sets the regular expression for entry parsing and create a new {@link Pattern} instance.
     *
     * @param regex The new regular expression
     * @return true
     * @throws IllegalArgumentException if the regex cannot be compiled
     * @since 2.0
     */
    public boolean setRegex(final String regex) {
        compileRegex(regex, 0);
        return true;
    }

    /**
     * Sets the regular expression for entry parsing and create a new {@link Pattern} instance.
     *
     * @param regex The new regular expression
     * @param flags the flags to apply, see {@link Pattern#compile(String, int)}. Use 0 for none.
     * @return true
     * @throws IllegalArgumentException if the regex cannot be compiled
     * @since 3.4
     */
    public boolean setRegex(final String regex, final int flags) {
        compileRegex(regex, flags);
        return true;
    }
}
