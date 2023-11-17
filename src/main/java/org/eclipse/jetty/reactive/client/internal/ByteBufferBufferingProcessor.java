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
import java.util.List;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.reactive.client.ReactiveResponse;

public class ByteBufferBufferingProcessor extends AbstractBufferingProcessor<ByteBuffer> {
    public ByteBufferBufferingProcessor(ReactiveResponse response, int maxCapacity) {
        super(response, maxCapacity);
    }

    @Override
    protected ByteBuffer process(List<Content.Chunk> chunks) {
        int length = chunks.stream().mapToInt(Content.Chunk::remaining).sum();
        ByteBuffer result = ByteBuffer.allocateDirect(length);
        for (Content.Chunk chunk : chunks) {
            result.put(chunk.getByteBuffer());
            chunk.release();
        }
        return result.flip();
    }
}
