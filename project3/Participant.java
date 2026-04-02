
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.List;

public class Participant {

    public enum State {
        UNREGISTERED, ONLINE, OFFLINE
    }

    // participant id, file, port, id addr & port of coordinator in a .txt file
    private int id;
    private Path filePath;
    private String coordinatorName;
    private int coordinatorPort;
    private State state = State.UNREGISTERED;
    private BufferedReader clientCommandInput = null;
    private InetAddress localHost = null;
    private Thread bThread;
    private ReceiveMulticastMessages receiver;

    public Participant(int id, String filePath, String coordinatorName, int coordinatorPort) {
        this.id = id;
        this.filePath = Paths.get(filePath);
        this.coordinatorName = coordinatorName;
        this.coordinatorPort = coordinatorPort;
        try {
            clientCommandInput = new BufferedReader(new InputStreamReader(System.in));
            localHost = InetAddress.getLocalHost();
        } catch (IOException e) {
            System.out.println("Could not resolve local address: " + e);
            return;
        }
        String command = "";
        while (!command.equals("quit")) {
            try {
                command = clientCommandInput.readLine();
                if (command == null) {
                    break;
                }
                String[] commandArgs = command.split(" ");
                String cmd = commandArgs[0].toLowerCase();

                switch (cmd) {
                    case "msend":
                        if (commandArgs.length < 2) { // length check
                            System.out.println("Usage: msend <message>");
                            break;
                        }
                        if (state != State.ONLINE) {
                            System.out.println("Cannot msend: not currently registered and online.");
                            break;
                        }
                        String msgContent = command.substring("msend ".length()).trim();
                        sendToCoordinator("msend " + id + " " + msgContent);
                        break;
                    case "reconnect":
                        if (state != State.OFFLINE) {
                            System.out.println("Cannot reconnect: not currently disconnected.");
                            break;
                        }
                        int reconPort = Integer.parseInt(commandArgs[1]);
                        // thread-B has to be operational before sending the message to the coordinator
                        startReceiver(reconPort);
                        sendToCoordinator("reconnect " + id + " " + localHost.getHostAddress() + " " + reconPort);
                        state = State.ONLINE;
                        break;
                    case "disconnect":
                        if (state != State.ONLINE) {
                            System.out.println("Cannot disconnect: not currently online.");
                            break;
                        }
                        sendToCoordinator("disconnect " + id);
                        stopReceiver();
                        state = State.OFFLINE;
                        break;
                    case "deregister":
                        if (state == State.UNREGISTERED) {
                            System.out.println("Cannot deregister: not currently registered.");
                            break;
                        }
                        sendToCoordinator("deregister " + id);
                        stopReceiver();
                        state = State.UNREGISTERED;
                        break;
                    case "register":
                        if (state != State.UNREGISTERED) {
                            System.out.println("Cannot register: already registered. Deregister first.");
                            break;
                        }
                        int regPort = Integer.parseInt(commandArgs[1]);
                        // thread-B has to be operational before sending the message to the coordinator
                        startReceiver(regPort);
                        sendToCoordinator("register " + id + " " + localHost.getHostAddress() + " " + regPort);
                        state = State.ONLINE;
                        break;
                    case "quit":
                        // auto deregister the participant on quit
                        if (state == State.ONLINE || state == State.OFFLINE) {
                            sendToCoordinator("deregister " + id);
                            stopReceiver();
                        }
                        break;
                    default:
                        System.out.println("Unknown command: " + cmd);
                        break;
                }
            } catch (IOException e) {
                System.out.println(e);
            }
        }
        stopReceiver();
        try {
            clientCommandInput.close();
        } catch (IOException e) {
            System.out.println(e);
        }
    }

    // opens a new connection to coordinator, sends command, waits for ACK, then
    // closes
    private void sendToCoordinator(String message) {
        try {
            Socket s = new Socket(coordinatorName, coordinatorPort);
            s.setSoTimeout(10000); // 10s timeout so main thread doesn't block forever
            DataOutputStream dataOut = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            DataInputStream dataIn = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            dataOut.writeUTF(message);
            dataOut.flush();
            // wait for acknowledgement from coordinator before unblocking
            String ack = dataIn.readUTF();
            System.out.println(ack);
            dataIn.close();
            dataOut.close();
            s.close();
        } catch (IOException e) {
            System.out.println("Error communicating with coordinator: " + e);
        }
    }

    private void startReceiver(int port) {
        stopReceiver();
        receiver = new ReceiveMulticastMessages(port);
        bThread = new Thread(receiver);
        bThread.start();
    }

    private void stopReceiver() {
        if (receiver != null) {
            receiver.shutdown();
            receiver = null;
        }
        if (bThread != null) {
            try {
                bThread.join();
            } catch (InterruptedException e) {
                System.out.println(e);
            }
            bThread = null;
        }
    }

    // separate thread handles recv msgs and logging them
    public class ReceiveMulticastMessages implements Runnable {

        private ServerSocket serverSocket = null;
        private volatile boolean running = true;

        public ReceiveMulticastMessages(int port) {
            try {
                serverSocket = new ServerSocket(port);
            } catch (IOException e) {
                System.out.println(e);
            }
        }

        @Override
        public void run() {
            if (serverSocket == null) {
                System.out.println("Receiver failed to start: serverSocket is null.");
                return;
            }
            try {
                while (running) {
                    Socket t = serverSocket.accept();
                    DataInputStream dataIn = new DataInputStream(new BufferedInputStream(t.getInputStream()));
                    String msg = dataIn.readUTF();
                    Files.write(filePath, (msg + "\n").getBytes(), StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND);
                    dataIn.close();
                    t.close();
                }
            } catch (IOException e) {
                if (running) {
                    System.out.println("Multicast Message Receiver disconnected " + e);
                }
            }
        }

        public void shutdown() {
            running = false;
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                }
            } catch (IOException e) {
                System.out.println(e);
            }
        }
    }

    public static void main(String[] args) {
        // gets PP3-participant-conf.txt... extract each line and assign vals to declared
        // attributes
        Path participantDescriptionFile = Paths.get(args[0]);
        try {
            if (!Files.exists(participantDescriptionFile)) {
                System.out.println("The required participant descriptor file cannot be found.");
            } else {
                System.out.println(
                        "Extracting the participant ID, Msg storing file name, and coordinator machine name & port.");
                List<String> fileLines = Files.readAllLines(participantDescriptionFile);
                String[] coordinatorDetails = fileLines.get(2).split(" ");
                Participant newParticipant = new Participant(Integer.parseInt(fileLines.get(0)), fileLines.get(1),
                        coordinatorDetails[0], Integer.parseInt(coordinatorDetails[1]));
            }
        } catch (IOException e) {
            System.out.println("An error occured.");
            e.printStackTrace();
        }
    }

}
