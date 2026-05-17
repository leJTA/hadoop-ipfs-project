package org.damas.hadoop.ipfs;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FSExceptionMessages;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PositionedReadable;
import org.apache.hadoop.fs.Seekable;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.util.Progressable;

import io.ipfs.api.IPFS;
import io.ipfs.api.JSONParser;
import io.ipfs.api.MerkleNode;

public class IPFSFileSystem extends FileSystem {

    public static final String API_VERSION = "/api/v0";
    public static final String SCHEME = "ipfs";
    public static final String IPFS_DEFAULT_HTTP_GATEWAY = "localhost";
    public static final int    IPFS_DEFAULT_HTTP_PORT = 5001;
    public static final int    CONNECT_TIMEOUT_MILLIS = 10000; 
    public static final int    READ_TIMEOUT_MILLIS = 60000;

    public enum FILE_TYPE {
        FILE, DIRECTORY, SYMLINK;

        public static FILE_TYPE getType(FileStatus fileStatus) {
            if (fileStatus.isFile()) {
                return FILE;
            }
            if (fileStatus.isDirectory()) {
                return DIRECTORY;
            }
            if (fileStatus.isSymlink()) {
                return SYMLINK;
            }
            throw new IllegalArgumentException("Could not determine filetype for: " +
                                                fileStatus.getPath());
        }
    }

    private URI uri;
    private String rootCID;
    private IPFS ipfs;

    @Override
    public String getScheme() {
        return SCHEME;
    }

    @Override
    public URI getUri() {
        return uri;
    }

    @Override
    public int getDefaultPort() {
        return IPFS_DEFAULT_HTTP_PORT;
    }

    private static class IPFSDataInputStream extends InputStream implements Seekable, PositionedReadable {
        private IPFS ipfs;
        private URI uri;
        private Path path;
        private int bufferSize;
        private long position;

        protected IPFSDataInputStream(IPFS ipfs, URI uri, Path path, int bufferSize) {
            if (bufferSize <= 0) {
                throw new IllegalArgumentException("Buffer size <= 0");
            }

            this.ipfs = ipfs;
            this.uri = uri;
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
            // "http://<host>:<port>/api/v0/cat?arg=<rootCID>/<path>&offset=<offset>&length=<length>"
            String rootCID = uri.getAuthority();
            String apiVersion = IPFSFileSystem.API_VERSION;
            String path = URLEncoder.encode(this.path.toString(), "UTF-8");
            String arg = rootCID + path + "&offset=" + (position + offset) + "&length=" + length;
            URL target = new URL(ipfs.protocol, ipfs.host, ipfs.port, apiVersion + "cat?arg=" + arg);

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

                return in.read(buffer, 0, length);
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
                LOG.debug("Downgrading EOFException raised trying to" +
                    " read {} bytes at offset {}", length, offset, e);
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

    @Override
    public void initialize(URI uri, Configuration conf) throws IOException {
        super.initialize(uri, conf);
        try {
            this.uri = new URI(uri.getScheme() + "://" + uri.getAuthority());
            this.ipfs = new IPFS(IPFS_DEFAULT_HTTP_GATEWAY, IPFS_DEFAULT_HTTP_PORT);
        } catch (URISyntaxException ex) {
            throw new IOException(ex);
        }
    }

    @Override
    public FSDataInputStream open(Path f, int bufferSize) throws IOException {
        return new FSDataInputStream(new IPFSDataInputStream(ipfs, uri, f, bufferSize));
    }

    @Override
    public FSDataOutputStream create(Path f, FsPermission permission, boolean overwrite, int bufferSize,
            short replication, long blockSize, Progressable progress) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public FSDataOutputStream append(Path f, int bufferSize, Progressable progress) throws IOException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'append'");
    }

    @Override
    public boolean rename(Path src, Path dst) throws IOException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'rename'");
    }

    @Override
    public boolean delete(Path f, boolean recursive) throws IOException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'delete'");
    }

    private String retrieve(String query) throws IOException{
        URL target = new URL(ipfs.protocol, ipfs.host, ipfs.port, API_VERSION + query);
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
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream resp = new ByteArrayOutputStream();

            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) >= 0) resp.write(buf, 0, r);
            return resp.toString();
        } catch (ConnectException var9) {
            throw new RuntimeException("Couldn't connect to IPFS daemon at " + 
                                        String.valueOf(target) + "\n Is IPFS running?");
        } catch (IOException e) {
            throw IPFS.extractError(e, conn);
        }
    }

    @Override
    public FileStatus[] listStatus(Path f) throws FileNotFoundException, IOException {
        String arg = rootCID + URLEncoder.encode(f.toString(), "UTF-8");
        Map reply = (Map)JSONParser.parse(retrieve("ls?arg=" + arg));
        List<MerkleNode> nodeList = ((List<Object>) reply.get("Objects")).stream()
        .flatMap(x -> ((List<Object>) ((Map) x).get("Links")).stream().map(MerkleNode::fromJSON))
        .collect(Collectors.toList());
        FileStatus[] statusList = new FileStatus[nodeList.size()];

        for (int i = 0; i < nodeList.size(); ++i) {
            MerkleNode node = nodeList.get(i);
            statusList[i] = new FileStatus(
                (long)node.size.get(),
                node.type.get() == 1,  // dir = 1, file = 2, symlink = 3
                0,
                0,
                0,
                Path.mergePaths(f, new Path(node.name.get()))
            );
        }

        return statusList;
    }

    @Override
    public void setWorkingDirectory(Path new_dir) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'setWorkingDirectory'");
    }

    @Override
    public Path getWorkingDirectory() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getWorkingDirectory'");
    }

    @Override
    public boolean mkdirs(Path f, FsPermission permission) throws IOException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'mkdirs'");
    }

    @Override
    public FileStatus getFileStatus(Path f) throws IOException {
        String arg = URLEncoder.encode("/ipfs/" + rootCID + f.toString(), "UTF-8");
        // Change the JSON’s `"Type"` field from `"folder"` / `"file"` strings 
        // to integers `1` / `2` so the MerkleNode can be initialized from it.
        String resp = retrieve("files/stat?arg=" + arg)
                .replace("\"file\"", "2")
                .replace("\"directory\"", "1");
        MerkleNode node = MerkleNode.fromJSON(JSONParser.parse(resp));
        return new FileStatus(
            (long)node.size.get(),
            node.type.get() == 1,  // dir = 1, file = 2, symlink = 3
            0,
            0,
            0,
            f
        );
    }

}
