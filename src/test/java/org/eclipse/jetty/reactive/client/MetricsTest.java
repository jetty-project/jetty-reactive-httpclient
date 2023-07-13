/*
 * Copyright (c) 2017-2022 the original author or authors.
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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.Histogram;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.toolchain.perf.HistogramSnapshot;
import org.eclipse.jetty.util.Callback;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.testng.Assert;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class MetricsTest extends AbstractTest {
    @Factory(dataProvider = "protocols", dataProviderClass = AbstractTest.class)
    public MetricsTest(String protocol) {
        super(protocol);
    }

    @Test
    public void testMetrics() throws Exception {
        prepare(new Handler.Abstract() {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, Response response, Callback callback) throws Exception {
                callback.succeeded();
                return true;
            }
        });

        // Data structure to hold response status codes.
        Map<Integer, AtomicInteger> responses = new ConcurrentHashMap<>();

        // Data structure to hold response times.
        Histogram responseTimes = new ConcurrentHistogram(
                TimeUnit.MICROSECONDS.toNanos(1),
                TimeUnit.MINUTES.toNanos(1),
                3
        );

        int count = 100;
        CountDownLatch latch = new CountDownLatch(count);
        IntStream.range(0, count)
                .parallel()
                .forEach(i -> {
                    Request request = httpClient().newRequest(uri() + "/" + i);

                    // Collect information about response status codes.
                    request.onResponseSuccess(rsp ->
                    {
                        int key = rsp.getStatus() / 100;
                        responses.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
                    });

                    // Collect information about response times.
                    request.onRequestBegin(req -> req.attribute("nanoTime", System.nanoTime()))
                            .onComplete(result -> {
                                Long nanoTime = (Long)result.getRequest().getAttributes().get("nanoTime");
                                if (nanoTime != null) {
                                    long responseTime = System.nanoTime() - nanoTime;
                                    responseTimes.recordValue(responseTime);
                                }
                            });

                    ReactiveRequest.newBuilder(request)
                            .build()
                            .response()
                            .subscribe(new Subscriber<>() {
                                @Override
                                public void onSubscribe(Subscription subscription) {
                                    subscription.request(1);
                                }

                                @Override
                                public void onNext(ReactiveResponse reactiveResponse) {
                                }

                                @Override
                                public void onError(Throwable throwable) {
                                }

                                @Override
                                public void onComplete() {
                                    latch.countDown();
                                }
                            });
                });

        // Wait for all the responses to arrive.
        Assert.assertTrue(latch.await(10, TimeUnit.SECONDS));

        System.err.println("responses = " + responses);

        HistogramSnapshot snapshot = new HistogramSnapshot(responseTimes, 32, "Response Times", "us", TimeUnit.NANOSECONDS::toMicros);
        System.err.println(snapshot);
    }
}
