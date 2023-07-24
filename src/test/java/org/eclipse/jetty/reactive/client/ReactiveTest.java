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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.reactive.client.internal.QueuedSinglePublisher;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReactiveTest extends AbstractTest {
    @ParameterizedTest
    @MethodSource("protocols")
    public void testSimpleReactiveUsage(String protocol) throws Exception {
        prepare(protocol, new Handler.Abstract() {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, Response response, Callback callback) {
                Content.Sink.write(response, true, "hello world", callback);
                return true;
            }
        });

        Publisher<ReactiveResponse> publisher = ReactiveRequest.newBuilder(httpClient(), uri()).build().response();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ReactiveResponse> responseRef = new AtomicReference<>();
        publisher.subscribe(new Subscriber<>() {
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

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        ReactiveResponse response = responseRef.get();
        assertNotNull(response);
        assertEquals(response.getStatus(), HttpStatus.OK_200);
    }

    @ParameterizedTest
    @MethodSource("protocols")
    public void testDelayedDemand(String protocol) throws Exception {
        prepare(protocol, new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                Content.Sink.write(response, true, "0123456789".repeat(16 * 1024), callback);
                return true;
            }
        });

        AtomicReference<ReactiveResponse> responseRef = new AtomicReference<>();
        AtomicReference<Publisher<Content.Chunk>> chunkPublisherRef = new AtomicReference<>();
        CountDownLatch responseLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(1);
        QueuedSinglePublisher<ReactiveResponse> publisher = new QueuedSinglePublisher<>();
        ReactiveRequest request = ReactiveRequest.newBuilder(httpClient().newRequest(uri())).build();
        request.response((reactiveResponse, chunkPublisher) -> {
            responseRef.set(reactiveResponse);
            chunkPublisherRef.set(chunkPublisher);
            responseLatch.countDown();
            return publisher;
        }).subscribe(new Subscriber<>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(1);
            }

            @Override
            public void onNext(ReactiveResponse response) {
            }

            @Override
            public void onError(Throwable failure) {
            }

            @Override
            public void onComplete() {
                completeLatch.countDown();
            }
        });

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));

        // Delay the demand of content.
        Thread.sleep(100);
        chunkPublisherRef.get().subscribe(new Subscriber<>() {
            private Subscription subscription;

            @Override
            public void onSubscribe(Subscription subscription) {
                this.subscription = subscription;
                httpClient().getScheduler().schedule(() -> subscription.request(1), 100, TimeUnit.MILLISECONDS);
            }

            @Override
            public void onNext(Content.Chunk chunk) {
                chunk.release();
                httpClient().getScheduler().schedule(() -> subscription.request(1), 100, TimeUnit.MILLISECONDS);
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
                publisher.offer(responseRef.get());
                publisher.complete();
            }
        });

        assertTrue(completeLatch.await(15, TimeUnit.SECONDS));
    }
}
