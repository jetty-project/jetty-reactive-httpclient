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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.reactive.client.ReactiveResponse;

public class StringBufferingProcessor extends AbstractBufferingProcessor<String> {
    private final ByteArrayBufferingProcessor bytesProcessor;

    public StringBufferingProcessor(ReactiveResponse response, int maxCapacity) {
        super(response, maxCapacity);
        bytesProcessor = new ByteArrayBufferingProcessor(response, maxCapacity);
    }

    @Override
    protected String process(List<Content.Chunk> chunks) {
        byte[] bytes = bytesProcessor.process(chunks);
        String encoding = Objects.requireNonNullElse(getResponse().getEncoding(), StandardCharsets.UTF_8.name());
        return new String(bytes, Charset.forName(encoding));
    }
}
