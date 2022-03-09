package io.github.darkreaper.corruptedunzip;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class Utils {

    static class AutoCloseableExecutorService extends ThreadPoolExecutor implements AutoCloseable {
        public AutoCloseableExecutorService(final String threadNamePrefix, final int numThreads) {
            super(numThreads, numThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(),
                    new ThreadFactory() {
                        ThreadLocal<AtomicInteger> threadIdx = ThreadLocal.withInitial(() -> new AtomicInteger());

                        @Override
                        public Thread newThread(final Runnable r) {
                            final Thread t = new Thread(r,
                                    threadNamePrefix + "-" + threadIdx.get().getAndIncrement());
                            t.setDaemon(true);
                            return t;
                        }
                    });
        }

        @Override
        public void close() {
            try {
                shutdown();
            } catch (Exception e) {
            }
            try {
                awaitTermination(2500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
            }
            try {
                shutdownNow();
            } catch (final Exception e) {
                throw new RuntimeException("Exception shutting down ExecutorService: " + e);
            }
        }
    }

    static class AutoCloseableConcurrentQueue<T extends AutoCloseable> extends ConcurrentLinkedQueue<T>
            implements AutoCloseable {
        @Override
        public void close() {
            while (!isEmpty()) {
                final T item = remove();
                try {
                    item.close();
                } catch (final Exception e) {
                }
            }
        }
    }

    static class AutoCloseableFutureListWithCompletionBarrier extends ArrayList<Future<Void>>
            implements AutoCloseable {
        public AutoCloseableFutureListWithCompletionBarrier(final int size) {
            super(size);
        }

        @Override
        public void close() {
            for (final var future : this) {
                try {
                    future.get();
                } catch (final Exception e) {
                }
            }
        }
    }

    static abstract class SingletonMap<K, V> {
        private final ConcurrentMap<K, SingletonHolder<V>> map = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<SingletonHolder<V>> singletonHolderRecycler = new ConcurrentLinkedQueue<>();

        private static class SingletonHolder<V> {
            private V singleton;
            private final CountDownLatch initialized = new CountDownLatch(1);

            public void set(final V singleton) {
                this.singleton = singleton;
                initialized.countDown();
            }

            public V get() throws InterruptedException {
                initialized.await();
                return singleton;
            }
        }

        public boolean createSingleton(final K key) throws Exception {
            SingletonHolder<V> newSingletonHolder = singletonHolderRecycler.poll();
            if (newSingletonHolder == null) {
                newSingletonHolder = new SingletonHolder<>();
            }
            final SingletonHolder<V> oldSingletonHolder = map.putIfAbsent(key, newSingletonHolder);
            if (oldSingletonHolder == null) {

                V newInstance = null;
                try {
                    newInstance = newInstance(key);
                    if (newInstance == null) {
                        throw new IllegalArgumentException("newInstance(key) returned null");
                    }
                } finally {
                    newSingletonHolder.set(newInstance);
                }
                return true;
            } else {
                singletonHolderRecycler.add(newSingletonHolder);
                return false;
            }
        }

        public V getOrCreateSingleton(final K key) throws Exception {
            final V existingSingleton = get(key);
            if (existingSingleton != null) {
                return existingSingleton;
            } else {
                createSingleton(key);
                return get(key);
            }
        }

        public abstract V newInstance(K key) throws Exception;

        public V get(final K key) throws InterruptedException {
            final SingletonHolder<V> singletonHolder = map.get(key);
            return singletonHolder == null ? null : singletonHolder.get();
        }

        public List<V> values() throws InterruptedException {
            final List<V> entries = new ArrayList<>(map.size());
            for (final Entry<K, SingletonHolder<V>> ent : map.entrySet()) {
                entries.add(ent.getValue().get());
            }
            return entries;
        }

        public void clear() {
            map.clear();
        }
    }
}
