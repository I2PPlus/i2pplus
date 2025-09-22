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

import java.io.BufferedReader;
import java.io.IOException;
import java.text.ParseException;
import java.util.StringTokenizer;

import org.apache.commons.net.ftp.FTPClientConfig;
import org.apache.commons.net.ftp.FTPFile;

/**
 * Implementation FTPFileEntryParser and FTPFileListParser for VMS Systems. This is a sample of VMS LIST output
 *
 * <pre>
 *  "1-JUN.LIS;1              9/9           2-JUN-1998 07:32:04  [GROUP,OWNER]    (RWED,RWED,RWED,RE)",
 *  "1-JUN.LIS;2              9/9           2-JUN-1998 07:32:04  [GROUP,OWNER]    (RWED,RWED,RWED,RE)",
 *  "DATA.DIR;1               1/9           2-JUN-1998 07:32:04  [GROUP,OWNER]    (RWED,RWED,RWED,RE)",
 * </pre>
 * <p>
 * Note: VMSFTPEntryParser can only be instantiated through the DefaultFTPParserFactory by class name. It will not be chosen by the autodetection scheme.
 * </p>
 *
 * @see org.apache.commons.net.ftp.FTPFileEntryParser FTPFileEntryParser (for usage instructions)
 * @see org.apache.commons.net.ftp.parser.DefaultFTPFileEntryParserFactory
 */
public class VMSFTPEntryParser extends ConfigurableFTPFileEntryParserImpl {

    private static final String DEFAULT_DATE_FORMAT = "d-MMM-yyyy HH:mm:ss"; // 9-NOV-2001 12:30:24

    /**
     * this is the regular expression used by this parser.
     */
    private static final String REGEX = "(.*?;[0-9]+)\\s*" // 1 file and version
            + "(\\d+)(?:/\\d+)?\\s*" // 2 size/allocated
            + "(\\S+)\\s+(\\S+)\\s+" // 3+4 date and time
            + "\\[(([0-9$A-Za-z_]+)|([0-9$A-Za-z_]+),([0-9$a-zA-Z_]+))\\]?\\s*" // 5(6,7,8) owner
            + "\\([a-zA-Z]*,([a-zA-Z]*),([a-zA-Z]*),([a-zA-Z]*)\\)"; // 9,10,11 Permissions (O,G,W)
    // TODO - perhaps restrict permissions to [RWED]* ?

    /**
     * Constructor for a VMSFTPEntryParser object.
     *
     * @throws IllegalArgumentException Thrown if the regular expression is unparseable. Should not be seen under normal conditions. If the exception is seen,
     *                                  this is a sign that {@code REGEX} is not a valid regular expression.
     */
    public VMSFTPEntryParser() {
        this(null);
    }

    /**
     * This constructor allows the creation of a VMSFTPEntryParser object with something other than the default configuration.
     *
     * @param config The {@link FTPClientConfig configuration} object used to configure this parser.
     * @throws IllegalArgumentException Thrown if the regular expression is unparseable. Should not be seen under normal conditions. If the exception is seen,
     *                                  this is a sign that {@code REGEX} is not a valid regular expression.
     * @since 1.4
     */
    public VMSFTPEntryParser(final FTPClientConfig config) {
        super(REGEX);
        configure(config);
    }

    /**
     * Gets a new default configuration to be used when this class is instantiated without a {@link FTPClientConfig FTPClientConfig} parameter being specified.
     *
     * @return the default configuration for this parser.
     */
    @Override
    protected FTPClientConfig getDefaultConfiguration() {
        return new FTPClientConfig(FTPClientConfig.SYST_VMS, DEFAULT_DATE_FORMAT, null);
    }

    /**
     * Returns {@code false}.
     *
     * @return {@code false}.
     */
    protected boolean isVersioning() {
        return false;
    }

