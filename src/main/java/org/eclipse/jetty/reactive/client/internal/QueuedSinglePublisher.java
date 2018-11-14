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

import java.util.ArrayDeque;
import java.util.Queue;

import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QueuedSinglePublisher<T> extends AbstractSinglePublisher<T> {
    public static final Terminal COMPLETE = Subscriber::onComplete;

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Queue<Object> items = new ArrayDeque<>();
    private long demand;
    private boolean stalled = true;
    private boolean started = false;

    public void start(){
        Subscriber<? super T> subscriber;
        synchronized (this) {
            started = true;
            subscriber = subscriber();
        }
        if (subscriber != null) {
            proceed(subscriber);
        }
    }

    public void offer(T item) {
        if (logger.isDebugEnabled()) {
            logger.debug("offered item {} to {}", item, this);
        }
        process(item);
    }

    public boolean complete() {
        if (logger.isDebugEnabled()) {
            logger.debug("completed {}", this);
        }
        return process(COMPLETE);
    }

    public boolean fail(Throwable failure) {
        return process(new Failure(failure));
    }

    @Override
    protected void onRequest(Subscriber<? super T> subscriber, long n) {
        boolean proceed = false;
        synchronized (this) {
            demand = cappedAdd(demand, n);
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
            items.offer(item);
            if(started) {
                subscriber = subscriber();
                if (subscriber != null) {
                    if (stalled) {
                        stalled = false;
                    }
                }
            } else {
                subscriber = null;
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
        Object item;
        boolean terminal;
        while (true) {
            synchronized (this) {
                item = items.peek();
                if (item == null) {
                    stalled = true;
                    return;
                } else {
                    if (demand > 0) {
                        --demand;
                        terminal = isTerminal(item);
                    } else {
                        stalled = true;
                        return;
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
                subscriber.onNext(t);
            }
        }
    }

    private boolean isTerminal(Object item) {
        return item instanceof Terminal;
    }

    @FunctionalInterface
    private interface Terminal<T> {
        public void notify(Subscriber<? super T> subscriber);
    }

    private class Failure<F> implements Terminal<F> {
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
