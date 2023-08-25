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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.reactivex.rxjava3.core.Emitter;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.reactive.client.internal.AbstractSingleProcessor;
import org.eclipse.jetty.reactive.client.internal.BufferingProcessor;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RxJava2Test extends AbstractTest {
    @Test
    @Tag("external")
    public void testExternalServer() throws Exception {
        prepare("http", new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                callback.succeeded();
                return true;
            }
        });

        CountDownLatch contentLatch = new CountDownLatch(1);
        CountDownLatch responseLatch = new CountDownLatch(1);
        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient().newRequest("https://example.org")).build();
        Flowable.fromPublisher(request.response((reactiveResponse, chunkPublisher) -> Flowable.fromPublisher(chunkPublisher)
                .map(chunk -> {
                    ByteBuffer byteBuffer = chunk.getByteBuffer();
                    CharBuffer charBuffer = StandardCharsets.UTF_8.decode(byteBuffer);
                    chunk.release();
                    return charBuffer.toString();
                }).doOnComplete(contentLatch::countDown)))
                .doOnComplete(responseLatch::countDown)
                .subscribe();

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("protocols")
    public void testSimpleUsage(String protocol) throws Exception {
        prepare(protocol, new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                callback.succeeded();
                return true;
            }
        });

        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient().newRequest(uri())).build();
        int status = Single.fromPublisher(request.response(ReactiveResponse.Content.discard()))
                .map(ReactiveResponse::getStatus)
                .blockingGet();

        assertEquals(status, HttpStatus.OK_200);
    }

    @ParameterizedTest
    @MethodSource("protocols")
    public void testRequestEvents(String protocol) throws Exception {
        prepare(protocol, new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                callback.succeeded();
                return true;
            }

        });

        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient(), uri()).build();
        Publisher<ReactiveRequest.Event> events = request.requestEvents();

        CountDownLatch latch = new CountDownLatch(1);
        List<String> names = new ArrayList<>();
        Flowable.fromPublisher(events)
                .map(ReactiveRequest.Event::getType)
                .map(ReactiveRequest.Event.Type::name)
                .subscribe(new Subscriber<>() {
                    private Subscription subscription;

                    @Override
                    public void onSubscribe(Subscription subscription) {
                        this.subscription = subscription;
                        subscription.request(1);
                    }

                    @Override
                    public void onNext(String name) {
                        names.add(name);
                        subscription.request(1);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                    }

                    @Override
                    public void onComplete() {
                        latch.countDown();
                    }
                });

        ReactiveResponse response = Single.fromPublisher(request.response()).blockingGet();

        assertEquals(response.getStatus(), HttpStatus.OK_200);
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        List<String> expected = Stream.of(
                        ReactiveRequest.Event.Type.QUEUED,
                        ReactiveRequest.Event.Type.BEGIN,
                        ReactiveRequest.Event.Type.HEADERS,
                        ReactiveRequest.Event.Type.COMMIT,
                        ReactiveRequest.Event.Type.SUCCESS)
                .map(Enum::name)
                .collect(Collectors.toList());
        assertEquals(names, expected);
    }

    @ParameterizedTest
    @MethodSource("protocols")
    public void testResponseEvents(String protocol) throws Exception {
        prepare(protocol, new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                response.write(true, ByteBuffer.wrap(new byte[]{0}), callback);
                return true;
            }
        });

        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient(), uri()).build();
        Publisher<ReactiveResponse.Event> events = request.responseEvents();

        CountDownLatch latch = new CountDownLatch(1);
        List<String> names = new ArrayList<>();
        Flowable.fromPublisher(events)
                .map(ReactiveResponse.Event::getType)
                .map(ReactiveResponse.Event.Type::name)
                .subscribe(new Subscriber<>() {
                    private Subscription subscription;

                    @Override
                    public void onSubscribe(Subscription subscription) {
                        this.subscription = subscription;
                        subscription.request(1);
                    }

                    @Override
                    public void onNext(String name) {
                        names.add(name);
                        subscription.request(1);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                    }

                    @Override
                    public void onComplete() {
                        latch.countDown();
                    }
                });

        ReactiveResponse response = Single.fromPublisher(request.response()).blockingGet();

        assertEquals(response.getStatus(), HttpStatus.OK_200);
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        List<String> expected = Stream.of(
                        ReactiveResponse.Event.Type.BEGIN,
                        ReactiveResponse.Event.Type.HEADERS,
                        ReactiveResponse.Event.Type.CONTENT,
                        ReactiveResponse.Event.Type.SUCCESS,
                        ReactiveResponse.Event.Type.COMPLETE)
                .map(Enum::name)
                .collect(Collectors.toList());
        assertEquals(names, expected);
    }

    @ParameterizedTest
    @MethodSource("protocols")
    public void testRequestBody(String protocol) throws Exception {
        prepare(protocol, new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                HttpField contentType = request.getHeaders().getField(HttpHeader.CONTENT_TYPE);
                if (contentType != null) {
                    response.getHeaders().put(contentType);
                }
                Content.copy(request, response, callback);
                return true;
            }
        });

        String text = "Γειά σου Κόσμε";
        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient(), uri())
                .content(ReactiveRequest.Content.fromString(text, "text/plain", StandardCharsets.UTF_8))
                .build();

        String content = Single.fromPublisher(request.response(ReactiveResponse.Content.asString()))
                .blockingGet();

        assertEquals(content, text);
    }

    @ParameterizedTest
    @MethodSource("protocols")
    public void testFlowableRequestBody(String protocol) throws Exception {
        prepare(protocol, new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                HttpField contentType = request.getHeaders().getField(HttpHeader.CONTENT_TYPE);
                if (contentType != null) {
                    response.getHeaders().put(contentType);
                }
                Content.copy(request, response, callback);
                return true;
            }
        });

        String data = "01234";
        // Data from a generic stream (the regexp will split the data into single characters).
        Flowable<String> stream = Flowable.fromArray(data.split("(?!^)"));

        // Transform it to chunks, showing what you can use the callback for.
        Charset charset = StandardCharsets.UTF_8;
        ByteBufferPool bufferPool = httpClient().getByteBufferPool();
        Flowable<Content.Chunk> chunks = stream.map(item -> item.getBytes(charset))
                .map(bytes -> {
                    RetainableByteBuffer buffer = bufferPool.acquire(bytes.length, true);
                    BufferUtil.append(buffer.getByteBuffer(), bytes, 0, bytes.length);
                    return buffer;
                })
                .map(buffer -> Content.Chunk.from(buffer.getByteBuffer(), false, buffer::release));

        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient(), uri())
                .content(ReactiveRequest.Content.fromPublisher(chunks, "text/plain", charset))
                .build();

        String content = Single.fromPublisher(request.response(ReactiveResponse.Content.asString()))
                .blockingGet();

        assertEquals(content, data);
    }

    @ParameterizedTest
    @MethodSource("protocols")
    public void testResponseBody(String protocol) throws Exception {
        Charset charset = StandardCharsets.UTF_16;
        String data = "\u20ac";
        prepare(protocol, new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=" + charset.name());
                response.write(true, ByteBuffer.wrap(data.getBytes(charset)), callback);
                return true;
            }
        });

        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient(), uri()).build();
        String text = Single.fromPublisher(request.response(ReactiveResponse.Content.asString()))
                .blockingGet();

        assertEquals(text, data);
    }

    @ParameterizedTest
    @MethodSource("protocols")
    public void testFlowableResponseBody(String protocol) throws Exception {
        byte[] data = new byte[1024];
        new Random().nextBytes(data);
        prepare(protocol, new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                response.write(true, ByteBuffer.wrap(data), callback);
                return true;
            }
        });

        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient(), uri()).build();
        byte[] bytes = Flowable.fromPublisher(request.response((response, content) -> content))
                .flatMap(chunk -> Flowable.generate((Emitter<Byte> emitter) -> {
                    ByteBuffer buffer = chunk.getByteBuffer();
                    if (buffer.hasRemaining()) {
                        emitter.onNext(buffer.get());
                    } else {
                        chunk.release();
                        emitter.onComplete();
                    }
                }))
                .reduce(new ByteArrayOutputStream(), (acc, b) -> {
                    acc.write(b);
                    return acc;
                })
                .map(ByteArrayOutputStream::toByteArray)
                .blockingGet();

        assertArrayEquals(bytes, data);
    }

    @ParameterizedTest
    @MethodSource("protocols")
    public void testFlowableResponseThenBody(String protocol) throws Exception {
        String pangram = "quizzical twins proved my hijack bug fix";
        prepare(protocol, new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                Content.Sink.write(response, true, pangram, callback);
                return true;
            }
        });

        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient(), uri()).build();
        Pair<ReactiveResponse, Publisher<Content.Chunk>> pair = Single.fromPublisher(request.response((response, content) ->
                Flowable.just(new Pair<>(response, content)))).blockingGet();
        ReactiveResponse response = pair.one;

        assertEquals(response.getStatus(), HttpStatus.OK_200);

        BufferingProcessor processor = new BufferingProcessor(response);
        pair.two.subscribe(processor);
        String responseContent = Single.fromPublisher(processor).blockingGet();

        assertEquals(responseContent, pangram);
    }

    @ParameterizedTest
    @MethodSource("protocols")
    public void testFlowableResponsePipedToRequest(String protocol) throws Exception {
        String data1 = "data1";
        String data2 = "data2";
        prepare(protocol, new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                String target = Request.getPathInContext(request);
                if ("/1".equals(target)) {
                    Content.Sink.write(response, true, data1, callback);
                } else if ("/2".equals(target)) {
                    if (Content.Source.asString(request).equals(data1)) {
                        Content.Sink.write(response, true, data2, callback);
                    }
                }
                return true;
            }
        });

        ReactiveRequest request1 = ReactiveRequest.newBuilder(httpClient().newRequest(uri()).path("/1")).build();
        Publisher<String> sender1 = request1.response((response1, content1) -> {
            ReactiveRequest request2 = ReactiveRequest.newBuilder(httpClient().newRequest(uri()).path("/2"))
                    .content(ReactiveRequest.Content.fromPublisher(content1, "text/plain")).build();
            return request2.response(ReactiveResponse.Content.asString());
        });
        String result = Single.fromPublisher(sender1).blockingGet();

        assertEquals(result, data2);
    }

    @ParameterizedTest
    @MethodSource("protocols")
    public void testFlowableTimeout(String protocol) throws Exception {
        long timeout = 500;
        prepare(protocol, new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception {
                Thread.sleep(timeout * 2);
                callback.succeeded();
                return true;
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient().newRequest(uri())).build();
        Single.fromPublisher(request.response(ReactiveResponse.Content.discard()))
                .map(ReactiveResponse::getStatus)
                .timeout(timeout, TimeUnit.MILLISECONDS)
                .subscribe((status, failure) -> {
                    if (failure != null) {
                        latch.countDown();
                    }
                });

        assertTrue(latch.await(timeout * 2, TimeUnit.MILLISECONDS));
    }

    @ParameterizedTest
    @MethodSource("protocols")
    public void testDelayedContentSubscriptionWithoutResponseContent(String protocol) throws Exception {
        prepare(protocol, new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                callback.succeeded();
                return true;
            }
        });

        long delay = 1000;
        CountDownLatch latch = new CountDownLatch(1);
        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient().newRequest(uri())).build();
        Single.fromPublisher(request.response((response, content) ->
                        // Subscribe to the content after a delay,
                        // discard the content and emit the response.
                        Flowable.fromPublisher(content)
                                .delaySubscription(delay, TimeUnit.MILLISECONDS)
                                .doOnNext(Content.Chunk::release)
                                .filter(chunk -> false)
                                .isEmpty()
                                .map(empty -> response)
                                .toFlowable()))
                .subscribe(response -> latch.countDown());

        assertTrue(latch.await(delay * 2, TimeUnit.MILLISECONDS));
    }

    @ParameterizedTest
    @MethodSource("protocols")
    public void testConnectTimeout(String protocol) throws Exception {
        prepare(protocol, new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                callback.succeeded();
                return true;
            }
        });
        String uri = uri();
        server.stop();

        long connectTimeout = 500;
        httpClient().setConnectTimeout(connectTimeout);
        CountDownLatch latch = new CountDownLatch(1);
        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient().newRequest(uri)).build();
        Single.fromPublisher(request.response(ReactiveResponse.Content.discard()))
                .map(ReactiveResponse::getStatus)
                .subscribe((status, failure) -> {
                    if (failure != null) {
                        latch.countDown();
                    }
                });

        assertTrue(latch.await(connectTimeout * 2, TimeUnit.MILLISECONDS));
    }

    @ParameterizedTest
    @MethodSource("protocols")
    public void testResponseContentTimeout(String protocol) throws Exception {
        long timeout = 500;
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        prepare(protocol, new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception {
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH, data.length);
                response.write(false, null, Callback.NOOP);
                Thread.sleep(timeout * 2);
                response.write(true, ByteBuffer.wrap(data), callback);
                return true;
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient().newRequest(uri()).timeout(timeout, TimeUnit.MILLISECONDS)).build();
        Single.fromPublisher(request.response((response, content) -> {
            int status = response.getStatus();
            if (status != HttpStatus.OK_200) {
                ReactiveResponse.Content.discard().apply(response, content);
                return Flowable.error(new IOException(String.valueOf(status)));
            } else {
                return ReactiveResponse.Content.asString().apply(response, content);
            }
        })).subscribe((status, failure) -> {
            if (failure != null) {
                latch.countDown();
            }
        });

        assertTrue(latch.await(timeout * 2, TimeUnit.MILLISECONDS));
    }

    @ParameterizedTest
    @MethodSource("protocols")
    public void testBufferedResponseContent(String protocol) throws Exception {
        Random random = new Random();
        byte[] content1 = new byte[1024];
        random.nextBytes(content1);
        byte[] content2 = new byte[2048];
        random.nextBytes(content2);
        byte[] original = new byte[content1.length + content2.length];
        System.arraycopy(content1, 0, original, 0, content1.length);
        System.arraycopy(content2, 0, original, content1.length, content2.length);

        prepare(protocol, new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception {
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH, original.length);
                try (Blocker.Callback c = Blocker.callback()) {
                    response.write(false, ByteBuffer.wrap(content1), c);
                    c.block();
                }
                Thread.sleep(500);
                response.write(true, ByteBuffer.wrap(content2), callback);
                return true;
            }
        });

        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient().newRequest(uri())).build();

        // RxJava2 API always call request(Long.MAX_VALUE),
        // but I want to control backpressure explicitly,
        // so below the RxJava2 APIs are not used (much).

        Publisher<BufferedResponse> bufRespPub = request.response((response, content) -> {
            BufferedResponse result = new BufferedResponse(response);
            if (response.getStatus() == HttpStatus.OK_200) {
                Processor<Content.Chunk, BufferedResponse> processor = new AbstractSingleProcessor<>() {
                    @Override
                    public void onNext(Content.Chunk chunk) {
                        // Accumulate the chunks, without consuming
                        // the buffers nor completing the callbacks.
                        result.chunks.add(chunk);
                        upStreamRequest(1);
                    }

                    @Override
                    public void onComplete() {
                        downStreamOnNext(result);
                        super.onComplete();
                    }
                };
                content.subscribe(processor);
                return processor;
            } else {
                ReactiveResponse.Content.discard().apply(response, content);
                return Flowable.just(result);
            }
        });

        AtomicReference<BufferedResponse> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        bufRespPub.subscribe(new Subscriber<>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(1);
            }

            @Override
            public void onNext(BufferedResponse bufferedResponse) {
                ref.set(bufferedResponse);
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        BufferedResponse bufferedResponse = ref.get();
        assertEquals(bufferedResponse.response.getStatus(), HttpStatus.OK_200);
        ByteBuffer content = ByteBuffer.allocate(content1.length + content2.length);
        bufferedResponse.chunks.forEach(chunk -> {
            content.put(chunk.getByteBuffer());
            chunk.release();
        });
        assertEquals(content.flip(), ByteBuffer.wrap(original));
    }

    private record Pair<X, Y>(X one, Y two) {
    }

    private record BufferedResponse(ReactiveResponse response, List<Content.Chunk> chunks) {
        private BufferedResponse(ReactiveResponse response) {
            this(response, new ArrayList<>());
        }
    }
}
