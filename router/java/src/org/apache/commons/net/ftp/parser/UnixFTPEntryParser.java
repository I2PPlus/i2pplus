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

import java.text.ParseException;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.net.ftp.FTPClientConfig;
import org.apache.commons.net.ftp.FTPFile;

/**
 * Implementation FTPFileEntryParser and FTPFileListParser for standard Unix Systems.
 * <p>
 * This class is based on the logic of Daniel Savarese's DefaultFTPListParser, but adapted to use regular expressions and to fit the new FTPFileEntryParser
 * interface.
 * </p>
 *
 * @see org.apache.commons.net.ftp.FTPFileEntryParser FTPFileEntryParser (for usage instructions)
 */
public class UnixFTPEntryParser extends ConfigurableFTPFileEntryParserImpl {

    static final String DEFAULT_DATE_FORMAT = "MMM d yyyy"; // Nov 9 2001

    static final String DEFAULT_RECENT_DATE_FORMAT = "MMM d HH:mm"; // Nov 9 20:06

    static final String NUMERIC_DATE_FORMAT = "yyyy-MM-dd HH:mm"; // 2001-11-09 20:06

    // Suffixes used in Japanese listings after the numeric values
    private static final String JA_MONTH = "\u6708";
    private static final String JA_DAY = "\u65e5";
    private static final String JA_YEAR = "\u5e74";

    private static final String DEFAULT_DATE_FORMAT_JA = "M'" + JA_MONTH + "' d'" + JA_DAY + "' yyyy'" + JA_YEAR + "'"; // 6月 3日 2003年

    private static final String DEFAULT_RECENT_DATE_FORMAT_JA = "M'" + JA_MONTH + "' d'" + JA_DAY + "' HH:mm"; // 8月 17日 20:10

    private static final Pattern TOTAL_PATTERN = Pattern.compile("^total \\d+$");

    /**
     * Some Linux distributions are now shipping an FTP server which formats file listing dates in an all-numeric format: {@code "yyyy-MM-dd HH:mm}. This
     * is a very welcome development, and hopefully it will soon become the standard. However, since it is so new, for now, and possibly forever, we merely
     * accommodate it, but do not make it the default.
     * <p>
     * For now end users may specify this format only via {@code UnixFTPEntryParser(FTPClientConfig)}. Steve Cohen - 2005-04-17
     * </p>
     */
    public static final FTPClientConfig NUMERIC_DATE_CONFIG = new FTPClientConfig(FTPClientConfig.SYST_UNIX, NUMERIC_DATE_FORMAT, null);

