
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class Coordinator {

    // member record
    private static class Member {
        int id;
        String ip;
        int port;
        Participant.State state;
        long disconnectTime; // epoch ms, set when OFFLINE
        Set<Integer> deliveredMsgIds; // tracks which messages this member has received

        Member(int id, String ip, int port) {
            this.id = id;
            this.ip = ip;
            this.port = port;
            this.state = Participant.State.ONLINE;
            this.disconnectTime = -1;
            this.deliveredMsgIds = ConcurrentHashMap.newKeySet();
        }
    }

    // message record
    private static class MulticastMessage {
        int id;
        int senderId;
        String content;
        long timestamp; // epoch ms

        MulticastMessage(int id, int senderId, String content) {
            this.id = id;
            this.senderId = senderId;
            this.content = content;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private final int port;
    private final long tdMillis; // persistence threshold in ms

    // shared state... all access via synchronized methods or concurrent collections
    private final Map<Integer, Member> members = new ConcurrentHashMap<>();
    private final List<MulticastMessage> messageLog = new CopyOnWriteArrayList<>();
    private int nextMsgId = 0;

    public Coordinator(int port, int tdSeconds) {
        this.port = port;
        this.tdMillis = tdSeconds * 1000L;
    }

    public void start() {
        //capped at 8 worker threads
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Coordinator listening on port " + port + " (td=" + (tdMillis / 1000) + "s)");
            while (true) {
                Socket client = serverSocket.accept();
                pool.execute(() -> handleRequest(client));
            }
        } catch (IOException e) {
            System.out.println("Coordinator error: " + e);
        } finally {
            pool.shutdown();
        }
    }

    // request handler
    private void handleRequest(Socket socket) {
        try {
            socket.setSoTimeout(5000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            String message = in.readUTF();
            String[] parts = message.split(" ");
            String cmd = parts[0].toLowerCase();

            switch (cmd) {
                case "register":
                    handleRegister(parts, out);
                    break;
                case "deregister":
                    handleDeregister(parts, out);
                    break;
                case "disconnect":
                    handleDisconnect(parts, out);
                    break;
                case "reconnect":
                    handleReconnect(parts, out);
                    break;
                case "msend":
                    handleMsend(message, out);
                    break;
                default:
                    out.writeUTF("ERROR unknown command: " + cmd);
                    out.flush();
            }

            in.close();
            out.close();
            socket.close();
        } catch (IOException e) {
            System.out.println("Error handling request: " + e);
        }
    }

    // register <id> <ip> <port>
    private synchronized void handleRegister(String[] parts, DataOutputStream out) throws IOException {
        int id = Integer.parseInt(parts[1]);
        String ip = parts[2];
        int port = Integer.parseInt(parts[3]);

        Member m = members.get(id);
        if (m == null) {
            // brand new participant
            members.put(id, new Member(id, ip, port));
            System.out.println("Registered new participant " + id);
        } else {
            // re-registering after deregister/clear history
            m.ip = ip;
            m.port = port;
            m.state = Participant.State.ONLINE;
            m.disconnectTime = -1;
            m.deliveredMsgIds.clear();
            System.out.println("Re-registered participant " + id);
        }
        out.writeUTF("ACK register " + id);
        out.flush();
    }

    // deregister <id>
    private synchronized void handleDeregister(String[] parts, DataOutputStream out) throws IOException {
        int id = Integer.parseInt(parts[1]);
        Member m = members.get(id);
        if (m != null) {
            m.state = Participant.State.UNREGISTERED;
            System.out.println("Deregistered participant " + id);
        } else {
            System.out.println("Deregister ignored: participant " + id + " not found");
        }
        out.writeUTF("ACK deregister " + id);
        out.flush();
    }

    // disconnect <id>
    private synchronized void handleDisconnect(String[] parts, DataOutputStream out) throws IOException {
        int id = Integer.parseInt(parts[1]);
        Member m = members.get(id);
        if (m != null) {
            m.state = Participant.State.OFFLINE;
            m.disconnectTime = System.currentTimeMillis();
            System.out.println("Participant " + id + " disconnected");
        }
        out.writeUTF("ACK disconnect " + id);
        out.flush();
    }

    // reconnect <id> <ip> <port>
    private void handleReconnect(String[] parts, DataOutputStream out) throws IOException {
        int id = Integer.parseInt(parts[1]);
        String ip = parts[2];
        int port = Integer.parseInt(parts[3]);

        List<MulticastMessage> toDeliver;

        // synchronized block to update state and snapshot messages to deliver
        synchronized (this) {
            Member m = members.get(id);
            if (m == null) {
                out.writeUTF("ERROR participant " + id + " not found");
                out.flush();
                return;
            }

            m.ip = ip;
            m.port = port;
            long prevDisconnectTime = m.disconnectTime;
            m.state = Participant.State.ONLINE;
            m.disconnectTime = -1;

            // ACK first, then deliver
            out.writeUTF("ACK reconnect " + id);
            out.flush();

            System.out.println("Participant " + id + " reconnected");

            long cutoff = System.currentTimeMillis() - tdMillis;
            toDeliver = new ArrayList<>();
            for (MulticastMessage msg : messageLog) {
                if (msg.timestamp >= cutoff
                        && msg.timestamp >= prevDisconnectTime
                        && !m.deliveredMsgIds.contains(msg.id)) {
                    toDeliver.add(msg);
                }
            }
        }

        // deliver catch-up messages outside the synchronized block
        for (MulticastMessage msg : toDeliver) {
            deliverToParticipant(members.get(id), msg);
        }
    }

    // msend <senderId> <message>
    private void handleMsend(String message, DataOutputStream out) throws IOException {
        String[] parts = message.split(" ", 3);
        if (parts.length < 3 || parts[2].trim().isEmpty()) {
            out.writeUTF("ERROR usage: msend <senderId> <message>");
            out.flush();
            return;
        }

        int senderId;
        try {
            senderId = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            out.writeUTF("ERROR invalid senderId: " + parts[1]);
            out.flush();
            return;
        }
        String content = parts[2].trim();

        MulticastMessage msg;
        List<Member> onlineMembers;

        synchronized (this) {
            msg = new MulticastMessage(nextMsgId++, senderId, content);
            messageLog.add(msg);

            // ACK the sender immediately
            out.writeUTF("ACK msend msgId=" + msg.id);
            out.flush();

            System.out.println("Multicast from " + senderId + ": \"" + content + "\" (msgId=" + msg.id + ")");

            // snapshot online members
            onlineMembers = new ArrayList<>();
            for (Member m : members.values()) {
                if (m.state == Participant.State.ONLINE) {
                    onlineMembers.add(m);
                }
            }
        }

        // deliver to all online members outside the synchronized block
        for (Member m : onlineMembers) {
            deliverToParticipant(m, msg);
        }
    }

    // deliver one message to one participant
    private void deliverToParticipant(Member m, MulticastMessage msg) {
        if (m == null)
            return;
        if (!m.deliveredMsgIds.add(msg.id))
            return;

        try {
            Socket s = new Socket(m.ip, m.port);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            // MSG <msgId> <senderId> <content>
            out.writeUTF("MSG " + msg.id + " " + msg.senderId + " " + msg.content);
            out.flush();
            out.close();
            s.close();
            System.out.println("Delivered msgId=" + msg.id + " to participant " + m.id);
        } catch (IOException e) {
            m.deliveredMsgIds.remove(msg.id);
            System.out.println("Failed to deliver msgId=" + msg.id + " to participant " + m.id + ": " + e);
        }
    }

    public static void main(String[] args) {
        Path configFile = Paths.get(args[0]);
        try {
            if (!Files.exists(configFile)) {
                System.out.println("Coordinator config file not found.");
                return;
            }
            List<String> lines = Files.readAllLines(configFile);
            int port = Integer.parseInt(lines.get(0).trim());
            int td = Integer.parseInt(lines.get(1).trim());
            System.out.println("Starting coordinator on port " + port + " with td=" + td + "s");
            new Coordinator(port, td).start();
        } catch (IOException e) {
            System.out.println("Failed to read config: " + e);
        }
    }
}
