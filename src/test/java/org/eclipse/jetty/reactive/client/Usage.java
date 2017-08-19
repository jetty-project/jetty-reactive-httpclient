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
package org.eclipse.jetty.reactive.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import io.reactivex.Emitter;
import io.reactivex.Flowable;
import io.reactivex.Single;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.reactive.client.util.TextContent;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.IO;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class Usage {
    private Server server;
    private ServerConnector connector;
    private HttpClient httpClient;

    public void prepare(Handler handler) throws Exception {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();

        httpClient = new HttpClient();
        httpClient.start();
    }

    @After
    public void dispose() throws Exception {
        if (httpClient != null) {
            httpClient.stop();
        }
        if (server != null) {
            server.stop();
        }
    }

    private String uri() {
        return "http://localhost:" + connector.getLocalPort();
    }

    @Test
    public void simpleReactiveUsage() throws Exception {
        prepare(new EmptyHandler());

        Publisher<ReactiveResponse> publisher = ReactiveRequest.newBuilder(httpClient, uri()).build().response();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ReactiveResponse> responseRef = new AtomicReference<>();
        publisher.subscribe(new Subscriber<ReactiveResponse>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(1);
            }

            @Override
            public void onNext(ReactiveResponse response) {
                responseRef.set(response);
            }

            @Override
            public void onError(Throwable failure) {
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        ReactiveResponse response = responseRef.get();
        Assert.assertNotNull(response);
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void simpleFlowableUsage() throws Exception {
        prepare(new EmptyHandler());

        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient.newRequest(uri())).build();
        int status = Single.fromPublisher(request.response(ReactiveResponse.Content.discard()))
                .map(ReactiveResponse::getStatus)
                .blockingGet();

        Assert.assertEquals(HttpStatus.OK_200, status);
    }

    @Test
    public void requestEvents() throws Exception {
        prepare(new EmptyHandler());

        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient, uri()).build();
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

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        List<String> expected = Stream.of(
                ReactiveRequest.Event.Type.QUEUED,
                ReactiveRequest.Event.Type.BEGIN,
                ReactiveRequest.Event.Type.HEADERS,
                ReactiveRequest.Event.Type.COMMIT,
                ReactiveRequest.Event.Type.SUCCESS)
                .map(Enum::name)
                .collect(Collectors.toList());
        Assert.assertEquals(expected, names);
    }

    @Test
    public void responseEvents() throws Exception {
        prepare(new EmptyHandler() {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
                response.getOutputStream().write(0);
            }
        });

        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient, uri()).build();
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

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        List<String> expected = Stream.of(
                ReactiveResponse.Event.Type.BEGIN,
                ReactiveResponse.Event.Type.HEADERS,
                ReactiveResponse.Event.Type.CONTENT,
                ReactiveResponse.Event.Type.SUCCESS,
                ReactiveResponse.Event.Type.COMPLETE)
                .map(Enum::name)
                .collect(Collectors.toList());
        Assert.assertEquals(expected, names);
    }

    @Test
    public void requestBody() throws Exception {
        prepare(new EmptyHandler() {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
                response.setContentType(request.getContentType());
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        String text = "Γειά σου Κόσμε";
        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient, uri())
                .content(new TextContent(text, "text/plain", StandardCharsets.UTF_8))
                .build();

        String content = Single.fromPublisher(request.response(ReactiveResponse.Content.asString()))
                .blockingGet();

        Assert.assertEquals(text, content);
    }

    @Test
    public void responseBody() throws Exception {
        Charset charset = StandardCharsets.UTF_16;
        String data = "\u20ac";
        prepare(new EmptyHandler() {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
                response.setContentType("text/plain;charset=" + charset.name());
                response.getOutputStream().print(data);
            }
        });

        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient, uri()).build();
        String text = Single.fromPublisher(request.response(ReactiveResponse.Content.asString()))
                .blockingGet();

        Assert.assertEquals(data, text);
    }

    @Test
    public void flowableResponseBody() throws Exception {
        byte[] data = new byte[1024];
        new Random().nextBytes(data);
        prepare(new EmptyHandler() {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
                response.getOutputStream().write(data);
            }
        });

        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient, uri()).build();
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

        Assert.assertArrayEquals(data, bytes);
    }
}