    /**
     * Regular expression used by this parser.
     * <p>
     * Permissions:
     * </p>
     * <ul>
     * <li>r the file is readable</li>
     * <li>w the file is writable</li>
     * <li>x the file is executable</li>
     * <li>- the indicated permission is not granted</li>
     * <li>L mandatory locking occurs</li> during access (the set-group-ID bit is on and the group execution bit is off)</li>
     * <li>s the set-user-ID or set-group-ID bit is on, and the corresponding user or group execution bit is also on
     * <li>S undefined bit-state (the set-user-ID bit is on and the user execution bit is off)</li>
     * <li>t the 1000 (octal) bit, or sticky bit, is on [see chmod(1)], and execution is on</li>
     * <li>T the 1000 bit is turned on, and execution is off (undefined bit-state)</li>
     * <li>e z/OS external link bit.</li>
     * <p>
     * Final letter may be appended: + file has extended security attributes (e.g. ACL) Note: local listings on MacOSX also use '@'; this is not allowed for
     * here as does not appear to be shown by FTP servers {@code @} file has extended attributes
     * </p>
     */
    private static final String REGEX = "([bcdelfmpSs-])" // file type
            + "(((r|-)(w|-)([xsStTL-]))((r|-)(w|-)([xsStTL-]))((r|-)(w|-)([xsStTL-])))\\+?" // permissions
            + "\\s*" // separator TODO why allow it to be omitted??
            + "(\\d+)" // link count
            + "\\s+" // separator
            + "(?:(\\S+(?:\\s\\S+)*?)\\s+)?" // owner name (optional spaces)
            + "(?:(\\S+(?:\\s\\S+)*)\\s+)?" // group name (optional spaces)
            + "(\\d+(?:,\\s*\\d+)?)" // size or n,m
            + "\\s+" // separator
            /*
             * numeric or standard format date: yyyy-mm-dd (expecting hh:mm to follow) MMM [d]d [d]d MMM Use non-space for MMM to allow for languages such
             * as German which use diacritics (e.g. umlaut) in some abbreviations. Japanese uses numeric day and month with suffixes to distinguish them [d]dXX
             * [d]dZZ
             */
            + "(" + "(?:\\d+[-/]\\d+[-/]\\d+)" + // yyyy-mm-dd
            "|(?:\\S{3}\\s+\\d{1,2})" + // MMM [d]d
            "|(?:\\d{1,2}\\s+\\S{3})" + // [d]d MMM
            "|(?:\\d{1,2}" + JA_MONTH + "\\s+\\d{1,2}" + JA_DAY + ")" + ")"
            + "\\s+" // separator
            /*
             * year (for non-recent standard format) - yyyy or time (for numeric or recent standard format) [h]h:mm or Japanese year - yyyyXX
             */
            + "((?:\\d+(?::\\d+)?)|(?:\\d{4}" + JA_YEAR + "))" // (20)
            + "\\s" // separator
            + "(.*)"; // the rest (21)

    /**
     * Whether leading spaces are trimmed from file names this was the case for the original implementation.
     */
    final boolean trimLeadingSpaces; // package protected for access from test code

    /**
     * Constructs a new instance.
     *
     * @throws IllegalArgumentException Thrown if the regular expression is unparseable. Should not be seen under normal conditions.
     *                                  If this exception is seen, this is a sign that {@code REGEX} is not a valid regular expression.
     */
    public UnixFTPEntryParser() {
        this(null);
    }

    /**
     * Constructs a new instance with something other than the default configuration.
     *
     * @param config The {@link FTPClientConfig configuration} object used to configure this parser.
     * @throws IllegalArgumentException Thrown if the regular expression is unparseable. Should not be seen under normal conditions.
     *                                  If this exception is seen, this is a sign that {@code REGEX} is not a valid regular expression.
     * @since 1.4
     */
    public UnixFTPEntryParser(final FTPClientConfig config) {
        this(config, false);
    }

    /**
     * Constructs a new instance  with something other than the default configuration.
     *
     * @param config            The {@link FTPClientConfig configuration} object used to configure this parser.
     * @param trimLeadingSpaces if {@code true}, trim leading spaces from file names
     * @throws IllegalArgumentException Thrown if the regular expression is unparseable. Should not be seen under normal conditions.
     *                                  If this exception is seen, this is a sign that {@code REGEX} is not a valid regular expression.
     * @since 3.4
     */
    public UnixFTPEntryParser(final FTPClientConfig config, final boolean trimLeadingSpaces) {
        super(REGEX);
        configure(config);
        this.trimLeadingSpaces = trimLeadingSpaces;
    }

    /**
     * Gets a new default configuration to be used when this class is instantiated without a {@link FTPClientConfig FTPClientConfig} parameter being specified.
     *
     * @return the default configuration for this parser.
     */
    @Override
    protected FTPClientConfig getDefaultConfiguration() {
        return new FTPClientConfig(FTPClientConfig.SYST_UNIX, DEFAULT_DATE_FORMAT, DEFAULT_RECENT_DATE_FORMAT);
    }

