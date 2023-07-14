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
import java.util.concurrent.CancellationException;
import java.util.function.BiFunction;

import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.reactive.client.ReactiveRequest;
import org.eclipse.jetty.reactive.client.ReactiveResponse;
import org.eclipse.jetty.util.thread.AutoLock;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A Processor that listens for response events.</p>
 * <p>When this Processor is demanded data, it first sends the request and produces no data.
 * When the response arrives, the application is invoked with a Publisher that produces
 * response content chunks.</p>
 * <p>The application <em>processes</em> the response content chunks into some other data
 * structure (for example, splits them further, or coalesce them into a single chunk) and
 * returns the application Processor to this implementation, which then builds this chain:</p>
 * <pre>
 * HTTP response content chunks Publisher - (produces Content.Chunks)
 *   Application Processor                - (processes Content.Chunks and produces Ts)
 *     ResponseListenerProcessor          - (forwards Ts to application)
 *       Application Subscriber           - (consumes Ts)
 * </pre>
 * <p>Data flows from top to bottom, demand from bottom to top.</p>
 * <p>ResponseListenerProcessor acts as a "hot" publisher: it is returned to the application
 * <em>before</em> the response content arrives so that the application can subscribe to it.</p>
 * <p>Any further data demand to this Processor is forwarded to the Application Processor,
 * which in turn demands response content chunks.
 * Response content chunks arrive to the Application Processor, which processes them and
 * produces data that is forwarded to this Processor, which forwards it to the Application
 * Subscriber.</p>
 */
public class ResponseListenerProcessor<T> extends AbstractSingleProcessor<T, T> implements Response.Listener {
    private static final Logger logger = LoggerFactory.getLogger(ResponseListenerProcessor.class);

    private final ContentPublisher content = new ContentPublisher();
    private final ReactiveRequest request;
    private final BiFunction<ReactiveResponse, Publisher<Content.Chunk>, Publisher<T>> contentFn;
    private final boolean abortOnCancel;
    private boolean requestSent;
    private boolean responseReceived;

    public ResponseListenerProcessor(ReactiveRequest request, BiFunction<ReactiveResponse, Publisher<Content.Chunk>, Publisher<T>> contentFn, boolean abortOnCancel) {
        this.request = request;
        this.contentFn = contentFn;
        this.abortOnCancel = abortOnCancel;
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
            logger.debug("received response headers {} on {}", response, this);
        }
        responseReceived = true;
        Publisher<T> publisher = contentFn.apply(request.getReactiveResponse(), content);
        publisher.subscribe(this);
    }

    @Override
    public void onContent(Response response, ByteBuffer content) {
    }

    @Override
    public void onContent(Response response, Content.Chunk chunk, Runnable demander) {
    }

    @Override
    public void onContentSource(Response response, Content.Source source) {
        if (logger.isDebugEnabled()) {
            logger.debug("received response content source {} {} on {}", response, source, this);
        }
        content.accept(source);
    }

    @Override
    public void onSuccess(Response response) {
        if (logger.isDebugEnabled()) {
            logger.debug("response complete {} on {}", response, this);
        }
    }

    @Override
    public void onFailure(Response response, Throwable failure) {
        if (logger.isDebugEnabled()) {
            logger.debug("response failure {} on {}", response, this, failure);
        }
    }

    @Override
    public void onComplete(Result result) {
        if (result.isSucceeded()) {
            content.complete();
        } else {
            Throwable failure = result.getFailure();
            if (!content.fail(failure)) {
                if (!responseReceived) {
                    onError(failure);
                }
            }
        }
    }

    @Override
    protected void onRequest(Subscriber<? super T> subscriber, long n) {
        boolean send;
        try (AutoLock ignored = lock()) {
            send = !requestSent;
            requestSent = true;
        }
        if (send) {
            send();
        }
        super.onRequest(subscriber, n);
    }

    @Override
    public void onNext(T t) {
        downStreamOnNext(t);
    }

    private void send() {
        if (logger.isDebugEnabled()) {
            logger.debug("sending request {} from {}", request, this);
        }
        request.getRequest().send(this);
    }

    @Override
    public void cancel() {
        if (abortOnCancel) {
            request.getRequest().abort(new CancellationException());
        }
        super.cancel();
    }

    @Override
    public String toString() {
        return String.format("%s@%x[%s]", getClass().getSimpleName(), hashCode(), request);
    }

    /**
     * <p>Publishes response {@link Content.Chunk}s to application code.</p>
     */
    private static class ContentPublisher extends QueuedSinglePublisher<Content.Chunk> {
        private boolean initialDemand;
        private Content.Source source;

        private void accept(Content.Source contentSource) {
            boolean demand;
            try (AutoLock ignored = lock()) {
                source = contentSource;
                demand = initialDemand;
            }
            if (demand) {
                read(contentSource);
            }
        }

        @Override
        protected void onRequest(Subscriber<? super Content.Chunk> subscriber, long n) {
            if (logger.isDebugEnabled()) {
                logger.debug("demand {} on {}", n, this);
            }
            super.onRequest(subscriber, n);
            Content.Source content;
            try (AutoLock ignored = lock()) {
                content = source;
                if (content == null) {
                    initialDemand = true;
                }
            }
            if (content != null) {
                read(content);
            }
        }

        private void read(Content.Source source) {
            Content.Chunk chunk = source.read();
            if (logger.isDebugEnabled()) {
                logger.debug("read {} from {} on {}", chunk, source, this);
            }
            if (chunk == null) {
                source.demand(() -> read(source));
                return;
            }
            if (Content.Chunk.isFailure(chunk)) {
                fail(chunk.getFailure());
                return;
            }
            if (chunk.hasRemaining()) {
                try {
                    offer(chunk);
                } catch (Throwable x) {
                    chunk.release();
                    fail(x);
                }
            }
        }
    }
}
