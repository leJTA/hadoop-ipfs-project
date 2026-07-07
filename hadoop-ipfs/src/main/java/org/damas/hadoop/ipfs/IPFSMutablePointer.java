package org.damas.hadoop.ipfs;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.retry.ExponentialBackoffRetry;

public class IPFSMutablePointer {
    public static final String ZK_IPFS_ROOT = "/ipfs";
    public static final String IPFS_EMPTY_DIR_CID = "QmUNLLsPACCz1vLxQVkXqqLX5R1X345qqfHbsf67hvA3Nn";
    
    private final CuratorFramework client;
    private final InterProcessMutex lock;
    private final String lockPath = ZK_IPFS_ROOT + "/lock";
    private final String cidPath = ZK_IPFS_ROOT + "/root-cid";

    public IPFSMutablePointer(String zookeeperConnectionString, String rootCID) throws Exception {
        ExponentialBackoffRetry retryPolicy = new ExponentialBackoffRetry(1000, 3);
        
        this.client = CuratorFrameworkFactory.newClient(zookeeperConnectionString, retryPolicy);
        this.client.start();
        this.client.create().orSetData().forPath(this.cidPath, IPFS_EMPTY_DIR_CID.getBytes());
        
        this.lock = new InterProcessMutex(this.client, this.lockPath);
    }

    public IPFSMutablePointer(String zookeeperConnectionString) throws Exception {
        this(zookeeperConnectionString, IPFS_EMPTY_DIR_CID);
    }

    void lock() throws Exception {
        lock.acquire();
    }

    void unlock() throws Exception {
        lock.release();
    }
    
    public void set(String newCID) throws Exception {
        lock();
        this.client.setData().forPath(cidPath, newCID.getBytes());
        unlock();
    }

    public String get() throws Exception {
        lock();
        String cid = new String(this.client.getData().forPath(cidPath));
        unlock();
        return cid;
    }
}
