# mongkn

[![ktlint](https://img.shields.io/badge/ktlint%20code--style-%E2%9D%A4-FF4081.svg)](https://ktlint.github.io/)
[![kotlin](https://img.shields.io/badge/Kotlin-2.4.10-blue?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![native](https://img.shields.io/badge/Native-blue?logoColor=white)](https://kotlinlang.org)
[![mongkn-core](https://reposilite.kotlin.website/api/badge/latest/snapshots/io/github/youndie/mongkn/mongkn-core?name=mongkn-core&color=40c14a&prefix=v)](https://reposilite.kotlin.website/#/snapshots/io/github/youndie/mongkn/mongkn-core)
[![license](https://img.shields.io/badge/license-Apache--2.0-green.svg)](LICENSE)

MongoDB for Kotlin/Native: a binding over the official C driver (`libmongoc`) with an API whose
shape is taken from `mongodb-driver-kotlin-coroutine`.

There is no official MongoDB driver for Kotlin/Native — `mongodb-driver-kotlin-coroutine` is
JVM-only. mongkn writes the `cinterop` once and hides it behind `suspend fun insertOne(…)` and
`fun find(…): Flow<Document>`.

## Overview

- 23 of 30 collection operations: insert, update, delete, `find`, aggregation, indexes,
  `bulkWrite`, `findOneAnd*`, `distinct`
- Sessions and transactions, `withTransaction` retrying on server error labels
- Change streams on collection, database and client, with automatic resumption
- All topologies: standalone, replica set, sharded cluster through `mongos`
- SCRAM, TLS and x509, verified against servers started with `--auth` and `--tlsMode requireTLS`
- Command monitoring (`CommandListener`) and a driver log handler
- 18 of 20 BSON types, own tree model and a `kotlinx.serialization` format on top
- `BsonEncoder` / `BsonDecoder` as the extension point for custom types, plus ready-made
  serializers for `ObjectId` and dates

## Performance

Overhead over the C driver it wraps, measured by a release binary against a local `mongo:8`
([docs/performance.md](docs/performance.md) — methodology and full tables).

| | |
|---|---|
| Write, per operation | 1470 µs against 1492 µs for bare C — below the measurement threshold |
| Read into a class, per document | **1.26 µs** on Linux/x86_64, **1.66 µs** on macOS/arm64 |
| Read overhead over a bare C cursor loop | −0.35 %, indistinguishable |
| BSON round trip `Document` → `bson_t` → `Document` | 1.25 µs |
| `kotlinx.serialization` codec on top of that | 0.27 µs, about 5 % of the mandatory conversion |
| Throughput, 64 coroutines | 10 565 ops/s on 8 cores, ~20 000 ops/s on 20 cores |
| Resident memory | 21.9 MB idle, 93 MB under 256 concurrent operations |

Two changes did most of that. Documents cross the coroutine channel **in batches of 64** rather
than one at a time, and a typed collection decodes straight from the cursor into the class instead
of building an intermediate `Document`. The latter removes 37 % of the decoding work.

Both were measured before and after **within one run**. Comparing separate runs is not sound here:
the floor — a bare C loop with no mongkn code in it — moved by 1.5× between runs on a busy machine.

The throughput knob is `ioThreads`, not the client pool: `libmongoc` calls are blocking, so
concurrency equals the number of threads. The default is 32; on a many-core server under high
concurrency set it explicitly and measure memory — 64 threads cost +68 % resident memory under
load, and nothing at all when idle, because the threads are created lazily.

## Add dependencies

```kotlin
repositories {
    mavenCentral()
    maven {
        name = "WipSnapshots"
        url = uri("https://reposilite.kotlin.website/snapshots")
    }
}

dependencies {
    implementation("io.github.youndie.mongkn:mongkn-core:$mongkn_version")
    // optional: infix DSL for filters and updates
    implementation("io.github.youndie.mongkn:mongkn-extensions:$mongkn_version")
}
```

The C driver must be installed: `apt install libmongoc-dev libbson-dev` on Linux,
`brew install mongo-c-driver` on macOS. Only the host target is built — `cinterop` needs the
headers of the target platform — and only `linuxX64` is published.

## Usage

```kotlin
@Serializable
data class Person(val name: String, val born: Int)

fun main() = runBlocking {
    MongoClient("mongodb://127.0.0.1:27017").use { client ->
        val people = client.getDatabase("app").getCollection<Person>("people")

        people.insertOne(Person("Ada", 1815))
        people.find { Person::born lt 1900 }.collect { println(it) }
    }
}
```

Untyped works too: `getCollection("people")` returns a `MongoCollection<Document>`.

Inside `filter { }` and `update { }` both the field name and the value are resolved against the
class. A property renamed with `@SerialName` fails loudly instead of querying a field that does not
exist, and the value is encoded with the **field's** serializer rather than by its Kotlin runtime
type. The second check exists because the failure it prevents is silent: a `String` compared
against a stored `ObjectId` matches nothing, and MongoDB reports no error.

## Custom types

The extension point mirrors `JsonEncoder.encodeJsonElement` from `kotlinx-serialization-json`:
a serializer recognises the encoder and hands it a ready `BsonValue`. Needed by types with an exact
BSON representation — money as `decimal128`, timestamps as `dateTime` (a TTL index silently stops
deleting when the field holds a string).

```kotlin
object MoneySerializer : KSerializer<Money> {
    override val descriptor = PrimitiveSerialDescriptor("Money", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Money) {
        val bson = encoder as? BsonEncoder
            ?: throw SerializationException("Money is only serialisable to BSON")
        bson.encodeBsonValue(BsonDecimal128(value.toPlainString()))
    }

    override fun deserialize(decoder: Decoder): Money {
        val bson = decoder as? BsonDecoder
            ?: throw SerializationException("Money is only readable from BSON")
        return Money((bson.decodeBsonValue() as BsonDecimal128).value)
    }
}
```

`StringAsBsonObjectId` and `InstantAsBsonDateTime` ship in `mongkn-extensions`.

## Internals

| Layer | |
|---|---|
| cinterop | `libmongoc` 1.26+ and 2.x; library names and header paths are resolved in Gradle — they differ between branches |
| Resources | `mongoc_client_pool_t` plus a semaphore: `mongoc_client_t` is not thread-safe and `pool_pop` blocks uninterruptibly |
| Threads | a dedicated pool for blocking calls — `Dispatchers.IO` is `internal` on Kotlin/Native, contrary to its own documentation |
| Cursors | a `Flow` that releases the cursor on every outcome, cancellation included |
| Decoding | typed collections read from `bson_iter_t` directly; maps, polymorphism and `BsonValue` fields fall back to the tree decoder, per subtree |

| Module | |
|---|---|
| `mongkn-core` | cinterop, BSON, client, operations |
| `mongkn-extensions` | infix DSL for filters and updates, ObjectId and date serializers |
| `mongkn-difftest` | JVM reference: the official driver, used for differential tests |

## Testing

294 tests on macOS, 277 of them on Linux, plus **71 of 75 official MongoDB specification
scenarios** — the four skipped ones need a server version we do not run.

Integration tests need four separate contours, and none reduces to another: a replica set (without
it there are no transactions and no change streams), a server with `--auth`, a server with
`--tlsMode requireTLS`, and a `mongos` in front of a sharded cluster. Without those servers the
tests **fail** rather than skip.

Agreement with the official driver is checked by **differential tests**: one document goes through
the JVM driver and through mongkn against the same `mongod`, and results are compared both ways.
The two read paths are compared against each other for the same reason — the risk is not that one
is wrong, but that they diverge in a corner case.

Failure modes are staged with server failpoints rather than simulated on the client: cancellation
mid-call, a broken cursor, retried reads and writes, a resumed change stream. Each failpoint is
bound to its own client by `appName`, because `mode: {times: 1}` is otherwise spent by whichever
command arrives first.

## What mongkn is not

- **Not a drop-in JVM driver.** The API shape follows `mongodb-driver-kotlin-coroutine`, but the
  surface is narrower: 23 of 30 collection operations, no GridFS, no client-side field level
  encryption, no Atlas Search indexes.
- **Not cross-compiling.** Only the host target is built, because `cinterop` needs the target's
  headers. There is no `mingwX64` build — on Windows, work from WSL2.
- **Not a complete BSON model.** `dbpointer` and `code with scope` are rejected on read: the first
  is removed from the specification, the second is deprecated.

## Documentation

[docs/](docs/) — architecture research with the reasoning behind each decision, coverage in
numbers, the performance study and the serialization guide. Written in Russian.

The research document is worth reading before changing anything: several decisions here are
counter-intuitive, and the backlog records cases where the obvious answer was measured and turned
out to be wrong.

## License

Apache 2.0, the same licence as the `mongo-c-driver` this library wraps. See [LICENSE](LICENSE).
