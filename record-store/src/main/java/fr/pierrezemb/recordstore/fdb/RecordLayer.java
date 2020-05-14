package fr.pierrezemb.recordstore.fdb;

import com.apple.foundationdb.record.CursorStreamingMode;
import com.apple.foundationdb.record.EvaluationContext;
import com.apple.foundationdb.record.ExecuteProperties;
import com.apple.foundationdb.record.IsolationLevel;
import com.apple.foundationdb.record.RecordCursor;
import com.apple.foundationdb.record.RecordMetaData;
import com.apple.foundationdb.record.RecordMetaDataBuilder;
import com.apple.foundationdb.record.ScanProperties;
import com.apple.foundationdb.record.TupleRange;
import com.apple.foundationdb.record.metadata.Index;
import com.apple.foundationdb.record.metadata.IndexTypes;
import com.apple.foundationdb.record.metadata.Key;
import com.apple.foundationdb.record.metadata.MetaDataEvolutionValidator;
import com.apple.foundationdb.record.metadata.expressions.KeyExpression;
import com.apple.foundationdb.record.metadata.expressions.VersionKeyExpression;
import com.apple.foundationdb.record.provider.foundationdb.FDBDatabase;
import com.apple.foundationdb.record.provider.foundationdb.FDBDatabaseFactory;
import com.apple.foundationdb.record.provider.foundationdb.FDBMetaDataStore;
import com.apple.foundationdb.record.provider.foundationdb.FDBQueriedRecord;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecord;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordContext;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordStore;
import com.apple.foundationdb.record.provider.foundationdb.keyspace.KeySpacePath;
import com.apple.foundationdb.record.provider.foundationdb.keyspace.ResolvedKeySpacePath;
import com.apple.foundationdb.record.query.RecordQuery;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryPlan;
import com.apple.foundationdb.tuple.Tuple;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import fr.pierrezemb.recordstore.fdb.metrics.FDBMetricsStoreTimer;
import fr.pierrezemb.recordstore.proto.RecordStoreProtocol;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

import static fr.pierrezemb.recordstore.fdb.UniversalIndexes.COUNT_INDEX;
import static fr.pierrezemb.recordstore.fdb.UniversalIndexes.COUNT_UPDATES_INDEX;
import static fr.pierrezemb.recordstore.fdb.UniversalIndexes.INDEX_COUNT_AGGREGATE_FUNCTION;
import static fr.pierrezemb.recordstore.fdb.UniversalIndexes.INDEX_COUNT_UPDATES_AGGREGATE_FUNCTION;

public class RecordLayer {
  private static final Logger LOGGER = LoggerFactory.getLogger(RecordLayer.class);
  private final FDBDatabase db;
  private final FDBMetricsStoreTimer timer;

  public RecordLayer(String clusterFilePath, boolean enableMetrics) throws InterruptedException, ExecutionException, TimeoutException {
    db = FDBDatabaseFactory.instance().getDatabase(clusterFilePath);
    db.performNoOpAsync().get(2, TimeUnit.SECONDS);
    System.out.println("connected to FDB!");
    timer = new FDBMetricsStoreTimer(enableMetrics);

  }

  /**
   * List all containers for a tenant
   */
  public List<String> listContainers(String tenantID) {
    FDBRecordContext context = db.openContext(Collections.singletonMap("tenant", tenantID), timer);
    KeySpacePath tenantKeySpace = RecordStoreKeySpace.getApplicationKeySpacePath(tenantID);
    System.out.println(tenantKeySpace);
    List<ResolvedKeySpacePath> containers = tenantKeySpace
      .listSubdirectory(context, "container", ScanProperties.FORWARD_SCAN);
    return containers.stream()
      .map(e -> e.getResolvedValue().toString())
      .collect(Collectors.toList());
  }

  /**
   * delete a container for a tenant
   */
  public void deleteContainer(String tenantID, String container) {
    FDBRecordContext context = db.openContext(Collections.singletonMap("tenant", tenantID), timer);
    FDBRecordStore.deleteStore(context, RecordStoreKeySpace.getDataKeySpacePath(tenantID, container));
    FDBRecordStore.deleteStore(context, RecordStoreKeySpace.getMetaDataKeySpacePath(tenantID, container));
    context.commit();
  }

