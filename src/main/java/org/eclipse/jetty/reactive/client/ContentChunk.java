/*
 * Copyright (c) 2017-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.eclipse.jetty.reactive.client;

import java.nio.ByteBuffer;
import java.util.Objects;

import org.eclipse.jetty.util.Callback;

/**
 * A chunk of content, made of a ByteBuffer and a Callback.
 */
public class ContentChunk {
    public final ByteBuffer buffer;
    public final Callback callback;

    public ContentChunk(ByteBuffer buffer) {
        this(buffer, Callback.NOOP);
    }

    public ContentChunk(ByteBuffer buffer, Callback callback) {
        this.buffer = Objects.requireNonNull(buffer);
        this.callback = Objects.requireNonNull(callback);
    }

    @Override
    public String toString() {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }
}
