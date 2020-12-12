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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.eclipse.jetty.reactive.client.ContentChunk;
import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.tck.IdentityProcessorVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.BeforeMethod;

public class SingleProcessorTCKTest extends IdentityProcessorVerification<ContentChunk> {
    public SingleProcessorTCKTest() {
        super(new TestEnvironment());
    }

    @BeforeMethod
    public void printTestName(Method method) {
        System.err.printf("Running %s.%s()%n", getClass().getName(), method.getName());
    }

    @Override
    public Processor<ContentChunk, ContentChunk> createIdentityProcessor(int bufferSize) {
        return new AbstractSingleProcessor<ContentChunk, ContentChunk>() {
            @Override
            public void onNext(ContentChunk contentChunk) {
                downStreamOnNext(Objects.requireNonNull(contentChunk));
            }
        };
    }

    @Override
    public Publisher<ContentChunk> createFailedPublisher() {
        return null;
    }

    @Override
    public ExecutorService publisherExecutorService() {
        return Executors.newCachedThreadPool();
    }

    @Override
    public ContentChunk createElement(int element) {
        return newChunk("element_" + element);
    }

    private ContentChunk newChunk(String data) {
        return new ContentChunk(ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public long maxSupportedSubscribers() {
        return 1;
    }

    @Override
    public boolean skipStochasticTests() {
        return true;
    }
}
