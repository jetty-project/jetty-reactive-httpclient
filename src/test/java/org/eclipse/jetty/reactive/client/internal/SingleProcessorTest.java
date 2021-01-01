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
package org.eclipse.jetty.reactive.client.internal;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import io.reactivex.rxjava3.core.Flowable;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class SingleProcessorTest {
    @BeforeMethod
    public void printTestName(Method method) {
        System.err.printf("Running %s.%s()%n", getClass().getName(), method.getName());
    }

    @Test
    public void testDemandWithoutUpStreamIsRemembered() throws Exception {
        AbstractSingleProcessor<String, String> processor = new AbstractSingleProcessor<String, String>() {
            @Override
            public void onNext(String item) {
                downStreamOnNext(item);
            }
        };

        // First link a Subscriber, calling request(1) - no upStream yet.
        CountDownLatch latch = new CountDownLatch(1);
        List<String> items = new ArrayList<>();
        processor.subscribe(new Subscriber<String>() {
            private Subscription subscription;

            @Override
            public void onSubscribe(Subscription subscription) {
                this.subscription = subscription;
                subscription.request(1);
            }

            @Override
            public void onNext(String item) {
                items.add(item);
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

        // Now create an upStream Publisher and subscribe the processor.
        int count = 16;
        Flowable.range(0, count)
                .map(String::valueOf)
                .subscribe(processor);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        List<String> expected = IntStream.range(0, count)
                .mapToObj(String::valueOf)
                .collect(Collectors.toList());
        Assert.assertEquals(items, expected);
    }
}
