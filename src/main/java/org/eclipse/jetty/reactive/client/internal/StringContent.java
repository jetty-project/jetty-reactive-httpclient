/*
 * Copyright (c) 2017-2018 the original author or authors.
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
import java.nio.charset.Charset;
import java.util.Objects;

import org.eclipse.jetty.reactive.client.ContentChunk;
import org.eclipse.jetty.reactive.client.ReactiveRequest;
import org.eclipse.jetty.util.Callback;
import org.reactivestreams.Subscriber;

/**
 * <p>Utility class that provides a String as reactive content.</p>
 */
public class StringContent extends AbstractSinglePublisher<ContentChunk> implements ReactiveRequest.Content {
    private final String mediaType;
    private final Charset encoding;
    private final byte[] bytes;
    private boolean complete;

    public StringContent(String string, String mediaType, Charset encoding) {
        this.mediaType = Objects.requireNonNull(mediaType);
        this.encoding = Objects.requireNonNull(encoding);
        this.bytes = string.getBytes(encoding);
    }

    @Override
    public long getLength() {
        return bytes.length;
    }

    @Override
    public String getContentType() {
        return mediaType + ";charset=" + encoding.name();
    }

    @Override
    protected void onRequest(Subscriber<? super ContentChunk> subscriber, long n) {
        if (!complete) {
            complete = true;
            subscriber.onNext(new ContentChunk(ByteBuffer.wrap(bytes), Callback.NOOP));
            subscriber.onComplete();
        }
    }
}
