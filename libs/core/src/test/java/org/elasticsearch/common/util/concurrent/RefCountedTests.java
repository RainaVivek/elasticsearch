/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.common.util.concurrent;

import org.elasticsearch.core.AbstractRefCounted;
import org.elasticsearch.test.ESTestCase;
import org.hamcrest.Matchers;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class RefCountedTests extends ESTestCase {

    public void testRefCount() {
        MyRefCounted counted = new MyRefCounted();

        int incs = randomIntBetween(1, 100);
        for (int i = 0; i < incs; i++) {
            if (randomBoolean()) {
                counted.incRef();
            } else {
                assertTrue(counted.tryIncRef());
            }
            counted.ensureOpen();
        }

        for (int i = 0; i < incs; i++) {
            counted.decRef();
            counted.ensureOpen();
        }

        counted.incRef();
        counted.decRef();
        for (int i = 0; i < incs; i++) {
            if (randomBoolean()) {
                counted.incRef();
            } else {
                assertTrue(counted.tryIncRef());
            }
            counted.ensureOpen();
        }

        for (int i = 0; i < incs; i++) {
            counted.decRef();
            counted.ensureOpen();
        }

        counted.decRef();
        assertFalse(counted.tryIncRef());
        assertThat(
            expectThrows(IllegalStateException.class, counted::incRef).getMessage(),
            equalTo(AbstractRefCounted.ALREADY_CLOSED_MESSAGE));

        try {
            counted.ensureOpen();
            fail(" expected exception");
        } catch (IllegalStateException ex) {
            assertThat(ex.getMessage(), equalTo("closed"));
        }
    }

    public void testMultiThreaded() throws InterruptedException {
        final MyRefCounted counted = new MyRefCounted();
        Thread[] threads = new Thread[randomIntBetween(2, 5)];
        final CountDownLatch latch = new CountDownLatch(1);
        final CopyOnWriteArrayList<Exception> exceptions = new CopyOnWriteArrayList<>();
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                try {
                    latch.await();
                    for (int j = 0; j < 10000; j++) {
                        counted.incRef();
                        try {
                            counted.ensureOpen();
                        } finally {
                            counted.decRef();
                        }
                    }
                } catch (Exception e) {
                    exceptions.add(e);
                }
            });
            threads[i].start();
        }
        latch.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        counted.decRef();
        try {
            counted.ensureOpen();
            fail("expected to be closed");
        } catch (IllegalStateException ex) {
            assertThat(ex.getMessage(), equalTo("closed"));
        }
        assertThat(counted.refCount(), is(0));
        assertThat(exceptions, Matchers.emptyIterable());
    }

    private static final class MyRefCounted extends AbstractRefCounted {

        private final AtomicBoolean closed = new AtomicBoolean(false);

        @Override
        protected void closeInternal() {
            this.closed.set(true);
        }

        public void ensureOpen() {
            if (closed.get()) {
                assert this.refCount() == 0;
                throw new IllegalStateException("closed");
            }
        }
    }
}
