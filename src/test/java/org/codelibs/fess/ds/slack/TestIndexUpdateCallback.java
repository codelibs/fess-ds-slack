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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codelibs.fess.ds.callback.IndexUpdateCallback;
import org.codelibs.fess.entity.DataStoreParams;

/**
 * Collects the documents handed to {@link IndexUpdateCallback#store} so that
 * tests can assert on what would have been indexed.
 */
public class TestIndexUpdateCallback implements IndexUpdateCallback {

    private final List<Map<String, Object>> dataMaps = new ArrayList<>();

    @Override
    public void store(final DataStoreParams paramMap, final Map<String, Object> dataMap) {
        dataMaps.add(new HashMap<>(dataMap));
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
     * Returns the collected documents in the order they were stored.
     *
     * @return the stored data maps
     */
    public List<Map<String, Object>> getDataMaps() {
        return dataMaps;
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
