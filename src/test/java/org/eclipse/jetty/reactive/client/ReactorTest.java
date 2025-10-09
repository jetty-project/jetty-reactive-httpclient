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

import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.JettyClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReactorTest extends AbstractTest {
    @ParameterizedTest
    @MethodSource("protocols")
    public void testResponseWithContent(String protocol) throws Exception {
        byte[] data = new byte[1024];
        new Random().nextBytes(data);
        prepare(protocol, new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                response.write(true, ByteBuffer.wrap(data), callback);
                return true;
            }
        });

        WebClient client = WebClient.builder().clientConnector(new JettyClientHttpConnector(httpClient())).build();
        byte[] responseContent = client.get()
                .uri(uri())
                .retrieve()
                .bodyToMono(byte[].class)
                .block();
        assertNotNull(responseContent);
        assertArrayEquals(data, responseContent);
    }

    @ParameterizedTest
    @MethodSource("protocols")
    public void testRequestWithContentResponseWithContent(String protocol) throws Exception {
        byte[] data = new byte[1024];
        new Random().nextBytes(data);
        prepare(protocol, new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                Content.copy(request, response, callback);
                return true;
            }
        });

        ReactiveRequest.Content requestContent = ReactiveRequest.Content.fromBytes(data, "application/octet-stream");
        WebClient client = WebClient.builder().clientConnector(new JettyClientHttpConnector(httpClient())).build();
        byte[] responseContent = client.post()
                .uri(uri())
                .contentType(MediaType.parseMediaType(requestContent.getContentType()))
                .body(Flux.from(requestContent).map(Content.Chunk::getByteBuffer), ByteBuffer.class)
                .retrieve()
                .bodyToMono(byte[].class)
                .block();
        assertNotNull(responseContent);
        assertArrayEquals(data, responseContent);
    }

    @ParameterizedTest
    @MethodSource("protocols")
    public void testTotalTimeout(String protocol) throws Exception {
        long timeout = 1000;
        String result = "HELLO";
        prepare(protocol, new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception {
                try {
                    Thread.sleep(2 * timeout);
                    Content.Sink.write(response, true, result, callback);
                    return true;
                } catch (InterruptedException x) {
                    throw new InterruptedIOException();
                }
            }
        });

        // Suppresses weird exception thrown by Reactor.
        Hooks.onErrorDropped(t -> {});

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

        assertEquals(timeoutResult, responseContent);
    }

    @ParameterizedTest
    @MethodSource("protocols")
    public void test401(String protocol) throws Exception {
        prepare(protocol, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                response.setStatus(HttpStatus.UNAUTHORIZED_401);
                response.getHeaders().add(HttpHeader.WWW_AUTHENTICATE, "Basic realm=\"test\"");
                callback.succeeded();
                return true;
            }
        });

        var request = httpClient().newRequest(uri());
        ReactiveRequest reactiveRequest = ReactiveRequest.newBuilder(request).abortOnCancel(true).build();
        Mono<ReactiveResponse> responseMono = Mono.fromDirect(reactiveRequest.response());
        ReactiveResponse reactiveResponse = responseMono.block(Duration.ofSeconds(5));

        assertNotNull(reactiveResponse);
        assertEquals(HttpStatus.UNAUTHORIZED_401, reactiveResponse.getStatus());
    }

    @ParameterizedTest
    @MethodSource("protocols")
    public void testEcho(String protocol) throws Exception {
        prepare(protocol, new Handler.Abstract() {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, Response response, Callback callback) {
                Content.copy(request, response, callback);
                return true;
            }
        });

        for (int i = 0; i < 5; ++i) {
            String content = "hello world";
            WebClient client = WebClient.builder().clientConnector(new JettyClientHttpConnector(httpClient())).build();
            Mono<String> publisher = client.post()
                    .uri(new URI(uri()))
                    .bodyValue(content)
                    .exchangeToMono(response -> {
                        HttpStatusCode status = response.statusCode();
                        if (status.value() != HttpStatus.OK_200)
                            return Mono.just("status " + status);
                        return response.bodyToMono(String.class);
                    });

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> resultRef = new AtomicReference<>();
            publisher.subscribe(new Subscriber<>() {
                @Override
                public void onSubscribe(Subscription subscription) {
                    subscription.request(1);
                }

                @Override
                public void onNext(String result) {
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
            assertEquals(content, resultRef.get());
        }
    }
}
