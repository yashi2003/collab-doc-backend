# Real-Time Collaborative Document Backend

A reactive collaborative editing backend using **Spring WebFlux**, **WebSockets**, **Redis Pub/Sub**, and **MongoDB** — with an **Operational Transformation (OT) engine** built from scratch.

## Architecture

```
┌──────────┐   WebSocket    ┌───────────────────┐   Redis Pub/Sub   ┌───────────────────┐
│  Client A │◄──────────────►│   Server Node 1   │◄────────────────►│   Server Node 2   │
└──────────┘                 │  (Spring WebFlux)  │                  │  (Spring WebFlux)  │
                             │  ┌──────────────┐  │                  └───────────────────┘
┌──────────┐   WebSocket     │  │  OT Engine    │  │                           ▲
│  Client B │◄──────────────►│  └──────────────┘  │                           │
└──────────┘                 └─────────┬───────────┘                  WebSocket│
                                       │                             ┌──────────┐
                              MongoDB  │                             │  Client C │
                             (Op Log + │ Docs)                       └──────────┘
                                       ▼
                             ┌───────────────────┐
                             │     MongoDB        │
                             └───────────────────┘
```

## Tech Stack

| Component | Technology | Why |
|-----------|-----------|-----|
| Web Framework | Spring WebFlux | Non-blocking, handles 1000s of concurrent WebSocket connections on few threads |
| Real-time Communication | WebSocket (Reactor Netty) | Full-duplex, low-latency communication for live editing |
| Conflict Resolution | Operational Transformation (OT) | Resolves concurrent edits deterministically without data loss |
| Cross-node Messaging | Redis Pub/Sub | Lightweight message bus for horizontal scaling |
| Persistence | MongoDB (Reactive) | Document store for flexible schema; reactive driver for non-blocking I/O |
| Crash Recovery | Versioned Operation Log + Snapshots | Full operation history enables replay; snapshots speed up reconstruction |

## Project Structure

```
src/main/java/com/collaborative/docs/
├── CollaborativeDocsApplication.java   # Entry point
├── config/
│   ├── WebSocketConfig.java            # WebSocket endpoint mapping
│   ├── JacksonConfig.java              # JSON serialization
│   └── CorsConfig.java                 # CORS for REST API
├── model/
│   ├── OperationType.java              # INSERT, DELETE, RETAIN enum
│   ├── TextOperation.java              # Single atomic text operation
│   ├── CollaborativeDocument.java      # Document entity
│   ├── OperationLog.java               # Versioned operation record
│   └── DocumentSnapshot.java           # Periodic full-text snapshot
├── dto/
│   ├── EditRequest.java                # Client → Server WebSocket message
│   ├── EditResponse.java               # Server → Client WebSocket message
│   ├── CreateDocumentRequest.java      # REST API request body
│   └── RedisBroadcastMessage.java      # Inter-node Redis message
├── engine/
│   └── OTEngine.java                   # ★ THE CORE: Operational Transformation
├── repository/
│   ├── DocumentRepository.java         # Reactive MongoDB for documents
│   ├── OperationLogRepository.java     # Reactive MongoDB for op log
│   └── SnapshotRepository.java         # Reactive MongoDB for snapshots
├── service/
│   ├── DocumentService.java            # Main orchestration: edit → transform → persist
│   ├── RedisBroadcastService.java      # Publish ops to Redis
│   └── RedisSubscriptionService.java   # Subscribe to Redis, forward to local clients
└── websocket/
    ├── CollaborativeEditHandler.java   # WebSocket message handler
    └── SessionRegistry.java            # Thread-safe session management
```

## How It Works — Step by Step

### Step 1: Client connects via WebSocket
```
ws://localhost:8080/ws/docs/{documentId}?userId=user-123
```
The session is registered in `SessionRegistry` under the document ID.

