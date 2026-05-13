package org.damas.hadoop.ipfs;

import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PositionedReadable;
import org.apache.hadoop.fs.Seekable;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.util.Progressable;

import io.ipfs.api.IPFS;

public class IPFSFileSystem extends FileSystem {

    public static final String SERVICE_VERSION = "/api/v0";
    public static final String SCHEME = "ipfs";
    public static final String IPFS_DEFAULT_HTTP_GATEWAY = "localhost";
    public static final int    IPFS_DEFAULT_HTTP_PORT = 5001;

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

    private static class IPFSDataInputStream extends FilterInputStream implements Seekable, PositionedReadable {

        protected IPFSDataInputStream(InputStream in, int bufferSize) {
            super(new BufferedInputStream(in, bufferSize));
        }

        @Override
        public int read(long position, byte[] buffer, int offset, int length) throws IOException {
            throw new UnsupportedOperationException("Unimplemented method 'read'");
        }

        @Override
        public void readFully(long position, byte[] buffer, int offset, int length) throws IOException {
            throw new UnsupportedOperationException("Unimplemented method 'readFully'");
        }

        @Override
        public void readFully(long position, byte[] buffer) throws IOException {
            throw new UnsupportedOperationException("Unimplemented method 'readFully'");
        }

        @Override
        public void seek(long pos) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getPos() throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean seekToNewSource(long targetPos) throws IOException {
            throw new UnsupportedOperationException();
        }
        
    }

    @Override
    public void initialize(URI name, Configuration conf) throws IOException {
        super.initialize(name, conf);
        try {
            uri = new URI(name.getScheme() + "://" + name.getAuthority());
            ipfs = new IPFS(IPFS_DEFAULT_HTTP_GATEWAY, IPFS_DEFAULT_HTTP_PORT);
        } catch (URISyntaxException ex) {
            throw new IOException(ex);
        }
    }

    @Override
    public FSDataInputStream open(Path f, int bufferSize) throws IOException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'open'");
    }

    @Override
    public FSDataOutputStream create(Path f, FsPermission permission, boolean overwrite, int bufferSize,
            short replication, long blockSize, Progressable progress) throws IOException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'create'");
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

    @Override
    public FileStatus[] listStatus(Path f) throws FileNotFoundException, IOException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'listStatus'");
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
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getFileStatus'");
    }

}
