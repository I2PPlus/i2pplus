/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.commons.net.nntp;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.apache.commons.io.IOUtils;
import org.apache.commons.net.io.DotTerminatedMessageReader;

/**
 * Wraps a {@link BufferedReader} and returns an {@code Iterable<String>} which returns the individual lines from the reader.
 *
 * @since 3.0
 */
final class ReplyIterator implements Iterator<String>, Iterable<String> {

    private final BufferedReader reader;

    private String line;

    private Exception savedException;

    ReplyIterator(final BufferedReader _reader) throws IOException {
        this(_reader, true);
    }

    /**
     *
     * @param _reader      the reader to wrap
     * @param addDotReader whether to additionally wrap the reader in a DotTerminatedMessageReader
     * @throws IOException
     */
    ReplyIterator(final BufferedReader _reader, final boolean addDotReader) throws IOException {
        reader = addDotReader ? new DotTerminatedMessageReader(_reader) : _reader;
        line = reader.readLine(); // prime the iterator
        if (line == null) {
            IOUtils.closeQuietly(reader);
        }
    }

    @Override
    public boolean hasNext() {
        if (savedException != null) {
            throw new NoSuchElementException(savedException.toString());
        }
        return line != null;
    }

    @Override
    public Iterator<String> iterator() {
        return this;
    }

    @Override
    public String next() throws NoSuchElementException {
        if (savedException != null) {
            throw new NoSuchElementException(savedException.toString());
        }
        final String prev = line;
        if (prev == null) {
            throw new NoSuchElementException();
        }
        try {
            line = reader.readLine(); // save next line
            if (line == null) {
                IOUtils.closeQuietly(reader);
            }
        } catch (final IOException ex) {
            savedException = ex; // if it fails, save the exception, as it does not apply to this call
            IOUtils.closeQuietly(reader);
        }
        return prev;
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }
}