  /**
   * get schema for a tenant and a container
   */
  public RecordMetaData getSchema(String tenantID, String container) {
    FDBRecordContext context = db.openContext(Collections.singletonMap("tenant", tenantID), timer);
    FDBMetaDataStore metaDataStore = RecordStoreMetaDataStore.createMetadataStore(context, tenantID, container);

    List<RecordStoreProtocol.IndexDescription> indexes = metaDataStore.getRecordMetaData().getAllIndexes().stream()
      .filter(e -> !e.getName().startsWith("global"))
      .map(e ->
        RecordStoreProtocol.IndexDescription.newBuilder()
          .build()
      ).collect(Collectors.toList());

    return metaDataStore.getRecordMetaData();
  }

  public List<RecordStoreProtocol.IndexDescription> getIndexes(String tenantID, String container) {
    FDBRecordContext context = db.openContext(Collections.singletonMap("tenant", tenantID), timer);
    FDBMetaDataStore metaDataStore = RecordStoreMetaDataStore.createMetadataStore(context, tenantID, container);

    return metaDataStore.getRecordMetaData().getAllIndexes().stream()
      .filter(e -> !e.getName().startsWith("global"))
      .map(e ->
        RecordStoreProtocol.IndexDescription.newBuilder()
          .build()
      ).collect(Collectors.toList());
  }

  public void upsertSchema(String tenantID, String container, String table, DescriptorProtos.FileDescriptorSet schema, List<RecordStoreProtocol.IndexDefinition> indexes, List<String> primaryKeyFields) throws Descriptors.DescriptorValidationException {
    FDBRecordContext context = db.openContext(Collections.singletonMap("tenant", tenantID), timer);
    FDBMetaDataStore metaDataStore = RecordStoreMetaDataStore.createMetadataStore(context, tenantID, container);

    RecordMetaData oldMetaData = null;
    int version = 0;
    try {
      oldMetaData = metaDataStore.getRecordMetaData();
      LOGGER.debug("metadata for {}:{} is in version {}", tenantID, container, oldMetaData.getVersion());
      version = oldMetaData.getVersion() + 1;
    } catch (FDBMetaDataStore.MissingMetaDataException e) {
      LOGGER.info("missing metadata, creating one");
    }

    RecordMetaData newRecordMetaData = createRecordMetaData(table, schema, indexes, primaryKeyFields, version, oldMetaData);

    // handling upgrade
    if (null != oldMetaData) {
      MetaDataEvolutionValidator metaDataEvolutionValidator = MetaDataEvolutionValidator.newBuilder()
        .setAllowIndexRebuilds(true)
        .setAllowMissingFormerIndexNames(false)
        .build();

      metaDataEvolutionValidator.validate(oldMetaData, newRecordMetaData);
    }

    // and save it
    metaDataStore.saveRecordMetaData(newRecordMetaData.getRecordMetaData().toProto());

    context.commit();

  }

