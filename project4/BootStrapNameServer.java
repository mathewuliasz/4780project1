
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;

public class BootStrapNameServer {

    InetAddress localHost = null;
    BufferedReader clientCommandReader = null;
    HashMap<Integer, String> keys;
    HashSet<Integer> listOfServers;
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

    public BootStrapNameServer(String ipAddress, int port) {
        this.id = 0;
        this.bootstrapServerName = ipAddress;
        this.port = port;
        this.bootstrapServerPort = port;
        keys = new HashMap<>();
        listOfServers = new HashSet<>();
        try {
            localHost = InetAddress.getLocalHost();
            incomingMsgs = new ServerSocket(port);
        } catch (IOException e) {
            System.out.println("Could not initialize bootstrap: " + e);
            return;
        }
        this.keyRange = new int[] { 0, 1023 };
        this.successor = new NameServerInfo(this.id, localHost.getHostAddress(), this.port);
        this.predecessor = new NameServerInfo(this.id, localHost.getHostAddress(), this.port);
    }

    public void start() {
        if (incomingMsgs == null) {
            System.out.println("Bootstrap not initialized, cannot start.");
            return;
        }
        new Thread(new UserInteraction(), "bootstrap-cli").start();
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
                if (!exited) System.out.println(e);
            }
        }
    }

    void handleMessage(Message msg) {
        if (msg.type.equals("enter")) {
            if (listOfServers.contains(msg.senderID)) {
                System.out.println("Node with server id " + msg.senderID + " already exists on ring.");
                sendMessage(new Message().type("enter_rejected"), msg.ipAddress, msg.port);
            } else if (msg.senderID > this.id && (successor.id == 0 || successor.id > msg.senderID)) {
                int threshold = msg.senderID;
                HashMap<Integer, String> slice = new HashMap<>();
                Iterator<Map.Entry<Integer, String>> it = this.keys.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Integer, String> kv = it.next();
                    if (kv.getKey() >= threshold) {
                        slice.put(kv.getKey(), kv.getValue());
                        it.remove();
                    }
                }

                Message foundPosition = new Message()
                        .type("found_position")
                        .predecessor(new NameServerInfo(this.id, localHost.getHostAddress(), this.port))
                        .successor(this.successor)
                        .keyRange(threshold, this.keyRange[1])
                        .kvPairs(slice);

                sendMessage(foundPosition, msg.ipAddress, msg.port);

                this.successor = new NameServerInfo(msg.senderID, msg.ipAddress, msg.port);
                this.keyRange[1] = threshold - 1;
                listOfServers.add(msg.senderID);

            } else {
                Message findPos = new Message()
                        .type("find_position")
                        .senderID(msg.senderID)
                        .ipAddress(msg.ipAddress)
                        .port(msg.port);
                sendMessage(findPos, successor.ipAddress, successor.port);
                listOfServers.add(msg.senderID);
            }
        } else if (msg.type.equals("exit")) {
            if (msg.senderID == successor.id && msg.successor != null) {
                this.successor = msg.successor;
                this.keyRange[1] = (msg.successor.id == 0) ? 1023 : msg.successor.id - 1;
                if (msg.kvPairs != null)
                    this.keys.putAll(msg.kvPairs);
            }
            if (msg.senderID == predecessor.id) {
                this.predecessor = msg.predecessor;
            }
            listOfServers.remove(msg.senderID);
        } else if (msg.type.equals("update_predecessor")) {
            this.predecessor = msg.predecessor;
        } else if (msg.type.equals("lookup")) {
            if (msg.value != null) {
                System.out.println("Key " + msg.key + " = " + msg.value);
            } else {
                System.out.println("Key " + msg.key + " not found");
            }
            System.out.println(msg.serverTraversal);
        } else if (msg.type.equals("insert")) {
            System.out.println("Insert complete for key " + msg.key);
            System.out.println(msg.serverTraversal);
        } else if (msg.type.equals("delete")) {
            System.out.println("Delete complete for key " + msg.key);
            System.out.println(msg.serverTraversal);
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
            while (true) {
                String clientInput;
                try {
                    clientInput = clientCommandReader.readLine();
                } catch (IOException e) {
                    System.out.println(e);
                    continue;
                }
                String[] clientInputArgs = clientInput.split(" ");
                if (clientInputArgs[0].equals("insert")) {
                    if (clientInputArgs.length < 3) {
                        System.out.println("Usage: insert <key> <value>");
                        continue;
                    }
                    int parsedKey;
                    try {
                        parsedKey = Integer.parseInt(clientInputArgs[1]);
                    } catch (NumberFormatException e) {
                        System.out.println("Key must be an integer");
                        continue;
                    }
                    if (parsedKey >= keyRange[0] && parsedKey <= keyRange[1]) {
                        keys.put(parsedKey, clientInputArgs[2]);
                        System.out.println("Inserted at server " + id);
                    } else if (successor == null) {
                        System.out.println("Cannot put k,v pair into ring");
                    } else {
                        Message insertMsg = new Message()
                                .type("insert")
                                .ipAddress(bootstrapServerName)
                                .port(bootstrapServerPort)
                                .key(parsedKey)
                                .value(clientInputArgs[2])
                                .serverTraverMessage("Sequence of servers: " + id);

                        sendMessage(insertMsg, successor.ipAddress, successor.port);
                    }
                } else if (clientInputArgs[0].equals("lookup")) {
                    if (clientInputArgs.length < 2) {
                        System.out.println("Usage: lookup <key>");
                        continue;
                    }
                    int parsedArg;
                    try {
                        parsedArg = Integer.parseInt(clientInputArgs[1]);
                    } catch (NumberFormatException e) {
                        System.out.println("Key must be an integer");
                        continue;
                    }
                    if (parsedArg >= keyRange[0] && parsedArg <= keyRange[1]) {
                        if (keys.containsKey(parsedArg)) {
                            System.out.println("Value: " + keys.get(parsedArg));
                            System.out.println("Sequence of servers: " + id);
                        } else {
                            System.out.println("Key not found");
                        }
                    } else {
                        Message lookupMsg = new Message()
                                .type("lookup")
                                .ipAddress(bootstrapServerName)
                                .port(bootstrapServerPort)
                                .key(parsedArg)
                                .serverTraverMessage("Sequence of servers: " + id);

                        sendMessage(lookupMsg, successor.ipAddress, successor.port);
                    }

                } else if (clientInputArgs[0].equals("delete")) {
                    if (clientInputArgs.length < 2) {
                        System.out.println("Usage: delete <key>");
                        continue;
                    }
                    int parsedKey;
                    try {
                        parsedKey = Integer.parseInt(clientInputArgs[1]);
                    } catch (NumberFormatException e) {
                        System.out.println("Key must be an integer");
                        continue;
                    }
                    if (parsedKey >= keyRange[0] && parsedKey <= keyRange[1]) {
                        if (keys.containsKey(parsedKey)) {
                            keys.remove(parsedKey);
                            System.out.println("Successful Deletion");
                            System.out.println("Sequence of servers: " + id);
                        } else {
                            System.out.println("Key not found");
                        }
                    } else {
                        Message deleteMsg = new Message()
                                .type("delete")
                                .ipAddress(bootstrapServerName)
                                .port(bootstrapServerPort)
                                .key(parsedKey)
                                .serverTraverMessage("Sequence of servers: " + id);

                        sendMessage(deleteMsg, successor.ipAddress, successor.port);
                    }

                } else {
                    System.out.println(
                            "A valid command was not inputted. Only insert, lookup, and delete are valid commands");
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java BootStrapNameServer <port>");
            return;
        }
        int port = Integer.parseInt(args[0]);
        String ip = InetAddress.getLocalHost().getHostAddress();
        new BootStrapNameServer(ip, port).start();
    }
}
