/*
-Implement tcp socket conn first then manage threads for each incoming request by user
-Need to deploy worker threads and make each one go do a specific func per. the clients req.
-Cleans up socket conn before shutting down, manage errors appropriately
*/

//block on accept
import java.net.*;
import java.io.*;
import java.nio.file.*;
import java.util.HashMap;
public class myftpserver {
    // DataInput/Output Streams need to be formed in each individual client connection thread
    //HashMap goes here in parent class
    private ServerSocket ss = null;
    private ServerSocket ts = null;
    private Path rootDirectory;
    private HashMap<String, Object> fileLocks;
    private HashMap<Long, Thread> commandIds;

    
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

    public myftpserver(int nport,int tport) {
        try {
            ss = new ServerSocket(nport);
            ts = new ServerSocket(tport);
            fileLocks = new HashMap<>();
            commandIds = new HashMap<>();
            this.rootDirectory = Paths.get(System.getProperty("user.dir"));

            while (true) {
                System.out.println("Waiting for client connection...");
                Socket s = ss.accept();
                Socket t = ts.accept();
                Thread newClient = new Thread(new clientHandler(s,t));
                newClient.start();
            }
        } catch (IOException e) {
            System.out.println(e);
        }
    }

    /*
     * Class to handle a client command and runs a new thread for each inputted
     * command
     * to prevent blockage from I/O of an existing command task. This is where
     * multithreading
     * comes into place as it allows a dispatcher to spin up worker threads to
     * handle each individual
     * client command request allowing for true parallel execution.
     */
    private class clientHandler implements Runnable{
        private Path currentDirectory;
        private Socket s; // Socket for nport
        private Socket t; // Socket for tport
        private DataInputStream nsockDataIn = null;
        private DataOutputStream nsockDataOut = null;
        private DataInputStream tsockDataIn = null;
        private DataOutputStream tsockDataOut = null;
        
        public clientHandler(Socket s, Socket t){
            this.currentDirectory = rootDirectory;
            this.s = s;
            this.t = t;
        }

        private void serverResponse(String msg, DataOutputStream dos) {
        try {
            dos.writeBytes(msg + "\n");
            dos.flush();
        } catch (IOException e) {
            System.out.println("Error sending response: " + e.getMessage());
        }
        }

        // Method to terminate command based on its thread-ID. thread-ID is unique for each thread during its life so using this to terminate.
    // Will end Thread that is associated with the commandId
    // Synchronized/ reentrant lock to handle concurrent req to cancel 
        public void terminateCommand(long commandId){
        if(commandIds.containsKey(commandId)){
            Thread terminationThread = commandIds.get(commandId);
            terminationThread.interrupt();
            commandIds.remove(commandId);
        } else{
            serverResponse("The command-id: " + commandId + " does not correspond to any current Thread in process", tsockDataOut);
        }
    }

        @Override
        public void run() {
            try{
            System.out.println("Client connected from: " + s.getInetAddress());
            // Each client Thread gets its own streams for input/output for both socket connections
            nsockDataIn = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            nsockDataOut = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));

            tsockDataIn = new DataInputStream(new BufferedInputStream(t.getInputStream()));
            tsockDataOut = new DataOutputStream(new BufferedOutputStream(t.getOutputStream()));

