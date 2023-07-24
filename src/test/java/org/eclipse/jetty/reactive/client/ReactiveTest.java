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
import org.eclipse.jetty.server.Handler;
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
}
