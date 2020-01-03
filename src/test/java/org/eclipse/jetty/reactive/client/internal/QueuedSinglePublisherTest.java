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
package org.eclipse.jetty.reactive.client.internal;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class QueuedSinglePublisherTest {
    @BeforeMethod
    public void printTestName(Method method) {
        System.err.printf("Running %s.%s()%n", getClass().getName(), method.getName());
    }

    @Test
    public void testReentrancyFromOnNextToOnComplete() throws Exception {
        QueuedSinglePublisher<Runnable> publisher = new QueuedSinglePublisher<>();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger next = new AtomicInteger();
        publisher.subscribe(new Subscriber<Runnable>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(1);
            }

            @Override
            public void onNext(Runnable item) {
                item.run();
                next.incrementAndGet();
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
                if (next.get() > 0) {
                    latch.countDown();
                }
            }
        });

        // We offer a Runnable that will call complete().
        publisher.offer(publisher::complete);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testReentrancyFromOnNextToOnError() throws Exception {
        QueuedSinglePublisher<Runnable> publisher = new QueuedSinglePublisher<>();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger next = new AtomicInteger();
        publisher.subscribe(new Subscriber<Runnable>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(1);
            }

            @Override
            public void onNext(Runnable item) {
                item.run();
                next.incrementAndGet();
            }

            @Override
            public void onError(Throwable throwable) {
                if (next.get() > 0) {
                    latch.countDown();
                }
            }

            @Override
            public void onComplete() {
            }
        });

        // We offer a Runnable that will call complete().
        publisher.offer(() -> publisher.fail(new Exception()));

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void testOfferAfterComplete() {
        QueuedSinglePublisher<Runnable> publisher = new QueuedSinglePublisher<>();
        publisher.offer(() -> {});
        publisher.complete();
        publisher.offer(() -> {});
    }

    @Test
    public void testCompleteWithoutDemand() throws Exception {
        QueuedSinglePublisher<Runnable> publisher = new QueuedSinglePublisher<>();

        CountDownLatch latch = new CountDownLatch(1);
        publisher.subscribe(new Subscriber<Runnable>() {
            @Override
            public void onSubscribe(Subscription subscription) {
            }

            @Override
            public void onNext(Runnable runnable) {
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }
        });

        publisher.complete();

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testOfferCompleteWithDemandOne() throws Exception {
        QueuedSinglePublisher<Runnable> publisher = new QueuedSinglePublisher<>();

        CountDownLatch nextLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(1);
        publisher.subscribe(new Subscriber<Runnable>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(1);
            }

            @Override
            public void onNext(Runnable runnable) {
                nextLatch.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
                completeLatch.countDown();
            }
        });

        publisher.offer(() -> {});
        Assert.assertTrue(nextLatch.await(5, TimeUnit.SECONDS));

        publisher.complete();
        Assert.assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
    }
}
