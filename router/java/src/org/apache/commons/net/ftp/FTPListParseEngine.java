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

package org.apache.commons.net.ftp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.stream.Collectors;

import org.apache.commons.io.Charsets;


/**
 * This class handles the entire process of parsing a listing of file entries from the server.
 * <p>
 * This object defines a two-part parsing mechanism.
 * </p>
 * <p>
 * The first part consists of reading the raw input into an internal list of strings. Every item in this list corresponds to an actual file. All extraneous
 * matter emitted by the server will have been removed by the end of this phase. This is accomplished in conjunction with the FTPFileEntryParser associated with
 * this engine, by calling its methods {@code readNextEntry()} - which handles the issue of what delimits one entry from another, usually but not always a line
 * feed and {@code preParse()} - which handles removal of extraneous matter such as the preliminary lines of a listing, removal of duplicates on versioning
 * systems, etc.
 * </p>
 * <p>
 * The second part is composed of the actual parsing, again in conjunction with the particular parser used by this engine. This is controlled by an iterator
 * over the internal list of strings. This may be done either in block mode, by calling the {@code getNext()} and {@code getPrevious()} methods to provide
 * "paged" output of less than the whole list at one time, or by calling the {@code getFiles()} method to return the entire list.
 * </p>
 * <p>
 * Examples:
 * </p>
 * <p>
 * Paged access:
 * </p>
 * <pre>
 * FTPClient f = FTPClient();
 * f.connect(server);
 * f.login(user, password);
 * FTPListParseEngine engine = f.initiateListParsing(directory);
 *
 * while (engine.hasNext()) {
 *     FTPFile[] files = engine.getNext(25); // "page size" you want
 *     // do whatever you want with these files, display them, etc.
 *     // expensive FTPFile objects not created until needed.
 * }
 * </pre>
 * <p>
 * For unpaged access, simply use FTPClient.listFiles(). That method uses this class transparently.
 * </p>
 */
public class FTPListParseEngine {
    /**
     * An empty immutable {@code FTPFile} array.
     */
    private static final FTPFile[] EMPTY_FTP_FILE_ARRAY = {};
    private List<String> entries = new LinkedList<>();

    private ListIterator<String> internalIterator = entries.listIterator();
    private final FTPFileEntryParser parser;

    // Should invalid files (parse failures) be allowed?
    private final boolean saveUnparseableEntries;

    /**
     * Constructs a new instance.
     *
     * @param parser How to parse file entries.
     */
    public FTPListParseEngine(final FTPFileEntryParser parser) {
        this(parser, null);
    }

    /**
     * Intended for use by FTPClient only
     *
     * @since 3.4
     */
    FTPListParseEngine(final FTPFileEntryParser parser, final FTPClientConfig configuration) {
        this.parser = parser;
        this.saveUnparseableEntries = configuration != null && configuration.getUnparseableEntries();
    }

    /**
     * Gets a list of FTPFile objects containing the whole list of files returned by the server as read by this object's parser. The files are filtered
     * before being added to the array.
     *
     * @param filter FTPFileFilter, must not be {@code null}.
     * @return a list of FTPFile objects containing the whole list of files returned by the server as read by this object's parser.
     *         <p>
     *         <strong> NOTE:</strong> This array may contain null members if any of the individual file listings failed to parse. The caller should check each
     *         entry for null before referencing it, or use a filter such as {@link FTPFileFilters#NON_NULL} which does not allow null entries.
     * @since 3.9.0
     */
    public List<FTPFile> getFileList(final FTPFileFilter filter) {
        return entries.stream().map(e -> {
            final FTPFile file = parser.parseFTPEntry(e);
            return file == null && saveUnparseableEntries ? new FTPFile(e) : file;
        }).filter(filter::accept).collect(Collectors.toList());
    }

    /**
     * Gets an array of FTPFile objects containing the whole list of files returned by the server as read by this object's parser.
     *
     * @return an array of FTPFile objects containing the whole list of files returned by the server as read by this object's parser. None of the entries will
     *         be null
     * @throws IOException - not ever thrown, may be removed in a later release
     */
    // TODO remove; not actually thrown
    public FTPFile[] getFiles() throws IOException {
        return getFiles(FTPFileFilters.NON_NULL);
    }

    /**
     * Gets an array of FTPFile objects containing the whole list of files returned by the server as read by this object's parser. The files are filtered
     * before being added to the array.
     *
     * @param filter FTPFileFilter, must not be {@code null}.
     * @return an array of FTPFile objects containing the whole list of files returned by the server as read by this object's parser.
     *         <p>
     *         <strong> NOTE:</strong> This array may contain null members if any of the individual file listings failed to parse. The caller should check each
     *         entry for null before referencing it, or use a filter such as {@link FTPFileFilters#NON_NULL} which does not allow null entries.
     * @throws IOException - not ever thrown, may be removed in a later release
     * @since 2.2
     */
    // TODO remove; not actually thrown
    public FTPFile[] getFiles(final FTPFileFilter filter) throws IOException {
        return getFileList(filter).toArray(EMPTY_FTP_FILE_ARRAY);
    }

