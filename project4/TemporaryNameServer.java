
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

public class TemporaryNameServer {

    InetAddress localHost = null;
    BufferedReader clientCommandReader = null;
    HashMap<Integer, String> keys;
    int port;
    int id;
    String bootstrapServerName;
    int bootstrapServerPort;
    NameServerInfo predecessor;
    NameServerInfo successor;
    ServerSocket incomingMsgs = null;
    Socket p = null;
    DataInputStream dis = null;
    DataOutputStream dos = null;
    boolean exited = false;
    int[] keyRange;

    public TemporaryNameServer(int port, int id, String bootstrapServerName, int bootstrapServerPort) {
        try {
            localHost = InetAddress.getLocalHost();
        } catch (IOException e) {
            System.out.println("Could not resolve local address: " + e);
            return;
        }
        try {
            incomingMsgs = new ServerSocket(port);
        } catch (IOException e) {
            System.out.println(e);
            return;
        }
        this.port = port;
        this.id = id;
        this.bootstrapServerPort = bootstrapServerPort;
        this.bootstrapServerName = bootstrapServerName;
        keys = new HashMap<>();
    }

    public void start() {
        if (incomingMsgs == null) {
            System.out.println("Name server not initialized, cannot start.");
            return;
        }
        new Thread(new UserInteraction(), "ns-cli-" + id).start();
        acceptLoop();
    }

    private void acceptLoop() {
        while (!exited) {
            try {
                Socket liSocket = incomingMsgs.accept();
                DataInputStream dis = new DataInputStream(liSocket.getInputStream());
                int len = dis.readInt();
                byte[] data = dis.readNBytes(len);
                Message msg = Message.fromBytes(data);
                handleMessage(msg);
                liSocket.close();
            } catch (Exception e) {
                if (!exited)
                    System.out.println(e);
            }
        }
    }

