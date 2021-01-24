import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Scanner;



public class Client {


	MasterServerClientInterface masterStub;
	static Registry registry;
	int regPort = Configurations.REG_PORT;
	String regAddr = Configurations.REG_ADDR;
	int chunkSize = Configurations.CHUNK_SIZE; // in bytes 
	
	public static BufferedWriter clientLog;
	
	public Client() throws IOException {
		try {
			clientLog = new BufferedWriter(new FileWriter("clientLog.txt"));
			registry = LocateRegistry.getRegistry(regAddr, regPort);
			masterStub =  (MasterServerClientInterface) registry.lookup("MasterServerClientInterface");
			System.out.println("[@client] Master Stub fetched successfuly");
			clientLog.write(new Timestamp(new Date().getTime()) + ": [@client] Master Stub fetched successfuly\n");
			clientLog.flush();
		} catch (RemoteException | NotBoundException e) {
			// fatal error .. no registry could be linked
			e.printStackTrace();
		}
	}

	public byte[] read(String fileName) throws IOException, NotBoundException{
		List<ReplicaLoc> locations = masterStub.read(fileName);
		System.out.println("[@client] Master Granted read operation");
		clientLog.write(new Timestamp(new Date().getTime()) + ": [@client] Master Granted read operation\n");
		clientLog.flush();
		
		// TODO fetch from all and verify 
		ReplicaLoc replicaLoc = locations.get(0);

		ReplicaServerClientInterface replicaStub = (ReplicaServerClientInterface) registry.lookup("ReplicaClient"+replicaLoc.getId());
		FileContent fileContent = replicaStub.read(fileName);
		System.out.println("[@client] read operation completed successfuly");
		clientLog.write(new Timestamp(new Date().getTime()) + ": [@client] read operation completed successfuly\n");
		clientLog.flush();
		System.out.println("[@client] read data:");
		clientLog.write(new Timestamp(new Date().getTime()) + ": [@client] read data:\n");
		clientLog.flush();
		System.out.println(new String(fileContent.getData()));		
		
		return fileContent.getData();
	}
	
	public ReplicaServerClientInterface initWrite(String fileName, Long txnID) throws IOException, NotBoundException{
		WriteAck ackMsg = masterStub.write(fileName);
		txnID = new Long(ackMsg.getTransactionId());
		return (ReplicaServerClientInterface) registry.lookup("ReplicaClient"+ackMsg.getLoc().getId());
	}
	
	public void writeChunk (long txnID, String fileName, byte[] chunk, long seqN, ReplicaServerClientInterface replicaStub) throws RemoteException, IOException{
		FileContent fileContent = new FileContent(fileName, chunk);
		ChunkAck chunkAck;
		
		do { 
			chunkAck = replicaStub.write(txnID, seqN, fileContent);
		} while(chunkAck.getSeqNo() != seqN);
	}
	
	public void write (String fileName, byte[] data) throws IOException, NotBoundException, MessageNotFoundException{
		WriteAck ackMsg = masterStub.write(fileName);
		ReplicaServerClientInterface replicaStub = (ReplicaServerClientInterface) registry.lookup("ReplicaClient"+ackMsg.getLoc().getId());
		
		System.out.println("[@client] Master granted write operation");
		clientLog.write(new Timestamp(new Date().getTime()) + ": [@client] Master granted write operation\n");
		clientLog.flush();
		
		int segN = (int) Math.ceil(1.0*data.length/chunkSize);
		FileContent fileContent = new FileContent(fileName);
		ChunkAck chunkAck;
		byte[] chunk = new byte[chunkSize];
		
		for (int i = 0; i < segN-1; i++) {
			System.arraycopy(data, i*chunkSize, chunk, 0, chunkSize);
			fileContent.setData(chunk);
			do { 
				chunkAck = replicaStub.write(ackMsg.getTransactionId(), i, fileContent);
			} while(chunkAck.getSeqNo() != i);
		}

		// Handling last chunk of the file < chunk size
		int lastChunkLen = chunkSize;
		if (data.length%chunkSize > 0)
			lastChunkLen = data.length%chunkSize;
		//System.out.println("Last chunck length: "+ lastChunkLen);
		chunk = new byte[lastChunkLen];
		System.arraycopy(data, data.length-lastChunkLen, chunk, 0, lastChunkLen);
		//System.arraycopy(data, segN-1, chunk, 0, lastChunkLen);
		fileContent.setData(chunk);
		do { 
			chunkAck = replicaStub.write(ackMsg.getTransactionId(), segN-1, fileContent);
			//chunkAck = replicaStub.write(ackMsg.getTransactionId(), segN-1, fileContent);
		//} while(chunkAck.getSeqNo() != segN-1 );
		} while(chunkAck.getSeqNo() != segN-1);
		
		
		System.out.println("[@client] write operation complete");
		clientLog.write(new Timestamp(new Date().getTime()) + ": [@client] write operation complete\n");
		clientLog.flush();
		replicaStub.commit(ackMsg.getTransactionId(), segN);
		System.out.println("[@client] commit operation complete");
		clientLog.write(new Timestamp(new Date().getTime()) + ": [@client] commit operation complete\n");
		clientLog.flush();
	}
	
