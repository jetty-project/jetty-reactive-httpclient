/*
 * Copyright (c) 2017-2018 the original author or authors.
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

import org.reactivestreams.Subscriber;

public abstract class AbstractEventPublisher<T> extends AbstractSinglePublisher<T> {
    private State state = State.EMITTING;
    private Throwable failure;
    private long demand;

    @Override
    protected void onRequest(long n) {
        boolean complete = false;
        Throwable failure = null;
        synchronized (this) {
            demand += n;
            switch (state) {
                case COMPLETING:
                    state = State.TERMINATED;
                    complete = true;
                    break;
                case FAILING:
                    state = State.TERMINATED;
                    failure = this.failure;
                    break;
                default:
                    break;
            }
        }
        if (complete) {
            subscriber().onComplete();
        } else if (failure != null) {
            subscriber().onError(failure);
        }
    }

    protected void emit(T event) {
        boolean emit = false;
        synchronized (this) {
            if (demand > 0) {
                --demand;
                emit = true;
            }
        }
        if (emit) {
            subscriber().onNext(event);
        }
    }

    protected void succeed() {
        synchronized (this) {
            state = State.COMPLETING;
        }
        Subscriber<? super T> subscriber = subscriber();
        if (subscriber != null) {
            boolean complete = false;
            synchronized (this) {
                if (state == State.COMPLETING) {
                    state = State.TERMINATED;
                    complete = true;
                }
            }
            if (complete) {
                subscriber.onComplete();
            }
        }
    }

    protected void fail(Throwable failure) {
        synchronized (this) {
            state = State.FAILING;
            this.failure = failure;
        }
        Subscriber<? super T> subscriber = subscriber();
        if (subscriber != null) {
            boolean error = false;
            synchronized (this) {
                if (state == State.FAILING) {
                    state = State.TERMINATED;
                    error = true;
                }
            }
            if (error) {
                subscriber.onError(failure);
            }
        }
    }

    private enum State {
        EMITTING, COMPLETING, FAILING, TERMINATED
    }
}
