# Consistent Hashing-Based Naming Service
## CSCI 4780/6780 — Project 4

## Overview

This project implements a consistent hashing (CH)-based flat naming system. The system stores key-value pairs across a distributed set of servers that collaboratively provide lookup, insertion, and deletion operations. Keys and server IDs are integers in the range [0, 1023].

## Files

| File | Description |
|---|---|
| `BootStrapNameServer.java` | Permanent bootstrap node (ID 0), manages the full ring and user commands |
| `TemporaryNameServer.java` | Non-permanent name server that can join and leave the ring |
| `Message.java` | Serializable message class used for all inter-node communication |
| `NameServerInfo.java` | Stores ID, IP, and port for predecessor/successor references |

---

## Assumptions

1. Keys and server IDs are integers in [0, 1023] (both inclusive).
2. Values are alphanumeric strings with no special characters.
3. The bootstrap server always has ID 0 and is permanent.
4. No race conditions occur (no simultaneous operations).
5. All lookup, insert, and delete operations are initiated at the bootstrap server.
6. Ring traversal always goes clockwise (via successors).
7. Each name server only knows its immediate predecessor and successor.
8. The bootstrap server's address is known to all name servers at startup via config file.

---

## Compilation

```bash
javac *.java
```

---

## Configuration Files

### Bootstrap Config (`bnConfig.txt`)
```
0
5001
100 apple
300 cherry
600 grape
900 mango
```
- Line 0: Server ID (always 0 for bootstrap)
- Line 1: Port number
- Lines 2+: Initial key-value pairs (optional), one per line

### Name Server Config
```
200
5002
127.0.0.1 5001
```
- Line 0: Server ID (integer in [1, 1023])
- Line 1: Port number for this server
- Line 2: Bootstrap server IP and port (space-delimited)

### Create All Config Files
```bash
echo "0
5001
100 apple
300 cherry
600 grape
900 mango" > bnConfig.txt

echo "200
5002
127.0.0.1 5001" > ns1Config.txt

echo "500
5003
127.0.0.1 5001" > ns2Config.txt

echo "800
5004
127.0.0.1 5001" > ns3Config.txt
```

---

## Running the Servers

### Bootstrap Server
```bash
java BootStrapNameServer bnConfig.txt
```

### Name Server
```bash
java TemporaryNameServer ns1Config.txt
```

---

## User Commands

### Bootstrap Server Commands
| Command | Description |
|---|---|
| `insert <key> <value>` | Insert a key-value pair into the ring |
| `lookup <key>` | Retrieve the value for a given key |
| `delete <key>` | Delete a key-value pair from the ring |

### Name Server Commands
| Command | Description |
|---|---|
| `enter` | Join the ring via the bootstrap server |
| `exit` | Gracefully leave the ring, handing keys to successor |

---

## Bugs Fixed

### 1. Bootstrap Config File Parsing
**Problem:** Original `main()` took only a port number as an argument, not a config file as required by the spec.

**Fix:** Rewrote `main()` in `BootStrapNameServer.java` to accept a config file path, read the port from line 1, and load initial key-value pairs from lines 2 onward.

---

### 2. Missing "Successful Entry" Output
**Problem:** After a name server joined the ring, no output was printed — the spec requires printing confirmation, key range, predecessor ID, and successor ID.

**Fix:** Added print statements at the end of the `found_position` case in `TemporaryNameServer.handleMessage()`:
```java
System.out.println("Successful entry");
System.out.println("Key range: [" + this.keyRange[0] + ", " + this.keyRange[1] + "]");
System.out.println("Predecessor ID: " + this.predecessor.id);
System.out.println("Successor ID: " + this.successor.id);
```

---

### 3. Missing "Successful Exit" Output
**Problem:** After a name server exited, no output was printed — the spec requires printing confirmation, successor ID, and key range handed over.

**Fix:** Added print statements in the `exit` branch of `UserInteraction.run()` in `TemporaryNameServer.java`:
```java
System.out.println("Successful exit");
System.out.println("Successor ID: " + successor.id);
System.out.println("Key range handed over: [" + keyRange[0] + ", " + keyRange[1] + "]");
```

---

### 4. Delete "Key Not Found" Not Reported Correctly Via Ring
**Problem:** When a delete message traversed the ring and the key didn't exist, the bootstrap always printed "Delete complete" regardless.

**Fix:** Added a `deleted` boolean field to `Message.java`. The `delete` handler in `TemporaryNameServer` sets `msg.deleted = true` only when the key is actually found and removed. The bootstrap then checks this flag:
```java
if (msg.deleted) {
    System.out.println("Successful deletion");
} else {
    System.out.println("Key not found");
}
```

---

### 5. Redundant Branches in `find_position`
**Problem:** The `find_position` case had two identical `else if` / `else` branches both calling `sendMessage()` to the successor.

**Fix:** Collapsed into a single `else` branch since both cases forward the message identically.

---

## Tests

> **Important:** Start fresh terminals for each test so the bootstrap's `listOfServers` is clean. Always start the bootstrap first.

---

### Test 1: Bootstrap Alone

**Terminal 1:**
```bash
java BootStrapNameServer bnConfig.txt
```
Commands:
```
lookup 100
lookup 999
insert 512 banana
lookup 512
delete 100
lookup 100
delete 100
```

