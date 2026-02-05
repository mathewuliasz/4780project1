/*
-Implement tcp socket conn first then manage threads for each incoming request by user
-Need to deploy worker threads and make each one go do a specific func per. the clients req.
-Cleans up socket conn before shutting down, manage errors appropriately
*/

//block on accept
import java.net.*;
import java.io.*;
import java.nio.file.*;
public class myftpserver{
    private Socket s = null;
    private ServerSocket ss = null;
    private DataInputStream dataIn = null;
    private DataOutputStream dataOut = null;
    private Path currentDirectory;
    private Path rootDirectory;

    private synchronized void serverResponse(String msg){
        try {
            dataOut.writeBytes(msg + "\n");
            dataOut.flush();
        } catch (IOException e) {
            System.out.println("Error sending response: " + e.getMessage());
        }
    }

    // Helper method to read a line from DataInputStream
    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = dataIn.read()) != -1) {
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

    public myftpserver(int port) {
        try{
            ss = new ServerSocket(port);
            this.rootDirectory = Paths.get(System.getProperty("user.dir"));

            //keeps socket open even when client disconnects
            while(true){
                System.out.println("Waiting for client connection...");
                s = ss.accept();
                System.out.println("Client connected");

                
                dataIn = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                dataOut = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));

                
                this.currentDirectory = rootDirectory;

                //ensure clientMsg actually has args or return statement about false command
                while(true){
                    String clientMsg = readLine();
                    if(clientMsg == null || clientMsg.equals("quit")){
                        break;
                    }
                    System.out.println("Received command: " + clientMsg);
                    String[] commandArgs = clientMsg.split(" ");
                    if(commandArgs.length == 0 || commandArgs[0].isEmpty()){
                        serverResponse("Error with the formatting of the command");

                        continue;
                    }
                    clientCommandHandler clientCommand = new clientCommandHandler(commandArgs[0],commandArgs);
                    Thread clientCommandThread = new Thread(clientCommand);
                    clientCommandThread.start();
                }
                //close client connection, then loop back to accept new client
                System.out.println("Client disconnected");
                dataIn.close();
                dataOut.close();
                s.close();
            }
        }
        catch (IOException e){
            System.out.println(e);
        }
    }

    /*
    Class to handle a client command and runs a new thread for each inputted command
    to prevent blockage from I/O of an existing command task. This is where multithreading
    comes into place as it allows a dispatcher to spin up worker threads to handle each individual
    client command request allowing for true parallel execution.
    */
    private class clientCommandHandler implements Runnable{
        private String command;
        private String[] args;
    
        public clientCommandHandler(String command, String[] args){
            this.command = command;
            this.args = args;
        }
        //generated new thread for each command sent by the client to server
        //switch statements to send command to appropriate method handler
        @Override
        public void run(){
            boolean skipEnd = false;
            switch(command.toLowerCase()){
                case "get":
                    if (args.length > 1) {
                        get(this.args[1]);
                        skipEnd = true;  // get uses binary protocol, no END needed
                    } else {
                        serverResponse("Not enough args");
                    }
                    break;
                case "put":
                    if (args.length > 1) put(this.args[1]);
                    else serverResponse("Not enough args");
                    break;
                case "delete":
                    if (args.length > 1) delete(this.args[1]);
                    else serverResponse("Not enough args");
                    break;
                case "ls":
                    ls();
                    break;
                case "cd":
                    if (args.length > 1) cd(this.args[1]);
                    else serverResponse("Not enough args");
                    break;
                case "mkdir":
                    if (args.length > 1) mkdir(this.args[1]);
                    else serverResponse("Not enough args");
                    break;
                case "pwd":
                    pwd();
                    break;
                default:
                    serverResponse("Invalid command");
                    break;
            }
            if (!skipEnd) {
                serverResponse("END"); // Signal end of response
            }
        }
        /*
        FTP methods to handle client requests
        */
        private void get(String remote_filename){
            File file = new File(currentDirectory.toFile(), remote_filename);

            if (!file.exists() || !file.isFile()) {
                try {
                    dataOut.writeLong(-1);  
                    dataOut.flush();
                } catch (IOException e) {
                    System.out.println("Error sending error response: " + e.getMessage());
                }
                System.out.println("File not found: " + remote_filename);
                return;
            }

            try (FileInputStream fis = new FileInputStream(file);
                 BufferedInputStream bis = new BufferedInputStream(fis)) {

                long fileSize = file.length();
                dataOut.writeLong(fileSize);  

                byte[] buffer = new byte[8192];
                int bytesRead;

                while ((bytesRead = bis.read(buffer)) != -1) {
                    dataOut.write(buffer, 0, bytesRead);
                }
                dataOut.flush();
                System.out.println("File sent successfully: " + remote_filename);
            } 
            catch (IOException e){
                System.out.println("Error: failed to send file " + e.getMessage());
            }
        }
        //lets a client send File over TCP Socket to remote_directory
        //Prints out a statement to client if successful/not

        private void put(String local_filename){
            File file = new File(currentDirectory.toFile(), local_filename);
            try (FileOutputStream fos = new FileOutputStream(file);
                 BufferedOutputStream bos = new BufferedOutputStream(fos)) {
                long fileSize = dataIn.readLong();
                if (fileSize < 0){
                    serverResponse("Client has stopped the transfer of file: " + local_filename);
                    return;
                }
                byte[] buffer = new byte[8192];
                long totalRead = 0;
                int bytesRead;
                while (totalRead < fileSize && (bytesRead = dataIn.read(buffer, 0, (int)Math.min(buffer.length, fileSize - totalRead))) != -1) {
                    bos.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                }
                bos.flush();
                serverResponse("File " + local_filename + " received successfully");
            } catch (FileNotFoundException e){
                serverResponse("Could not create file: "+ local_filename);
            } catch (IOException e){
                serverResponse("IOException error: " + e.getMessage());
            }
        }
        //delete remote_filename file if found
        //returns a statement to client depending if successful/not
        private void delete(String remote_filename){
            File fileToDelete = new File(currentDirectory.toFile(), remote_filename);
            if(fileToDelete.delete()){
                serverResponse("Deleted file: " + remote_filename);
            } else{
                serverResponse("Failed to delete file: " + remote_filename);
            }
        }
        //lists all files and subdirectories within current working directory to client
        private void ls(){
            File currentDir = currentDirectory.toFile();
            File[] files = currentDir.listFiles();
            if(files != null){
                for(File file: files){
                    if(file.isDirectory()){
                        serverResponse("Directory: " + file.getName());
                    }else{
                        serverResponse("File: " + file.getName());
                    }
                }
            }
        }
        //ask professor about this one (subdirectory or given full absolute path?)
        //Prints response to client depending if dir exists or not
        //handles both remote_directory_name & .. (parent directory)
        //Need to handle edge case where user tries to exceed root directory
        private void cd(String remote_directory_name){
            if(remote_directory_name.equals("..") && !currentDirectory.equals(rootDirectory)){
                Path parentPath = currentDirectory.getParent();
                if (Files.exists(parentPath) && Files.isDirectory(parentPath)){
                currentDirectory = parentPath;
                serverResponse("Changed current working directory to " + currentDirectory.toString());
            } else{
                serverResponse("Already at root directory, cannot go further");
            }
            }else{
            Path desiredPath = currentDirectory.resolve(remote_directory_name).normalize();
            if(!desiredPath.getParent().equals(currentDirectory)){
                serverResponse("Can only cd into subdirectories of current working directory");
                return;
            }
            if (Files.exists(desiredPath) && Files.isDirectory(desiredPath)){
                currentDirectory = desiredPath;
                serverResponse("Changed current working directory to " + currentDirectory.toString());
            } else{
                serverResponse("Remote Directory not found");
            }
            }
        }
        //Creates dir using remote_directory_name using File library
        //Prints a certain response to client depending if successful or not
        private void mkdir(String remote_directory_name){
            File directory = new File(currentDirectory.toFile(),remote_directory_name);
            if(directory.exists()){
                serverResponse("This Directory already exists");
                return;
            }
            if(directory.mkdirs()){
                serverResponse("Directory created successfully at " + directory.getAbsolutePath());
            } else{
                serverResponse("Directory creation failed or the directory already exists");
            }
        }
        //Prints current working directory using system lib to client
        private void pwd(){
            serverResponse(currentDirectory.toString());
        }

    }
   public static void main(String[] args){
    int port = Integer.parseInt(args[0]);
    myftpserver serv = new myftpserver(port);
   }
}