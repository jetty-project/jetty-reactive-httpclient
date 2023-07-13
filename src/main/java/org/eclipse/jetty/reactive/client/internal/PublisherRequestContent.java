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

import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.io.content.AsyncContent;
import org.eclipse.jetty.reactive.client.ContentChunk;
import org.eclipse.jetty.reactive.client.ReactiveRequest;
import org.eclipse.jetty.reactive.client.ReactiveRequest.Content;
import org.eclipse.jetty.util.Callback;

public class PublisherRequestContent implements Request.Content, org.reactivestreams.Subscriber<ContentChunk> {
    private final AsyncContent asyncContent = new AsyncContent();
    private final ReactiveRequest.Content reactiveContent;
    private org.reactivestreams.Subscription subscription;

    public PublisherRequestContent(Content content) {
        this.reactiveContent = content;
        content.subscribe(this);
    }

    @Override
    public long getLength() {
        return reactiveContent.getLength();
    }

    @Override
    public org.eclipse.jetty.io.Content.Chunk read() {
        return asyncContent.read();
    }

    @Override
    public void demand(Runnable runnable) {
        asyncContent.demand(runnable);
    }

    @Override
    public void fail(Throwable failure) {
        onError(failure);
    }

    @Override
    public String getContentType() {
        return reactiveContent.getContentType();
    }

    @Override
    public void onSubscribe(org.reactivestreams.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(1);
    }

    @Override
    public void onNext(ContentChunk chunk) {
        asyncContent.write(false, chunk.buffer, new Callback.Nested(chunk.callback) {
            @Override
            public void succeeded() {
                super.succeeded();
                subscription.request(1);
            }

            @Override
            public void failed(Throwable x) {
                super.failed(x);
                subscription.cancel();
            }
        });
    }

    @Override
    public void onError(Throwable failure) {
        asyncContent.fail(failure);
    }

    @Override
    public void onComplete() {
        asyncContent.close();
    }

    @Override
    public String toString() {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }
}
