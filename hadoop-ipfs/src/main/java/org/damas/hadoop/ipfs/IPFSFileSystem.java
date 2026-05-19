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
import java.nio.file.Paths;
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
import io.ipfs.api.Multipart;
import io.ipfs.api.NamedStreamable;

public class IPFSFileSystem extends FileSystem {

    public static final String API_VERSION = "/api/v0/";
    public static final String SCHEME = "ipfs";
    public static final String IPFS_DEFAULT_HTTP_GATEWAY = "localhost";
    public static final int    IPFS_DEFAULT_HTTP_PORT = 5001;
    public static final int    CONNECT_TIMEOUT_MILLIS = 10000; 
    public static final int    READ_TIMEOUT_MILLIS = 60000;
    public static final long   IPFS_DEFAULT_CHUNK_SIZE = 256 * 1024;

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
    private IPFS ipfs;
    private Path workingDirectory;

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
            // "http://<host>:<port>/api/v0/cat?arg=/ipfs/<rootCID>/<path>&offset=<offset>&length=<length>"
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

    private static class IPFSDataOutputStream extends OutputStream implements Seekable {
        private IPFS ipfs;
        private Path path;
        private long position;

        public IPFSDataOutputStream(IPFS ipfs, Path path) {
            this.ipfs = ipfs;
            this.path = path;
            this.position = 0;
        }

        @Override
        public void write(int b) throws IOException {
            throw new UnsupportedOperationException("Unimplemented method 'write'");
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            // The "write" request sent through the ipfs http api is 
            // "http://<host>:<port>/api/v0/files/write?arg=/ipfs/<rootCID>/<path>&offset=<offset>&count=<length>"
            String apiVersion = IPFSFileSystem.API_VERSION;
            String path = URLEncoder.encode(this.path.toString(), "UTF-8");
            String arg = path + "&offset=" + (offset + position) + "&count=" + length + "&parents=true&create=true";
            URL target = new URL(ipfs.protocol, ipfs.host, ipfs.port, apiVersion + "files/write?arg=" + arg);
            Multipart m = new Multipart(target.toString(), "UTF-8");
            m.addFilePart("file", Paths.get(""), new NamedStreamable.ByteArrayWrapper(buffer));
            m.finish();
            seek(position + Math.min(buffer.length, length));
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
    } 

    @Override
    public void initialize(URI uri, Configuration conf) throws IOException {
        super.initialize(uri, conf);
        try {
            this.uri = new URI(uri.getScheme() + "://" + uri.getAuthority());
            this.ipfs = new IPFS(uri.getHost(), uri.getPort());
            this.workingDirectory = new Path("/");
        } catch (URISyntaxException ex) {
            throw new IOException(ex);
        }
    }

    @Override
    public FSDataInputStream open(Path f, int bufferSize) throws IOException {
        Path path = new Path(f.toUri().getPath()); // get rid of the scheme and the authority
        return new FSDataInputStream(new IPFSDataInputStream(ipfs, path, bufferSize));
    }

    @Override
    public FSDataOutputStream create(Path f, FsPermission permission, boolean overwrite, int bufferSize,
            short replication, long blockSize, Progressable progress) throws IOException {
        Path path = new Path(f.toUri().getPath()); // get rid of the scheme and the authority
        return new FSDataOutputStream(new IPFSDataOutputStream(ipfs, path), statistics);
    }

    @Override
    public FSDataOutputStream append(Path f, int bufferSize, Progressable progress) throws IOException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'append'");
    }

    @Override
    public boolean rename(Path src, Path dst) throws IOException {
        String source = src.toUri().getPath(); // get rid of the scheme and the authority
        String dest = dst.toUri().getPath();
        String arg1 = URLEncoder.encode(source, "UTF-8");
        String arg2 = URLEncoder.encode(dest, "UTF-8");
        return !retrieve("files/mv?arg=" + arg1 + "&arg=" + arg2).contains("error");
    }

    @Override
    public boolean delete(Path f, boolean recursive) throws IOException {
        String path = f.toUri().getPath(); // get rid of the scheme and the authority
        String arg = URLEncoder.encode(path, "UTF-8") + "&recursive=" + recursive;
        return !retrieve("files/rm?arg=" + arg).contains("\"error\"");
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
        // If the path starts with "/ipfs/", then it is an IPFS path otherwise it is an MFS one
        if (f.toUri().getPath().startsWith("/ipfs/")) {
            return listStatusIPFS(f);
        }
        else {
            return listStatusMFS(f);
        }        
    }

    private FileStatus[] listStatusIPFS(Path f) throws FileNotFoundException, IOException {
        String dirPath = f.toUri().getPath();
        String arg = URLEncoder.encode(dirPath, "UTF-8");
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
                1,
                IPFS_DEFAULT_CHUNK_SIZE,
                0,
                new Path(f.toUri().getPath() + "/" + node.name.get())
            );
        }
        return statusList;
    }

    private FileStatus[] listStatusMFS(Path f) throws FileNotFoundException, IOException {
        String dirPath = f.toUri().getPath(); // get rid of the scheme and the authority
        List<Map> nodeList = ipfs.files.ls(dirPath, true, true);
        FileStatus[] statusList = new FileStatus[nodeList.size()];
        int i = 0;

        for (Map node : nodeList) {
            statusList[i++] = new FileStatus(
                (int)node.get("Size"),
                (int)node.get("Type") == 1, 
                1,
                IPFS_DEFAULT_CHUNK_SIZE,
                0,
                new Path(dirPath + (dirPath.equals("/") ? "":"/") + node.get("Name"))
            );
        }
        return statusList;
    }

    @Override
    public void setWorkingDirectory(Path new_dir) {
        workingDirectory = new Path(new_dir.toUri().getPath());
    }

    @Override
    public Path getWorkingDirectory() {
        return workingDirectory;
    }

    @Override
    public boolean mkdirs(Path f, FsPermission permission) throws IOException {
        String dirPath = f.toUri().getPath();
        String arg = URLEncoder.encode(dirPath, "UTF-8");
        return !retrieve("files/mkdir?arg=" + arg + "&parents=true").contains("error");
    }

    @Override
    public FileStatus getFileStatus(Path f) throws IOException {
        String filePath = f.toUri().getPath();
        String arg = URLEncoder.encode(filePath, "UTF-8");
        String resp = new String();
        try {
            // Change the JSON’s `"Type"` field from `"folder"` / `"file"` strings 
            // to integers `1` / `2` so the MerkleNode can be initialized from it.
            resp = retrieve("files/stat?arg=" + arg).replace("\"file\"", "2")
                   .replace("\"directory\"", "1");
        } catch(RuntimeException e) {
            // IPFS api throws a RuntimeException when the file is missing,
            // we therefore need to catch it and return a null file status.
            return null;
        }

        MerkleNode node = MerkleNode.fromJSON(JSONParser.parse(resp));
        return new FileStatus(
            (long)node.size.get(),
            node.type.get() == 1,  // dir = 1, file = 2, symlink = 3
            1,
            IPFS_DEFAULT_CHUNK_SIZE,
            0,
            new Path(filePath)
        );
    }
}