                Runnable task = () -> {
                    try{
                        while (true) {
                            String terminationMsg = readLine(tsockDataIn);
                                if(terminationMsg == null){
                                    break;
                                }else{
                                    System.out.println("Received termination command: " + terminationMsg);
                                    String[] terminationMsgArgs = terminationMsg.split(" ");
                                    if(terminationMsgArgs.length == 0 || terminationMsgArgs[0].isEmpty()) {
                                        serverResponse("Error with the formatting of the command", tsockDataOut);
                                        continue;
                                    }
                                    terminateCommand(Long.parseLong(terminationMsgArgs[1])); // terminate <command-ID>
                                }
                        }
                    } catch (IOException e){
                        serverResponse("Error occured " + e, tsockDataOut);
                    }
                };
            while (true) {
                    String clientMsg = readLine(nsockDataIn);
                    if (clientMsg == null || clientMsg.equals("quit")) {
                        break;
                    }else{
                    System.out.println("Received command: " + clientMsg);
                    String[] commandArgs = clientMsg.split(" ");
                    if (commandArgs.length == 0 || commandArgs[0].isEmpty()) {
                        serverResponse("Error with the formatting of the command", nsockDataOut);
                        serverResponse("END", nsockDataOut);
                        continue;
                    }
                    Thread clientCommand = new Thread(new clientCommandHandler(commandArgs[0], commandArgs));
                    commandIds.put(clientCommand.threadId(), clientCommand); // Puts the command-id & Thread into hashMap for easy checking
                    clientCommand.start();

                }
            }
        } catch(IOException e){
            System.out.println("Error occured " + e);
        } finally{
            System.out.println("Client disconnected from " + s.getInetAddress());
            try { nsockDataIn.close(); } catch (IOException e) { System.out.println("Error closing nsockDataIn: " + e); }
            try { nsockDataOut.close(); } catch (IOException e) { System.out.println("Error closing nsockDataOut: " + e); }
            try { tsockDataIn.close(); } catch (IOException e) { System.out.println("Error closing tsockDataIn: " + e); }
            try { tsockDataOut.close(); } catch (IOException e) { System.out.println("Error closing tsockDataOut: " + e); }
            try { s.close(); } catch (IOException e) { System.out.println("Error closing s: " + e); }
            try { t.close(); } catch (IOException e) { System.out.println("Error closing t: " + e); }
        }
    }
    
        private class clientCommandHandler implements Runnable {
        private String command;
        private String[] args;

        public clientCommandHandler(String command, String[] args) {
            this.command = command;
            this.args = args;
        }

        // generated new thread for each command sent by the client to server
        // switch statements to send command to appropriate method handler
        @Override
        public void run() {
            boolean skipEnd = false;
            switch (command.toLowerCase()) {
                case "get":
                    if (args.length > 1) {
                        get(this.args[1]);
                        skipEnd = true; // get uses binary protocol, no END needed
                        serverResponse(Long.toString(Thread.currentThread().threadId()), nsockDataOut);
                    } else {
                        serverResponse("Not enough args", nsockDataOut);
                    }
                    break;
                case "put":
                    if (args.length > 1) {
                        put(this.args[1]);
                        serverResponse(Long.toString(Thread.currentThread().threadId()), nsockDataOut);
                    }
                    else {
                        serverResponse("Not enough args", nsockDataOut);
                    }
                    break;
                case "delete":
                    if (args.length > 1) {
                        delete(this.args[1]);
                    }
                    else {
                        serverResponse("Not enough args", nsockDataOut);
                    }
                    break;
                case "ls":
                    ls();
                    break;
                case "cd":
                    if (args.length > 1) {
                        cd(this.args[1]);
                    }
                    else {
                        serverResponse("Not enough args", nsockDataOut);
                    }
                    break;
                case "mkdir":
                    if (args.length > 1) {
                        mkdir(this.args[1]);
                    }
                    else {
                        serverResponse("Not enough args", nsockDataOut);
                    }
                    break;
                case "pwd":
                    pwd();
                    break;
                default:
                    serverResponse("Invalid command", nsockDataOut);
                    break;
            }
            if (!skipEnd) {
                serverResponse("END", nsockDataOut); // Signal end of response
            }
        }
        
        //Have a queue for concurrent req acting on same files? -> Reentrant lock? for get/put

        /*
         * FTP methods to handle client requests
         */
        private void get(String remote_filename) {
            File file = new File(currentDirectory.toFile(), remote_filename);

            if (!file.exists() || !file.isFile()) {
                try {
                    nsockDataOut.writeLong(-1);
                    nsockDataOut.flush();
                } catch (IOException e) {
                    System.out.println("Error sending error response: " + e.getMessage());
                }
                System.out.println("File not found: " + remote_filename);
                return;
            }

            try (FileInputStream fis = new FileInputStream(file);
                    BufferedInputStream bis = new BufferedInputStream(fis)) {

                long fileSize = file.length();
                nsockDataOut.writeLong(fileSize);

                byte[] buffer = new byte[8192];
                int bytesRead;

                while ((bytesRead = bis.read(buffer)) != -1) {
                    if(bytesRead % 1000 == 0){
                        if(!commandIds.containsKey(Thread.currentThread().threadId())){

                            // Ensure creation of file is deleted on client?
                            break;
                        }
                    }
                    nsockDataOut.write(buffer, 0, bytesRead);
                }
                nsockDataOut.flush();
                System.out.println("File sent successfully: " + remote_filename);
            } catch (IOException e) {
                System.out.println("Error: failed to send file " + e.getMessage());
            }
        }
        // lets a client send File over TCP Socket to remote_directory
        // Prints out a statement to client if successful/not

        private void put(String local_filename) {
            serverResponse("Command-ID: " + Long.toString(Thread.currentThread().threadId()), nsockDataOut);
            File file = new File(currentDirectory.toFile(), local_filename);
            try (FileOutputStream fos = new FileOutputStream(file);
                    BufferedOutputStream bos = new BufferedOutputStream(fos)) {
                System.out.println("Reading file size");
                long fileSize = nsockDataIn.readLong();
                System.out.println("File size received: " + fileSize);
                if (fileSize < 0) {
                    serverResponse("Client has stopped the transfer of file: " + local_filename, nsockDataOut);
                    return;
                }
                byte[] buffer = new byte[8192];
                long totalRead = 0;
                int bytesRead;
                while (totalRead < fileSize && (bytesRead = nsockDataIn.read(buffer, 0,
                    (int) Math.min(buffer.length, fileSize - totalRead))) != -1) 
                    {
                    bos.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                    if(totalRead % 1000 == 0){
                        if(!commandIds.containsKey(Thread.currentThread().threadId())){
                            //delete is not the appropriate way of handling this
                            //delete(file); //Deletes the file that was created before receiving bytes
                            break;
                        }
                    }
                }
                bos.flush();
                System.out.println("File received, sending response");
                serverResponse("File " + local_filename + " received successfully", nsockDataOut);
                System.out.println("Response sent");
            } catch (FileNotFoundException e) {
                serverResponse("Could not create file: " + local_filename, nsockDataOut);
            } catch (IOException e) {
                serverResponse("IOException error: " + e.getMessage(), nsockDataOut);
            }
        }

        // delete remote_filename file if found
        // returns a statement to client depending if successful/not
        private void delete(String remote_filename) {
            serverResponse("Command-ID: " + Long.toString(Thread.currentThread().threadId()), nsockDataOut);
            File fileToDelete = new File(currentDirectory.toFile(), remote_filename);
            if (fileToDelete.delete()) {
                serverResponse("Deleted file: " + remote_filename, nsockDataOut);
            } else {
                serverResponse("Failed to delete file: " + remote_filename, nsockDataOut);
            }
        }

        // lists all files and subdirectories within current working directory to client
        private void ls() {
            File currentDir = currentDirectory.toFile();
            File[] files = currentDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        serverResponse("Directory: " + file.getName(), nsockDataOut);
                    } else {
                        serverResponse("File: " + file.getName(), nsockDataOut);
                    }
                }
            }
        }

        // ask professor about this one (subdirectory or given full absolute path?)
        // Prints response to client depending if dir exists or not
        // handles both remote_directory_name & .. (parent directory)
        // Need to handle edge case where user tries to exceed root directory
        private void cd(String remote_directory_name) {
            if (remote_directory_name.equals("..") && !currentDirectory.equals(rootDirectory)) {
                Path parentPath = currentDirectory.getParent();
                if (Files.exists(parentPath) && Files.isDirectory(parentPath)) {
                    currentDirectory = parentPath;
                    serverResponse("Changed current working directory to " + currentDirectory.toString(), nsockDataOut);
                } else {
                    serverResponse("Already at root directory, cannot go further", nsockDataOut);
                }
            } else {
                Path desiredPath = currentDirectory.resolve(remote_directory_name).normalize();
                if (!desiredPath.getParent().equals(currentDirectory)) {
                    serverResponse("Can only cd into subdirectories of current working directory", nsockDataOut);
                    return;
                }
                if (Files.exists(desiredPath) && Files.isDirectory(desiredPath)) {
                    currentDirectory = desiredPath;
                    serverResponse("Changed current working directory to " + currentDirectory.toString(), nsockDataOut);
                } else {
                    serverResponse("Remote Directory not found", nsockDataOut);
                }
            }
        }

        // Creates dir using remote_directory_name using File library
        // Prints a certain response to client depending if successful or not
        private void mkdir(String remote_directory_name) {
            File directory = new File(currentDirectory.toFile(), remote_directory_name);
            if (directory.exists()) {
                serverResponse("This Directory already exists", nsockDataOut);
                return;
            }
            if (directory.mkdirs()) {
                serverResponse("Directory created successfully at " + directory.getAbsolutePath(), nsockDataOut);
            } else {
                serverResponse("Directory creation failed or the directory already exists", nsockDataOut);
            }
        }

        // Prints current working directory using system lib to client
        private void pwd() {
            serverResponse(currentDirectory.toString(), nsockDataOut);
        }
        }
    }

    public static void main(String[] args) {
        int nport = Integer.parseInt(args[0]);
        int tport = Integer.parseInt(args[1]);
        new myftpserver(nport, tport);
    }
}