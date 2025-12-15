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

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;

public class AbstractTest {
    public static void printTestName(TestInfo testInfo) {
        System.err.printf("Running %s%n", testInfo.getTestMethod()
                .map(m -> "%s.%s() %s".formatted(m.getDeclaringClass().getSimpleName(), m.getName(), testInfo.getDisplayName()))
                .orElseThrow());
    }

    public static List<String> protocols() {
        return List.of("http", "h2c");
    }

    private final HttpConfiguration httpConfiguration = new HttpConfiguration();
    private HttpClient httpClient;
    protected Server server;
    private ServerConnector connector;
    private ArrayByteBufferPool.Tracking serverBufferPool;
    private ArrayByteBufferPool.Tracking clientBufferPool;

    @BeforeEach
    public void before(TestInfo testInfo) {
        printTestName(testInfo);
    }

    public void prepare(String protocol, Handler handler) throws Exception {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        serverBufferPool = new ArrayByteBufferPool.Tracking();
        server = new Server(serverThreads, null, serverBufferPool);
        connector = new ServerConnector(server, 1, 1, createServerConnectionFactory(protocol));
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();

        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        clientBufferPool = new ArrayByteBufferPool.Tracking();
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setExecutor(clientThreads);
        clientConnector.setSelectors(1);
        httpClient = new HttpClient(createClientTransport(clientConnector, protocol));
        httpClient.setExecutor(clientThreads);
        httpClient.setByteBufferPool(clientBufferPool);
        httpClient.start();
    }

    private ConnectionFactory createServerConnectionFactory(String protocol) {
        return switch (protocol) {
            case "h2c" -> new HTTP2CServerConnectionFactory(httpConfiguration);
            default -> new HttpConnectionFactory(httpConfiguration);
        };
    }

    private HttpClientTransport createClientTransport(ClientConnector clientConnector, String protocol) {
        return switch (protocol) {
            case "h2c" -> new HttpClientTransportOverHTTP2(new HTTP2Client(clientConnector));
            default -> new HttpClientTransportOverHTTP(clientConnector);
        };
    }

    @AfterEach
    public void dispose() {
        try
        {
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> MatcherAssert.assertThat("Client leaks: " + clientBufferPool.dumpLeaks(), clientBufferPool.getLeaks().size(), is(0)));
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> MatcherAssert.assertThat("Server leaks: " + serverBufferPool.dumpLeaks(), serverBufferPool.getLeaks().size(), is(0)));
        }
        finally
        {
            LifeCycle.stop(httpClient);
            LifeCycle.stop(server);
        }
    }

    protected HttpClient httpClient() {
        return httpClient;
    }

    protected String uri() {
        return "http://localhost:" + connector.getLocalPort();
    }
}
