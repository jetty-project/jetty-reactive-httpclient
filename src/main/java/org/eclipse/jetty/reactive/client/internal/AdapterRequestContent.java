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

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.reactive.client.ReactiveRequest;
import org.eclipse.jetty.util.thread.AutoLock;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A {@link Request.Content} whose source is a {@link ReactiveRequest.Content}.</p>
 */
public class AdapterRequestContent implements Request.Content {
    private static final Logger logger = LoggerFactory.getLogger(AdapterRequestContent.class);

    private final ReactiveRequest.Content reactiveContent;
    private Bridge bridge;

    public AdapterRequestContent(ReactiveRequest.Content content) {
        this.reactiveContent = content;
    }

    @Override
    public long getLength() {
        return reactiveContent.getLength();
    }

    @Override
    public Content.Chunk read() {
        return getOrCreateBridge().read();
    }

    @Override
    public void demand(Runnable runnable) {
        getOrCreateBridge().demand(runnable);
    }

    @Override
    public void fail(Throwable failure) {
        getOrCreateBridge().fail(failure);
    }

    @Override
    public boolean rewind() {
        boolean rewound = reactiveContent.rewind();
        if (rewound) {
            if (bridge != null) {
                bridge.cancel();
                bridge = null;
            }
        }
        return rewound;
    }

    private Bridge getOrCreateBridge() {
        if (bridge == null) {
            bridge = new Bridge();
        }
        return bridge;
    }

    @Override
    public String getContentType() {
        return reactiveContent.getContentType();
    }

    @Override
    public String toString() {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }

    /**
     * <p>A bridge between the {@link Request.Content} read by the {@link HttpClient}
     * implementation and the {@link ReactiveRequest.Content} provided by applications.</p>
     * <p>The first access to the {@link Request.Content} from the {@link HttpClient}
     * implementation creates the bridge and forwards the access, calling either
     * {@link #read()} or {@link #demand(Runnable)}.
     * Method {@link #read()} returns the current {@link Content.Chunk}.
     * Method {@link #demand(Runnable)} forwards the demand to the {@link ReactiveRequest.Content},
     * which in turns calls {@link #onNext(Content.Chunk)}, providing the current chunk
     * returned by {@link #read()}.</p>
     */
    private class Bridge implements Subscriber<Content.Chunk> {
        private final AutoLock lock = new AutoLock();
        private Subscription subscription;
        private Content.Chunk chunk;
        private Throwable failure;
        private boolean complete;
        private Runnable demand;

        private Bridge() {
            reactiveContent.subscribe(this);
        }

        @Override
        public void onSubscribe(Subscription s) {
            subscription = s;
        }

        @Override
        public void onNext(Content.Chunk c) {
            Runnable onDemand;
            try (AutoLock ignored = lock.lock()) {
                chunk = c;
                onDemand = demand;
                demand = null;
            }

            if (logger.isDebugEnabled()) {
                logger.debug("content {} on {}", c, this);
            }

            invokeDemand(onDemand);
        }

        @Override
        public void onError(Throwable error) {
            Runnable onDemand;
            try (AutoLock ignored = lock.lock()) {
                failure = error;
                onDemand = demand;
                demand = null;
            }

            if (logger.isDebugEnabled()) {
                logger.debug("error on {}", this, error);
            }

            if (onDemand != null) {
                invokeDemand(onDemand);
            }
        }

        @Override
        public void onComplete() {
            Runnable onDemand;
            try (AutoLock ignored = lock.lock()) {
                complete = true;
                onDemand = demand;
                demand = null;
            }

            if (logger.isDebugEnabled()) {
                logger.debug("complete on {}", this);
            }

            if (onDemand != null) {
                invokeDemand(onDemand);
            }
        }

        private Content.Chunk read() {
            try (AutoLock ignored = lock.lock()) {
                Content.Chunk result = chunk;
                if (result == null) {
                    if (complete) {
                        result = Content.Chunk.EOF;
                    } else if (failure != null) {
                        result = Content.Chunk.from(failure);
                    }
                }
                chunk = Content.Chunk.next(result);
                if (logger.isDebugEnabled()) {
                    logger.debug("read {} on {}", result, this);
                }
                return result;
            }
        }

        private void demand(Runnable onDemand) {
            try (AutoLock ignored = lock.lock()) {
                if (demand != null) {
                    throw new IllegalStateException("demand already exists");
                }
                demand = onDemand;
            }

            if (logger.isDebugEnabled()) {
                logger.debug("demand {} on {}", onDemand, this);
            }

            // Forward the demand.
            subscription.request(1);
        }

        private void invokeDemand(Runnable demand) {
            try {
                if (logger.isDebugEnabled()) {
                    logger.debug("invoking demand callback {} on {}", demand, this);
                }
                demand.run();
            } catch (Throwable x) {
                fail(x);
            }
        }

        private void fail(Throwable failure) {
            if (logger.isDebugEnabled()) {
                logger.debug("failure while processing request content on {}", this, failure);
            }
            cancel();
        }

        private void cancel() {
            subscription.cancel();
        }
    }
}
