package org.damas.hadoop.ipfs;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

import org.apache.hadoop.fs.FSExceptionMessages;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PositionedReadable;
import org.apache.hadoop.fs.Seekable;

import io.ipfs.api.IPFS;

public class IPFSDataInputStream extends InputStream implements Seekable, PositionedReadable {
    private IPFS ipfs;
    private Path path;
    private int bufferSize;
    private long position;

    public IPFSDataInputStream(IPFS ipfs, Path path, int bufferSize) {
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("Buffer size <= 0");
        }

        this.ipfs = ipfs;
        this.path = path;
        this.bufferSize = bufferSize;
        this.position = 0;
    }

    @Override
    public int read() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        // The url pattern for IPFSFilesystem is "ipfs://rootCID/<path>"
        // The "cat" request sent through the ipfs http api is 
        // "http://<host>:<port>/api/v0/cat?arg=<path>&offset=<offset>&length=<length>"
        String apiVersion = IPFSFileSystem.API_VERSION;
        String path = URLEncoder.encode(this.path.toString(), "UTF-8");
        String arg;
        URL target;
        
        // IPFS path
        if (this.path.toString().startsWith("/ipfs/")) {
            arg = path + "&offset=" + (position + offset) + "&length=" + length;
            target = new URL(ipfs.protocol, ipfs.host, ipfs.port, apiVersion + "cat?arg=" + arg);
        }
        // MFS path
        else {
            arg = path + "&offset=" + (position + offset) + "&count=" + length;
            target = new URL(ipfs.protocol, ipfs.host, ipfs.port, apiVersion + "files/read?arg=" + arg);
        }
        
        HttpURLConnection conn = (HttpURLConnection)target.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(IPFSFileSystem.CONNECT_TIMEOUT_MILLIS);
        conn.setReadTimeout(IPFSFileSystem.READ_TIMEOUT_MILLIS);
        conn.setDoOutput(true);

        try {
            OutputStream out = conn.getOutputStream();
            out.write(new byte[0]);
            out.flush();
            out.close();
            InputStream in = new BufferedInputStream(conn.getInputStream(), bufferSize);
            int bytes = in.read(buffer, 0, length);
            seek(position + bytes);

            return bytes;
        } catch (ConnectException var9) {
            throw new RuntimeException("Couldn't connect to IPFS daemon at " + 
                                        String.valueOf(target) + "\n Is IPFS running?");
        } catch (IOException e) {
            throw IPFS.extractError(e, conn);
        }
    }

    @Override
    public int read(long position, byte[] buffer, int offset, int length) throws IOException {
        validatePositionedReadArgs(position, buffer, offset, length);
        if (length == 0) {
            return 0;
        }
        long oldPos = getPos();
        int nread = -1;
        try {
            seek(position);
            nread = read(buffer, offset, length);
        } catch (EOFException e) { 
            e.printStackTrace();
        } finally {
            seek(oldPos);
        }
        return nread;
    }

    @Override
    public void readFully(long position, byte[] buffer, int offset, int length) throws IOException {
        validatePositionedReadArgs(position, buffer, offset, length);
        int nread = 0;
        while (nread < length) {
            int nbytes = read(position + nread, buffer, offset + nread, length - nread);
            if (nbytes < 0) {
                throw new EOFException(FSExceptionMessages.EOF_IN_READ_FULLY);
            }
            nread += nbytes;
        }
    }

    @Override
    public void readFully(long position, byte[] buffer) throws IOException {
        readFully(position, buffer, 0, buffer.length);
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
    public boolean seekToNewSource(long targetPos) throws IOException {
        throw new UnsupportedOperationException();
    }

    protected void validatePositionedReadArgs(long position,
        byte[] buffer, int offset, int length) throws EOFException {
        if (position < 0) {
            throw new EOFException("position is negative");
        }
        if (buffer.length - offset < length) {
            throw new IndexOutOfBoundsException(
                FSExceptionMessages.TOO_MANY_BYTES_FOR_DEST_BUFFER
                + ": request length=" + length
                + ", with offset ="+ offset
                + "; buffer capacity =" + (buffer.length - offset));
        }
    }
}
