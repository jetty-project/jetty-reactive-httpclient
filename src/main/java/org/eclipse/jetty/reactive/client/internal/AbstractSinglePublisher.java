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

import org.eclipse.jetty.util.thread.AutoLock;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A {@link Publisher} that supports a single {@link Subscriber} at a time.</p>
 *
 * @param <T> the type of items emitted by this {@link Publisher}
 */
public abstract class AbstractSinglePublisher<T> implements Publisher<T>, Subscription {
    private static final Logger logger = LoggerFactory.getLogger(AbstractSinglePublisher.class);

    private final AutoLock lock = new AutoLock();
    private Subscriber<? super T> subscriber;

    protected AutoLock lock() {
        return lock.lock();
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        Objects.requireNonNull(subscriber, "invalid 'null' subscriber");
        Throwable failure = null;
        try (AutoLock ignored = lock()) {
            if (this.subscriber != null) {
                failure = new IllegalStateException("multiple subscribers not supported");
            } else {
                this.subscriber = subscriber;
            }
        }
        if (logger.isDebugEnabled()) {
            logger.debug("{} subscription from {}", this, subscriber);
        }
        subscriber.onSubscribe(this);
        if (failure != null) {
            onFailure(subscriber, failure);
        }
    }

    protected Subscriber<? super T> subscriber() {
        try (AutoLock ignored = lock()) {
            return subscriber;
        }
    }

    @Override
    public void request(long n) {
        Subscriber<? super T> subscriber;
        Throwable failure = null;
        try (AutoLock ignored = lock()) {
            subscriber = this.subscriber;
            if (n <= 0) {
                failure = new IllegalArgumentException("reactive stream violation rule 3.9");
            }
        }
        if (failure != null) {
            onFailure(subscriber, failure);
        } else {
            onRequest(subscriber, n);
        }
    }

    protected abstract void onRequest(Subscriber<? super T> subscriber, long n);

    protected void onFailure(Subscriber<? super T> subscriber, Throwable failure) {
        subscriber.onError(failure);
    }

    @Override
    public void cancel() {
        try (AutoLock ignored = lock()) {
            if (subscriber != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("{} cancelled subscription from {}", this, subscriber);
                }
            }
            // Null-out the field to allow re-subscriptions.
            subscriber = null;
        }
    }

    @Override
    public String toString() {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }
}
