import java.io.FileNotFoundException;
import java.io.IOException;
import java.rmi.RemoteException;
import java.util.List;
import java.util.Map;

public interface MasterServerClientInterface extends MasterInterface {

	/**
	 * Read file from server
	 * 
	 * @param fileName
	 * @return File data
	 * @throws FileNotFoundException
	 * @throws IOException
	 * @throws RemoteException
	 */
	public List<ReplicaLoc> read(String fileName) throws FileNotFoundException,
			IOException, RemoteException;
	
	/**
	 * Lists files from server
	 * @throws RemoteException
	 */
	public Map<String, List<ReplicaLoc> > getFiles() throws RemoteException;

	
	/**
	 * Deletes files
	 * 
	 * @param fileName
	 * @throws RemoteException
	 */
	public void deleteFile(String fileName) throws RemoteException, IOException;
	
	
	/**
	 * Start a new write transaction
	 * 
	 * @param fileName
	 * @return the required info
	 * @throws RemoteException
	 * @throws IOException
	 */
	public WriteAck write(String fileName) throws RemoteException, IOException;
	
	
	/**
	 * @param fileName
	 * @return the replica location of the primary replica of that file
	 * @throws RemoteException
	 */
	public ReplicaLoc locatePrimaryReplica(String fileName) throws RemoteException;
}