  private RecordMetaData createRecordMetaData(String table, DescriptorProtos.FileDescriptorSet descriptorSet, List<RecordStoreProtocol.IndexDefinition> indexes, List<String> primaryKeyFields, int version, RecordMetaData oldMetadata) throws Descriptors.DescriptorValidationException {

    // retrieving protobuf descriptor
    RecordMetaDataBuilder metadataBuilder = RecordMetaData.newBuilder();

    for (DescriptorProtos.FileDescriptorProto fdp : descriptorSet.getFileList()) {
      Descriptors.FileDescriptor fd = Descriptors.FileDescriptor.buildFrom(fdp, new Descriptors.FileDescriptor[]{});
      // updating schema
      metadataBuilder.setRecords(fd);
    }

    // set options
    metadataBuilder.setVersion(version);
    metadataBuilder.setStoreRecordVersions(true);
    metadataBuilder.setSplitLongRecords(true);

    HashSet<Index> oldIndexes = oldMetadata != null ?
      new HashSet<>(oldMetadata.getAllIndexes()) :
      new HashSet<>();
    HashSet<String> oldIndexesNames = new HashSet<>();

    // add old indexes
    for (Index index : oldIndexes) {
      LOGGER.trace("adding old index {}", index.getName());
      oldIndexesNames.add(index.getName());
      metadataBuilder.addIndex(table, index);
    }

    // add new indexes
    for (RecordStoreProtocol.IndexDefinition indexDefinition : indexes) {
      String indexName = table + "_idx_" + indexDefinition.getField() + "_" + indexDefinition.getIndexType().toString();
      if (!oldIndexesNames.contains(indexName)) {
        LOGGER.trace("adding new index {} of type {}", indexName, indexDefinition.getIndexType());
        Index index = null;
        switch (indexDefinition.getIndexType()) {
          case VALUE:
            index = new Index(
              indexName,
              Key.Expressions.field(indexDefinition.getField()),
              IndexTypes.VALUE);
            break;
          // https://github.com/FoundationDB/fdb-record-layer/blob/e70d3f9b5cec1cf37b6f540d4e673059f2a628ab/fdb-record-layer-core/src/main/java/com/apple/foundationdb/record/provider/foundationdb/indexes/TextIndexMaintainer.java#L81-L93
          case TEXT_DEFAULT_TOKENIZER:
            index = new Index(
              indexName,
              Key.Expressions.field(indexDefinition.getField()),
              IndexTypes.TEXT);
            break;
          case VERSION:
            index = new Index(
              indexName,
              VersionKeyExpression.VERSION,
              IndexTypes.VERSION);
            break;
          case UNRECOGNIZED:
            continue;
        }
        metadataBuilder.addIndex(table, index);
      }
    }

    if (oldMetadata == null) {
      metadataBuilder.addUniversalIndex(COUNT_INDEX);
      metadataBuilder.addUniversalIndex(COUNT_UPDATES_INDEX);
    }

    // set primary key
    metadataBuilder.getRecordType(table)
      .setPrimaryKey(buildPrimaryKeyExpression(primaryKeyFields));

    return metadataBuilder.build();
  }

  private KeyExpression buildPrimaryKeyExpression(List<String> primaryKeyFields) {
    if (primaryKeyFields.size() == 1) {
      return Key.Expressions.field(primaryKeyFields.get(0));
    }

    return Key.Expressions.concat(
      primaryKeyFields
        .stream()
        .map(Key.Expressions::field)
        .collect(Collectors.toList())
    );
  }

  public Tuple getCountAndCountUpdates(String tenantID, String container) {
    FDBRecordContext context = db.openContext(Collections.singletonMap("tenant", tenantID), timer);
    FDBRecordStore r = createFDBRecordStore(context, tenantID, container);

    CompletableFuture<Tuple> countFuture = r.evaluateAggregateFunction(
      EvaluationContext.EMPTY,
      Collections.emptyList(),
      INDEX_COUNT_AGGREGATE_FUNCTION,
      TupleRange.ALL,
      IsolationLevel.SERIALIZABLE);

    CompletableFuture<Tuple> updateFuture = r.evaluateAggregateFunction(
      EvaluationContext.EMPTY,
      Collections.emptyList(),
      INDEX_COUNT_UPDATES_AGGREGATE_FUNCTION,
      TupleRange.ALL,
      IsolationLevel.SERIALIZABLE);

    return countFuture.thenCombine(updateFuture, (count, update)
      -> Tuple.from(count.getLong(0), update.getLong(0))).join();
  }

  public void putRecord(String tenantID, String container, String table, byte[] record) throws InvalidProtocolBufferException {
    FDBRecordContext context = db.openContext(Collections.singletonMap("tenant", tenantID), timer);
    // create recordStoreProvider
    FDBMetaDataStore metaDataStore = RecordStoreMetaDataStore.createMetadataStore(context, tenantID, container);

    // Helper func
    Function<FDBRecordContext, FDBRecordStore> recordStoreProvider = context2 -> FDBRecordStore.newBuilder()
      .setMetaDataProvider(metaDataStore)
      .setContext(context)
      .setKeySpacePath(RecordStoreKeySpace.getDataKeySpacePath(tenantID, container))
      .createOrOpen();

    FDBRecordStore r = recordStoreProvider.apply(context);
    Descriptors.Descriptor descriptor = metaDataStore.getRecordMetaData().getRecordsDescriptor().findMessageTypeByName(table);

    DynamicMessage msg = DynamicMessage.parseFrom(descriptor, record);

    r.saveRecord(msg);
    context.commit();
  }

