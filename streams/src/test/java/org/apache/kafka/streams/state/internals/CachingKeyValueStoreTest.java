/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.state.internals;

import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.kstream.internals.CacheFlushListener;
import org.apache.kafka.streams.kstream.internals.Change;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.internals.MockStreamsMetrics;
import org.apache.kafka.streams.processor.internals.ProcessorRecordContext;
import org.apache.kafka.streams.processor.internals.RecordCollector;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.test.MockProcessorContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.kafka.streams.state.internals.ThreadCacheTest.memoryCacheEntrySize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;

public class CachingKeyValueStoreTest extends AbstractKeyValueStoreTest {

    private final int maxCacheSizeBytes = 150;
    private MockProcessorContext context;
    private CachingKeyValueStore<String, String> store;
    private InMemoryKeyValueStore<Bytes, byte[]> underlyingStore;
    private ThreadCache cache;
    private CacheFlushListenerStub<String, String> cacheFlushListener;
    private String topic;

    @Before
    public void setUp() throws Exception {
        final String storeName = "store";
        underlyingStore = new InMemoryKeyValueStore<>(storeName, Serdes.Bytes(), Serdes.ByteArray());
        cacheFlushListener = new CacheFlushListenerStub<>();
        store = new CachingKeyValueStore<>(underlyingStore, Serdes.String(), Serdes.String());
        store.setFlushListener(cacheFlushListener);
        cache = new ThreadCache("testCache", maxCacheSizeBytes, new MockStreamsMetrics(new Metrics()));
        context = new MockProcessorContext(null, null, null, (RecordCollector) null, cache);
        topic = "topic";
        context.setRecordContext(new ProcessorRecordContext(10, 0, 0, topic));
        store.init(context, null);
    }

    @After
    public void after() {
        context.close();
    }

    @SuppressWarnings("unchecked")
    @Override
    protected <K, V> KeyValueStore<K, V> createKeyValueStore(final ProcessorContext context,
                                                             final Class<K> keyClass,
                                                             final Class<V> valueClass,
                                                             final boolean useContextSerdes) {
        final String storeName = "cache-store";

        final Stores.PersistentKeyValueFactory<?, ?> factory = Stores
                .create(storeName)
                .withKeys(Serdes.Bytes())
                .withValues(Serdes.ByteArray())
                .persistent();


        final KeyValueStore<Bytes, byte[]> underlyingStore = (KeyValueStore<Bytes, byte[]>) factory.build().get();
        final CacheFlushListenerStub<K, V> cacheFlushListener = new CacheFlushListenerStub<>();
        final CachingKeyValueStore<K, V> store;
        if (useContextSerdes) {
            store = new CachingKeyValueStore<>(underlyingStore,
                (Serde<K>) context.keySerde(), (Serde<V>) context.valueSerde());
        } else {
            store = new CachingKeyValueStore<>(underlyingStore,
                Serdes.serdeFrom(keyClass), Serdes.serdeFrom(valueClass));
        }
        store.setFlushListener(cacheFlushListener);
        store.init(context, store);
        return store;
    }

    @Test
    public void shouldPutGetToFromCache() throws Exception {
        store.put("key", "value");
        store.put("key2", "value2");
        assertEquals("value", store.get("key"));
        assertEquals("value2", store.get("key2"));
        // nothing evicted so underlying store should be empty
        assertEquals(2, cache.size());
        assertEquals(0, underlyingStore.approximateNumEntries());
    }

    @Test
    public void shouldFlushEvictedItemsIntoUnderlyingStore() throws Exception {
        int added = addItemsToCache();
        // all dirty entries should have been flushed
        assertEquals(added, underlyingStore.approximateNumEntries());
        assertEquals(added, store.approximateNumEntries());
        assertNotNull(underlyingStore.get(Bytes.wrap("0".getBytes())));
    }

    @Test
    public void shouldForwardDirtyItemToListenerWhenEvicted() throws Exception {
        int numRecords = addItemsToCache();
        assertEquals(numRecords, cacheFlushListener.forwarded.size());
    }

    @Test
    public void shouldForwardDirtyItemsWhenFlushCalled() throws Exception {
        store.put("1", "a");
        store.flush();
        assertEquals("a", cacheFlushListener.forwarded.get("1").newValue);
        assertNull(cacheFlushListener.forwarded.get("1").oldValue);
    }

    @Test
    public void shouldForwardOldValuesWhenEnabled() throws Exception {
        store.put("1", "a");
        store.flush();
        store.put("1", "b");
        store.flush();
        assertEquals("b", cacheFlushListener.forwarded.get("1").newValue);
        assertEquals("a", cacheFlushListener.forwarded.get("1").oldValue);
    }

    @Test
    public void shouldIterateAllStoredItems() throws Exception {
        int items = addItemsToCache();
        final KeyValueIterator<String, String> all = store.all();
        final List<String> results = new ArrayList<>();
        while (all.hasNext()) {
            results.add(all.next().key);
        }
        assertEquals(items, results.size());
    }

