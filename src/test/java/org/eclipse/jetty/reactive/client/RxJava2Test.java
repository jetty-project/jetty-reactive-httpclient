/*
 * Copyright (c) 2017-2018 the original author or authors.
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
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import io.reactivex.Emitter;
import io.reactivex.Flowable;
import io.reactivex.Single;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.reactive.client.internal.BufferingProcessor;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.testng.Assert;
import org.testng.annotations.Test;

public class RxJava2Test extends AbstractTest {
    @Test
    public void simpleUsage() throws Exception {
        prepare(new EmptyHandler());

        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient().newRequest(uri())).build();
        int status = Single.fromPublisher(request.response(ReactiveResponse.Content.discard()))
                .map(ReactiveResponse::getStatus)
                .blockingGet();

        Assert.assertEquals(status, HttpStatus.OK_200);
    }

    @Test
    public void requestEvents() throws Exception {
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
    public void responseEvents() throws Exception {
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
    public void requestBody() throws Exception {
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
    public void flowableRequestBody() throws Exception {
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
    public void responseBody() throws Exception {
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
    public void flowableResponseBody() throws Exception {
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
    public void flowableResponseThenBody() throws Exception {
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
    public void flowableResponsePipedToRequest() throws Exception {
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

    public static class Pair<X, Y> {
        public final X _1;
        public final Y _2;

        public Pair(X x, Y y) {
            _1 = x;
            _2 = y;
        }
    }
}
