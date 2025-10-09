/*
 * Copyright (c) 2017 the original author or authors.
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
package org.eclipse.jetty.reactive.client.internal;

import java.nio.ByteBuffer;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.reactive.client.ReactiveRequest;
import org.reactivestreams.Subscriber;

/**
 * <p>Utility class that provides a {@code byte[]} as reactive content.</p>
 */
public class BytesContent extends AbstractSinglePublisher<Content.Chunk> implements ReactiveRequest.Content {
    private final byte[] bytes;
    private final String contentType;

    public BytesContent(byte[] bytes, String contentType) {
        this.bytes = bytes;
        this.contentType = contentType;
    }

    @Override
    public long getLength() {
        return bytes.length;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean rewind() {
        return true;
    }

    @Override
    protected void onRequest(Subscriber<? super Content.Chunk> subscriber, long n) {
        // The whole byte[] is sent at once, so this is the last chunk.
        emitOnNext(subscriber, Content.Chunk.from(ByteBuffer.wrap(bytes), true));
        emitOnComplete(subscriber);
    }
}
