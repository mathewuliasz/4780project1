# MyFTP - Simple File Transfer Program

A client-server FTP application built with Java TCP sockets. The server listens for a client connection and spawns a worker thread for each command, enabling parallel execution. The client provides an interactive prompt to send file transfer and directory commands (`get`, `put`, `delete`, `ls`, `cd`, `mkdir`, `pwd`, `quit`) to the server.

## How to Run

**Compile:**

```bash
javac myftpserver.java myftp.java
```

**Start the server:**

```bash
java myftpserver <port>
```

**Start the client (in a separate terminal):**

```bash
java myftp <server_address> <port>
```

**Example:**

```bash
# Terminal 1
java myftpserver 5000

# Terminal 2
java myftp localhost 5000
```