### Step 2: Client sends an edit
```json
{
  "documentId": "doc-abc",
  "userId": "user-123",
  "sessionId": "sess-456",
  "operation": {
    "type": "INSERT",
    "position": 5,
    "text": "Hello",
    "length": 5
  },
  "baseVersion": 3
}
```
`baseVersion` = the document version the client was looking at when they made this edit.

### Step 3: Server transforms the operation (if needed)
If the server is at version 5 but the client is at version 3, there are 2 operations the client doesn't know about. The server:
1. Fetches ops at version 4 and 5 from the op log
2. Transforms the client's operation against each one sequentially
3. The result is an operation that "means the same thing" but works correctly on the current document

### Step 4: Server applies and persists
- Applies the transformed operation to the document content
- Saves the operation to the op log with a new version number
- Updates the document in MongoDB
- Creates a snapshot every N operations (configurable)

### Step 5: Broadcast to everyone
- **Same node**: Push the edit to all WebSocket sessions for this document
- **Other nodes**: Publish to Redis Pub/Sub channel `collab:doc:{documentId}`
- Other nodes receive from Redis and forward to their local WebSocket sessions
- The sender gets an `acknowledged: true` response (ACK pattern)

---

## Key Concepts for Interview

### 1. Why Spring WebFlux over Spring MVC?

> "Spring MVC is thread-per-request. With 100 concurrent WebSocket connections, that's 100 threads just sitting idle waiting for messages. Spring WebFlux uses an event loop model (Netty) — a single thread handles all 100 connections. This is critical for real-time applications where connections are long-lived."

### 2. What is Operational Transformation?

> "OT is an algorithm for resolving concurrent edits. When two users edit the same document at the same time, their operations are based on potentially different versions. The transform function adjusts one operation against the other so that, regardless of the order they're applied, the final document is the same. This is called the 'convergence property.'"

**The three operation types:**
- `INSERT(position, text)` — add text at a cursor position
- `DELETE(position, length)` — remove characters
- `RETAIN(count)` — skip characters without changing them

**Example:**
```
Document: "HELLO" (version 5)
User A: INSERT("X", pos=2)  → "HEXLLO"
User B: DELETE(pos=3, len=1) → "HELO" (deletes 'L')

Both based on version 5. Server processes A first:
1. Apply A → "HEXLLO" (version 6)
2. Transform B against A: B was pos=3, but A inserted at pos=2 (before 3)
   → B' = DELETE(pos=4, len=1)
3. Apply B' → "HEXLO" (version 7)

Both edits preserved: X was inserted AND one L was deleted.
```

### 3. Why Redis Pub/Sub for Horizontal Scaling?

> "Each server node only knows about its own WebSocket connections. If I have 3 nodes behind a load balancer, User A on Node 1 and User B on Node 2 can't communicate directly. Redis Pub/Sub acts as a message bus — when Node 1 processes an edit, it publishes to Redis. Node 2 subscribes and forwards it to its local clients."

**The echo loop problem:**
> "Without a guard, Node 1 publishes → Node 2 receives and re-publishes → Node 1 receives again → infinite loop. I solved this by tagging every message with a `sourceNodeId`. When a node receives a message, it checks: is this from me? If yes, skip it."

### 4. Why MongoDB for the Operation Log?

> "Three reasons: (1) **Crash recovery** — if a server dies, we replay all operations from the log to reconstruct the document. (2) **Late-joiner sync** — a user joining an active session gets the full history to catch up. (3) **Undo/redo** — we can walk backwards through the log."

**The snapshot optimization:**
> "Replaying 10,000 operations on every new connection would be slow. So I snapshot the full document text every 50 operations. To reconstruct, I find the nearest snapshot and replay only the ops after it — at most 50 operations instead of 10,000."

### 5. Thread Safety in Session Management

> "WebSocket connections and disconnections can happen from multiple Netty event loop threads simultaneously. I used `ConcurrentHashMap` for the session registry, with `ConcurrentHashMap.newKeySet()` for the per-document session sets. This provides thread-safe access without explicit synchronization, which would block the event loop."

### 6. Optimistic Concurrency Control

