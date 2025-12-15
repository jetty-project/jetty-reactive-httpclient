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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.server.RawHTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HTTP2Test {
    private Server server;
    private ServerConnector connector;
    private HttpClient httpClient;
    private ArrayByteBufferPool.Tracking serverBufferPool;
    private ArrayByteBufferPool.Tracking clientBufferPool;

    private void start(ServerSessionListener listener) throws Exception {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        serverBufferPool = new ArrayByteBufferPool.Tracking();
        server = new Server(serverThreads, null, serverBufferPool);
        RawHTTP2ServerConnectionFactory h2c = new RawHTTP2ServerConnectionFactory(listener);
        connector = new ServerConnector(server, 1, 1, h2c);
        server.addConnector(connector);
        server.start();

        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        clientBufferPool = new ArrayByteBufferPool.Tracking();
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setExecutor(clientThreads);
        clientConnector.setSelectors(1);
        httpClient = new HttpClient(new HttpClientTransportOverHTTP2(new HTTP2Client(clientConnector)));
        httpClient.setByteBufferPool(clientBufferPool);
        httpClient.start();
    }

    @AfterEach
    public void dispose() {
        try
        {
            MatcherAssert.assertThat("Client leaks: " + clientBufferPool.dumpLeaks(), clientBufferPool.getLeaks().size(), is(0));
            MatcherAssert.assertThat("Server leaks: " + serverBufferPool.dumpLeaks(), serverBufferPool.getLeaks().size(), is(0));
        }
        finally
        {
            LifeCycle.stop(httpClient);
            LifeCycle.stop(server);
        }
    }

    @Test
    public void testOptimizeLastEmptyDataFrame() throws Exception {
        List<Stream.Data> datas = new ArrayList<>();
        start(new ServerSessionListener() {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame) {
                MetaData.Response response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
                stream.headers(new HeadersFrame(stream.getId(), response, null, false));
                stream.demand();
                return new Stream.Listener() {
                    @Override
                    public void onDataAvailable(Stream stream) {
                        Stream.Data data = stream.readData();
                        if (data == null) {
                            stream.demand();
                            return;
                        }
                        datas.add(data);
                        stream.data(data.frame())
                                .thenRun(() ->
                                {
                                    data.release();
                                    if (!data.frame().isEndStream()) {
                                        stream.demand();
                                    }
                                });
                    }
                };
            }
        });

        String content = "hello world";
        String uri = "http://localhost:" + connector.getLocalPort();
        Publisher<ReactiveResponse.Result<String>> publisher = ReactiveRequest.newBuilder(httpClient, uri)
                .content(ReactiveRequest.Content.fromString(content, "text/plain", StandardCharsets.UTF_8))
                .build()
                .response(ReactiveResponse.Content.asStringResult());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ReactiveResponse.Result<String>> resultRef = new AtomicReference<>();
        publisher.subscribe(new Subscriber<>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(1);
            }

            @Override
            public void onNext(ReactiveResponse.Result<String> result) {
                resultRef.set(result);
            }

            @Override
            public void onError(Throwable failure) {
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        ReactiveResponse.Result<String> result = resultRef.get();
        assertNotNull(result);
        ReactiveResponse response = result.response();
        assertNotNull(response);
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(content, result.content());
        assertEquals(1, datas.size());
    }
}
