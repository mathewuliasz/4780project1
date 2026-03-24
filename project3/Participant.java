
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.List;

public class Participant {

    //participant id, file, port, id addr & port of coordinator in a .txt file
    private int id;
    private Path filePath;
    private String coordinatorName;
    private int coordinatorPort;
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
        } catch (UnknownHostException e) {
            System.out.println(e);
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

                //can do a switch statement to simplify
                switch (cmd) {
                    case "msend":
                        sendToCoordinator("msend " + id + " " + commandArgs[1]);
                        break;
                    case "reconnect":
                        int reconPort = Integer.parseInt(commandArgs[1]);
                        //thread-B has to be operational before sending the message to the coordinator
                        startReceiver(reconPort);
                        sendToCoordinator("reconnect " + id + " " + localHost.getHostAddress() + " " + reconPort);
                        break;
                    case "disconnect":
                        sendToCoordinator("disconnect " + id);
                        stopReceiver();
                        break;
                    case "deregister":
                        sendToCoordinator("deregister " + id);
                        stopReceiver();
                        break;
                    case "register":
                        int regPort = Integer.parseInt(commandArgs[1]);
                        //thread-B has to be operational before sending the message to the coordinator
                        startReceiver(regPort);
                        sendToCoordinator("register " + id + " " + localHost.getHostAddress() + " " + regPort);
                        break;
                    case "quit":
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

    //opens a new connection to coordinator, sends command, waits for ACK, then closes
    private void sendToCoordinator(String message) {
        try {
            Socket s = new Socket(coordinatorName, coordinatorPort);
            DataOutputStream dataOut = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            DataInputStream dataIn = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            dataOut.writeUTF(message);
            dataOut.flush();
            //wait for acknowledgement from coordinator before unblocking
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

    //separate thread handles recv msgs and logging them
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
            try {
                while (running) {
                    Socket t = serverSocket.accept();
                    DataInputStream dataIn = new DataInputStream(new BufferedInputStream(t.getInputStream()));
                    String msg = dataIn.readUTF();
                    Files.write(filePath, (msg + "\n").getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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

    /* Multicast [message] to all current members. Note that
[message] is an alpha-numeric string (e.g., UGACSRocks). The participant sends the message to
the coordinator and unblocks after an acknowledgement is received. */

 /* Participant indicates to the coordinator that it is
online and it will specify the IP address and port number where its thread-B will receive
multicast messages (thread-B has to be operational before sending the message to the
coordinator).*/

 /*Participant indicates to the coordinator that it is temporarily going
offline. The coordinator will have to send it messages sent during disconnection (subject to
temporal constraint). Thread-B will relinquish the port and may become dormant or die.
     */

 /*
    Participant indicates to the coordinator that it is no longer belongs
to the multicast group. Please note that this is different than being disconnected. A participant
that deregisters, may register again. But it will not get any messages that were sent since its
deregistration (i.e., it will be treated as a new entrant). Thread-B will relinquish the port and
may become dormant or die.*/

 /* Participant has to register with the coordinator
specifying its ID, IP address and port number where its thread-B will receive multicast messages
(thread-B has to be operational before sending the message to the coordinator). Upon
successful registration, the participant is a member of the multicast group and will begin
receiving messages.
     */
    public static void main(String[] args) {
        //gets PP3-participant-conf.txt - extract each line and assign vals to declared attributes
        Path participantDescriptionFile = Paths.get(args[0]);
        try {
            if (!Files.exists(participantDescriptionFile)) {
                System.out.println("The required participant descriptor file cannot be found.");
            } else {
                System.out.println("Extracting the participant ID, Msg storing file name, and coordinator machine name & port.");
                List<String> fileLines = Files.readAllLines(participantDescriptionFile);
                String[] coordinatorDetails = fileLines.get(2).split(" ");
                Participant newParticipant = new Participant(Integer.parseInt(fileLines.get(0)), fileLines.get(1), coordinatorDetails[0], Integer.parseInt(coordinatorDetails[1]));
            }
        } catch (IOException e) {
            System.out.println("An error occured.");
            e.printStackTrace();
        }
    }

}