    void handleMessage(Message msg) {
        switch (msg.type) {
            case "exit":
                if (msg.senderID == successor.id && msg.successor != null) {
                    this.successor = msg.successor;
                    this.keyRange[1] = (msg.successor.id == 0) ? 1023 : msg.successor.id - 1;
                    if (msg.kvPairs != null)
                        this.keys.putAll(msg.kvPairs);
                }
                if (msg.senderID == predecessor.id) {
                    this.predecessor = msg.predecessor;
                }
                break;
            case "enter_rejected":
                System.out.println("Join rejected: server id " + this.id + " is already on the ring.");
                exited = true;
                try {
                    incomingMsgs.close();
                } catch (Exception e) {
                    /* ignore */ }
                break;
            case "find_position":
                if (msg.senderID > this.id && (this.successor.id == 0 || this.successor.id > msg.senderID)) {

                    int threshold = msg.senderID;
                    HashMap<Integer, String> slice = new HashMap<>();
                    Iterator<Map.Entry<Integer, String>> it = this.keys.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<Integer, String> keyVal = it.next();
                        if (keyVal.getKey() >= threshold) {
                            slice.put(keyVal.getKey(), keyVal.getValue());
                            it.remove();
                        }
                    }

                    Message foundPosition = new Message()
                            .type("found_position")
                            .predecessor(new NameServerInfo(this.id, this.localHost.getHostAddress(), this.port))
                            .successor(this.successor)
                            .keyRange(msg.senderID, this.keyRange[1])
                            .kvPairs(slice);

                    sendMessage(foundPosition, msg.ipAddress, msg.port);

                    this.successor = new NameServerInfo(msg.senderID, msg.ipAddress, msg.port);
                    this.keyRange[1] = threshold - 1;
                } else {

                    sendMessage(msg, this.successor.ipAddress, this.successor.port);
                }
                break;
            case "found_position":
                keys.putAll(msg.kvPairs);
                this.keyRange = new int[] { msg.keyRangeStart, msg.keyRangeEnd };
                this.predecessor = msg.predecessor;
                this.successor = msg.successor;

                Message updatePredecessor = new Message()
                        .type("update_predecessor")
                        .predecessor(new NameServerInfo(this.id, this.localHost.getHostAddress(), this.port));

                sendMessage(updatePredecessor, this.successor.ipAddress, this.successor.port);

                break;
            case "update_predecessor":
                this.predecessor = msg.predecessor;
                break;
            case "lookup":
                msg.serverTraverMessage(" -> " + this.id);
                if (msg.key >= keyRange[0] && msg.key <= keyRange[1]) {
                    if (keys.containsKey(msg.key)) {
                        msg.value = keys.get(msg.key);
                    }
                    sendMessage(msg, bootstrapServerName, bootstrapServerPort);
                } else if (this.successor.id != 0) {
                    sendMessage(msg, this.successor.ipAddress, this.successor.port);
                } else {
                    sendMessage(msg, bootstrapServerName, bootstrapServerPort);
                }
                break;
            case "insert":
                msg.serverTraverMessage(" -> " + this.id);
                if (msg.key >= keyRange[0] && msg.key <= keyRange[1]) {
                    keys.put(msg.key, msg.value);
                    sendMessage(msg, bootstrapServerName, bootstrapServerPort);
                } else if (this.successor.id != 0) {
                    sendMessage(msg, this.successor.ipAddress, this.successor.port);
                } else {
                    sendMessage(msg, bootstrapServerName, bootstrapServerPort);
                }
                break;
            case "delete":
                msg.serverTraverMessage(" -> " + this.id);
                if (msg.key >= keyRange[0] && msg.key <= keyRange[1]) {
                    if (keys.containsKey(msg.key)) {
                        keys.remove(msg.key);
                    }
                    sendMessage(msg, bootstrapServerName, bootstrapServerPort);
                } else if (this.successor.id != 0) {
                    sendMessage(msg, this.successor.ipAddress, this.successor.port);
                } else {
                    sendMessage(msg, bootstrapServerName, bootstrapServerPort);
                }
                break;
            default:
                System.out.println("Unknown message type: " + msg.type);
        }
    }

    void sendMessage(Message msg, String ipAddress, int port) {
        try (Socket s = new Socket(ipAddress, port);
                DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()))) {
            byte[] msgToBytes = msg.toBytes();
            dos.writeInt(msgToBytes.length);
            dos.write(msgToBytes);
            dos.flush();
        } catch (Exception e) {
            System.out.println(e);
        }
    }

    public class UserInteraction implements Runnable {

        public UserInteraction() {
            clientCommandReader = new BufferedReader(new InputStreamReader(System.in));
        }

        @Override
        public void run() {
            while (!exited) {
                String clientInput;
                try {
                    clientInput = clientCommandReader.readLine();
                } catch (IOException e) {
                    System.out.println(e);
                    continue;
                }
                if (clientInput == null)
                    continue;

                if (clientInput.equals("enter")) {
                    try {
                        Message msg = new Message()
                                .type("enter")
                                .ipAddress(localHost.getHostAddress())
                                .port(port)
                                .senderID(id);
                        sendMessage(msg, bootstrapServerName, bootstrapServerPort);
                    } catch (Exception e) {
                        System.out.println(e);
                    }

                } else if (clientInput.equals("exit")) {

                    try {
                        if (predecessor == null || successor == null) {
                            System.out.println("Cannot exit: node has not joined the ring yet.");
                            continue;
                        }
                        Message msg = new Message()
                                .type("exit")
                                .senderID(id)
                                .successor(successor)
                                .kvPairs(keys);
                        sendMessage(msg, predecessor.ipAddress, predecessor.port);

                        Message msg2 = new Message()
                                .type("exit")
                                .senderID(id)
                                .predecessor(predecessor);

                        sendMessage(msg2, successor.ipAddress, successor.port);

                        exited = true;
                        incomingMsgs.close();
                        break;
                    } catch (Exception e) {
                        System.out.println(e);
                    }

                } else {
                    System.out.println("A valid command was not inputted. Only enter and exit are valid commands");
                }
            }
        }
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java TemporaryNameServer <config-file>");
            return;
        }
        Path nsConfigFile = Paths.get(args[0]);
        try {
            if (!Files.exists(nsConfigFile)) {
                System.out.println("The required name server descriptor file cannot be found.");
            } else {
                System.out.println("Extracting the name server ID, Port number, and Bootstrap Server name & port.");
                List<String> fileLines = Files.readAllLines(nsConfigFile);
                String[] bootstrapServerDetails = fileLines.get(2).split(" ");
                new TemporaryNameServer(Integer.parseInt(fileLines.get(1)),
                        Integer.parseInt(fileLines.get(0)), bootstrapServerDetails[0],
                        Integer.parseInt(bootstrapServerDetails[1])).start();
            }
        } catch (IOException e) {
            System.out.println("An error occured.");
            e.printStackTrace();
        }
    }
}
