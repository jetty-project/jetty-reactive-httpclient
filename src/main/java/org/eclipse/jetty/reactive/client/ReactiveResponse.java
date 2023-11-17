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
package org.eclipse.jetty.reactive.client;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.function.BiFunction;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content.Chunk;
import org.eclipse.jetty.reactive.client.internal.ByteBufferBufferingProcessor;
import org.eclipse.jetty.reactive.client.internal.DiscardingProcessor;
import org.eclipse.jetty.reactive.client.internal.ResultProcessor;
import org.eclipse.jetty.reactive.client.internal.StringBufferingProcessor;
import org.eclipse.jetty.reactive.client.internal.Transformer;
import org.reactivestreams.Publisher;

/**
 * <p>A reactive wrapper over Jetty's {@code HttpClient} {@link Response}.</p>
 * <p>A ReactiveResponse is available as soon as the response headers arrived
 * to the client. The response content is processed by a response content
 * function specified in {@link ReactiveRequest#response(BiFunction)}.</p>
 */
public class ReactiveResponse {
    private final ReactiveRequest request;
    private final Response response;
    private String mediaType;
    private String encoding;

    public ReactiveResponse(ReactiveRequest request, Response response) {
        this.request = request;
        this.response = response;
    }

    /**
     * @return the ReactiveRequest correspondent to this response
     */
    public ReactiveRequest getReactiveRequest() {
        return request;
    }

    /**
     * @return the wrapped Jetty response
     */
    public Response getResponse() {
        return response;
    }

    /**
     * @return the HTTP status code
     */
    public int getStatus() {
        return response.getStatus();
    }

    /**
     * @return the HTTP response headers
     */
    public HttpFields getHeaders() {
        return response.getHeaders();
    }

    /**
     * @return the media type specified by the {@code Content-Type} header
     * @see #getEncoding()
     */
    public String getMediaType() {
        resolveContentType();
        return mediaType.isEmpty() ? null : mediaType;
    }

    /**
     * @return the encoding specified by the {@code Content-Type} header
     * @see #getMediaType()
     */
    public String getEncoding() {
        resolveContentType();
        return encoding.isEmpty() ? null : encoding;
    }

    private void resolveContentType() {
        if (mediaType == null) {
            String contentType = getHeaders().get(HttpHeader.CONTENT_TYPE);
            if (contentType != null) {
                String media = contentType;
                String charset = "charset=";
                int index = contentType.toLowerCase(Locale.ENGLISH).indexOf(charset);
                if (index > 0) {
                    media = contentType.substring(0, index);
                    String encoding = contentType.substring(index + charset.length());
                    // Sometimes charsets arrive with an ending semicolon
                    int semicolon = encoding.indexOf(';');
                    if (semicolon > 0) {
                        encoding = encoding.substring(0, semicolon).trim();
                    }
                    this.encoding = encoding;
                } else {
                    this.encoding = "";
                }
                int semicolon = media.indexOf(';');
                if (semicolon > 0) {
                    media = media.substring(0, semicolon).trim();
                }
                this.mediaType = media;
            } else {
                this.mediaType = "";
                this.encoding = "";
            }
        }
    }

    @Override
    public String toString() {
        return String.format("Reactive[%s]", response);
    }

    /**
     * Collects utility methods to process response content.
     */
    public static class Content {
        /**
         * @return a response content processing function that discards the content
         */
        public static BiFunction<ReactiveResponse, Publisher<Chunk>, Publisher<ReactiveResponse>> discard() {
            return (response, content) -> {
                DiscardingProcessor result = new DiscardingProcessor(response);
                content.subscribe(result);
                return result;
            };
        }

        /**
         * @return a response content processing function that converts the content to a string
         * up to {@value StringBufferingProcessor#DEFAULT_MAX_CAPACITY} bytes.
         */
        public static BiFunction<ReactiveResponse, Publisher<Chunk>, Publisher<String>> asString() {
            return asString(StringBufferingProcessor.DEFAULT_MAX_CAPACITY);
        }

        /**
         * @return a response content processing function that converts the content to a string
         * up to the specified maximum capacity in bytes.
         */
        public static BiFunction<ReactiveResponse, Publisher<Chunk>, Publisher<String>> asString(int maxCapacity) {
            return (response, content) -> {
                StringBufferingProcessor result = new StringBufferingProcessor(response, maxCapacity);
                content.subscribe(result);
                return result;
            };
        }

