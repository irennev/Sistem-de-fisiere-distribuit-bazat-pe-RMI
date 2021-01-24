import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.rmi.AccessException;
import java.rmi.NotBoundException;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ReplicaServer implements ReplicaServerClientInterface,
		ReplicaMasterInterface, ReplicaReplicaInterface, Remote {

	
	private int regPort = Configurations.REG_PORT;
	private String regAddr = Configurations.REG_ADDR;
	
	private int id;
	private String dir;
	private Registry registry;
	
	private Map<Long, String> activeTxn; // map between active transactions and file names
	private Map<Long, Map<Long, byte[]>> txnFileMap; // map between transaction ID and corresponding file chunks
	private Map<String,	 List<ReplicaReplicaInterface> > filesReplicaMap; //replicas where files that this replica is its master are replicated  
	private Map<Integer, ReplicaLoc> replicaServersLoc; // Map<ReplicaID, replicaLoc>
	private Map<Integer, ReplicaReplicaInterface> replicaServersStubs; // Map<ReplicaID, replicaStub>
	private ConcurrentMap<String, ReentrantReadWriteLock> locks; // locks objects of the open files
	
	public static BufferedWriter replicaServerLog;
	
	public ReplicaServer(int id, String dir) throws IOException {
		this.id = id;
		this.dir = dir+"/Replica_"+id+"/";
		
		txnFileMap = new TreeMap<Long, Map<Long, byte[]>>();
		activeTxn = new TreeMap<Long, String>();
		filesReplicaMap = new TreeMap<String, List<ReplicaReplicaInterface>>();
		replicaServersLoc = new TreeMap<Integer, ReplicaLoc>();
		replicaServersStubs = new TreeMap<Integer, ReplicaReplicaInterface>();
		locks = new ConcurrentHashMap<String, ReentrantReadWriteLock>();
		
		replicaServerLog = new BufferedWriter(new FileWriter("replicaServerLog.txt"));
		
		File file = new File(this.dir);
		if (!file.exists()){
			file.mkdir();
		}
		
		try  {
			registry = LocateRegistry.getRegistry(regAddr, regPort);
		} catch (RemoteException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void deleteFile(String fileName) throws IOException {
		File file = new File(dir+fileName);
		ReentrantReadWriteLock lock = locks.get(fileName);
		
		lock.writeLock().lock();
		file.delete();
		lock.writeLock().unlock();
	}
	
	@Override
	public void createFile(String fileName) throws IOException {
		File file = new File(dir+fileName);
		
		locks.putIfAbsent(fileName, new ReentrantReadWriteLock());
		ReentrantReadWriteLock lock = locks.get(fileName);
		
		lock.writeLock().lock();
		file.createNewFile();
		lock.writeLock().unlock();
	}

	@Override
	public FileContent read(String fileName) throws FileNotFoundException,
			RemoteException, IOException {
		File f = new File(dir+fileName);
		
		locks.putIfAbsent(fileName, new ReentrantReadWriteLock());
		ReentrantReadWriteLock lock = locks.get(fileName);
		
		@SuppressWarnings("resource")
		BufferedInputStream br = new BufferedInputStream(new FileInputStream(f));
		
		// assuming files are small and can fit in memory
		byte data[] = new byte[(int) (f.length())];
		
		lock.readLock().lock();
		br.read(data);
		lock.readLock().unlock();
		
		FileContent content = new FileContent(fileName, data);
		return content;
	}

	@Override
	public ChunkAck write(long txnID, long msgSeqNum, FileContent data)
			throws RemoteException, IOException {
		System.out.println("[@ReplicaServer] write "+msgSeqNum);
		replicaServerLog.write(new Timestamp(new Date().getTime()) + ": [@ReplicaServer] write "+msgSeqNum + "\n");
		replicaServerLog.flush();
		
		// if this is not the first message of the write transaction
		if (!txnFileMap.containsKey(txnID)){
			txnFileMap.put(txnID, new TreeMap<Long, byte[]>());
			activeTxn.put(txnID, data.getFileName());
		}

		Map<Long, byte[]> chunkMap =  txnFileMap.get(txnID);
		chunkMap.put(msgSeqNum, data.getData());
		return new ChunkAck(txnID, msgSeqNum);
	}

	@Override
	public boolean commit(long txnID, long numOfMsgs)
			throws MessageNotFoundException, RemoteException, IOException {
		
		
		System.out.println("[@Replica] commit intiated");
		replicaServerLog.write(new Timestamp(new Date().getTime()) + ": [@Replica] commit intiated\n");
		replicaServerLog.flush();
		
		Map<Long, byte[]> chunkMap = txnFileMap.get(txnID);
		if (chunkMap.size() < numOfMsgs)
			throw new MessageNotFoundException();
		
		String fileName = activeTxn.get(txnID);
		List<ReplicaReplicaInterface> slaveReplicas = filesReplicaMap.get(fileName);
		
		for (ReplicaReplicaInterface replica : slaveReplicas) {
			boolean sucess = replica.reflectUpdate(txnID, fileName, new ArrayList<>(chunkMap.values()));
			if (!sucess) {
				// TODO handle failure 
			}
		}
		
		
		BufferedOutputStream bw =new BufferedOutputStream(new FileOutputStream(dir+fileName, true));
		
		locks.putIfAbsent(fileName, new ReentrantReadWriteLock());
		ReentrantReadWriteLock lock = locks.get(fileName);
		
		lock.writeLock().lock();
		for (Iterator<byte[]> iterator = chunkMap.values().iterator(); iterator.hasNext();) 
			bw.write(iterator.next());
		bw.close();
		lock.writeLock().unlock();
		
		
		for (ReplicaReplicaInterface replica : slaveReplicas) 
			replica.releaseLock(fileName);
		
		
		activeTxn.remove(txnID);
		txnFileMap.remove(txnID);
		
		return false;
	}

	@Override
	public boolean abort(long txnID) throws RemoteException {
		activeTxn.remove(txnID);
		filesReplicaMap.remove(txnID);
		return false;
	}


	@Override
	public boolean reflectUpdate(long txnID, String fileName, ArrayList<byte[]> data) throws IOException{
		System.out.println("[@Replica] reflect update initiated");
		replicaServerLog.write(new Timestamp(new Date().getTime()) + ": [@Replica] reflect update initiated\n");
		replicaServerLog.flush();
		BufferedOutputStream bw =new BufferedOutputStream(new FileOutputStream(dir+fileName, true));


		locks.putIfAbsent(fileName, new ReentrantReadWriteLock());
		ReentrantReadWriteLock lock = locks.get(fileName);
		
		lock.writeLock().lock(); // don't release lock here .. making sure coming reads can't proceed
		for (Iterator<byte[]> iterator = data.iterator(); iterator.hasNext();) 
			bw.write(iterator.next());
		bw.close();
		
		
		activeTxn.remove(txnID);
		return true;
	}

	@Override
	public void releaseLock(String fileName) {
		ReentrantReadWriteLock lock = locks.get(fileName);
		lock.writeLock().unlock();
	}

	@Override
	public void takeCharge(String fileName, List<ReplicaLoc> slaveReplicas) throws NotBoundException, IOException {
		System.out.println("[@Replica] taking charge of file: "+fileName);
		replicaServerLog.write(new Timestamp(new Date().getTime()) + ": [@Replica] taking charge of file: "+fileName+"\n");
		replicaServerLog.flush();
		System.out.println(slaveReplicas);
		
		List<ReplicaReplicaInterface> slaveReplicasStubs = new ArrayList<ReplicaReplicaInterface>(slaveReplicas.size());
		
		for (ReplicaLoc loc : slaveReplicas) {
			// if the current locations is this replica .. ignore
			if (loc.getId() == this.id)
				continue;
			  
			// if this is a new replica generate stub for this replica
			if (!replicaServersLoc.containsKey(loc.getId())){
				replicaServersLoc.put(loc.getId(), loc);
				ReplicaReplicaInterface stub = (ReplicaReplicaInterface) registry.lookup("ReplicaClient"+loc.getId());
				replicaServersStubs.put(loc.getId(), stub);
			}
			ReplicaReplicaInterface replicaStub = replicaServersStubs.get(loc.getId());
			slaveReplicasStubs.add(replicaStub);
		}
		
		filesReplicaMap.put(fileName, slaveReplicasStubs);
	}
	

	@Override
	public boolean isAlive() {
		return true;
	}
	
}
