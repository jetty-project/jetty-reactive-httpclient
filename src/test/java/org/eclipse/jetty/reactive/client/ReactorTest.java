/*
 * Copyright (c) 2017-2020 the original author or authors.
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

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.TimeoutException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.springframework.http.client.reactive.JettyClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.testng.Assert;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class ReactorTest extends AbstractTest {
    @Factory(dataProvider = "protocols", dataProviderClass = AbstractTest.class)
    public ReactorTest(String protocol) {
        super(protocol);
    }

    @Test
    public void testResponseWithContent() throws Exception {
        byte[] data = new byte[1024];
        new Random().nextBytes(data);
        prepare(new EmptyHandler() {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
                response.getOutputStream().write(data);
            }
        });

        WebClient client = WebClient.builder().clientConnector(new JettyClientHttpConnector(httpClient())).build();
        byte[] responseContent = client.get()
                .uri(uri())
                .exchange()
                .flatMap(r -> r.bodyToMono(byte[].class))
                .block();
        Assert.assertNotNull(responseContent);
        Assert.assertEquals(data, responseContent);
    }

    @Test
    public void testTotalTimeout() throws Exception {
        long timeout = 1000;
        String result = "HELLO";
        prepare(new EmptyHandler() {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
                try {
                    Thread.sleep(2 * timeout);
                    response.getWriter().write(result);
                } catch (InterruptedException x) {
                    throw new InterruptedIOException();
                }
            }
        });

        String timeoutResult = "TIMEOUT";
        String responseContent = WebClient.builder()
                .clientConnector(new JettyClientHttpConnector(httpClient()))
                .build()
                .get()
                .uri(new URI(uri()))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(timeout))
                .onErrorReturn(TimeoutException.class::isInstance, timeoutResult)
                .block();

        Assert.assertEquals(timeoutResult, responseContent);
    }
}