        /**
         * @return a response content processing function that converts the content to a {@link ByteBuffer}
         * up to {@value ByteBufferBufferingProcessor#DEFAULT_MAX_CAPACITY} bytes.
         */
        public static BiFunction<ReactiveResponse, Publisher<Chunk>, Publisher<ByteBuffer>> asByteBuffer() {
            return asByteBuffer(ByteBufferBufferingProcessor.DEFAULT_MAX_CAPACITY);
        }

        /**
         * @return a response content processing function that converts the content to a {@link ByteBuffer}
         * up to the specified maximum capacity in bytes.
         */
        public static BiFunction<ReactiveResponse, Publisher<Chunk>, Publisher<ByteBuffer>> asByteBuffer(int maxCapacity) {
            return (response, content) -> {
                ByteBufferBufferingProcessor result = new ByteBufferBufferingProcessor(response, maxCapacity);
                content.subscribe(result);
                return result;
            };
        }

        /**
         * @return a response content processing function that discards the content
         * and produces a {@link Result} with a {@code null} content of the given type.
         *
         * @param <T> the type of the content
         */
        public static <T> BiFunction<ReactiveResponse, Publisher<Chunk>, Publisher<Result<T>>> asDiscardResult() {
            return (response, content) -> {
                ResultProcessor<ReactiveResponse> resultProcessor = new ResultProcessor<>(response);
                discard().apply(response, content).subscribe(resultProcessor);
                Transformer<Result<ReactiveResponse>, Result<T>> transformer = new Transformer<>(r -> null);
                resultProcessor.subscribe(transformer);
                return transformer;
            };
        }

        /**
         * @return a response content processing function that converts the content to a string
         * up to {@value StringBufferingProcessor#DEFAULT_MAX_CAPACITY} bytes,
         * and produces a {@link Result} with the string content.
         */
        public static BiFunction<ReactiveResponse, Publisher<Chunk>, Publisher<Result<String>>> asStringResult() {
            return asStringResult(StringBufferingProcessor.DEFAULT_MAX_CAPACITY);
        }

        /**
         * @return a response content processing function that converts the content to a string
         * up to the specified maximum capacity in bytes,
         * and produces a {@link Result} with the string content.
         */
        public static BiFunction<ReactiveResponse, Publisher<Chunk>, Publisher<Result<String>>> asStringResult(int maxCapacity) {
            return (response, content) -> {
                ResultProcessor<String> resultProcessor = new ResultProcessor<>(response);
                asString(maxCapacity).apply(response, content).subscribe(resultProcessor);
                return resultProcessor;
            };
        }

        /**
         * @return a response content processing function that converts the content to a {@link ByteBuffer}
         * up to {@value ByteBufferBufferingProcessor#DEFAULT_MAX_CAPACITY} bytes,
         * and produces a {@link Result} with the {@link ByteBuffer} content.
         */
        public static BiFunction<ReactiveResponse, Publisher<Chunk>, Publisher<Result<ByteBuffer>>> asByteBufferResult() {
            return asByteBufferResult(ByteBufferBufferingProcessor.DEFAULT_MAX_CAPACITY);
        }

        /**
         * @return a response content processing function that converts the content to a {@link ByteBuffer}
         * up to the specified maximum capacity in bytes,
         * and produces a {@link Result} with the {@link ByteBuffer} content.
         */
        public static BiFunction<ReactiveResponse, Publisher<Chunk>, Publisher<Result<ByteBuffer>>> asByteBufferResult(int maxCapacity) {
            return (response, content) -> {
                ResultProcessor<ByteBuffer> resultProcessor = new ResultProcessor<>(response);
                asByteBuffer(maxCapacity).apply(response, content).subscribe(resultProcessor);
                return resultProcessor;
            };
        }
    }

    /**
     * <p>A record holding the {@link ReactiveResponse} and the response content.</p>
     *
     * @param response the response
     * @param content the response content
     * @param <T> the type of the response content
     */
    public record Result<T>(ReactiveResponse response, T content) {
    }

    public static class Event {
        private final Type type;
        private final ReactiveResponse response;
        private final ByteBuffer content;
        private final Throwable failure;

        public Event(Type type, ReactiveResponse response) {
            this(type, response, null, null);
        }

        public Event(Type type, ReactiveResponse response, ByteBuffer content) {
            this(type, response, content, null);
        }

        public Event(Type type, ReactiveResponse response, Throwable failure) {
            this(type, response, null, failure);
        }

        private Event(Type type, ReactiveResponse response, ByteBuffer content, Throwable failure) {
            this.type = type;
            this.response = response;
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
         * @return the response that generated this event
         */
        public ReactiveResponse getResponse() {
            return response;
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

        public enum Type {
            BEGIN,
            HEADERS,
            CONTENT,
            SUCCESS,
            FAILURE,
            COMPLETE
        }
    }
}