    /**
     * DO NOT USE.
     *
     * @param listStream the stream
     * @return the array of files
     * @throws IOException on error
     * @deprecated (2.2) No other FTPFileEntryParser implementations have this method.
     */
    @Deprecated
    public FTPFile[] parseFileList(final java.io.InputStream listStream) throws IOException {
        final org.apache.commons.net.ftp.FTPListParseEngine engine = new org.apache.commons.net.ftp.FTPListParseEngine(this);
        engine.readServerList(listStream, null);
        return engine.getFiles();
    }

    /**
     * Parses a line of a VMS FTP server file listing and converts it into a usable format in the form of an {@code FTPFile} instance. If the file
     * listing line doesn't describe a file, {@code null} is returned, otherwise a {@code FTPFile} instance representing the files in the
     * directory is returned.
     *
     * @param entry A line of text from the file listing
     * @return An FTPFile instance corresponding to the supplied entry
     */
    @Override
    public FTPFile parseFTPEntry(final String entry) {
        // one block in VMS equals 512 bytes
        final long longBlock = 512;

        if (matches(entry)) {
            final FTPFile f = new FTPFile();
            f.setRawListing(entry);
            String name = group(1);
            final String size = group(2);
            final String datestr = group(3) + " " + group(4);
            final String owner = group(5);
            final String[] permissions = new String[3];
            permissions[0] = group(9);
            permissions[1] = group(10);
            permissions[2] = group(11);
            try {
                f.setTimestamp(super.parseTimestamp(datestr));
            } catch (final ParseException e) {
                // intentionally do nothing
            }

            final String grp;
            final String user;
            final StringTokenizer t = new StringTokenizer(owner, ",");
            switch (t.countTokens()) {
            case 1:
                grp = null;
                user = t.nextToken();
                break;
            case 2:
                grp = t.nextToken();
                user = t.nextToken();
                break;
            default:
                grp = null;
                user = null;
            }

            if (name.lastIndexOf(".DIR") != -1) {
                f.setType(FTPFile.DIRECTORY_TYPE);
            } else {
                f.setType(FTPFile.FILE_TYPE);
            }
            // set FTPFile name
            // Check also for versions to be returned or not
            if (!isVersioning()) {
                name = name.substring(0, name.lastIndexOf(';'));
            }
            f.setName(name);
            // size is retreived in blocks and needs to be put in bytes
            // for us humans and added to the FTPFile array
            final long sizeInBytes = Long.parseLong(size) * longBlock;
            f.setSize(sizeInBytes);

            f.setGroup(grp);
            f.setUser(user);
            // set group and owner

            // Set file permission.
            // VMS has (SYSTEM,OWNER,GROUP,WORLD) users that can contain
            // R (read) W (write) E (execute) D (delete)

            // iterate for OWNER GROUP WORLD permissions
            for (int access = 0; access < 3; access++) {
                final String permission = permissions[access];

                f.setPermission(access, FTPFile.READ_PERMISSION, permission.indexOf('R') >= 0);
                f.setPermission(access, FTPFile.WRITE_PERMISSION, permission.indexOf('W') >= 0);
                f.setPermission(access, FTPFile.EXECUTE_PERMISSION, permission.indexOf('E') >= 0);
            }

            return f;
        }
        return null;
    }

    // DEPRECATED METHODS - for API compatibility only - DO NOT USE

    /**
     * Reads the next entry using the supplied BufferedReader object up to whatever delimits one entry from the next. This parser cannot use the default
     * implementation of simply calling BufferedReader.readLine(), because one entry may span multiple lines.
     *
     * @param reader The BufferedReader object from which entries are to be read.
     * @return A string representing the next ftp entry or null if none found.
     * @throws IOException thrown on any IO Error reading from the reader.
     */
    @Override
    public String readNextEntry(final BufferedReader reader) throws IOException {
        String line = reader.readLine();
        final StringBuilder entry = new StringBuilder();
        while (line != null) {
            if (line.startsWith("Directory") || line.startsWith("Total")) {
                line = reader.readLine();
                continue;
            }

            entry.append(line);
            if (line.trim().endsWith(")")) {
                break;
            }
            line = reader.readLine();
        }
        return entry.length() == 0 ? null : entry.toString();
    }

}
