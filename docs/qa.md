---
title: Questions and Answers
---

# Questions and Answers

### What is Record-Store?

Record-Store is a layer running on top of FoundationDB that provide a new kind of abstractions for storing data. We like to think that we are offering something between an SQL table and a MongoDB collections.

### Why?

Please see the [motivation page](/record-store/motivations).

### What can I do with it?

You can use the Record-Store to create as many data holders(called RecordSpace) as you need.

We would like to have this kind of flow for developers:

1. Opening RecordSpace, for example `prod/users`
2. Create a protobuf definition which will be used as schema
3. Upsert schema
4. Push records
5. Query records
6. delete records

You need another `KeySpace` to store another type of data, or maybe a KeySpace dedicated for a production environment? Juste create it and you are good to go!

### How complicated queries can be?

Please see the [query capabilities page](/record-store/query-capabilities).

### What is the terminology used within Record-Store?

* `tenant` is your account
* `RecordSpace` is like a virtual SQL table, it is a logical place where you have `schemas` and `data`. If you know your way around distributed key-value store, it can be also view as a keySpace, except that you are manipulating records instead of keys.
* `RecordType` is the name of the message structure defined in protobuf
* `Record` is an instance of a protobuf message

### Is it production-ready?

It is experimental.

### What is working for now?

#### Working:

* multi-tenancy
* encryption
* access with crypto-tokens
* gRPC endpoint
* schema upload/upgrade
* push data
* retrieve data
* GraphQL proof-of-concept (converting a Protobuf schema to GraphQL and allowed queries based on the indexes)

#### Work in progress:

* working GraphQL
* Java/Go/Rust/(your language) clients

### Can I test it?

This will be tricky as we have (yet) no usable clients but we are focusing on those. If you want to contribute, please see [the dedicated page](/record-store/write-client).

### Why gRPC?

Because the Record-Layer is already massively using Protobuf.

### Why GraphQL?

We think that offering an GraphQL interface will allow web developers to use the Record-Store.

### Can I disable encryption?

No.

### Why FoundationDB?

* We are heavily relying on FDB's [Record-Layer](https://github.com/foundationdb/fdb-record-layer/)
* we need transactions capabilities to make indexes possible
* we need a distributed database to scale the `RecordSpaces`
* [FoundationDB's testing is more rigorous than Jepsen](https://twitter.com/aphyr/status/405017101804396546?s=20)
