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
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.reactive.client.ReactiveRequest;

public class RequestEventPublisher extends AbstractEventPublisher<ReactiveRequest.Event> implements Request.Listener {
    private final ReactiveRequest request;

    public RequestEventPublisher(ReactiveRequest request) {
        this.request = request;
    }

    @Override
    public void onQueued(Request request) {
        emit(new ReactiveRequest.Event(ReactiveRequest.Event.Type.QUEUED, this.request));
    }

    @Override
    public void onBegin(Request request) {
        emit(new ReactiveRequest.Event(ReactiveRequest.Event.Type.BEGIN, this.request));
    }

    @Override
    public void onHeaders(Request request) {
        emit(new ReactiveRequest.Event(ReactiveRequest.Event.Type.HEADERS, this.request));
    }

    @Override
    public void onCommit(Request request) {
        emit(new ReactiveRequest.Event(ReactiveRequest.Event.Type.COMMIT, this.request));
    }

    @Override
    public void onContent(Request request, ByteBuffer content) {
        emit(new ReactiveRequest.Event(ReactiveRequest.Event.Type.CONTENT, this.request, content));
    }

    @Override
    public void onSuccess(Request request) {
        emit(new ReactiveRequest.Event(ReactiveRequest.Event.Type.SUCCESS, this.request));
        succeed();
    }

    @Override
    public void onFailure(Request request, Throwable failure) {
        emit(new ReactiveRequest.Event(ReactiveRequest.Event.Type.FAILURE, this.request, failure));
        fail(failure);
    }
}
