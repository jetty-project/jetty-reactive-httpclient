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

import org.reactivestreams.Processor;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public abstract class AbstractSingleProcessor<I, O> extends AbstractSinglePublisher<O> implements Processor<I, O> {
    private Subscription upStream;

    protected Subscriber<? super O> downStream() {
        return subscriber();
    }

    @Override
    protected void onFailure(Throwable failure) {
        cancelUpStream();
        super.onFailure(failure);
    }

    @Override
    public void cancel() {
        cancelUpStream();
        super.cancel();
    }

    private void cancelUpStream() {
        Subscription upStream = upStream();
        if (upStream != null) {
            upStream.cancel();
        }
    }

    @Override
    protected void onRequest(long n) {
        upStream().request(n);
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        synchronized (this) {
            if (this.upStream != null) {
                throw new IllegalStateException("multiple subscriptions not supported");
            } else {
                if (isCancelled()) {
                    subscription.cancel();
                } else {
                    this.upStream = subscription;
                }
            }
        }
    }

    protected Subscription upStream() {
        synchronized (this) {
            return upStream;
        }
    }

    @Override
    public void onError(Throwable throwable) {
        downStream().onError(throwable);
    }

    @Override
    public void onComplete() {
        downStream().onComplete();
    }

    @Override
    public String toString() {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }
}
