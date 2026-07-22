package org.damas.hadoop.ipfs;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import io.ipfs.api.IPFS;
import io.ipfs.api.MerkleNode;
import io.ipfs.api.NamedStreamable;

public class IPFSDataOutputStream extends OutputStream {
    private static final String TEMP_DIRECTORY = System.getProperty("java.io.tmpdir");

    private IPFS ipfs;
    private Path path;
    private FileOutputStream fostream;
    private Path localPath;
    protected byte[] buf;
    protected int count;

    public IPFSDataOutputStream(IPFS ipfs, Path path) throws  IOException {
        this.ipfs = ipfs;
        this.path = path;
        this.localPath = new Path(TEMP_DIRECTORY + this.path.toString());

        File parentDir = new File(this.localPath.getParent().toString());
        if (!parentDir.exists()) {
            parentDir.mkdirs();
        }

        this.fostream = new FileOutputStream(TEMP_DIRECTORY + this.path.toString());
    }

    @Override 
    public void write(int b) throws IOException {
        this.fostream.write(b);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
        this.fostream.write(buffer, offset, length);
    }

    @Override
    public void flush() throws IOException {
        this.fostream.flush();
    }

    @Override
    public void close() throws IOException {
        this.fostream.close();
        // When finished writing, push the file to IPFS
        push();
    }

    private void push() throws IOException {
        List<MerkleNode> nodes = ipfs.add(new NamedStreamable.FileWrapper(new File(localPath.toString())));
        dumpCids(nodes);
    }
    
    private void dumpCids(List<MerkleNode> nodes) throws IOException {
        Configuration conf = new Configuration();
        FileSystem fs = FileSystem.get(conf);
        Path path = new Path(this.path + "/cids.txt");

        FSDataOutputStream out = null;
        try {
            if (fs.exists(path)) {
                out = fs.append(path);
            } else {
                out = fs.create(path);
            }
            for (MerkleNode node : nodes) {
                out.write(node.hash.toBytes());
                out.write(System.lineSeparator().getBytes(StandardCharsets.UTF_8));
                out.hflush();
            }
        } finally {
            if (out != null) {
                out.close();
            }
            fs.close();
        }
    }
}
