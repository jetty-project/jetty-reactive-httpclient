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
package org.eclipse.jetty.reactive.client.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.reactive.client.AbstractTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.tck.IdentityProcessorVerification;
import org.reactivestreams.tck.TestEnvironment;

public class SingleProcessorTCKTest extends IdentityProcessorVerification<Content.Chunk> {
    public SingleProcessorTCKTest() {
        super(new TestEnvironment());
    }

    @BeforeEach
    public void before(TestInfo testInfo) {
        AbstractTest.printTestName(testInfo);
    }

    @Override
    public Processor<Content.Chunk, Content.Chunk> createIdentityProcessor(int bufferSize) {
        return new AbstractSingleProcessor<>() {
            @Override
            public void onNext(Content.Chunk chunk) {
                downStreamOnNext(Objects.requireNonNull(chunk));
            }
        };
    }

    @Override
    public Publisher<Content.Chunk> createFailedPublisher() {
        return null;
    }

    @Override
    public ExecutorService publisherExecutorService() {
        return Executors.newCachedThreadPool();
    }

    @Override
    public Content.Chunk createElement(int element) {
        return newChunk("element_" + element);
    }

    private Content.Chunk newChunk(String data) {
        return Content.Chunk.from(ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8)), false);
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
