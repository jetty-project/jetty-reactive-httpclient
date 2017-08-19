/*
 * Copyright (c) 2017-2017 the original author or authors.
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
import java.util.function.BiFunction;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.reactive.client.ContentChunk;
import org.eclipse.jetty.reactive.client.ReactiveRequest;
import org.eclipse.jetty.reactive.client.ReactiveResponse;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Publisher that listens for response events.
 * When this Publisher is demanded data, it first sends the request and produces no data.
 * When the response arrives, the application is invoked and an application Publisher is
 * returned to this implementation.
 * Any further data demand to this Publisher is forwarded to the application Publisher.
 * In turn, the application Publisher produces data that is forwarded to the subscriber
 * of this Publisher.
 */
public class ResponseListenerPublisher<T> extends AbstractSingleProcessor<T, T> implements Response.Listener {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final QueuedSinglePublisher<ContentChunk> content = new QueuedSinglePublisher<>();
    private final ReactiveRequest request;
    private final BiFunction<ReactiveResponse, Publisher<ContentChunk>, Publisher<T>> contentFn;
    private boolean requestSent;
    private long demand;

    public ResponseListenerPublisher(ReactiveRequest request, BiFunction<ReactiveResponse, Publisher<ContentChunk>, Publisher<T>> contentFn) {
        this.request = request;
        this.contentFn = contentFn;
    }

    @Override
    public void onBegin(Response response) {
    }

    @Override
    public boolean onHeader(Response response, HttpField field) {
        return true;
    }

    @Override
    public void onHeaders(Response response) {
        if (logger.isDebugEnabled()) {
            logger.debug("received response headers {}", response);
        }
        Publisher<T> publisher = contentFn.apply(request.getReactiveResponse(), content);
        publisher.subscribe(this);
    }

    @Override
    public void onContent(Response response, ByteBuffer buffer, Callback callback) {
        if (logger.isDebugEnabled()) {
            logger.debug("received response chunk {} {}", response, BufferUtil.toSummaryString(buffer));
        }
        content.offer(new ContentChunk(buffer, callback));
    }

    @Override
    public void onContent(Response response, ByteBuffer content) {
    }

    @Override
    public void onSuccess(Response response) {
        if (logger.isDebugEnabled()) {
            logger.debug("response complete {}", response);
        }
    }

    @Override
    public void onFailure(Response response, Throwable failure) {
        if (logger.isDebugEnabled()) {
            logger.debug("response failure " + response, failure);
        }
    }

    @Override
    public void onComplete(Result result) {
        if (result.isSucceeded()) {
            content.complete();
        } else {
            content.fail(result.getFailure());
        }
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        super.onSubscribe(subscription);
        subscription.request(demand);
    }

    @Override
    protected void onRequest(long n) {
        boolean send = false;
        synchronized (this) {
            if (!requestSent) {
                requestSent = true;
                demand += n;
                send = true;
            }
        }
        if (send) {
            send();
        } else {
            super.onRequest(n);
        }
    }

    @Override
    public void onNext(T t) {
        downStream().onNext(t);
    }

    private void send() {
        if (logger.isDebugEnabled()) {
            logger.debug("sending request {}", request);
        }
        request.getRequest().send(this);
    }
}
