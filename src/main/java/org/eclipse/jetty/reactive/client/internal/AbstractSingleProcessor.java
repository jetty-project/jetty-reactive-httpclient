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

import org.eclipse.jetty.util.MathUtils;
import org.eclipse.jetty.util.thread.AutoLock;
import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * <p>A {@link Processor} that allows a single {@link Subscriber} at a time,
 * and can subscribe to only one {@link Publisher} at a time.</p>
 * <p>The implementation acts as a {@link Subscriber} to upstream input,
 * and acts as a {@link Publisher} for the downstream output.</p>
 * <p>Subclasses implement the transformation of the input elements into the
 * output elements by overriding {@link #onNext(Object)}.</p>
 *
 * @param <I> the type of the input elements
 * @param <O> the type of the output elements
 */
public abstract class AbstractSingleProcessor<I, O> extends AbstractSinglePublisher<O> implements Processor<I, O> {
    private Subscription upStream;
    private long demand;

    protected Subscriber<? super O> downStream() {
        return subscriber();
    }

    @Override
    protected void onFailure(Subscriber<? super O> subscriber, Throwable failure) {
        upStreamCancel();
        super.onFailure(subscriber, failure);
    }

    @Override
    public void cancel() {
        upStreamCancel();
        super.cancel();
    }

    protected void upStreamCancel() {
        Subscription upStream;
        try (AutoLock ignored = lock()) {
            upStream = this.upStream;
            // Null-out the field to allow re-subscriptions.
            this.upStream = null;
        }
        if (upStream != null) {
            upStream.cancel();
        }
    }

    @Override
    protected void onRequest(Subscriber<? super O> subscriber, long n) {
        long demand;
        Subscription upStream;
        try (AutoLock ignored = lock()) {
            demand = MathUtils.cappedAdd(this.demand, n);
            upStream = this.upStream;
            // If there is not upStream yet, store the demand.
            this.demand = upStream == null ? demand : 0;
        }
        upStreamRequest(upStream, demand);
    }

    protected void upStreamRequest(long n) {
        upStreamRequest(upStream(), n);
    }

    private void upStreamRequest(Subscription upStream, long demand) {
        if (upStream != null) {
            upStream.request(demand);
        }
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        Objects.requireNonNull(subscription, "invalid 'null' subscription");
        long demand = 0;
        Throwable failure = null;
        try (AutoLock ignored = lock()) {
            if (this.upStream != null) {
                failure = new IllegalStateException("multiple subscriptions not supported");
            } else {
                this.upStream = subscription;
                // The demand stored so far will be forwarded upstream.
                demand = this.demand;
                this.demand = 0;
            }
        }
        if (failure != null) {
            subscription.cancel();
            downStreamOnError(failure);
        } else if (demand > 0) {
            // Forward upstream any previously stored demand.
            subscription.request(demand);
        }
    }

    private Subscription upStream() {
        try (AutoLock ignored = lock()) {
            return upStream;
        }
    }

    protected void downStreamOnNext(O item) {
        Subscriber<? super O> downStream = downStream();
        if (downStream != null) {
            emitOnNext(downStream, item);
        }
    }

    @Override
    public void onError(Throwable throwable) {
        // This method is called by the upStream
        // publisher, forward to downStream.
        downStreamOnError(throwable);
    }

    private void downStreamOnError(Throwable throwable) {
        Subscriber<? super O> downStream = downStream();
        if (downStream != null) {
            emitOnError(downStream, throwable);
        }
    }

    @Override
    public void onComplete() {
        // This method is called by the upStream
        // publisher, forward to downStream.
        downStreamOnComplete();
    }

    private void downStreamOnComplete() {
        Subscriber<? super O> downStream = downStream();
        if (downStream != null) {
            emitOnComplete(downStream);
        }
    }

    @Override
    public String toString() {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }
}
