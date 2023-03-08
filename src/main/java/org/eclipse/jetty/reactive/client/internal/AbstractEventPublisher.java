/*
 * Copyright (c) 2017-2022 the original author or authors.
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

import org.eclipse.jetty.util.MathUtils;
import org.eclipse.jetty.util.thread.AutoLock;
import org.reactivestreams.Subscriber;

public abstract class AbstractEventPublisher<T> extends AbstractSinglePublisher<T> {
    private long demand;
    private boolean initial;
    private boolean terminated;
    private Throwable failure;

    @Override
    protected void onRequest(Subscriber<? super T> subscriber, long n) {
        boolean notify = false;
        Throwable failure = null;
        try (AutoLock ignored = lock()) {
            demand = MathUtils.cappedAdd(demand, n);
            boolean isInitial = initial;
            initial = false;
            if (isInitial && terminated) {
                notify = true;
                failure = this.failure;
            }
        }
        if (notify) {
            if (failure == null) {
                subscriber.onComplete();
            } else {
                subscriber.onError(failure);
            }
        }
    }

    protected void emit(T event) {
        Subscriber<? super T> subscriber = null;
        try (AutoLock ignored = lock()) {
            if (!isCancelled() && demand > 0) {
                --demand;
                subscriber = subscriber();
            }
        }
        if (subscriber != null) {
            subscriber.onNext(event);
        }
    }

    protected void succeed() {
        Subscriber<? super T> subscriber;
        try (AutoLock ignored = lock()) {
            terminated = true;
            subscriber = subscriber();
        }
        if (subscriber != null) {
            subscriber.onComplete();
        }
    }

    protected void fail(Throwable failure) {
        Subscriber<? super T> subscriber;
        try (AutoLock ignored = lock()) {
            terminated = true;
            this.failure = failure;
            subscriber = subscriber();
        }
        if (subscriber != null) {
            subscriber.onError(failure);
        }
    }
}
