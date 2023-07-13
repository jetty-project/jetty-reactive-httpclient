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

import java.util.Objects;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.reactive.client.ReactiveRequest;
import org.reactivestreams.Publisher;

/**
 * A {@link ReactiveRequest.Content} that wraps a Publisher.
 */
public class PublisherContent extends AbstractSingleProcessor<Content.Chunk, Content.Chunk> implements ReactiveRequest.Content {
    private final String contentType;

    public PublisherContent(Publisher<Content.Chunk> publisher, String contentType) {
        this.contentType = Objects.requireNonNull(contentType);
        publisher.subscribe(this);
    }

    @Override
    public long getLength() {
        return -1;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public void onNext(Content.Chunk chunk) {
        downStreamOnNext(chunk);
    }
}
