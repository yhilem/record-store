---
---

# Examples

## Java

> the API is a work-in-progress

### Open RecordSpace

```java
recordStoreClient = new RecordStoreClient.Builder()
  .withTenant("my-tenant")
  .withRecordSpace("user-dev")
  .withToken(token)
  .withAddress("localhost:" + port)
  .connect();
```

### Upsert a Schema

Given this schema:

```proto
message User {
  int64 id = 1;
  string name = 2;
  string email = 3;
}
```

You can write the following code:

```java
RecordStoreProtocol.UpsertSchemaRequest request =
  SchemaUtils.createSchemaRequest(
    // descriptor generated by Protobuf
    DemoUserProto.User.getDescriptor(),
    // name of the RecordType
    DemoUserProto.User.class.getSimpleName(),
    // primary key field
    "id",
    // field to index
    "name",
    // how the field should be indexed
    RecordStoreProtocol.IndexType.VALUE
);
```

### Push a Record

```java
DemoUserProto.User record =
  DemoUserProto.User.newBuilder()
    .setId(999)
    .setName("Pierre Zemb")
    .setEmail("pz@example.org")
    .build();

recordStoreClient.putRecord(record).get();
```

### Query a record

```java
RecordStoreProtocol.QueryRequest request =
  RecordStoreProtocol.QueryRequest.newBuilder()
    // name of the RecordType to query
    .setRecordTypeName(DemoUserProto.User.class.getSimpleName())
    // retrieve only users with an id lower than 1000
    .setFilter(RecordQuery.field("id").lessThan(1000L))
    .build();

Iterator<RecordStoreProtocol.QueryResponse> results =
  recordStoreClient.queryRecords(request);
```