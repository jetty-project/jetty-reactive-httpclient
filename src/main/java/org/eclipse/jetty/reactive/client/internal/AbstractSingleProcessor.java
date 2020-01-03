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

import java.util.Objects;

import org.eclipse.jetty.util.MathUtils;
import org.reactivestreams.Processor;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

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

    private void upStreamCancel() {
        upStreamCancel(upStream());
    }

    private void upStreamCancel(Subscription upStream) {
        if (upStream != null) {
            upStream.cancel();
        }
    }

    @Override
    protected void onRequest(Subscriber<? super O> subscriber, long n) {
        long demand;
        Subscription upStream;
        synchronized (this) {
            demand = MathUtils.cappedAdd(this.demand, n);
            upStream = upStream();
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
        boolean cancel = false;
        synchronized (this) {
            if (this.upStream != null) {
                cancel = true;
            } else {
                if (isCancelled()) {
                    cancel = true;
                } else {
                    this.upStream = subscription;
                    demand = this.demand;
                    this.demand = 0;
                }
            }
        }
        if (cancel) {
            subscription.cancel();
        } else if (demand > 0) {
            subscription.request(demand);
        }
    }

    protected Subscription upStream() {
        synchronized (this) {
            return upStream;
        }
    }

    protected void downStreamOnNext(O item) {
        Subscriber<? super O> downStream = downStream();
        if (downStream != null) {
            downStream.onNext(item);
        }
    }

    @Override
    public void onError(Throwable throwable) {
        Subscriber<? super O> downStream = downStream();
        if (downStream != null) {
            downStream.onError(throwable);
        }
    }

    @Override
    public void onComplete() {
        Subscriber<? super O> downStream = downStream();
        if (downStream != null) {
            downStream.onComplete();
        }
    }

    @Override
    public String toString() {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }
}
