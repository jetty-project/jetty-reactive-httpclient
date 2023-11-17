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

import org.eclipse.jetty.reactive.client.ReactiveResponse;

/**
 * <p>A {@link org.reactivestreams.Processor} that receives (typically)
 * a single item of response content of type {@code T} from upstream,
 * and produces a single {@link ReactiveResponse.Result} that wraps
 * the {@link ReactiveResponse} and the response content item.</p>
 *
 * @param <T> the type of the response content associated
 */
public class ResultProcessor<T> extends AbstractSingleProcessor<T, ReactiveResponse.Result<T>> {
    private final ReactiveResponse response;
    private T item;

    public ResultProcessor(ReactiveResponse response) {
        this.response = response;
    }

    @Override
    public void onNext(T item) {
        this.item = item;
    }

    @Override
    public void onComplete() {
        downStreamOnNext(new ReactiveResponse.Result<>(response, item));
        super.onComplete();
    }
}
