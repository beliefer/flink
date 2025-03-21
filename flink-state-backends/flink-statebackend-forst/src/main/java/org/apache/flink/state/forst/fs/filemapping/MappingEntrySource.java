/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.state.forst.fs.filemapping;

import org.apache.flink.core.fs.FSDataInputStream;
import org.apache.flink.core.fs.Path;
import org.apache.flink.runtime.state.StreamStateHandle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.IOException;

/**
 * Indicates the source file of the {@link MappingEntry}. It may be backed either by a file or a
 * {@link StreamStateHandle}.
 */
public abstract class MappingEntrySource {

    protected static final Logger LOG = LoggerFactory.getLogger(MappingEntrySource.class);

    MappingEntrySource() {}

    public abstract void delete(boolean recursive) throws IOException;

    public abstract @Nullable Path getFilePath();

    public abstract long getSize() throws IOException;

    public abstract FSDataInputStream openInputStream() throws IOException;

    public abstract FSDataInputStream openInputStream(int bufferSize) throws IOException;

    public abstract boolean cacheable();

    public abstract StreamStateHandle toStateHandle() throws IOException;
}
