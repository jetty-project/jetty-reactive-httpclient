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

import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.io.Content;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.reactivestreams.Publisher;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;

public class StringContentTCKTest extends PublisherVerification<Content.Chunk> {
    public StringContentTCKTest() {
        super(new TestEnvironment());
    }

    @BeforeEach
    public void printTestName(TestInfo testInfo) {
        System.err.printf("Running %s.%s()%n", getClass().getName(), testInfo.getDisplayName());
    }

    @Override
    public Publisher<Content.Chunk> createPublisher(long elements) {
        return new StringContent("data", "text/plain", StandardCharsets.UTF_8);
    }

    @Override
    public Publisher<Content.Chunk> createFailedPublisher() {
        return null;
    }

    @Override
    public long maxElementsFromPublisher() {
        return 1;
    }
}
