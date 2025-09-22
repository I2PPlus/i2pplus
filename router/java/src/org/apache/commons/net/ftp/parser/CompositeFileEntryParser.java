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

import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPFileEntryParser;
import org.apache.commons.net.ftp.FTPFileEntryParserImpl;

/**
 * This implementation allows to pack some FileEntryParsers together and handle the case where the returned dir style isn't clearly defined. The matching parser
 * will be cached. If the cached parser won't match due to the server changed the dir style, a new matching parser will be searched.
 */
public class CompositeFileEntryParser extends FTPFileEntryParserImpl {
    private final FTPFileEntryParser[] ftpFileEntryParsers;
    private FTPFileEntryParser cachedFtpFileEntryParser;

    /**
     * Constructs a new instance.
     *
     * @param ftpFileEntryParsers An array of FTPFileEntryParser.
     */
    public CompositeFileEntryParser(final FTPFileEntryParser[] ftpFileEntryParsers) {
        this.cachedFtpFileEntryParser = null;
        this.ftpFileEntryParsers = ftpFileEntryParsers;
    }

    @Override
    public FTPFile parseFTPEntry(final String listEntry) {
        if (cachedFtpFileEntryParser != null) {
            return cachedFtpFileEntryParser.parseFTPEntry(listEntry);
        }
        for (final FTPFileEntryParser ftpFileEntryParser : ftpFileEntryParsers) {
            final FTPFile matched = ftpFileEntryParser.parseFTPEntry(listEntry);
            if (matched != null) {
                cachedFtpFileEntryParser = ftpFileEntryParser;
                return matched;
            }
        }
        return null;
    }
}
