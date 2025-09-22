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

package org.apache.commons.net.ftp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Wrapper class for FTP data channel sockets when compressing data in the "deflate" compression format. All methods except of {@link #getInputStream()} and
 * {@link #getOutputStream()} are calling the delegate methods directly.
 */
final class DeflateSocket extends DelegateSocket {

    DeflateSocket(final Socket delegate) {
        super(delegate);
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return new InflaterInputStream(delegate.getInputStream());
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return new DeflaterOutputStream(delegate.getOutputStream());
    }
}
