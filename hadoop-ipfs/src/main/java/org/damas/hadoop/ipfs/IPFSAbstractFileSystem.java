package org.damas.hadoop.ipfs;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.DelegateToFileSystem;

public class IPFSAbstractFileSystem extends DelegateToFileSystem {
    public IPFSAbstractFileSystem(URI uri, Configuration conf) 
        throws IOException, URISyntaxException {
        super(uri, new IPFSFileSystem(), conf, "ipfs", false);
    }
}
