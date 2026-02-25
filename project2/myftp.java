
/*
EVERYTHING needs to be error checked via try/catch if needed
*/
import java.io.*;
import java.net.*;
import java.nio.file.*;

public class myftp {
    private Socket s = null;
    private Socket t = null;
    private DataInputStream nsockDataIn = null;
    private DataOutputStream nsockDataOut = null;
    private DataInputStream tsockDataIn = null;
    private DataOutputStream tsockDataOut = null;
    private BufferedReader clientCommandInput = null;

    public myftp(String addr, int nport, int tport) {
        try {
            s = new Socket(addr, nport);
            t = new Socket(addr, tport);
            System.out.println("Connected");

            nsockDataIn = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            nsockDataOut = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            tsockDataIn = new DataInputStream(new BufferedInputStream(t.getInputStream()));
            tsockDataOut = new DataOutputStream(new BufferedOutputStream(t.getOutputStream()));

            clientCommandInput = new BufferedReader(new InputStreamReader(System.in));

        } catch (UnknownHostException e) {
            System.out.println(e);
            return;
        } catch (IOException e) {
            System.out.println(e);
            return;
        }
        
        String command = "";
        while (!command.equals("quit")) {
            try {
                System.out.print("myftp> ");
                command = clientCommandInput.readLine();
                if(command == null){
                    break;
                }

                String[] parts = command.split(" ");
                String cmd = parts[0].toLowerCase();
                if (cmd.equals("terminate") && parts.length > 1){
                    tsockDataOut.writeBytes(command + "\n");
                    tsockDataOut.flush();
                }
                else if (cmd.equals("get") && parts.length > 1) {
                    nsockDataOut.writeBytes(command + "\n");
                    nsockDataOut.flush();
                    receiveFileFromServer(parts[1]);

                } else if (cmd.equals("put") && parts.length > 1) {
                    Path currentDirectory = Paths.get(System.getProperty("user.dir"));
                    File file = new File(currentDirectory.toFile(), parts[1]);
                    if (!file.exists() || !file.isFile()) {
                        System.out.println("File not found: " + parts[1]);
                        continue;
                    }
                    nsockDataOut.writeBytes(command + "\n");
                    nsockDataOut.flush();
                    sendFileToServer(parts[1]);
                    String serverResp;
                    while ((serverResp = readLine(nsockDataIn)) != null) {
                        if (serverResp.equals("END")) {
                            break;
                        }
                        System.out.println(serverResp);
                    }

                } else {
                    nsockDataOut.writeBytes(command + "\n");
                    nsockDataOut.flush();
                    String serverResp;
                    //check if this is correct
                    while ((serverResp = readLine(nsockDataIn)) != null) {
                        if (serverResp.equals("END")) {
                            break;
                        }
                        System.out.println(serverResp);
                    }
                }
            } catch (IOException e) {
                System.out.println(e);
            }
        }
        try {
            nsockDataIn.close();
            nsockDataOut.close();
            tsockDataIn.close();
            tsockDataOut.close();
            clientCommandInput.close();
            s.close();
            t.close();
        } catch (IOException e) {
            System.out.println(e);
        }
    }

    
    private String readLine(DataInputStream dis) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = dis.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                sb.append((char) c);
            }
        }
        if (c == -1 && sb.length() == 0) {
            return null;
        }
        return sb.toString();
    }

    // Receive file from server using shared dataIn stream
    public void receiveFileFromServer(String file_name) {
        Path currentDirectory = Paths.get(System.getProperty("user.dir"));
        File file = new File(currentDirectory.toFile(), file_name);

        try {
            long fileSize = nsockDataIn.readLong();
            if (fileSize < 0) {
                System.out.println("Server error: file not found");
                return; // Return before creating file
            }

            try (FileOutputStream fos = new FileOutputStream(file);
                    BufferedOutputStream bos = new BufferedOutputStream(fos)) {

                byte[] buffer = new byte[8192];
                long totalRead = 0;
                int bytesRead;
                while (totalRead < fileSize && (bytesRead = nsockDataIn.read(buffer, 0,
                        (int) Math.min(buffer.length, fileSize - totalRead))) != -1) {
                    bos.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                }
                bos.flush();
                System.out.println("File " + file_name + " received successfully");
            }
        } catch (FileNotFoundException e) {
            System.out.println("Could not create file: " + file_name);
        } catch (IOException e) {
            System.out.println("IOException error: " + e.getMessage());
        }
    }

    // Send file to server using shared dataOut stream
    public void sendFileToServer(String file_name) {
        Path currentDirectory = Paths.get(System.getProperty("user.dir"));
        File file = new File(currentDirectory.toFile(), file_name);

        if (!file.exists() || !file.isFile()) {
            System.out.println("File not found: " + file_name);
            return;
        }

        try (FileInputStream fis = new FileInputStream(file);
                BufferedInputStream bis = new BufferedInputStream(fis)) {
            long fileSize = file.length();
            nsockDataOut.writeLong(fileSize);

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = bis.read(buffer)) != -1) {
                nsockDataOut.write(buffer, 0, bytesRead);
            }
            nsockDataOut.flush();
            System.out.println("File " + file_name + " sent successfully");
        } catch (FileNotFoundException e) {
            System.out.println("File not found: " + file_name);
        } catch (IOException e) {
            System.out.println("IOException error: " + e.getMessage());
        }
    }
    

    public static void main(String[] args) {
        String machineName = args[0];
        int nportNumber = Integer.parseInt(args[1]);
        int tportNumber = Integer.parseInt(args[2]);
        new myftp(machineName, nportNumber, tportNumber);
    }
}