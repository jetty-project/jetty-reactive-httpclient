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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.reactive.client.ReactiveResponse;

/**
 * <p>A {@link org.reactivestreams.Processor} that buffers {@link Content.Chunk}s
 * up to a max capacity.</p>
 * <p>When  the {@code complete} event is received from upstream, {@link #process(List)}
 * is invoked to processes the chunks and produce a single item of type {@code T},
 * that is then published to downstream.</p>
 *
 * @param <T> the type of the item resulted from processing the chunks
 */
public abstract class AbstractBufferingProcessor<T> extends AbstractSingleProcessor<Content.Chunk, T> {
    public static final int DEFAULT_MAX_CAPACITY = 2 * 1024 * 1024;

    private final List<Content.Chunk> chunks = new ArrayList<>();
    private final ReactiveResponse response;
    private final int maxCapacity;
    private int capacity;

    public AbstractBufferingProcessor(ReactiveResponse response, int maxCapacity) {
        this.response = response;
        this.maxCapacity = maxCapacity;
    }

    public ReactiveResponse getResponse() {
        return response;
    }

    @Override
    public void onNext(Content.Chunk chunk) {
        capacity += chunk.remaining();
        if ((maxCapacity > 0 && capacity > maxCapacity) || capacity < 0) {
            upStreamCancel();
            onError(new IllegalStateException("buffering capacity %d exceeded".formatted(maxCapacity)));
            return;
        }
        chunks.add(chunk);
        upStreamRequest(1);
    }

    @Override
    public void onError(Throwable throwable) {
        chunks.forEach(Content.Chunk::release);
        super.onError(throwable);
    }

    @Override
    public void onComplete() {
        T result = process(chunks);
        chunks.clear();
        downStreamOnNext(result);
        super.onComplete();
    }

    @Override
    public void cancel() {
        chunks.forEach(Content.Chunk::release);
        super.cancel();
    }

    protected abstract T process(List<Content.Chunk> chunks);
}
