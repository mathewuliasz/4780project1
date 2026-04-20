
/*
involved in all diff. message types for traversing a ring of server nodes.
-Join Ring, Find_position, Position_found
-Update predecessor & successor
-Lookup, Insert, Delete
-Result msg containing list of servers contacted
-EXit_notify msg for temp name server leaving the ring

 */
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.HashMap;

public class Message implements Serializable {

    private static final long serialVersionUID = 1L;

    public String type;
    public int senderID;
    public int targetID;
    public NameServerInfo predecessor;
    public NameServerInfo successor;
    public int keyRangeStart;
    public int keyRangeEnd;
    public HashMap<Integer, String> kvPairs;
    public int key;
    public String value;
    public String ipAddress;
    public String serverTraversal;
    public int port;

    public Message() {
        this.serverTraversal = "";
    }

    public Message type(String type) {
        this.type = type;
        return this;
    }

    public Message senderID(int senderID) {
        this.senderID = senderID;
        return this;
    }

    public Message targetID(int targetID) {
        this.targetID = targetID;
        return this;
    }

    public Message predecessor(NameServerInfo p) {
        this.predecessor = p;
        return this;
    }

    public Message successor(NameServerInfo s) {
        this.successor = s;
        return this;
    }

    public Message keyRangeStart(int start) {
        this.keyRangeStart = start;
        return this;
    }

    public Message keyRangeEnd(int end) {
        this.keyRangeEnd = end;
        return this;
    }

    public Message keyRange(int start, int end) {
        this.keyRangeStart = start;
        this.keyRangeEnd = end;
        return this;
    }

    public Message kvPairs(HashMap<Integer, String> kv) {
        this.kvPairs = kv;
        return this;
    }

    public Message key(int key) {
        this.key = key;
        return this;
    }

    public Message value(String value) {
        this.value = value;
        return this;
    }

    public Message ipAddress(String ipAddress) {
        this.ipAddress = ipAddress;
        return this;
    }

    public Message port(int port) {
        this.port = port;
        return this;
    }

    public byte[] toBytes() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(this);
        }
        return baos.toByteArray();
    }

    public static Message fromBytes(byte[] data) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return (Message) ois.readObject();
        }
    }

    public Message serverTraverMessage(String msg) {
        this.serverTraversal += msg;
        return this;
    }
}
