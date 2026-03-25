# Persistent and Asynchronous Multicast System
**CSCI 4760 – Programming Project 3**

This project implements a persistent asynchronous multicast system on top of TCP using a coordinator-participant paradigm. Both the multicast coordinator and participants are implemented in Java.

---

## Group Members
- Nathan Brown
- Mathew Uliasz
- Diego Sanchez-Carapia

*This project was done in its entirety by Nathan Brown, Mathew Uliasz, and Diego Sanchez-Carapia. We hereby state that we have not received unauthorized help of any form.*

---

## Project Structure

```
project3/
├── Coordinator.java
├── Participant.java
├── PP3-coordinator-conf.txt
├── PP3-participant-conf-A.txt
├── PP3-participant-conf-B.txt
└── README.md
```

---

## Configuration Files

**PP3-coordinator-conf.txt**
```
6000       ← port the coordinator listens on
600        ← persistence time threshold (td) in seconds
```

**PP3-participant-conf.txt**
```
101                  ← participant ID
101-message.txt      ← message log file
localhost 6000       ← coordinator host and port
```

> **Note:** Update the coordinator host from `localhost` to the actual machine hostname (e.g. `odin.cs.uga.edu`) when running across multiple machines.

---

## Compilation

Both files must be compiled together since `Coordinator.java` references `Participant.State`:

```bash
javac Coordinator.java Participant.java
```

---

## Running the System

> **Important:** Always start the coordinator first and wait for it to print `Coordinator listening on port XXXX` before starting any participants.

**Terminal 1 — Coordinator:**
```bash
java Coordinator PP3-coordinator-conf.txt
```

**Terminal 2 — Participant A:**
```bash
java Participant PP3-participant-conf-A.txt
```

**Terminal 3 — Participant B:**
```bash
java Participant PP3-participant-conf-B.txt
```

### Port Conflicts (macOS)
On macOS, port 5000 is used by AirPlay Receiver. To free it go to:
**System Settings → General → AirDrop & Handoff → turn off AirPlay Receiver**

Or simply use a different port (e.g. `6000`) in both config files.

---

## Participant Commands

| Command | Format | Description |
|---|---|---|
| Register | `register <port>` | Join the multicast group on the given port |
| Deregister | `deregister` | Leave the multicast group permanently |
| Disconnect | `disconnect` | Go temporarily offline |
| Reconnect | `reconnect <port>` | Come back online and receive missed messages |
| Multicast Send | `msend <message>` | Send a message to all group members |
| Quit | `quit` | Exit the participant (auto-deregisters) |

---

## Test Flow

### Test 1 — Basic Registration & Multicast
```
PART-A:  register 5001
PART-B:  register 5002
PART-A:  msend HelloEveryone
```
**Expected:** Both participants receive `HelloEveryone` in their log files. Coordinator prints delivery confirmations for both.

---

### Test 2 — Disconnect & Persistence (Catch-up on Reconnect)
```
PART-B:  disconnect
PART-A:  msend MissedMessage
PART-B:  reconnect 5002
```
**Expected:** PART-B receives `MissedMessage` after reconnecting. Coordinator logs catch-up delivery to participant 102.

---

### Test 3 — Deregister (No Catch-up on Re-register)
```
PART-B:  deregister
PART-A:  msend LostForever
PART-B:  register 5002
```
**Expected:** PART-B does **not** receive `LostForever`. Deregistered participants are treated as brand new entrants upon re-registration.

---

### Test 4 — Sender Receives Own Message
```
PART-A:  msend SelfTest
```
**Expected:** PART-A's own log file contains `SelfTest`. The spec requires all group members including the sender receive multicast messages.

---

### Test 5 — Temporal Bound (change td to 5s for testing)
Update `PP3-coordinator-conf.txt` to set `td=5`, then restart everything.
```
PART-B:  disconnect
         (wait 6+ seconds)
PART-A:  msend TooOld
PART-B:  reconnect 5002
```
**Expected:** PART-B does **not** receive `TooOld` because it was sent more than `td` seconds after disconnect. Only messages within the `td` window are delivered.

---

## Clean Shutdown

To stop the system cleanly and avoid port-in-use errors on restart:

```
PART-A terminal:   quit
PART-B terminal:   quit
COOR terminal:     Ctrl+C
```

If you still get a port conflict on restart:
```bash
kill -9 $(lsof -t -i :6000)
```

---

## Design & Implementation Notes

### Architecture

**Coordinator:**
- One accept loop hands incoming connections off to a fixed thread pool (8 threads, per spec guidance of < 10)
- Shared member state uses `ConcurrentHashMap` for thread-safe reads
- Synchronized blocks are used only during state mutation and message snapshotting — delivery happens outside the lock to prevent blocking
- Each member tracks a `deliveredMsgIds` set to prevent duplicate delivery

**Participant:**
- Thread-A reads user commands from stdin
- Thread-B binds a `ServerSocket` on the specified port and receives incoming multicast messages from the coordinator, logging them to the message file
- Thread-B is started **before** sending `register` or `reconnect` to the coordinator as required by the spec

### Message Protocol

| Direction | Format |
|---|---|
| Participant → Coordinator | `register <id> <ip> <port>` |
| Participant → Coordinator | `deregister <id>` |
| Participant → Coordinator | `disconnect <id>` |
| Participant → Coordinator | `reconnect <id> <ip> <port>` |
| Participant → Coordinator | `msend <id> <message>` |
| Coordinator → Participant | `MSG <msgId> <senderId> <content>` |
| Coordinator ACK | `ACK <command> <id>` |

### Temporal Persistence Logic

On reconnect, messages delivered satisfy all three conditions:
```
msg.timestamp >= (now - td)           ← within temporal bound
msg.timestamp >= participant.disconnectTime   ← sent after they disconnected
msg.id not in participant.deliveredMsgIds     ← not already received
```

### Key Changes Made During Development

- **Local IP resolution:** Uses a probe socket to the coordinator to discover the correct routable local IP address, instead of `InetAddress.getLocalHost()` which returns `127.0.0.1` on some systems
- **State machine:** Participant tracks `UNREGISTERED / ONLINE / OFFLINE` states and guards each command to prevent invalid transitions (e.g. `msend` while offline)
- **Auto-deregister on quit:** Typing `quit` sends a deregister to the coordinator if the participant is currently registered, so the coordinator doesn't keep trying to deliver to a dead port
- **Null ServerSocket guard:** Thread-B exits cleanly if the `ServerSocket` failed to bind (e.g. port already in use)
- **Duplicate delivery prevention:** Each member maintains a set of delivered message IDs; if delivery fails mid-transfer it's removed from the set so it can be retried on reconnect
- **EOFException guard:** Probe socket connections from participants are handled gracefully by the coordinator without logging spurious errors