> "What if two operations arrive at the exact same millisecond? I have a unique compound index on `(documentId, version)` in MongoDB. If two ops try to claim the same version, one gets a `DuplicateKeyException`. The failed one retries with the latest state. This is optimistic locking — we assume conflicts are rare and handle them when they happen, rather than locking pessimistically on every write."

### 7. The ACK Pattern

> "When a client sends an edit, it needs to know: did the server accept it? I use an acknowledgment pattern. The sender gets `acknowledged: true` in the response. Other clients get `acknowledged: false`. The sender knows to discard its local pending operation and accept the server's transformed version."

---

## Challenges Faced During Development

| Challenge | What Happened | How I Solved It |
|-----------|--------------|-----------------|
| **Lambda variable mutation** | `transformedOp` was reassigned inside a loop, but used in a lambda — Java requires it to be effectively final | Split into `currentOp` (mutable loop var) and `final transformedOp = currentOp` after the loop |
| **Off-by-one in transform** | INSERT at same position as DELETE: does insert go before or after the delete? | Established convention: "insert wins" — inserts at the same position are preserved before deletes |
| **Redis echo loops** | Publishing ops to Redis and receiving your own messages back caused infinite broadcast loops | Added `sourceNodeId` to every message; nodes skip messages from themselves |
| **Blocking MongoDB in reactive pipeline** | Initially used blocking `MongoTemplate` inside WebFlux, which starved the event loop | Switched to `ReactiveMongoRepository` for fully non-blocking pipeline |
| **WebSocket outbound in WebFlux** | Can't imperatively send messages; outbound must be a Publisher | Used `Sinks.Many` as a reactive queue — push messages into the sink, WebSocket subscribes to its flux |
| **Document reconstruction speed** | Replaying all operations from version 0 was O(n) and slow for large documents | Implemented periodic snapshots; reconstruction finds nearest snapshot and replays only remaining ops |

---

## Quick Start

### Prerequisites
- Java 17+
- Docker & Docker Compose

### Run with Docker Compose (recommended)
```bash
docker-compose up --build
```
This starts:
- **MongoDB** on port 27017
- **Redis** on port 6379
- **App Node 1** on port 8080
- **App Node 2** on port 8081

### Run locally (development)
```bash
# Start MongoDB and Redis
docker run -d -p 27017:27017 mongo:7.0
docker run -d -p 6379:6379 redis:7-alpine

# Build and run
./gradlew bootRun
```

### API Usage

**Create a document:**
```bash
curl -X POST http://localhost:8080/api/documents \
  -H "Content-Type: application/json" \
  -d '{"title": "My Document", "createdBy": "user-1"}'
```

**Get a document:**
```bash
curl http://localhost:8080/api/documents/{documentId}
```

**Connect via WebSocket (using wscat):**
```bash
npm install -g wscat
wscat -c "ws://localhost:8080/ws/docs/{documentId}?userId=user-1"
```

**Send an edit over WebSocket:**
```json
{
  "documentId": "doc-id-here",
  "userId": "user-1",
  "sessionId": "sess-1",
  "operation": {
    "type": "INSERT",
    "position": 0,
    "text": "Hello World",
    "length": 11
  },
  "baseVersion": 0
}
```

**Get operation history:**
```bash
curl "http://localhost:8080/api/documents/{documentId}/ops?sinceVersion=0"
```

**Reconstruct from operation log:**
```bash
curl http://localhost:8080/api/documents/{documentId}/reconstruct
```

**Health check:**
```bash
curl http://localhost:8080/api/health
```

### Run Tests
```bash
./gradlew test
```

---

## Load Testing

To verify the "100+ concurrent sessions" claim, use a WebSocket load testing tool:

```bash
# Install artillery
npm install -g artillery

# Create a load test config (artillery.yml) and run:
# artillery run artillery.yml
```

Or a simple test with multiple wscat connections:
```bash
for i in $(seq 1 100); do
  wscat -c "ws://localhost:8080/ws/docs/doc-123?userId=user-$i" &
done
```

---

## License

MIT
