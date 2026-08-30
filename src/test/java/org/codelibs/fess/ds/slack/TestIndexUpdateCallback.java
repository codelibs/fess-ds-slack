/*
 * Copyright 2012-2025 CodeLibs Project and the Others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.codelibs.fess.ds.slack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codelibs.fess.ds.callback.IndexUpdateCallback;
import org.codelibs.fess.entity.DataStoreParams;

/**
 * Collects the documents handed to {@link IndexUpdateCallback#store} so that
 * tests can assert on what would have been indexed.
 *
 * <p>
 * {@code store} runs on whichever worker thread {@code SlackDataStore}'s
 * {@code executorService} dispatches it to, so this class must tolerate
 * concurrent calls: the backing list is a {@linkplain Collections#synchronizedList
 * synchronized list}, and {@link #getDataMaps()} hands back a defensive
 * snapshot copy rather than the live list.
 * </p>
 */
public class TestIndexUpdateCallback implements IndexUpdateCallback {

    private final List<Map<String, Object>> dataMaps = Collections.synchronizedList(new ArrayList<>());

    private final List<DataStoreParams> paramMaps = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void store(final DataStoreParams paramMap, final Map<String, Object> dataMap) {
        dataMaps.add(new HashMap<>(dataMap));
        // Deliberately the live reference, not a copy: the point of recording it is to let a
        // test assert which instance arrived -- that each document brought its own copy and
        // that the shared one was never handed over. A defensive copy here would make both
        // assertions unwritable.
        paramMaps.add(paramMap);
    }

    @Override
    public long getExecuteTime() {
        return 0;
    }

    @Override
    public long getDocumentSize() {
        return dataMaps.size();
    }

    @Override
    public void commit() {
        // nothing to do
    }

    /**
     * Returns a snapshot copy of the collected documents, in the order they were stored.
     *
     * @return a defensive copy of the stored data maps
     */
    public List<Map<String, Object>> getDataMaps() {
        return new ArrayList<>(dataMaps);
    }

    /**
     * Returns a snapshot of the {@link DataStoreParams} instances handed to {@link #store}, in
     * call order. The elements are the instances themselves, not copies.
     *
     * @return the recorded parameter instances
     */
    public List<DataStoreParams> getParamMaps() {
        return new ArrayList<>(paramMaps);
    }

    /**
     * Returns the number of collected documents.
     *
     * @return the number of stored data maps
     */
    public int size() {
        return dataMaps.size();
    }
}
