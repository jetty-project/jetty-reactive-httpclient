/*
 * Copyright (c) 2017-2022 the original author or authors.
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
import java.nio.charset.Charset;
import java.util.function.BiFunction;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.reactive.client.internal.PublisherContent;
import org.eclipse.jetty.reactive.client.internal.PublisherRequestContent;
import org.eclipse.jetty.reactive.client.internal.RequestEventPublisher;
import org.eclipse.jetty.reactive.client.internal.ResponseEventPublisher;
import org.eclipse.jetty.reactive.client.internal.ResponseListenerProcessor;
import org.eclipse.jetty.reactive.client.internal.StringContent;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;

/**
 * <p>A reactive wrapper over Jetty's {@code HttpClient} {@link Request}.</p>
 * <p>A ReactiveRequest can be obtained via a builder:</p>
 * <pre>
 * // Built with HttpClient and a string URI.
 * ReactiveRequest request = ReactiveRequest.newBuilder(httpClient, uri()).build();
 *
 * // Built by wrapping a Request.
 * Request req = httpClient.newRequest(...);
 * ...
 * ReactiveRequest request = ReactiveRequest.newBuilder(req).build();
 * </pre>
 * <p>Once created, a ReactiveRequest can be sent to obtain a {@link Publisher}
 * for a {@link ReactiveResponse} passing a function that handles the response
 * content:</p>
 * <pre>
 * Publisher&lt;T&gt; response = request.response((response, content) -&gt; { ... });
 * </pre>
 */
public class ReactiveRequest {
    /**
     * @param httpClient the HttpClient instance
     * @param uri        the target URI for the request - must be properly encoded already
     * @return a builder for a GET request for the given URI
     */
    public static ReactiveRequest.Builder newBuilder(HttpClient httpClient, String uri) {
        return new Builder(httpClient, uri);
    }

    /**
     * @param request the request instance
     * @return a builder for the given Request
     */
    public static ReactiveRequest.Builder newBuilder(Request request) {
        return new Builder(request);
    }

    private final RequestEventPublisher requestEvents = new RequestEventPublisher(this);
    private final ResponseEventPublisher responseEvents = new ResponseEventPublisher(this);
    private final Request request;
    private final boolean abortOnCancel;
    private volatile ReactiveResponse response;

    protected ReactiveRequest(Request request) {
        this(request, false);
    }

    private ReactiveRequest(Request request, boolean abortOnCancel) {
        this.request = request.listener(requestEvents)
                .onResponseBegin(r -> {
                    this.response = new ReactiveResponse(this, r);
                })
                .onResponseBegin(responseEvents)
                .onResponseHeaders(responseEvents)
                .onResponseContentDemanded(responseEvents)
                .onResponseSuccess(responseEvents)
                .onResponseFailure(responseEvents)
                .onComplete(responseEvents);
        this.abortOnCancel = abortOnCancel;
    }

    /**
     * @return the ReactiveResponse correspondent to this request,
     * or null if the response is not available yet
     */
    public ReactiveResponse getReactiveResponse() {
        return response;
    }

    /**
     * @return the wrapped Jetty request
     */
    public Request getRequest() {
        return request;
    }

    /**
     * <p>Creates a Publisher that sends the request when a Subscriber requests the response
     * via {@link Subscription#request(long)}, discarding the response content.</p>
     *
     * @return a Publisher for the response
     */
    public Publisher<ReactiveResponse> response() {
        return response(ReactiveResponse.Content.discard());
    }

    /**
     * <p>Creates a Publisher that sends the request when a Subscriber requests the response
     * via {@link Subscription#request(long)}, processing the response content with the given
     * function.</p>
     * <p>Applications must subscribe (possibly asynchronously) to the response content Publisher,
     * even if it is known that the response has no content, to receive the response success/failure
     * events.</p>
     *
     * @param contentFn the function that processes the response content
     * @param <T>       the element type of the processed response content
     * @return a Publisher for the processed content
     */
    public <T> Publisher<T> response(BiFunction<ReactiveResponse, Publisher<ContentChunk>, Publisher<T>> contentFn) {
        return new ResponseListenerProcessor<>(this, contentFn, abortOnCancel);
    }

