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

import java.lang.reflect.Method;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;

public class AbstractTest {
    @DataProvider(name = "protocols")
    public static Object[][] protocols() {
        return new Object[][]{
                new Object[]{"http"},
                new Object[]{"h2c"}
        };
    }

    private final HttpConfiguration httpConfiguration = new HttpConfiguration();
    private final String protocol;
    private HttpClient httpClient;
    protected Server server;
    private ServerConnector connector;

    public AbstractTest(String protocol) {
        this.protocol = protocol;
    }

    @BeforeMethod
    public void printTestName(Method method) {
        System.err.printf("Running %s.%s() [%s]%n", getClass().getName(), method.getName(), protocol);
    }

    public void prepare(Handler handler) throws Exception {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        connector = new ServerConnector(server, createServerConnectionFactory(protocol));
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();

        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setExecutor(clientThreads);
        clientConnector.setSelectors(1);
        httpClient = new HttpClient(createClientTransport(clientConnector, protocol));
        httpClient.setExecutor(clientThreads);
        httpClient.start();
    }

    private ConnectionFactory createServerConnectionFactory(String protocol) {
        switch (protocol) {
            case "h2c":
                return new HTTP2CServerConnectionFactory(httpConfiguration);
            default:
                return new HttpConnectionFactory(httpConfiguration);
        }
    }

    private HttpClientTransport createClientTransport(ClientConnector clientConnector, String protocol) {
        switch (protocol) {
            case "h2c":
                return new HttpClientTransportOverHTTP2(new HTTP2Client(clientConnector));
            default:
                return new HttpClientTransportOverHTTP(clientConnector);
        }
    }

    @AfterMethod
    public void dispose() throws Exception {
        if (httpClient != null) {
            httpClient.stop();
        }
        if (server != null) {
            server.stop();
        }
    }

    protected HttpClient httpClient() {
        return httpClient;
    }

    protected String uri() {
        return "http://localhost:" + connector.getLocalPort();
    }
}
