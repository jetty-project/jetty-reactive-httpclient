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
import java.nio.charset.StandardCharsets;
import org.eclipse.jetty.reactive.client.ContentChunk;
import org.reactivestreams.Publisher;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.BeforeMethod;

public class StringContentTCKTest extends PublisherVerification<ContentChunk> {
    public StringContentTCKTest() {
        super(new TestEnvironment());
    }

    @BeforeMethod
    public void printTestName(Method method) {
        System.err.printf("Running %s.%s()%n", getClass().getName(), method.getName());
    }

    @Override
    public Publisher<ContentChunk> createPublisher(long elements) {
        return new StringContent("data", "text/plain", StandardCharsets.UTF_8);
    }

    @Override
    public Publisher<ContentChunk> createFailedPublisher() {
        return null;
    }

    @Override
    public long maxElementsFromPublisher() {
        return 1;
    }
}
