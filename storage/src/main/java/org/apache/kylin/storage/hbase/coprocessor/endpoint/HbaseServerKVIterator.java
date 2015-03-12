/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.kylin.storage.hbase.coprocessor.endpoint;

import org.apache.commons.io.IOUtils;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.kylin.common.util.BytesUtil;
import org.apache.kylin.common.util.ClassUtil;
import org.apache.kylin.dict.Dictionary;
import org.apache.kylin.invertedindex.model.IIDesc;
import org.apache.kylin.invertedindex.model.KeyValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Created by honma on 11/10/14.
 */
public class HbaseServerKVIterator implements Iterable<KeyValuePair>, Closeable {

    private RegionScanner innerScanner;
    private Logger logger = LoggerFactory.getLogger(HbaseServerKVIterator.class);

    List<Cell> results = new ArrayList<Cell>();

    public HbaseServerKVIterator(RegionScanner innerScanner) {
        this.innerScanner = innerScanner;
    }

    @Override
    public void close() throws IOException {
        IOUtils.closeQuietly(this.innerScanner);
    }

    @Override
    public Iterator<KeyValuePair> iterator() {
        return new Iterator<KeyValuePair>() {

            ImmutableBytesWritable key = new ImmutableBytesWritable();
            ImmutableBytesWritable value = new ImmutableBytesWritable();
            ImmutableBytesWritable dict = new ImmutableBytesWritable();
            KeyValuePair pair = new KeyValuePair(key, value);

            private boolean hasMore = true;

            @Override
            public boolean hasNext() {
                return hasMore;
            }

            @Override
            public KeyValuePair next() {
                if (hasNext()) {
                    try {
                        hasMore = innerScanner.nextRaw(results);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }

                    if (results.size() < 1)
                        throw new IllegalStateException("Hbase row contains less than 1 cell");

                    Dictionary<?> dictionary = null;
                    boolean hasDictData = false;
                    for (Cell c : results) {
                        if (BytesUtil.compareBytes(IIDesc.HBASE_QUALIFIER_BYTES, 0, c.getQualifierArray(), c.getQualifierOffset(), IIDesc.HBASE_QUALIFIER_BYTES.length) == 0) {
                            key.set(c.getRowArray(), c.getRowOffset(), c.getRowLength());
                            value.set(c.getValueArray(), c.getValueOffset(), c.getValueLength());
                        } else if (BytesUtil.compareBytes(IIDesc.HBASE_DICTIONARY_BYTES, 0, c.getQualifierArray(), c.getQualifierOffset(), IIDesc.HBASE_DICTIONARY_BYTES.length) == 0) {
                            dict.set(c.getValueArray(), c.getValueOffset(), c.getValueLength());
                            hasDictData = true;
                        }
                    }
                    if (hasDictData) {
                        try {
                            final DataInputStream in = new DataInputStream(new ByteArrayInputStream(dict.get(), dict.getOffset(), dict.getLength()));
                            String type = in.readUTF();
                            dictionary = (Dictionary<?>) ClassUtil.forName(type, Dictionary.class).newInstance();
                            dictionary.readFields(in);
                        } catch (Exception e) {
                            logger.error("error create dictionary", e);
                        }
                    }
                    pair.setDictionary(dictionary);

                    results.clear();
                    return pair;
                } else {
                    return null;
                }
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

}