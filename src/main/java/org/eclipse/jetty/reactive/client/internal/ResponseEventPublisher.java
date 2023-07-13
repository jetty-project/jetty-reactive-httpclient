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

import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.reactive.client.ReactiveRequest;
import org.eclipse.jetty.reactive.client.ReactiveResponse;

public class ResponseEventPublisher extends AbstractEventPublisher<ReactiveResponse.Event> implements Response.Listener {
    private final ReactiveRequest request;

    public ResponseEventPublisher(ReactiveRequest request) {
        this.request = request;
    }

    @Override
    public void onBegin(Response response) {
        emit(new ReactiveResponse.Event(ReactiveResponse.Event.Type.BEGIN, request.getReactiveResponse()));
    }

    @Override
    public boolean onHeader(Response response, HttpField field) {
        return true;
    }

    @Override
    public void onHeaders(Response response) {
        emit(new ReactiveResponse.Event(ReactiveResponse.Event.Type.HEADERS, request.getReactiveResponse()));
    }

    @Override
    public void onContent(Response response, ByteBuffer content) {
    }

    @Override
    public void onContent(Response response, Content.Chunk chunk, Runnable demander) {
    }

    @Override
    public void onContentSource(Response response, Content.Source source) {
        Runnable reader = new Runnable() {
            @Override
            public void run() {
                while (true) {
                    Content.Chunk chunk = source.read();
                    if (chunk == null) {
                        source.demand(this);
                        return;
                    }
                    if (Content.Chunk.isFailure(chunk)) {
                        onFailure(response, chunk.getFailure());
                        return;
                    }
                    if (chunk.hasRemaining()) {
                        emit(new ReactiveResponse.Event(ReactiveResponse.Event.Type.CONTENT, request.getReactiveResponse(), chunk.getByteBuffer().asReadOnlyBuffer()));
                    }
                    chunk.release();
                    if (chunk.isLast()) {
                        break;
                    }
                }
            }
        };
        reader.run();
    }

    @Override
    public void onSuccess(Response response) {
        emit(new ReactiveResponse.Event(ReactiveResponse.Event.Type.SUCCESS, request.getReactiveResponse()));
    }

    @Override
    public void onFailure(Response response, Throwable failure) {
        emit(new ReactiveResponse.Event(ReactiveResponse.Event.Type.FAILURE, request.getReactiveResponse(), failure));
    }

    @Override
    public void onComplete(Result result) {
        emit(new ReactiveResponse.Event(ReactiveResponse.Event.Type.COMPLETE, request.getReactiveResponse()));
        if (result.isSucceeded()) {
            succeed();
        } else {
            fail(result.getFailure());
        }
    }
}
