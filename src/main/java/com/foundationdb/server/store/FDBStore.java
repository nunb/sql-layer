/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.foundationdb.server.store;

import com.foundationdb.ais.model.Group;
import com.foundationdb.ais.model.GroupIndex;
import com.foundationdb.ais.model.HasStorage;
import com.foundationdb.ais.model.Index;
import com.foundationdb.ais.model.Sequence;
import com.foundationdb.ais.model.StorageDescription;
import com.foundationdb.ais.model.Table;
import com.foundationdb.ais.model.TableName;
import com.foundationdb.ais.util.TableChangeValidator.ChangeLevel;
import com.foundationdb.qp.storeadapter.FDBAdapter;
import com.foundationdb.qp.storeadapter.PersistitHKey;
import com.foundationdb.qp.storeadapter.indexrow.PersistitIndexRow;
import com.foundationdb.qp.storeadapter.indexrow.PersistitIndexRowBuffer;
import com.foundationdb.qp.rowtype.IndexRowType;
import com.foundationdb.qp.rowtype.Schema;
import com.foundationdb.qp.util.SchemaCache;
import com.foundationdb.server.FDBTableStatusCache;
import com.foundationdb.server.error.AkibanInternalException;
import com.foundationdb.server.error.DuplicateKeyException;
import com.foundationdb.server.error.FDBAdapterException;
import com.foundationdb.server.error.QueryCanceledException;
import com.foundationdb.server.rowdata.FieldDef;
import com.foundationdb.server.rowdata.RowData;
import com.foundationdb.server.rowdata.RowDef;
import com.foundationdb.server.service.Service;
import com.foundationdb.server.service.config.ConfigurationService;
import com.foundationdb.server.service.listener.ListenerService;
import com.foundationdb.server.service.metrics.LongMetric;
import com.foundationdb.server.service.metrics.MetricsService;
import com.foundationdb.server.service.session.Session;
import com.foundationdb.server.service.transaction.TransactionService;
import com.foundationdb.server.store.FDBTransactionService.TransactionState;
import com.foundationdb.server.store.format.FDBStorageDescription;
import com.foundationdb.server.util.ReadWriteMap;
import com.foundationdb.FDBException;
import com.foundationdb.KeySelector;
import com.foundationdb.KeyValue;
import com.foundationdb.MutationType;
import com.foundationdb.Range;
import com.foundationdb.ReadTransaction;
import com.foundationdb.Transaction;
import com.foundationdb.async.AsyncIterator;
import com.foundationdb.async.Function;
import com.foundationdb.tuple.ByteArrayUtil;
import com.foundationdb.tuple.Tuple;
import com.foundationdb.util.layers.DirectorySubspace;
import com.google.inject.Inject;
import com.persistit.Key;
import com.persistit.Persistit;
import com.persistit.Value;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Directory usage:
 * <pre>
 * &lt;root_dir&gt;/
 *   indexCount/
 *   indexNull/
 * </pre>
 *
 * <p>
 *     The above directories are used in servicing per-group index row counts
 *     and NULL-able UNIQUE index separator values, respectively. The former
 *     is stored as a little endian long, for {@link Transaction#mutate} usage,
 *     and the latter is stored as {@link Tuple} encoded long. The keys for
 *     each entry are created by pre-pending the directory prefix onto the
 *     prefix of the given index.
 * </p>
 */
public class FDBStore extends AbstractStore<FDBStore,FDBStoreData,FDBStorageDescription> implements Service {
    private static final Tuple INDEX_COUNT_DIR_PATH = Tuple.from("indexCount");
    private static final Tuple INDEX_NULL_DIR_PATH = Tuple.from("indexNull");
    private static final Logger LOG = LoggerFactory.getLogger(FDBStore.class);

    private final FDBHolder holder;
    private final ConfigurationService configService;
    private final FDBSchemaManager schemaManager;
    private final FDBTransactionService txnService;
    private final MetricsService metricsService;

    private static final String ROWS_FETCHED_METRIC = "SQLLayerRowsFetched";
    private static final String ROWS_STORED_METRIC = "SQLLayerRowsStored";
    private static final String ROWS_CLEARED_METRIC = "SQLLayerRowsCleared";
    private LongMetric rowsFetchedMetric, rowsStoredMetric, rowsClearedMetric;

    private DirectorySubspace rootDir;
    private byte[] packedIndexCountPrefix;
    private byte[] packedIndexNullPrefix;


    @Inject
    public FDBStore(FDBHolder holder,
                    ConfigurationService configService,
                    SchemaManager schemaManager,
                    TransactionService txnService,
                    ListenerService listenerService,
                    MetricsService metricsService) {
        super(schemaManager, listenerService);
        this.holder = holder;
        this.configService = configService;
        if(schemaManager instanceof FDBSchemaManager) {
            this.schemaManager = (FDBSchemaManager)schemaManager;
        } else {
            throw new IllegalStateException("Only usable with FDBSchemaManager, found: " + txnService);
        }
        if(txnService instanceof FDBTransactionService) {
            this.txnService = (FDBTransactionService)txnService;
        } else {
            throw new IllegalStateException("Only usable with FDBTransactionService, found: " + txnService);
        }
        this.metricsService = metricsService;
    }

    public Iterator<KeyValue> groupIterator(Session session, Group group) {
        TransactionState txn = txnService.getTransaction(session);
        byte[] packedPrefix = prefixBytes(group);
        return txn.getTransaction().getRange(Range.startsWith(packedPrefix)).iterator();
    }

    // TODO: Creates range for hKey and descendants, add another API to specify
    public Iterator<KeyValue> groupIterator(Session session, Group group, Key hKey) {
        TransactionState txn = txnService.getTransaction(session);
        byte[] packedPrefix = packedTuple(group, hKey);
        byte[] packedAfter = packedTuple(group, hKey, Key.AFTER);
        return txn.getTransaction().getRange(packedPrefix, packedAfter).iterator();
    }

    public Iterator<KeyValue> groupIterator(Session session, Group group,
                                            int limit, KeyValue restart) {
        TransactionState txn = txnService.getTransaction(session);
        KeySelector begin, end;
        byte[] packedPrefix = prefixBytes(group);
        if (restart == null)
            begin = KeySelector.firstGreaterOrEqual(packedPrefix);
        else
            begin = KeySelector.firstGreaterThan(restart.getKey());
        end = KeySelector.firstGreaterOrEqual(ByteArrayUtil.strinc(packedPrefix));
        return txn.getTransaction().getRange(begin, end, limit).iterator();
    }

    public Iterator<KeyValue> indexIterator(Session session, Index index, boolean reverse) {
        TransactionState txn = txnService.getTransaction(session);
        byte[] packedPrefix = prefixBytes(index);
        return txn.getTransaction().getRange(
            Range.startsWith(packedPrefix),
            Transaction.ROW_LIMIT_UNLIMITED,
            reverse
        ).iterator();
    }

    public Iterator<KeyValue> indexIterator(Session session, Index index, Key key, boolean inclusive, boolean reverse) {
        TransactionState txn = txnService.getTransaction(session);
        byte[] packedEdge = prefixBytes(index);
        byte[] packedKey = packedTuple(index, key);

        // begin and end always need to be ordered properly (i.e begin less than end).
        // End values are *always* exclusive and KeySelector just picks which key ends up there (note strinc on edges).
        final KeySelector begin, end;
        if(inclusive) {
            if(reverse) {
                begin = KeySelector.firstGreaterThan(packedEdge);
                end = KeySelector.firstGreaterThan(packedKey);
            } else {
                begin = KeySelector.firstGreaterOrEqual(packedKey);
                end = KeySelector.firstGreaterThan(ByteArrayUtil.strinc(packedEdge));
            }
        } else {
            if(reverse) {
                begin = KeySelector.firstGreaterThan(packedEdge);
                end = KeySelector.firstGreaterOrEqual(packedKey);
            } else {
                begin = KeySelector.firstGreaterThan(packedKey);
                end = KeySelector.firstGreaterThan(ByteArrayUtil.strinc(packedEdge));
            }
        }

        return txn.getTransaction().getRange(begin, end, Transaction.ROW_LIMIT_UNLIMITED, reverse).iterator();
    }

    @Override
    public long nextSequenceValue(Session session, Sequence sequence) {
        long rawValue = 0;
        SequenceCache cache = sequenceCache.getOrCreateAndPut(sequence.getStorageUniqueKey(), SEQUENCE_CACHE_VALUE_CREATOR);
        cache.cacheLock();
        try {
            rawValue = cache.nextCacheValue();
            if (rawValue < 0) {
                rawValue = updateCacheFromServer (session, cache, sequence);
            }
        } finally {
            cache.cacheUnlock();
        }
        long outValue = sequence.realValueForRawNumber(rawValue);
        return outValue;
    }

    // insert or update the sequence value from the server.
    // Works only under the cache lock from nextSequenceValue. 
    private long updateCacheFromServer (Session session, final SequenceCache cache, final Sequence sequence) {
        final long [] rawValue = new long[1];
        
        try {
            txnService.runTransaction(new Function<Transaction,Void> (){
                @Override 
                public Void apply (Transaction tr) {
                    byte[] prefixBytes = prefixBytes(sequence);
                    byte[] byteValue = tr.get(prefixBytes).get();
                    if(byteValue != null) {
                        Tuple tuple = Tuple.fromBytes(byteValue);
                        rawValue[0] = tuple.getLong(0);
                    } else {
                        rawValue[0] = 1;
                    }
                    tr.set(prefixBytes, Tuple.from(rawValue[0] + sequence.getCacheSize()).pack());
                    return null;
                }
            });
        } catch (Exception e) {
            throw FDBAdapter.wrapFDBException(session, e);
        }
        cache.updateCache(rawValue[0], sequence.getCacheSize());
        return rawValue[0];
    }
    
    @Override
    public long curSequenceValue(Session session, Sequence sequence) {
        long rawValue = 0;
        
        SequenceCache cache = sequenceCache.get(sequence.getStorageUniqueKey());
        if (cache != null) {
            rawValue = cache.currentValue();
        } else {
            TransactionState txn = txnService.getTransaction(session);
            byte[] byteValue = txn.getTransaction().get(prefixBytes(sequence)).get();
            if(byteValue != null) {
                Tuple tuple = Tuple.fromBytes(byteValue);
                rawValue = tuple.getLong(0);
            }
        }
        return sequence.realValueForRawNumber(rawValue);
    }

    private final ReadWriteMap<Object, SequenceCache> sequenceCache = 
            ReadWriteMap.wrapFair(new HashMap<Object, SequenceCache>());

    public long getGICount(Session session, GroupIndex index) {
        TransactionState txn = txnService.getTransaction(session);
        return getGICountInternal(txn.getTransaction(), index);
    }

    public long getGICountApproximate(Session session, GroupIndex index) {
        TransactionState txn = txnService.getTransaction(session);
        return getGICountInternal(txn.getTransaction().snapshot(), index);
    }

    public static void expandRowData(RowData rowData, KeyValue kv, boolean copyBytes) {
        expandRowData(rowData, kv.getValue(), copyBytes);
    }

    public static void expandRowData(RowData rowData, byte[] value, boolean copyBytes) {
        if(copyBytes) {
            byte[] rowBytes = rowData.getBytes();
            if((rowBytes == null) || (rowBytes.length < value.length)) {
                rowBytes = Arrays.copyOf(value, value.length);
                rowData.reset(rowBytes);
            } else {
                System.arraycopy(value, 0, rowBytes, 0, value.length);
                rowData.reset(0, value.length);
            }
        } else {
            rowData.reset(value);
        }
        rowData.prepareRow(0);
    }

    public void setRollbackPending(Session session) {
        if(txnService.isTransactionActive(session)) {
            txnService.setRollbackPending(session);
        }
    }


    //
    // Service
    //

    @Override
    public void start() {
        rowsFetchedMetric = metricsService.addLongMetric(ROWS_FETCHED_METRIC);
        rowsStoredMetric = metricsService.addLongMetric(ROWS_STORED_METRIC);
        rowsClearedMetric = metricsService.addLongMetric(ROWS_CLEARED_METRIC);

        rootDir = holder.getRootDirectory();
        packedIndexCountPrefix = holder.getDatabase().run(new Function<Transaction, byte[]>()
        {
            @Override
            public byte[] apply(Transaction txn) {
                DirectorySubspace dirSub = holder.getRootDirectory().createOrOpen(txn, INDEX_COUNT_DIR_PATH);
                return dirSub.pack();
            }
        });
        packedIndexNullPrefix = holder.getDatabase().run(new Function<Transaction, byte[]>()
        {
            @Override
            public byte[] apply(Transaction txn) {
                DirectorySubspace dirSub = holder.getRootDirectory().createOrOpen(txn, INDEX_NULL_DIR_PATH);
                return dirSub.pack();
            }
        });
    }

    @Override
    public void stop() {
    }

    @Override
    public void crash() {
    }


    //
    // Store
    //

    @Override
    public FDBStoreData createStoreData(Session session, FDBStorageDescription storageDescription) {
        return new FDBStoreData(storageDescription, createKey());
    }

    @Override
    protected void releaseStoreData(Session session, FDBStoreData storeData) {
        // None
    }

    @Override
    FDBStorageDescription getStorageDescription(FDBStoreData storeData) {
        return storeData.storageDescription;
    }

    @Override
    protected Key getKey(Session session, FDBStoreData storeData) {
        return storeData.key;
    }

    @Override
    protected void store(Session session, FDBStoreData storeData) {
        TransactionState txn = txnService.getTransaction(session);
        byte[] packedKey = packedTuple(storeData.storageDescription, storeData.key);
        byte[] value;
        if(storeData.persistitValue != null) {
            value = Arrays.copyOf(storeData.persistitValue.getEncodedBytes(), storeData.persistitValue.getEncodedSize());
        } else {
            value = storeData.value;
        }
        txn.setBytes(packedKey, value);
        rowsStoredMetric.increment();
    }

    @Override
    protected boolean fetch(Session session, FDBStoreData storeData) {
        TransactionState txn = txnService.getTransaction(session);
        byte[] packedKey = packedTuple(storeData.storageDescription, storeData.key);
        storeData.value = txn.getTransaction().get(packedKey).get();
        rowsFetchedMetric.increment();
        return (storeData.value != null);
    }

    @Override
    protected boolean clear(Session session, FDBStoreData storeData) {
        TransactionState txn = txnService.getTransaction(session);
        byte[] packed = packedTuple(storeData.storageDescription, storeData.key);
        // TODO: Remove get when clear() API changes
        boolean existed = (txn.getTransaction().get(packed).get() != null);
        txn.getTransaction().clear(packed);
        rowsClearedMetric.increment();
        return existed;
    }

    @Override
    void resetForWrite(FDBStoreData storeData, Index index, PersistitIndexRowBuffer indexRowBuffer) {
        if(storeData.persistitValue == null) {
            storeData.persistitValue = new Value((Persistit) null);
        }
        indexRowBuffer.resetForWrite(index, storeData.key, storeData.persistitValue);
    }

    @Override
    protected Iterator<Void> createDescendantIterator(Session session, final FDBStoreData storeData) {
        TransactionState txn = txnService.getTransaction(session);
        byte[] packedBegin = packedTuple(storeData.storageDescription, storeData.key, Key.BEFORE);
        byte[] packedEnd = packedTuple(storeData.storageDescription, storeData.key, Key.AFTER);
        storeData.it = txn.getTransaction().getRange(packedBegin, packedEnd).iterator();
        return new Iterator<Void>() {
            @Override
            public boolean hasNext() {
                return storeData.it.hasNext();
            }

            @Override
            public Void next() {
                KeyValue kv = storeData.it.next();
                unpackTuple(storeData.storageDescription, storeData.key, kv.getKey());
                storeData.value = kv.getValue();
                return null;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Override
    protected void sumAddGICount(Session session, FDBStoreData storeData, GroupIndex index, int count) {
        TransactionState txn = txnService.getTransaction(session);
        txn.getTransaction().mutate(
            MutationType.ADD,
            packedTupleGICount(index),
            FDBTableStatusCache.packForAtomicOp(count)
        );
    }

    @Override
    protected PersistitIndexRowBuffer readIndexRow(Session session,
                                                   Index parentPKIndex,
                                                   FDBStoreData storeData,
                                                   RowDef childRowDef,
                                                   RowData childRowData) {
        Key parentPkKey = storeData.key;
        PersistitKeyAppender keyAppender = PersistitKeyAppender.create(parentPkKey);
        int[] fields = childRowDef.getParentJoinFields();
        for(int field : fields) {
            FieldDef fieldDef = childRowDef.getFieldDef(field);
            keyAppender.append(fieldDef, childRowData);
        }

        TransactionState txn = txnService.getTransaction(session);
        byte[] pkValue = txn.getTransaction().get(packedTuple(parentPKIndex, parentPkKey)).get();
        PersistitIndexRowBuffer indexRow = null;
        if (pkValue != null) {
            Value value = new Value((Persistit)null);
            value.putEncodedBytes(pkValue, 0, pkValue.length);
            indexRow = new PersistitIndexRowBuffer(this);
            indexRow.resetForRead(parentPKIndex, parentPkKey, value);
        }
        return indexRow;
    }

    @Override
    protected void writeIndexRow(Session session,
                                 Index index,
                                 RowData rowData,
                                 Key hKey,
                                 PersistitIndexRowBuffer indexRow) {
        TransactionState txn = txnService.getTransaction(session);
        Key indexKey = createKey();
        constructIndexRow(session, indexKey, rowData, index, hKey, indexRow, true);
        checkUniqueness(session, txn, index, rowData, indexKey);

        byte[] packedKey = packedTuple(index, indexRow.getPKey());
        byte[] packedValue = Arrays.copyOf(indexRow.getValue().getEncodedBytes(), indexRow.getValue().getEncodedSize());
        txn.setBytes(packedKey, packedValue);
    }

    @Override
    protected void deleteIndexRow(Session session,
                                  Index index,
                                  RowData rowData,
                                  Key hKey,
                                  PersistitIndexRowBuffer indexRowBuffer) {
        TransactionState txn = txnService.getTransaction(session);
        Key indexKey = createKey();
        // See big note in PersistitStore#deleteIndexRow() about format.
        if(index.isUniqueAndMayContainNulls()) {
            // IndexRow is used below, use these as intermediates.
            Key spareKey = indexRowBuffer.getPKey();
            Value spareValue = indexRowBuffer.getValue();
            if(spareKey == null) {
                spareKey = createKey();
            }
            if(spareValue == null) {
                spareValue = new Value((Persistit)null);
            }
            // Can't use a PIRB, because we need to get the hkey. Need a PersistitIndexRow.
            FDBAdapter adapter = createAdapter(session, SchemaCache.globalSchema(getAIS(session)));
            IndexRowType indexRowType = adapter.schema().indexRowType(index);
            PersistitIndexRow indexRow = adapter.takeIndexRow(indexRowType);
            constructIndexRow(session, indexKey, rowData, index, hKey, indexRow, false);
            // indexKey contains index values + 0 for null sep. Start there and iterate until we find hkey.
            Range r = new Range(packedTuple(index, indexKey), ByteArrayUtil.strinc(prefixBytes(index)));
            for(KeyValue kv : txn.getTransaction().getRange(r)) {
                // Key
                unpackTuple(index, spareKey, kv.getKey());
                // Value
                byte[] valueBytes = kv.getValue();
                spareValue.clear();
                spareValue.putEncodedBytes(valueBytes, 0, valueBytes.length);
                // Delicate: copyFromKeyValue initializes the key returned by hKey
                indexRow.copyFrom(spareKey, spareValue);
                PersistitHKey rowHKey = (PersistitHKey)indexRow.hKey();
                if(rowHKey.key().compareTo(hKey) == 0) {
                    txn.getTransaction().clear(kv.getKey());
                    break;
                }
            }
            adapter.returnIndexRow(indexRow);
        } else {
            constructIndexRow(session, indexKey, rowData, index, hKey, indexRowBuffer, false);
            txn.getTransaction().clear(packedTuple(index, indexKey));
        }
    }

    @Override
    protected void preWrite(Session session, FDBStoreData storeData, RowDef rowDef, RowData rowData) {
        // None
    }

    @Override
    public void truncateTree(Session session, HasStorage object) {
        TransactionState txn = txnService.getTransaction(session);
        txn.getTransaction().clear(Range.startsWith(prefixBytes(object)));
    }

    @Override
    public void deleteIndexes(Session session, Collection<? extends Index> indexes) {
        Transaction txn = txnService.getTransaction(session).getTransaction();
        for(Index index : indexes) {
            rootDir.removeIfExists(txn, FDBNameGenerator.dataPath(index));
            if(index.isGroupIndex()) {
                txn.clear(packedTupleGICount((GroupIndex)index));
            }
        }
    }

    @Override
    public void removeTrees(Session session, Table table) {
        Transaction txn = txnService.getTransaction(session).getTransaction();
        // Table and indexes (and group and group indexes if root table)
        rootDir.removeIfExists(txn, FDBNameGenerator.dataPath(table.getName()));
        // Sequence
        if(table.getIdentityColumn() != null) {
            deleteSequences(session, Collections.singleton(table.getIdentityColumn().getIdentityGenerator()));
        }
    }

    @Override
    public void removeTree(Session session, HasStorage object) {
        truncateTree(session, object);
        if(object instanceof Index) {
            Index index = (Index)object;
            if(index.isGroupIndex()) {
                TransactionState txn = txnService.getTransaction(session);
                txn.getTransaction().clear(packedTupleGICount((GroupIndex)index));
            }
        }
    }

    @Override
    public void truncateIndexes(Session session, Collection<? extends Index> indexes) {
        TransactionState txn = txnService.getTransaction(session);
        for(Index index : indexes) {
            truncateTree(session, index);
            if(index.isGroupIndex()) {
                txn.setBytes(packedTupleGICount((GroupIndex)index), FDBTableStatusCache.packForAtomicOp(0));
            }
        }
    }

    @Override
    public void deleteSequences(Session session, Collection<? extends Sequence> sequences) {
        for (Sequence sequence : sequences) {
            sequenceCache.remove(sequence.getStorageUniqueKey());
            rootDir.removeIfExists(
                txnService.getTransaction(session).getTransaction(),
                FDBNameGenerator.dataPath(sequence)
            );
        }
    }

    @Override
    public FDBAdapter createAdapter(Session session, Schema schema) {
        return new FDBAdapter(this, schema, session, txnService, configService);
    }

    @Override
    public boolean treeExists(Session session, StorageDescription storageDescription) {
        TransactionState txn = txnService.getTransaction(session);
        return txn.getTransaction().getRange(Range.startsWith(prefixBytes((FDBStorageDescription)storageDescription)), 1).iterator().hasNext();
    }

    @Override
    public boolean isRetryableException(Throwable t) {
        if(t instanceof FDBAdapterException) {
            t = t.getCause();
        }
        if(t instanceof FDBException) {
            int code = ((FDBException)t).getCode();
            // not_committed || commit_unknown_result
            return (code == 1020) || (code == 1021);
        }
        return false;
    }

    // TODO: A little ugly and slow, but unique indexes will get refactored soon and need for separator goes away.
    @Override
    public long nullIndexSeparatorValue(Session session, final Index index) {
        // New txn to avoid spurious conflicts
        return holder.getDatabase().run(new Function<Transaction,Long>() {
            @Override
            public Long apply(Transaction txn) {
                byte[] keyBytes = ByteArrayUtil.join(packedIndexNullPrefix, prefixBytes(index));
                byte[] valueBytes = txn.get(keyBytes).get();
                long outValue = 1;
                if(valueBytes != null) {
                    outValue += Tuple.fromBytes(valueBytes).getLong(0);
                }
                txn.set(keyBytes, Tuple.from(outValue).pack());
                return outValue;
            }
        });
    }

    @Override
    public void finishedAlter(Session session, Map<TableName, TableName> tableNames, ChangeLevel changeLevel) {
        if(changeLevel == ChangeLevel.NONE) {
            return;
        }

        Transaction txn = txnService.getTransaction(session).getTransaction();
        for(Entry<TableName, TableName> entry : tableNames.entrySet()) {
            TableName oldName = entry.getKey();
            TableName newName = entry.getValue();

            Tuple dataPath = FDBNameGenerator.dataPath(oldName);
            Tuple alterPath = FDBNameGenerator.alterPath(newName);

            switch(changeLevel) {
                case METADATA:
                case METADATA_NOT_NULL:
                    // - move renamed directories
                    if(!oldName.equals(newName)) {
                        schemaManager.renamingTable(session, oldName, newName);
                    }
                break;
                case INDEX:
                    if(!rootDir.exists(txn, alterPath)) {
                        continue;
                    }
                    // - Move everything from dataAltering/foo/ to data/foo/
                    // - remove dataAltering/foo/
                    for(Object subPath : rootDir.list(txn, alterPath)) {
                        Tuple subDataPath = dataPath.addObject(subPath);
                        Tuple subAlterPath = alterPath.addObject(subPath);
                        rootDir.move(txn, subAlterPath, subDataPath);
                    }
                    rootDir.remove(txn, alterPath);
                break;
                case TABLE:
                case GROUP:
                    if(!rootDir.exists(txn, alterPath)) {
                        continue;
                    }
                    // - move everything from data/foo/ to dataAltering/foo/
                    // - remove data/foo
                    // - move dataAltering/foo to data/foo/
                    if(rootDir.exists(txn, dataPath)) {
                        for(Object subPath : rootDir.list(txn, dataPath)) {
                            Tuple subDataPath = dataPath.addObject(subPath);
                            Tuple subAlterPath = alterPath.addObject(subPath);
                            if(!rootDir.exists(txn, subAlterPath)) {
                                rootDir.move(txn, subDataPath, subAlterPath);
                            }
                        }
                        rootDir.remove(txn, dataPath);
                    }
                    rootDir.move(txn, alterPath, dataPath);
                break;
                default:
                    throw new AkibanInternalException("Unexpected ChangeLevel: " + changeLevel);
            }
        }
    }

    @Override
    public void traverse(Session session, Group group, TreeRecordVisitor visitor) {
        TransactionState txn = txnService.getTransaction(session);
        visitor.initialize(session, this);
        FDBStoreData storeData = createStoreData(session, group);
        for(KeyValue kv : txn.getTransaction().getRange(Range.startsWith(prefixBytes(group)))) {
            RowData rowData = new RowData();
            expandGroupData(storeData, rowData, kv);
            visitor.visit(storeData.key, rowData);
        }
    }

    public void expandGroupData(FDBStoreData storeData, RowData rowData, KeyValue kv) {
        unpackTuple(storeData.storageDescription, storeData.key, kv.getKey());
        storeData.value = kv.getValue();
        expandRowData(storeData, rowData);
    }

    @Override
    public <V extends IndexVisitor<Key, Value>> V traverse(Session session, Index index, V visitor, long scanTimeLimit, long sleepTime) {
        Key key = createKey();
        Value value = new Value((Persistit)null);
        TransactionState txn = txnService.getTransaction(session);
        long nextCommitTime = 0;
        if (scanTimeLimit >= 0) {
            nextCommitTime = txn.getStartTime() + scanTimeLimit;
        }
        byte[] packedPrefix = prefixBytes(index);
        KeySelector start = KeySelector.firstGreaterOrEqual(packedPrefix);
        KeySelector end = KeySelector.firstGreaterThan(ByteArrayUtil.strinc(packedPrefix));
        Iterator<KeyValue> it = txn.getTransaction().getRange(start, end).iterator();
        while(it.hasNext()) {
            KeyValue kv = it.next();

            // Key
            unpackTuple(index, key, kv.getKey());

            // Value
            value.clear();
            byte[] valueBytes = kv.getValue();
            value.putEncodedBytes(valueBytes, 0, valueBytes.length);
            // Visit
            visitor.visit(key, value);

            if ((scanTimeLimit >= 0) &&
                (System.currentTimeMillis() >= nextCommitTime)) {
                ((AsyncIterator)it).dispose();
                txn.getTransaction().commit().get();
                if (sleepTime > 0) {
                    try {
                        Thread.sleep(sleepTime);
                    }
                    catch (InterruptedException ex) {
                        throw new QueryCanceledException(session);
                    }
                }
                txn.reset();
                txn.getTransaction().reset();
                nextCommitTime = txn.getStartTime() + scanTimeLimit;
                start = KeySelector.firstGreaterThan(kv.getKey());
                it = txn.getTransaction().getRange(start, end).iterator();
            }            
        }
        return visitor;
    }

    @Override
    public String getName() {
        return "FoundationDB APIv" + holder.getAPIVersion();
    }


    //
    // KeyCreator
    //

    @Override
    public Key createKey() {
        return new Key(null, 2047);
    }


    //
    // Internal
    //

    private void constructIndexRow(Session session,
                                   Key indexKey,
                                   RowData rowData,
                                   Index index,
                                   Key hKey,
                                   PersistitIndexRowBuffer indexRow,
                                   boolean forInsert) {
        indexKey.clear();
        indexRow.resetForWrite(index, indexKey, new Value((Persistit) null));
        indexRow.initialize(rowData, hKey);
        indexRow.close(session, this, forInsert);
    }

    private void checkUniqueness(Session session, TransactionState txn, Index index, RowData rowData, Key key) {
        if(index.isUnique() && !hasNullIndexSegments(rowData, index)) {
            int segmentCount = index.getKeyColumns().size();
            // An index that isUniqueAndMayContainNulls has the extra null-separating field.
            if (index.isUniqueAndMayContainNulls()) {
                segmentCount++;
            }
            key.setDepth(segmentCount);
            checkKeyDoesNotExistInIndex(session, txn, rowData, index, key);
        }
    }

    private void checkKeyDoesNotExistInIndex(Session session, TransactionState txn, RowData rowData, Index index, Key key) {
        assert index.isUnique() : index;
        FDBPendingIndexChecks.PendingCheck<?> check =
            FDBPendingIndexChecks.keyDoesNotExistInIndexCheck(session, txn, index, key);
        if (txn.getIndexChecks() == null) {
            check.blockUntilReady(txn);
            if (!check.check()) {
                // Using RowData, can give better error than check.throwException().
                String msg = formatIndexRowString(session, rowData, index);
                throw new DuplicateKeyException(index.getIndexName(), msg);
            }
        }
        else {
            txn.getIndexChecks().add(session, txn, index, check);
        }
    }

    //
    // Static
    //

    public static byte[] prefixBytes(HasStorage object) {
        return prefixBytes((FDBStorageDescription)object.getStorageDescription());
    }

    public static byte[] prefixBytes(FDBStorageDescription storageDescription) {
        return storageDescription.getPrefixBytes();
    }

    public static void unpackTuple(HasStorage object, Key key, byte[] tupleBytes) {
        unpackTuple((FDBStorageDescription)object.getStorageDescription(),
                    key, tupleBytes);
    }

    public static void unpackTuple(FDBStorageDescription storageDescription, Key key, byte[] tupleBytes) {
        byte[] treeBytes = prefixBytes(storageDescription);
        // TODO: Use fromBytes(byte[],int,int) when available.
        Tuple tuple = Tuple.fromBytes(Arrays.copyOfRange(tupleBytes, treeBytes.length, tupleBytes.length));
        storageDescription.getTupleKey(tuple, key);
    }

    public static byte[] packedTuple(HasStorage object, Key key) {
        return packedTuple(object, key, null);
    }

    public static byte[] packedTuple(HasStorage object, Key key, Key.EdgeValue edge) {
        return packedTuple((FDBStorageDescription)object.getStorageDescription(), key, edge);
    }

    public static byte[] packedTuple(FDBStorageDescription storageDescription, Key key) {
        return packedTuple(storageDescription, key, null);
    }

    public static byte[] packedTuple(FDBStorageDescription storageDescription, Key key, Key.EdgeValue edge) {
        byte[] treeBytes = prefixBytes(storageDescription);
        byte[] keyBytes = storageDescription.getKeyBytes(key, edge);
        return ByteArrayUtil.join(treeBytes, keyBytes);
    }

    private byte[] packedTupleGICount(GroupIndex index) {
        return ByteArrayUtil.join(packedIndexCountPrefix, prefixBytes(index));
    }

    private long getGICountInternal(ReadTransaction txn, GroupIndex index) {
        byte[] key = packedTupleGICount(index);
        byte[] value = txn.get(key).get();
        return FDBTableStatusCache.unpackForAtomicOp(value);
    }

    private static final ReadWriteMap.ValueCreator<Object, SequenceCache> SEQUENCE_CACHE_VALUE_CREATOR =
            new ReadWriteMap.ValueCreator<Object, SequenceCache>() {
                public SequenceCache createValueForKey (Object key) {
                    return new SequenceCache();
                }
            };

    private static class SequenceCache {
        private long value; 
        private long cacheSize;
        private final ReentrantLock cacheLock;

        
        public SequenceCache() {
            this(0L, 1L);
        }
        
        public SequenceCache(long startValue, long cacheSize) {
            this.value = startValue;
            this.cacheSize = startValue + cacheSize; 
            this.cacheLock = new ReentrantLock(false);
        }
        
        public void updateCache (long startValue, long cacheSize) {
            this.value = startValue;
            this.cacheSize = startValue + cacheSize;
        }
        
        public long nextCacheValue() {
            if (++value == cacheSize) {
                // ensure the next call to nextCacheValue also fails
                // and will do so until the updateCache() is called. 
                --value;
                return -1;
            }
            return value;
        }
        public long currentValue() {
            return value;
        }
        
        public void cacheLock() {
            cacheLock.lock();
        }
        
        public void cacheUnlock() {
            cacheLock.unlock();
        }
    }
}
