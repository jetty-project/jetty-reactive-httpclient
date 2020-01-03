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

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletionException;

import org.eclipse.jetty.util.MathUtils;
import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QueuedSinglePublisher<T> extends AbstractSinglePublisher<T> {
    public static final Terminal COMPLETE = Subscriber::onComplete;
    private static final Logger logger = LoggerFactory.getLogger(QueuedSinglePublisher.class);

    private final Queue<Object> items = new ArrayDeque<>();
    private long demand;
    private boolean stalled = true;
    private boolean active;
    private Throwable terminated;

    public void offer(T item) {
        if (logger.isDebugEnabled()) {
            logger.debug("offered item {} to {}", item, this);
        }
        process(item);
    }

    public void complete() {
        if (logger.isDebugEnabled()) {
            logger.debug("completed {}", this);
        }
        process(COMPLETE);
    }

    public boolean fail(Throwable failure) {
        if (logger.isDebugEnabled()) {
            logger.debug("failed {}", this, failure);
        }
        return process(new Failure(failure));
    }

    @Override
    protected void onRequest(Subscriber<? super T> subscriber, long n) {
        boolean proceed = false;
        synchronized (this) {
            demand = MathUtils.cappedAdd(demand, n);
            if (stalled) {
                stalled = false;
                proceed = true;
            }
        }
        if (proceed) {
            proceed(subscriber);
        }
    }

    private boolean process(Object item) {
        Subscriber<? super T> subscriber;
        synchronized (this) {
            if (terminated != null) {
                throw new IllegalStateException(terminated);
            }
            if (isTerminal(item)) {
                terminated = new CompletionException("terminated from " + Thread.currentThread(), null);
            }
            items.offer(item);
            subscriber = subscriber();
            if (subscriber != null) {
                if (stalled) {
                    stalled = false;
                }
            }
        }
        if (subscriber != null) {
            proceed(subscriber);
            return true;
        } else {
            return false;
        }
    }

    private void proceed(Subscriber<? super T> subscriber) {
        synchronized (this) {
            if (active) {
                return;
            }
            active = true;
        }

        Object item;
        boolean terminal;
        while (true) {
            synchronized (this) {
                item = items.peek();
                if (item == null) {
                    stalled = true;
                    active = false;
                    return;
                } else {
                    terminal = isTerminal(item);
                    if (!terminal) {
                        if (demand > 0) {
                            --demand;
                        } else  {
                            stalled = true;
                            active = false;
                            return;
                        }
                    }
                }
                item = items.poll();
            }

            if (logger.isDebugEnabled()) {
                logger.debug("processing {} item {} by {}", terminal ? "last" : "next", item, this);
            }

            if (terminal) {
                @SuppressWarnings("unchecked")
                Terminal<T> t = (Terminal<T>)item;
                t.notify(subscriber);
            } else {
                @SuppressWarnings("unchecked")
                T t = (T)item;
                onNext(subscriber, t);
            }
        }
    }

    protected void onNext(Subscriber<? super T> subscriber, T item) {
        subscriber.onNext(item);
    }

    private boolean isTerminal(Object item) {
        return item instanceof Terminal;
    }

    @FunctionalInterface
    private interface Terminal<T> {
        public void notify(Subscriber<? super T> subscriber);
    }

    private static class Failure<F> implements Terminal<F> {
        private final Throwable failure;

        private Failure(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public void notify(Subscriber<? super F> subscriber) {
            subscriber.onError(failure);
        }
    }
}