Expected output:
```
Value: apple
Sequence of servers: 0
Key not found
Inserted at server 0
Value: banana
Sequence of servers: 0
Successful Deletion
Sequence of servers: 0
Key not found
Key not found
```

---

### Test 2: One Node Joins

**Terminal 1:**
```bash
java BootStrapNameServer bnConfig.txt
```

**Terminal 2:**
```bash
java TemporaryNameServer ns2Config.txt
```
Type: `enter`

Expected on Terminal 2:
```
Successful entry
Key range: [500, 1023]
Predecessor ID: 0
Successor ID: 0
```

Then verify in Terminal 1:
```
lookup 600
lookup 900
lookup 100
lookup 300
```

Expected:
```
Key 600 = grape
Sequence of servers: 0 -> 500
Key 900 = mango
Sequence of servers: 0 -> 500
Key 100 = apple
Sequence of servers: 0
Key 300 = cherry
Sequence of servers: 0
```

---

### Test 3: Full Ring — Four Nodes

**Terminal 1:** `java BootStrapNameServer bnConfig.txt`

**Terminal 2:** `java TemporaryNameServer ns1Config.txt` → `enter`

**Terminal 3:** `java TemporaryNameServer ns2Config.txt` → `enter`

**Terminal 4:** `java TemporaryNameServer ns3Config.txt` → `enter`

Expected key ranges:
| Node | Range |
|---|---|
| 0 | [0, 199] |
| 200 | [200, 499] |
| 500 | [500, 799] |
| 800 | [800, 1023] |

Then in Terminal 1:
```
lookup 100
lookup 300
lookup 600
lookup 900
```

Expected:
```
Key 100 = apple
Sequence of servers: 0
Key 300 = cherry
Sequence of servers: 0 -> 200
Key 600 = grape
Sequence of servers: 0 -> 200 -> 500
Key 900 = mango
Sequence of servers: 0 -> 200 -> 500 -> 800
```

---

### Test 4: Insert and Lookup Via Ring

With the full 4-node ring from Test 3 still running:

Terminal 1:
```
insert 250 orange
insert 750 melon
lookup 250
lookup 750
```

Expected:
```
Insert complete for key 250
Sequence of servers: 0 -> 200
Insert complete for key 750
Sequence of servers: 0 -> 200 -> 500
Key 250 = orange
Sequence of servers: 0 -> 200
Key 750 = melon
Sequence of servers: 0 -> 200 -> 500
```

---

### Test 5: Node Exit and Key Handover

With the full 4-node ring still running, first insert a key on node 500:

Terminal 1:
```
insert 600 willmove
```

Terminal 3 (Node 500):
```
exit
```

Expected on Terminal 3:
```
Successful exit
Successor ID: 800
Key range handed over: [500, 799]
```

Then verify in Terminal 1:
```
lookup 600
lookup 750
```

Expected — keys now routed to node 800:
```
Key 600 = willmove
Sequence of servers: 0 -> 200 -> 800
Key 750 = melon
Sequence of servers: 0 -> 200 -> 800
```

---

### Test 6: Duplicate ID Rejection

**Terminal 1:** `java BootStrapNameServer bnConfig.txt`

**Terminal 2:** `java TemporaryNameServer ns1Config.txt` → `enter`

Wait for "Successful entry", then:

**Terminal 3:**
```bash
echo "200
5010
127.0.0.1 5001" > ns_dup.txt
java TemporaryNameServer ns_dup.txt
```
Type: `enter`

Expected on Terminal 3:
```
Join rejected: server id 200 is already on the ring.
```

---

### Test 7: Delete Key Not Found

**Terminal 1 only:**
```bash
java BootStrapNameServer bnConfig.txt
```
```
delete 999
lookup 999
```

Expected:
```
Key not found
Key not found
```

---

### Test 8: Out-of-Order Join

**Terminal 1:** `java BootStrapNameServer bnConfig.txt`

**Terminal 2 — join Node 500 first:**
```bash
java TemporaryNameServer ns2Config.txt
```
Type: `enter`

**Terminal 3 — then join Node 200:**
```bash
java TemporaryNameServer ns1Config.txt
```
Type: `enter`

Expected on Terminal 3:
```
Successful entry
Key range: [200, 499]
Predecessor ID: 0
Successor ID: 500
```

Then in Terminal 1:
```
lookup 100
lookup 300
lookup 600
```

Expected:
```
Key 100 = apple
Sequence of servers: 0
Key 300 = cherry
Sequence of servers: 0 -> 200
Key 600 = grape
Sequence of servers: 0 -> 200 -> 500
```

---

## Test Results Summary

| Test | Description | Status |
|---|---|---|
| 1 | Bootstrap standalone insert/lookup/delete | ✅ |
| 2 | Single node join and key transfer | ✅ |
| 3 | Full 4-node ring and traversal output | ✅ |
| 4 | Insert and lookup via ring routing | ✅ |
| 5 | Node exit and key handover to successor | ✅ |
| 6 | Duplicate ID rejection | ✅ |
| 7 | Delete and lookup for missing key | ✅ |
| 8 | Out-of-order node join | ✅ |