  public void queryRecordsWithObserver(String tenantID, String container, RecordQuery query, StreamObserver<RecordStoreProtocol.QueryResponse> responseObserver) {
    FDBRecordContext context = db.openContext(Collections.singletonMap("tenant", tenantID), timer);
    FDBRecordStore r = createFDBRecordStore(context, tenantID, container);

    this.executeQuery(r, query, tenantID, container)
      .map(e -> {
        if (LOGGER.isTraceEnabled()) {
          LOGGER.trace("found record '{}' from {}/{}", e.getPrimaryKey(), tenantID, container);
        }
        return e;
      })
      .map(FDBRecord::getRecord)
      .map(Message::toByteString)
      .forEach(e -> responseObserver.onNext(RecordStoreProtocol.QueryResponse.newBuilder().setRecord(e).build()))
      .join();
  }

  private RecordCursor<FDBQueriedRecord<Message>> executeQuery(FDBRecordStore r, RecordQuery query, String tenantID, String container) {
    // TODO: handle errors instead of throwing null
    if (query == null) {
      LOGGER.error("query is null, skipping");
      throw new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("bad query"));
    }

    RecordQueryPlan plan = r.planQuery(query);
    LOGGER.info("running query for {}/{}: '{}'", tenantID, container, plan);

    ExecuteProperties.Builder executeProperties = ExecuteProperties.newBuilder()
      .setIsolationLevel(IsolationLevel.SERIALIZABLE)
      .setDefaultCursorStreamingMode(CursorStreamingMode.ITERATOR); // either WANT_ALL OR streaming mode

    executeProperties.setScannedBytesLimit(1_000_000); // 1MB

    return r.executeQuery(query, null, executeProperties.build());
  }

  public long deleteAllRecords(String tenantID, String container) {
    FDBRecordContext context = db.openContext(Collections.singletonMap("tenant", tenantID), timer);
    FDBRecordStore r = createFDBRecordStore(context, tenantID, container);
    r.deleteAllRecords();
    // TODO: return count of records with the call stats
    return 0;
  }

  public long deleteRecords(String tenantID, String container, RecordQuery query) {
    FDBRecordContext context = db.openContext(Collections.singletonMap("tenant", tenantID), timer);
    FDBRecordStore r = createFDBRecordStore(context, tenantID, container);

    Integer count = this.executeQuery(r, query, tenantID, container)
      .map(e -> {
        if (LOGGER.isTraceEnabled()) {
          LOGGER.trace("deleting {} from {}/{}", e.getPrimaryKey(), tenantID, container);
        }
        return e;
      })
      .map(e -> r.deleteRecord(e.getPrimaryKey()))
      .getCount().join();
    context.commit();
    return count;
  }

  public String getQueryPlan(String tenantID, String container, RecordQuery query) {
    FDBRecordContext context = db.openContext(Collections.singletonMap("tenant", tenantID), timer);
    FDBRecordStore r = createFDBRecordStore(context, tenantID, container);
    return r.planQuery(query).toString();
  }

  private FDBRecordStore createFDBRecordStore(FDBRecordContext context, String tenantID, String container) {

    // create recordStoreProvider
    FDBMetaDataStore metaDataStore = RecordStoreMetaDataStore.createMetadataStore(context, tenantID, container);

    // Helper func
    Function<FDBRecordContext, FDBRecordStore> recordStoreProvider = context2 -> FDBRecordStore.newBuilder()
      .setMetaDataProvider(metaDataStore)
      .setContext(context)
      .setKeySpacePath(RecordStoreKeySpace.getDataKeySpacePath(tenantID, container))
      .createOrOpen();

    return recordStoreProvider.apply(context);
  }
}
