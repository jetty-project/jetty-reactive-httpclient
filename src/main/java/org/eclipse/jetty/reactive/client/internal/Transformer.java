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

import java.util.Objects;
import java.util.function.Function;

/**
 * <p>A {@link org.reactivestreams.Processor} that applies a function
 * to transform items from input type {@code I} to output type {@code O}.</p>
 *
 * @param <I> the input type
 * @param <O> the output type
 */
public class Transformer<I, O> extends AbstractSingleProcessor<I, O> {
    private final Function<I, O> transformer;

    public Transformer(Function<I, O> transformer) {
        this.transformer = Objects.requireNonNull(transformer);
    }

    @Override
    public void onNext(I i) {
        downStreamOnNext(transformer.apply(i));
    }
}