    /**
     * Gets an array of at most {@code quantityRequested} FTPFile objects starting at this object's internal iterator's current position. If fewer than
     * {@code quantityRequested} such elements are available, the returned array will have a length equal to the number of entries at and after the current
     * position. If no such entries are found, this array will have a length of 0.
     *
     * After this method is called this object's internal iterator is advanced by a number of positions equal to the size of the array returned.
     *
     * @param quantityRequested the maximum number of entries we want to get.
     * @return an array of at most {@code quantityRequested} FTPFile objects starting at the current position of this iterator within its list and at least the
     *         number of elements which exist in the list at and after its current position.
     *         <p>
     *         <strong> NOTE:</strong> This array may contain null members if any of the individual file listings failed to parse. The caller should check each
     *         entry for null before referencing it.
     */
    public FTPFile[] getNext(final int quantityRequested) {
        final List<FTPFile> tmpResults = new LinkedList<>();
        int count = quantityRequested;
        while (count > 0 && internalIterator.hasNext()) {
            final String entry = internalIterator.next();
            FTPFile temp = parser.parseFTPEntry(entry);
            if (temp == null && saveUnparseableEntries) {
                temp = new FTPFile(entry);
            }
            tmpResults.add(temp);
            count--;
        }
        return tmpResults.toArray(EMPTY_FTP_FILE_ARRAY);

    }

    /**
     * Gets an array of at most {@code quantityRequested} FTPFile objects starting at this object's internal iterator's current position, and working back
     * toward the beginning.
     *
     * If fewer than {@code quantityRequested} such elements are available, the returned array will have a length equal to the number of entries at and after
     * the current position. If no such entries are found, this array will have a length of 0.
     *
     * After this method is called this object's internal iterator is moved back by a number of positions equal to the size of the array returned.
     *
     * @param quantityRequested the maximum number of entries we want to get.
     * @return an array of at most {@code quantityRequested} FTPFile objects starting at the current position of this iterator within its list and at least the
     *         number of elements which exist in the list at and after its current position. This array will be in the same order as the underlying list (not
     *         reversed).
     *         <p>
     *         <strong> NOTE:</strong> This array may contain null members if any of the individual file listings failed to parse. The caller should check each
     *         entry for null before referencing it.
     */
    public FTPFile[] getPrevious(final int quantityRequested) {
        final List<FTPFile> tmpResults = new LinkedList<>();
        int count = quantityRequested;
        while (count > 0 && internalIterator.hasPrevious()) {
            final String entry = internalIterator.previous();
            FTPFile temp = parser.parseFTPEntry(entry);
            if (temp == null && saveUnparseableEntries) {
                temp = new FTPFile(entry);
            }
            tmpResults.add(0, temp);
            count--;
        }
        return tmpResults.toArray(EMPTY_FTP_FILE_ARRAY);
    }

    /**
     * convenience method to allow clients to know whether this object's internal iterator's current position is at the end of the list.
     *
     * @return true if internal iterator is not at end of list, false otherwise.
     */
    public boolean hasNext() {
        return internalIterator.hasNext();
    }

    /**
     * convenience method to allow clients to know whether this object's internal iterator's current position is at the beginning of the list.
     *
     * @return true if internal iterator is not at beginning of list, false otherwise.
     */
    public boolean hasPrevious() {
        return internalIterator.hasPrevious();
    }

    /**
     * Internal method for reading (and closing) the input into the {@code entries} list. After this method has completed, {@code entries} will contain a
     * collection of entries (as defined by {@code FTPFileEntryParser.readNextEntry()}), but this may contain various non-entry preliminary lines from the
     * server output, duplicates, and other data that will not be part of the final listing.
     *
     * @param inputStream The socket stream on which the input will be read.
     * @param charsetName The encoding to use.
     * @throws IOException thrown on any failure to read the stream
     */
    private void read(final InputStream inputStream, final String charsetName) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, Charsets.toCharset(charsetName)))) {
            String line = parser.readNextEntry(reader);
            while (line != null) {
                entries.add(line);
                line = parser.readNextEntry(reader);
            }
        }
    }

    /**
     * Do not use.
     *
     * @param inputStream the stream from which to read
     * @throws IOException on error
     * @deprecated use {@link #readServerList(InputStream, String)} instead
     */
    @Deprecated
    public void readServerList(final InputStream inputStream) throws IOException {
        readServerList(inputStream, null);
    }

    /**
     * Reads (and closes) the initial reading and preparsing of the list returned by the server. After this method has completed, this object will contain a
     * list of unparsed entries (Strings) each referring to a unique file on the server.
     *
     * @param inputStream input stream provided by the server socket.
     * @param charsetName the encoding to be used for reading the stream
     * @throws IOException thrown on any failure to read from the sever.
     */
    public void readServerList(final InputStream inputStream, final String charsetName) throws IOException {
        entries = new LinkedList<>();
        read(inputStream, charsetName);
        parser.preParse(entries);
        resetIterator();
    }

    /**
     * resets this object's internal iterator to the beginning of the list.
     */
    public void resetIterator() {
        internalIterator = entries.listIterator();
    }

}