    /**
     * @return a Publisher for request events
     */
    public Publisher<ReactiveRequest.Event> requestEvents() {
        return requestEvents;
    }

    public Publisher<ReactiveResponse.Event> responseEvents() {
        return responseEvents;
    }

    @Override
    public String toString() {
        return String.format("Reactive[%s]", request);
    }

    /**
     * A Builder for ReactiveRequest.
     */
    public static class Builder {
        private final Request request;
        private boolean abortOnCancel;

        public Builder(HttpClient client, String uri) {
            this(client.newRequest(uri));
        }

        public Builder(Request request) {
            this.request = request;
        }

        /**
         * <p>Provides the request content via a Publisher.</p>
         *
         * @param content the request content
         * @return this instance
         */
        public Builder content(Content content) {
            request.body(new PublisherRequestContent(content));
            return this;
        }

        /**
         * @param abortOnCancel whether a request should be aborted when the
         *                      content subscriber cancels the subscription
         * @return this instance
         */
        public Builder abortOnCancel(boolean abortOnCancel) {
            this.abortOnCancel = abortOnCancel;
            return this;
        }

        /**
         * @return a built ReactiveRequest
         */
        public ReactiveRequest build() {
            return new ReactiveRequest(request, abortOnCancel);
        }
    }

    /**
     * A ReactiveRequest event.
     */
    public static class Event {
        private final Type type;
        private final ReactiveRequest request;
        private final ByteBuffer content;
        private final Throwable failure;

        public Event(Type type, ReactiveRequest request) {
            this(type, request, null, null);
        }

        public Event(Type type, ReactiveRequest request, ByteBuffer content) {
            this(type, request, content, null);
        }

        public Event(Type type, ReactiveRequest request, Throwable failure) {
            this(type, request, null, failure);
        }

        private Event(Type type, ReactiveRequest request, ByteBuffer content, Throwable failure) {
            this.type = type;
            this.request = request;
            this.content = content;
            this.failure = failure;
        }

        /**
         * @return the event type
         */
        public Type getType() {
            return type;
        }

        /**
         * @return the request that generated this event
         */
        public ReactiveRequest getRequest() {
            return request;
        }

        /**
         * @return the event content, or null if this is not a content event
         */
        public ByteBuffer getContent() {
            return content;
        }

        /**
         * @return the event failure, or null if this is not a failure event
         */
        public Throwable getFailure() {
            return failure;
        }

        /**
         * The event types
         */
        public enum Type {
            /**
             * The request has been queued
             */
            QUEUED,
            /**
             * The request is ready to be sent
             */
            BEGIN,
            /**
             * The request headers have been prepared
             */
            HEADERS,
            /**
             * The request headers have been sent
             */
            COMMIT,
            /**
             * A chunk of content has been sent
             */
            CONTENT,
            /**
             * The request succeeded
             */
            SUCCESS,
            /**
             * The request failed
             */
            FAILURE
        }
    }

    /**
     * A Publisher of content chunks that also specifies the content length and type.
     */
    public static interface Content extends Publisher<ContentChunk> {
        /**
         * @return the content length
         */
        public long getLength();

        /**
         * @return the content type in the form {@code media_type[;charset=<charset>]}
         */
        public String getContentType();

        public static Content fromString(String string, String mediaType, Charset charset) {
            return new StringContent(string, mediaType, charset);
        }

        public static Content fromPublisher(Publisher<ContentChunk> publisher, String contentType) {
            return new PublisherContent(publisher, contentType);
        }

        public static Content fromPublisher(Publisher<ContentChunk> publisher, String mediaType, Charset charset) {
            return fromPublisher(publisher, mediaType + ";charset=" + charset.name());
        }
    }
}
