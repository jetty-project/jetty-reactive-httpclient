/*
 * Copyright (c) 2017-2017 the original author or authors.
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

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Publisher that supports a single Subscriber.
 *
 * @param <T> the type of items emitted by this Publisher
 */
public abstract class AbstractSinglePublisher<T> implements Publisher<T>, Subscription {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private Subscriber<? super T> subscriber;
    private boolean cancelled;

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        Throwable failure = null;
        synchronized (this) {
            if (this.subscriber != null) {
                failure = new IllegalStateException("multiple subscribers not supported");
            } else {
                this.subscriber = subscriber;
            }
        }
        if (failure != null) {
            onFailure(failure);
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("{} subscription from {}", this, subscriber);
            }
            subscriber.onSubscribe(this);
        }
    }

    protected Subscriber<? super T> subscriber() {
        synchronized (this) {
            return subscriber;
        }
    }

    @Override
    public void request(long n) {
        Throwable failure = null;
        synchronized (this) {
            if (isCancelled()) {
                return;
            }
            if (n <= 0) {
                failure = new IllegalArgumentException("reactive stream violation rule 3.9");
            }
        }
        if (failure != null) {
            onFailure(failure);
        } else {
            onRequest(n);
        }
    }

    protected abstract void onRequest(long n);

    protected void onFailure(Throwable failure) {
        subscriber().onError(failure);
    }

    @Override
    public void cancel() {
        synchronized (this) {
            cancelled = true;
        }
    }

    protected boolean isCancelled() {
        synchronized (this) {
            return cancelled;
        }
    }

    @Override
    public String toString() {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }
}
