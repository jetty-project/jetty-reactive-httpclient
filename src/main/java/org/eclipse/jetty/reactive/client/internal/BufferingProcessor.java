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
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.reactive.client.ReactiveResponse;

public class BufferingProcessor extends AbstractSingleProcessor<Content.Chunk, String> {
    private final List<Content.Chunk> chunks = new ArrayList<>();
    private final ReactiveResponse response;

    public BufferingProcessor(ReactiveResponse response) {
        this.response = response;
    }

    @Override
    public void onNext(Content.Chunk chunk) {
        chunks.add(chunk);
        upStreamRequest(1);
    }

    @Override
    public void onComplete() {
        int length = chunks.stream().mapToInt(Content.Chunk::remaining).sum();
        byte[] bytes = new byte[length];
        int offset = 0;
        for (Content.Chunk chunk : chunks) {
            int l = chunk.remaining();
            chunk.getByteBuffer().get(bytes, offset, l);
            offset += l;
            chunk.release();
        }

        String encoding = response.getEncoding();
        if (encoding == null) {
            encoding = StandardCharsets.UTF_8.name();
        }

        downStreamOnNext(new String(bytes, Charset.forName(encoding)));

        super.onComplete();
    }
}