    @Test
    public void shouldIterateOverRange() throws Exception {
        int items = addItemsToCache();
        final KeyValueIterator<String, String> range = store.range(String.valueOf(0), String.valueOf(items));
        final List<String> results = new ArrayList<>();
        while (range.hasNext()) {
            results.add(range.next().key);
        }
        assertEquals(items, results.size());
    }

    @Test
    public void shouldDeleteItemsFromCache() throws Exception {
        store.put("a", "a");
        store.delete("a");
        assertNull(store.get("a"));
        assertFalse(store.range("a", "b").hasNext());
        assertFalse(store.all().hasNext());
    }

    @Test
    public void shouldNotShowItemsDeletedFromCacheButFlushedToStoreBeforeDelete() throws Exception {
        store.put("a", "a");
        store.flush();
        store.delete("a");
        assertNull(store.get("a"));
        assertFalse(store.range("a", "b").hasNext());
        assertFalse(store.all().hasNext());
    }

    @Test
    public void shouldClearNamespaceCacheOnClose() throws Exception {
        store.put("a", "a");
        assertEquals(1, cache.size());
        store.close();
        assertEquals(0, cache.size());
    }

    @Test(expected = InvalidStateStoreException.class)
    public void shouldThrowIfTryingToGetFromClosedCachingStore() throws Exception {
        store.close();
        store.get("a");
    }

    @Test(expected = InvalidStateStoreException.class)
    public void shouldThrowIfTryingToWriteToClosedCachingStore() throws Exception {
        store.close();
        store.put("a", "a");
    }

    @Test(expected = InvalidStateStoreException.class)
    public void shouldThrowIfTryingToDoRangeQueryOnClosedCachingStore() throws Exception {
        store.close();
        store.range("a", "b");
    }

    @Test(expected = InvalidStateStoreException.class)
    public void shouldThrowIfTryingToDoAllQueryOnClosedCachingStore() throws Exception {
        store.close();
        store.all();
    }

    @Test(expected = InvalidStateStoreException.class)
    public void shouldThrowIfTryingToDoGetApproxSizeOnClosedCachingStore() throws Exception {
        store.close();
        store.approximateNumEntries();
    }

    @Test(expected = InvalidStateStoreException.class)
    public void shouldThrowIfTryingToDoPutAllClosedCachingStore() throws Exception {
        store.close();
        store.putAll(Collections.singletonList(KeyValue.pair("a", "a")));
    }

    @Test(expected = InvalidStateStoreException.class)
    public void shouldThrowIfTryingToDoPutIfAbsentClosedCachingStore() throws Exception {
        store.close();
        store.putIfAbsent("b", "c");
    }

    @Test(expected = NullPointerException.class)
    public void shouldThrowNullPointerExceptionOnPutWithNullKey() {
        store.put(null, "c");
    }

    @Test(expected = NullPointerException.class)
    public void shouldThrowNullPointerExceptionOnPutIfAbsentWithNullKey() {
        store.putIfAbsent(null, "c");
    }

    @Test
    public void shouldThrowNullPointerExceptionOnPutAllWithNullKey() {
        List<KeyValue<String, String>> entries = new ArrayList<>();
        entries.add(new KeyValue<String, String>(null, "a"));
        try {
            store.putAll(entries);
            fail("Should have thrown NullPointerException while putAll null key");
        } catch (NullPointerException e) { }
    }

    @Test
    public void shouldPutIfAbsent() {
        store.putIfAbsent("b", "2");
        assertTrue(store.get("b").equals("2"));

        store.putIfAbsent("b", "3");
        assertTrue(store.get("b").equals("2"));
    }

    @Test
    public void shouldPutAll() {
        List<KeyValue<String, String>> entries = new ArrayList<>();
        entries.add(new KeyValue<>("a", "1"));
        entries.add(new KeyValue<>("b", "2"));
        store.putAll(entries);
        assertEquals(store.get("a"), "1");
        assertEquals(store.get("b"), "2");
    }

    @Test
    public void shouldReturnUnderlying() {
        assertTrue(store.underlying().equals(underlyingStore));
    }

    @Test(expected = InvalidStateStoreException.class)
    public void shouldThrowIfTryingToDeleteFromClosedCachingStore() throws Exception {
        store.close();
        store.delete("key");
    }

    private int addItemsToCache() throws IOException {
        int cachedSize = 0;
        int i = 0;
        while (cachedSize < maxCacheSizeBytes) {
            final String kv = String.valueOf(i++);
            store.put(kv, kv);
            cachedSize += memoryCacheEntrySize(kv.getBytes(), kv.getBytes(), topic);
        }
        return i;
    }

    public static class CacheFlushListenerStub<K, V> implements CacheFlushListener<K, V> {
        final Map<K, Change<V>> forwarded = new HashMap<>();

        @Override
        public void apply(final K key, final V newValue, final V oldValue) {
            forwarded.put(key, new Change<>(newValue, oldValue));
        }
    }
}