    /**
     * Parses a line of a Unix (standard) FTP server file listing and converts it into a usable format in the form of an {@code FTPFile} instance. If the
     * file listing line doesn't describe a file, {@code null} is returned, otherwise a {@code FTPFile} instance representing the files in the
     * directory is returned.
     *
     * @param entry A line of text from the file listing
     * @return An FTPFile instance corresponding to the supplied entry
     */
    @Override
    public FTPFile parseFTPEntry(final String entry) {
        final FTPFile file = new FTPFile();
        file.setRawListing(entry);
        final int type;
        boolean isDevice = false;
        if (matches(entry)) {
            final String typeStr = group(1);
            final String hardLinkCount = group(15);
            final String usr = group(16);
            final String grp = group(17);
            final String fileSize = group(18);
            final String datestr = group(19) + " " + group(20);
            String name = group(21);
            if (trimLeadingSpaces) {
                name = name.replaceFirst("^\\s+", "");
            }
            try {
                if (group(19).contains(JA_MONTH)) { // special processing for Japanese format
                    final FTPTimestampParserImpl jaParser = new FTPTimestampParserImpl();
                    jaParser.configure(new FTPClientConfig(FTPClientConfig.SYST_UNIX, DEFAULT_DATE_FORMAT_JA, DEFAULT_RECENT_DATE_FORMAT_JA));
                    file.setTimestamp(jaParser.parseTimestamp(datestr));
                } else {
                    file.setTimestamp(super.parseTimestamp(datestr));
                }
            } catch (final ParseException e) {
                // intentionally do nothing
            }
            // A 'whiteout' file is an ARTIFICIAL entry in any of several types of
            // 'translucent' filesystems, of which a 'union' filesystem is one.
            // bcdelfmpSs-
            switch (typeStr.charAt(0)) {
            case 'd':
                type = FTPFile.DIRECTORY_TYPE;
                break;
            case 'e': // NET-39 => z/OS external link
                type = FTPFile.SYMBOLIC_LINK_TYPE;
                break;
            case 'l':
                type = FTPFile.SYMBOLIC_LINK_TYPE;
                break;
            case 'b':
            case 'c':
                isDevice = true;
                type = FTPFile.FILE_TYPE; // TODO change this if DEVICE_TYPE implemented
                break;
            case 'f':
            case '-':
                type = FTPFile.FILE_TYPE;
                break;
            default: // e.g. ? and w = whiteout
                type = FTPFile.UNKNOWN_TYPE;
            }
            file.setType(type);
            int g = 4;
            for (int access = 0; access < 3; access++, g += 4) {
                // Use != '-' to avoid having to check for suid and sticky bits
                file.setPermission(access, FTPFile.READ_PERMISSION, !group(g).equals("-"));
                file.setPermission(access, FTPFile.WRITE_PERMISSION, !group(g + 1).equals("-"));
                final String execPerm = group(g + 2);
                file.setPermission(access, FTPFile.EXECUTE_PERMISSION, !execPerm.equals("-") && !Character.isUpperCase(execPerm.charAt(0)));
            }
            if (!isDevice) {
                try {
                    file.setHardLinkCount(Integer.parseInt(hardLinkCount));
                } catch (final NumberFormatException e) {
                    // intentionally do nothing
                }
            }
            file.setUser(usr);
            file.setGroup(grp);
            try {
                file.setSize(Long.parseLong(fileSize));
            } catch (final NumberFormatException e) {
                // intentionally do nothing
            }
            // oddball cases like symbolic links, file names
            // with spaces in them.
            if (type == FTPFile.SYMBOLIC_LINK_TYPE) {
                final int end = name.indexOf(" -> ");
                // Give up if no link indicator is present
                if (end == -1) {
                    file.setName(name);
                } else {
                    file.setName(name.substring(0, end));
                    file.setLink(name.substring(end + 4));
                }
            } else {
                file.setName(name);
            }
            return file;
        }
        return null;
    }

    /**
     * Preparses the list to discard "total nnn" lines.
     */
    @Override
    public List<String> preParse(final List<String> original) {
        // NET-389
        original.removeIf(entry -> TOTAL_PATTERN.matcher(entry).matches());
        return original;
    }

}