	public void commit(String fileName, long txnID, long seqN) throws MessageNotFoundException, IOException, NotBoundException{
		ReplicaLoc primaryLoc = masterStub.locatePrimaryReplica(fileName);
		ReplicaServerClientInterface primaryStub = (ReplicaServerClientInterface) registry.lookup("ReplicaClient"+primaryLoc.getId());
		primaryStub.commit(txnID, seqN);
		System.out.println("[@client] commit operation complete");
		clientLog.write(new Timestamp(new Date().getTime()) + ": [@client] commit operation complete\n");
		clientLog.flush();
	}
	
	public void batchOperations(String[] cmds){
		System.out.println("[@client] batch operations started");
		String cmd ;
		String[] tokens;
		for (int i = 0; i < cmds.length; i++) {
			cmd = cmds[i];
			tokens = cmd.split(", ");
			try {
				if (tokens[0].trim().equals("read"))
					this.read(tokens[1].trim());
				else if (tokens[0].trim().equals("write"))
					this.write(tokens[1].trim(), tokens[2].trim().getBytes());
				else if (tokens[0].trim().equals("commit"))
						this.commit(tokens[1].trim(), Long.parseLong(tokens[2].trim()), Long.parseLong(tokens[3].trim()));
			}catch (IOException | NotBoundException | MessageNotFoundException e){
				System.err.println("Operation "+i+" Failed");
			}
		}
		System.out.println("[@client] batch operations completed");
	}
	
	//adaugat de Irina
	private static void printFiles(Map<String, List<ReplicaLoc> > filesLocationMap) {
		
		String leftAlignFormat = "| %-15s | %-20s |%n";

		System.out.format("+-----------------+----------------------+%n");
		System.out.format("| Files           | Locations            |%n");
		System.out.format("+-----------------+----------------------+%n");
		
		for (Map.Entry<String, List<ReplicaLoc>> entry : filesLocationMap.entrySet()) {
		    for(ReplicaLoc loc : entry.getValue()) {
		    	System.out.format(leftAlignFormat, entry.getKey(), loc.toString());
	        }
		}
		
		System.out.format("+-----------------+----------------------+%n");
		
	}
	
	//adaugat de Irina
	public static void main(String[] args) throws IOException, NotBoundException, MessageNotFoundException {
		Client client = new Client();
				
		/*char[] ss = "[INITIAL DATA!]".toCharArray(); // len = 15
		byte[] data = new byte[ss.length];
		for (int i = 0; i < ss.length; i++) 
			data[i] = (byte) ss[i];
		client.write("file4", data);*/

		
		@SuppressWarnings("resource")
		Scanner scanner = new Scanner(System.in);
		
        try {
        	System.out.println("\nWrite \"Help\" to see all commands");
        	
            while (true) {
            	
                System.out.print("Command > ");
                
                String command = scanner.nextLine();
                
                String[] userCommand = command.split(" <");
                
                if (userCommand[0].startsWith("append") 
                		|| userCommand[0].startsWith("del") 
                		|| userCommand[0].startsWith("type")) {
                	String text = userCommand[1].substring(0, userCommand[1].length()-1);
                	userCommand[1] = text;
                }
                
                //System.out.println("User's command " + userCommand[0] + "  "+userCommand[1]);
                
                switch(userCommand[0]) {
                	case "Help": {
                		System.out.println("dir                       \tDisplays a list of files and their locations.\n"
                						 + "del <yourFile>            \tDelets one file.\n"
                				         + "type <yourFile>           \tDisplays the contents of a file.\n"
                				         + "append <yourText:yourFile>\tAppends the text to a file.\n");
                		break;
                	}
                	case "dir": {
                		Map<String, List<ReplicaLoc> > filesLocationMap;
                		filesLocationMap = client.masterStub.getFiles();
                		Client.printFiles(filesLocationMap);
                		break;
                	}
                	case "del": {
                		client.masterStub.deleteFile(userCommand[1]);
                		System.out.println("[@Client] File successfully deleted.");
                		break;
                	}
                	case "type": {
                		try {
                			client.read(userCommand[1]);
                		}catch (FileNotFoundException e){
                			System.out.println(userCommand[1] + " file not found.");
                		}
                		break;
                	}
                	case "append": {
                		String[] text_file = new String[2];
                		try {
                			text_file = userCommand[1].split(":", 2);
                			
                			//check the commands
                			//for(int i =0; i<text_file.length; i++) {
                			//	System.out.println(text_file[i]);
                			//}
                			
                			String string = text_file[0];
                			
                			char[] ss = string.toCharArray(); 
                			byte[] data = new byte[ss.length];
                			for (int i = 0; i < ss.length; i++) 
                				data[i] = (byte) ss[i];
                			
                			
                			client.write(text_file[1], data);
                			
                		}catch (FileNotFoundException e){
                			System.out.println(text_file[1] + " file not found.");
                		}
                		break;
                	}
                	default:
                	    System.out.println("'" + userCommand[1] + "'" + " is not recognized as an internal or external command.");
                }
            }
        } catch(IllegalStateException | NoSuchElementException e) {
            // System.in has been closed
            System.out.println("System.in was closed; exiting");
        }
	}
	
}
