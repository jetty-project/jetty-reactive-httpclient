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

import java.util.concurrent.CancellationException;
import java.util.function.BiFunction;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
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
    public void onHeaders(Response response) {
        if (logger.isDebugEnabled()) {
            logger.debug("received response headers {} on {}", response, this);
        }
    }

    @Override
    public void onContentSource(Response response, Content.Source source) {
        if (logger.isDebugEnabled()) {
            logger.debug("received response content source {} {} on {}", response, source, this);
        }

        // Link the source of Chunks with the Publisher of Chunks.
        content.accept(source);

        responseReceived = true;

        // Call the application to obtain a response content transformer.
        Publisher<T> appPublisher = contentFn.apply(request.getReactiveResponse(), content);

        // Establish the stream chain (content chunks flow top-bottom)
        // upstream -- produces Chunks (HttpClient)
        //    -> this.content -- emits Chunks transformed by app's BiFunction
        //       -> appPublisher -- transform Chunks into Ts and emits Ts
        //          -> this -- receives Ts and emits Ts as Publisher<T> returned from request.response()
        //             -> app subscriber
        //                -> downstream (application)
        appPublisher.subscribe(this);
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
    private static class ContentPublisher extends QueuedSinglePublisher<Content.Chunk> implements Runnable {
        private volatile Content.Source contentSource;

        private void accept(Content.Source source) {
            contentSource = source;
        }

        @Override
        protected void onRequest(Subscriber<? super Content.Chunk> subscriber, long n) {
            super.onRequest(subscriber, n);

            // This method is called by:
            // 1) An application thread, in case of asynchronous demand => resume production.
            // 2) A producer thread, from onNext() + request() => must not resume production.

            tryProduce(this);
        }

        @Override
        public void run() {
            Content.Source source = contentSource;
            if (source != null) {
                read(source);
            }
        }

        private void read(Content.Source source) {
            while (true) {
                if (!hasDemand()) {
                    return;
                }

                Content.Chunk chunk = source.read();
                if (logger.isDebugEnabled()) {
                    logger.debug("read {} from {} on {}", chunk, source, this);
                }

                if (chunk == null) {
                    source.demand(this);
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
                        return;
                    }
                } else {
                    chunk.release();
                }

                if (chunk.isLast()) {
                    return;
                }
            }
        }
    }
}
