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

import java.util.Objects;

/**
 * Implements some simple FTPFileFilter classes.
 *
 * @since 2.2
 */
public class FTPFileFilters {

    /**
     * Accepts all FTPFile entries, including null.
     */
    public static final FTPFileFilter ALL = file -> true;

    /**
     * Accepts all non-null FTPFile entries.
     */
    public static final FTPFileFilter NON_NULL = Objects::nonNull;

    /**
     * Accepts all (non-null) FTPFile directory entries.
     */
    public static final FTPFileFilter DIRECTORIES = file -> file != null && file.isDirectory();

    /**
     * Constructs a new instance.
     *
     * @deprecated Will be private in the next major release.
     */
    @Deprecated
    public FTPFileFilters() {
        // empty
    }
}
