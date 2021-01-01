/*
 * Copyright (c) 2017-2021 the original author or authors.
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
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
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
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.reactivex.rxjava3.core.Emitter;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.reactive.client.internal.AbstractSingleProcessor;
import org.eclipse.jetty.reactive.client.internal.BufferingProcessor;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.testng.Assert;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class RxJava2Test extends AbstractTest {
    @Factory(dataProvider = "protocols", dataProviderClass = AbstractTest.class)
    public RxJava2Test(String protocol) {
        super(protocol);
    }

    @Test
    public void testSimpleUsage() throws Exception {
        prepare(new EmptyHandler());

        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient().newRequest(uri())).build();
        int status = Single.fromPublisher(request.response(ReactiveResponse.Content.discard()))
                .map(ReactiveResponse::getStatus)
                .blockingGet();

        Assert.assertEquals(status, HttpStatus.OK_200);
    }

    @Test
    public void testRequestEvents() throws Exception {
        prepare(new EmptyHandler());

        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient(), uri()).build();
        Publisher<ReactiveRequest.Event> events = request.requestEvents();

        CountDownLatch latch = new CountDownLatch(1);
        List<String> names = new ArrayList<>();
        Flowable.fromPublisher(events)
                .map(ReactiveRequest.Event::getType)
                .map(ReactiveRequest.Event.Type::name)
                .subscribe(new Subscriber<String>() {
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

        Assert.assertEquals(response.getStatus(), HttpStatus.OK_200);
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        List<String> expected = Stream.of(
                ReactiveRequest.Event.Type.QUEUED,
                ReactiveRequest.Event.Type.BEGIN,
                ReactiveRequest.Event.Type.HEADERS,
                ReactiveRequest.Event.Type.COMMIT,
                ReactiveRequest.Event.Type.SUCCESS)
                .map(Enum::name)
                .collect(Collectors.toList());
        Assert.assertEquals(names, expected);
    }

    @Test
    public void testResponseEvents() throws Exception {
        prepare(new EmptyHandler() {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
                response.getOutputStream().write(0);
            }
        });

        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient(), uri()).build();
        Publisher<ReactiveResponse.Event> events = request.responseEvents();

        CountDownLatch latch = new CountDownLatch(1);
        List<String> names = new ArrayList<>();
        Flowable.fromPublisher(events)
                .map(ReactiveResponse.Event::getType)
                .map(ReactiveResponse.Event.Type::name)
                .subscribe(new Subscriber<String>() {
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

        Assert.assertEquals(response.getStatus(), HttpStatus.OK_200);
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        List<String> expected = Stream.of(
                ReactiveResponse.Event.Type.BEGIN,
                ReactiveResponse.Event.Type.HEADERS,
                ReactiveResponse.Event.Type.CONTENT,
                ReactiveResponse.Event.Type.SUCCESS,
                ReactiveResponse.Event.Type.COMPLETE)
                .map(Enum::name)
                .collect(Collectors.toList());
        Assert.assertEquals(names, expected);
    }

    @Test
    public void testRequestBody() throws Exception {
        prepare(new EmptyHandler() {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
                response.setContentType(request.getContentType());
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        String text = "Γειά σου Κόσμε";
        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient(), uri())
                .content(ReactiveRequest.Content.fromString(text, "text/plain", StandardCharsets.UTF_8))
                .build();

        String content = Single.fromPublisher(request.response(ReactiveResponse.Content.asString()))
                .blockingGet();

        Assert.assertEquals(content, text);
    }

    @Test
    public void testFlowableRequestBody() throws Exception {
        prepare(new EmptyHandler() {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
                response.setContentType(request.getContentType());
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        String data = "01234";
        // Data from a generic stream (the regexp will split the data into single characters).
        Flowable<String> stream = Flowable.fromArray(data.split("(?!^)"));

        // Transform it to chunks, showing what you can use the callback for.
        Charset charset = StandardCharsets.UTF_8;
        ByteBufferPool bufferPool = httpClient().getByteBufferPool();
        Flowable<ContentChunk> chunks = stream.map(item -> item.getBytes(charset))
                .map(bytes -> {
                    ByteBuffer buffer = bufferPool.acquire(bytes.length, true);
                    BufferUtil.append(buffer, bytes, 0, bytes.length);
                    return buffer;
                })
                .map(buffer -> new ContentChunk(buffer, new Callback() {
                    @Override
                    public void succeeded() {
                        complete();
                    }

                    @Override
                    public void failed(Throwable x) {
                        complete();
                    }

                    @Override
                    public InvocationType getInvocationType() {
                        return InvocationType.NON_BLOCKING;
                    }

                    private void complete() {
                        bufferPool.release(buffer);
                    }
                }));

        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient(), uri())
                .content(ReactiveRequest.Content.fromPublisher(chunks, "text/plain", charset))
                .build();

        String content = Single.fromPublisher(request.response(ReactiveResponse.Content.asString()))
                .blockingGet();

        Assert.assertEquals(content, data);
    }

    @Test
    public void testResponseBody() throws Exception {
        Charset charset = StandardCharsets.UTF_16;
        String data = "\u20ac";
        prepare(new EmptyHandler() {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
                response.setContentType("text/plain;charset=" + charset.name());
                response.getOutputStream().print(data);
            }
        });

        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient(), uri()).build();
        String text = Single.fromPublisher(request.response(ReactiveResponse.Content.asString()))
                .blockingGet();

        Assert.assertEquals(text, data);
    }

    @Test
    public void testFlowableResponseBody() throws Exception {
        byte[] data = new byte[1024];
        new Random().nextBytes(data);
        prepare(new EmptyHandler() {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
                response.getOutputStream().write(data);
            }
        });

        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient(), uri()).build();
        byte[] bytes = Flowable.fromPublisher(request.response((response, content) -> content))
                .flatMap(chunk -> Flowable.generate((Emitter<Byte> emitter) -> {
                    ByteBuffer buffer = chunk.buffer;
                    if (buffer.hasRemaining()) {
                        emitter.onNext(buffer.get());
                    } else {
                        chunk.callback.succeeded();
                        emitter.onComplete();
                    }
                }))
                .reduce(new ByteArrayOutputStream(), (acc, b) -> {
                    acc.write(b);
                    return acc;
                })
                .map(ByteArrayOutputStream::toByteArray)
                .blockingGet();

        Assert.assertEquals(bytes, data);
    }

    @Test
    public void testFlowableResponseThenBody() throws Exception {
        String pangram = "quizzical twins proved my hijack bug fix";
        prepare(new EmptyHandler() {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
                response.setContentType("text/plain");
                response.getOutputStream().print(pangram);
            }
        });

        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient(), uri()).build();
        Pair<ReactiveResponse, Publisher<ContentChunk>> pair = Single.fromPublisher(request.response((response, content) ->
                Flowable.just(new Pair<>(response, content)))).blockingGet();
        ReactiveResponse response = pair._1;

        Assert.assertEquals(response.getStatus(), HttpStatus.OK_200);

        BufferingProcessor processor = new BufferingProcessor(response);
        pair._2.subscribe(processor);
        String responseContent = Single.fromPublisher(processor).blockingGet();

        Assert.assertEquals(responseContent, pangram);
    }

    @Test
    public void testFlowableResponsePipedToRequest() throws Exception {
        String data1 = "data1";
        String data2 = "data2";
        prepare(new EmptyHandler() {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
                response.setContentType("text/plain");
                PrintWriter writer = response.getWriter();
                if ("/1".equals(target)) {
                    writer.write(data1);
                } else if ("/2".equals(target)) {
                    if (IO.toString(request.getInputStream()).equals(data1)) {
                        writer.write(data2);
                    }
                }
            }
        });

        ReactiveRequest request1 = ReactiveRequest.newBuilder(httpClient().newRequest(uri()).path("/1")).build();
        Publisher<String> sender1 = request1.response((response1, content1) -> {
            ReactiveRequest request2 = ReactiveRequest.newBuilder(httpClient().newRequest(uri()).path("/2"))
                    .content(ReactiveRequest.Content.fromPublisher(content1, "text/plain")).build();
            return request2.response(ReactiveResponse.Content.asString());
        });
        String result = Single.fromPublisher(sender1).blockingGet();

        Assert.assertEquals(result, data2);
    }

    @Test
    public void testFlowableTimeout() throws Exception {
        long timeout = 500;
        prepare(new EmptyHandler() {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
                try {
                    Thread.sleep(timeout * 2);
                } catch (InterruptedException e) {
                    throw new InterruptedIOException();
                }
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

        Assert.assertTrue(latch.await(timeout * 2, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testDelayedContentSubscriptionWithoutResponseContent() throws Exception {
        prepare(new EmptyHandler());

        long delay = 1000;
        CountDownLatch latch = new CountDownLatch(1);
        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient().newRequest(uri())).build();
        Single.fromPublisher(request.response((response, content) ->
                // Subscribe to the content after a delay,
                // discard the content and emit the response.
                Flowable.fromPublisher(content)
                        .delaySubscription(delay, TimeUnit.MILLISECONDS)
                        .doOnNext(chunk -> chunk.callback.succeeded())
                        .filter(chunk -> false)
                        .isEmpty()
                        .map(empty -> response)
                        .toFlowable()))
                .subscribe(response -> latch.countDown());

        Assert.assertTrue(latch.await(delay * 2, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testConnectTimeout() throws Exception {
        prepare(new EmptyHandler());
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

        Assert.assertTrue(latch.await(connectTimeout * 2, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testResponseContentTimeout() throws Exception {
        long timeout = 500;
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        prepare(new EmptyHandler() {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
                try {
                    response.setContentLength(data.length);
                    response.flushBuffer();
                    Thread.sleep(timeout * 2);
                    response.getOutputStream().write(data);
                } catch (InterruptedException e) {
                    throw new InterruptedIOException();
                }
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient().newRequest(uri()).timeout(timeout, TimeUnit.MILLISECONDS)).build();
        Single.fromPublisher(request.response((response, content) -> {
            int status = response.getStatus();
            if (status != HttpStatus.OK_200) {
                return Flowable.error(new IOException(String.valueOf(status)));
            } else {
                return ReactiveResponse.Content.asString().apply(response, content);
            }
        })).subscribe((status, failure) -> {
            if (failure != null) {
                latch.countDown();
            }
        });

        Assert.assertTrue(latch.await(timeout * 2, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testBufferedResponseContent() throws Exception {
        Random random = new Random();
        byte[] content1 = new byte[1024];
        random.nextBytes(content1);
        byte[] content2 = new byte[2048];
        random.nextBytes(content2);
        byte[] original = new byte[content1.length + content2.length];
        System.arraycopy(content1, 0, original, 0, content1.length);
        System.arraycopy(content2, 0, original, content1.length, content2.length);

        prepare(new EmptyHandler() {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
                try {
                    response.setContentLength(original.length);
                    ServletOutputStream output = response.getOutputStream();
                    output.write(content1);
                    output.flush();
                    Thread.sleep(500);
                    output.write(content2);
                } catch (InterruptedException e) {
                    throw new InterruptedIOException();
                }
            }
        });

        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient().newRequest(uri())).build();

        // RxJava2 API always call request(Long.MAX_VALUE),
        // but I want to control backpressure explicitly,
        // so below the RxJava2 APIs are not used (much).

        Publisher<BufferedResponse> bufRespPub = request.response((response, content) -> {
            BufferedResponse result = new BufferedResponse(response);
            if (response.getStatus() == HttpStatus.OK_200) {
                Processor<ContentChunk, BufferedResponse> processor = new AbstractSingleProcessor<ContentChunk, BufferedResponse>() {
                    @Override
                    public void onNext(ContentChunk chunk) {
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
                return Flowable.just(result);
            }
        });

        AtomicReference<BufferedResponse> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        bufRespPub.subscribe(new Subscriber<BufferedResponse>() {
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

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        BufferedResponse bufferedResponse = ref.get();
        Assert.assertEquals(bufferedResponse.response.getStatus(), HttpStatus.OK_200);
        ByteBuffer content = ByteBuffer.allocate(content1.length + content2.length);
        bufferedResponse.chunks.forEach(chunk -> {
            content.put(chunk.buffer);
            chunk.callback.succeeded();
        });
        Assert.assertEquals(content.flip(), ByteBuffer.wrap(original));
    }

    public static class Pair<X, Y> {
        public final X _1;
        public final Y _2;

        public Pair(X x, Y y) {
            _1 = x;
            _2 = y;
        }
    }

    public static class BufferedResponse {
        private final List<ContentChunk> chunks = new ArrayList<>();
        private final ReactiveResponse response;

        public BufferedResponse(ReactiveResponse response) {
            this.response = response;
        }
    }

}
