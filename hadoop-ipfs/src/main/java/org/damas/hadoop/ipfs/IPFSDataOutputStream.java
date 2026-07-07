package org.damas.hadoop.ipfs;

import java.io.OutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Paths;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.Seekable;

import io.ipfs.api.IPFS;
import io.ipfs.api.Multipart;
import io.ipfs.api.NamedStreamable;

public class IPFSDataOutputStream extends OutputStream implements Seekable {
    // private ZkIPFSPointer zkIPFSPtr;
    private IPFS ipfs;
    private Path path;
    private long position;
    protected byte[] buf;
    protected int count;

    public IPFSDataOutputStream(IPFS ipfs, Path path) throws  IOException {
        this.ipfs = ipfs;
        this.path = path;
        this.position = 0;

        // Write dummy data to the file to make sure it is created if it doesn't exist
        write(new byte[0]);
    }

    @Override 
    public void write(int b) throws IOException {
        write(new byte[]{(byte)b}, 0, 1);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
        // The "write" request sent through the ipfs http api is 
        // "http://<host>:<port>/api/v0/files/write?arg=<path>&offset=<offset>&count=<length>&parents=true&create=true"
        // IPFS paths are not supported for this operation
        String apiVersion = IPFSFileSystem.API_VERSION;
        String path = URLEncoder.encode(this.path.toString(), "UTF-8");
        String arg = path + "&offset=" + (offset + position) + "&count=" + length + "&parents=true&create=true";
        URL target = new URL(ipfs.protocol, ipfs.host, ipfs.port, apiVersion + "files/write?arg=" + arg);
        Multipart m = new Multipart(target.toString(), "UTF-8");
        m.addFilePart("file", Paths.get(""), new NamedStreamable.ByteArrayWrapper(buffer));
        m.finish();
        seek(position + length);
    }

    @Override
    public void seek(long pos) throws IOException {
        position = pos;
    }

    @Override
    public long getPos() throws IOException {
        return position;
    }

    @Override
    public void flush() throws IOException {
        seek(0);
    }

    @Override
    public void close() throws IOException {
        seek(0);
    }

    @Override
    public boolean seekToNewSource(long targetPos) throws IOException {
        throw new UnsupportedOperationException();
    }
}
