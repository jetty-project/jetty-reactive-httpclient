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

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.reactive.client.ContentChunk;
import org.eclipse.jetty.reactive.client.ReactiveResponse;

public class BufferingProcessor extends AbstractSingleProcessor<ContentChunk, String> {
    private final List<byte[]> buffers = new ArrayList<>();
    private final ReactiveResponse response;

    public BufferingProcessor(ReactiveResponse response) {
        this.response = response;
    }

    @Override
    public void onNext(ContentChunk chunk) {
        ByteBuffer buffer = chunk.buffer;
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        buffers.add(bytes);
        chunk.callback.succeeded();
        upStreamRequest(1);
    }

    @Override
    public void onComplete() {
        int length = buffers.stream().mapToInt(bytes -> bytes.length).sum();
        byte[] bytes = new byte[length];
        int offset = 0;
        for (byte[] b : buffers) {
            int l = b.length;
            System.arraycopy(b, 0, bytes, offset, l);
            offset += l;
        }

        String encoding = response.getEncoding();
        if (encoding == null) {
            encoding = StandardCharsets.UTF_8.name();
        }

        downStreamOnNext(new String(bytes, Charset.forName(encoding)));

        super.onComplete();
    }
}
