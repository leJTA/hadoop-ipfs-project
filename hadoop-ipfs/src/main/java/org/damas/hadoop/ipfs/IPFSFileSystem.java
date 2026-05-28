package org.damas.hadoop.ipfs;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
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
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.util.Progressable;

import io.ipfs.api.IPFS;
import io.ipfs.api.JSONParser;
import io.ipfs.api.MerkleNode;

public class IPFSFileSystem extends FileSystem {

    public static final String API_VERSION = "/api/v0/";
    public static final String SCHEME = "ipfs";
    public static final String IPFS_DEFAULT_GATEWAY = "localhost";
    public static final int    IPFS_DEFAULT_PORT = 5001;
    public static final int    CONNECT_TIMEOUT_MILLIS = 10000; 
    public static final int    READ_TIMEOUT_MILLIS = 60000;
    public static final long   DEFAULT_BLOCK_SIZE = 32 * 1024 * 1024;

    private URI uri;
    private IPFS ipfs;
    private Path workingDirectory;

    public IPFSFileSystem() {
    }

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
        return IPFS_DEFAULT_PORT;
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
        return new FSDataOutputStream(
            new BufferedOutputStream(new IPFSDataOutputStream(ipfs, path), bufferSize), 
            statistics
        );
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
        if (f.toUri().getPath().startsWith("/ipfs/")) {
            throw new UnsupportedOperationException("Delete operation is only implemented for MFS paths");
        }

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
        Map reply;

        try{
            reply = (Map)JSONParser.parse(retrieve("ls?arg=" + arg));
        }
        catch (RuntimeException e) {
            throw new FileNotFoundException("File does not exist: " + dirPath);
        }

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
                DEFAULT_BLOCK_SIZE,
                0,
                0,
                null,
                System.getProperty("user.name"),
                null,
                new Path(f.toUri().getPath() + "/" + node.name.get())
            );
        }
        return statusList;
    }

    private FileStatus[] listStatusMFS(Path f) throws FileNotFoundException, IOException {
        String dirPath = f.toUri().getPath(); // get rid of the scheme and the authority
        List<Map> nodeList;

        try{
            nodeList = ipfs.files.ls(dirPath, true, true);
            if (nodeList == null) {
                return new FileStatus[0];
            }
        }
        catch (RuntimeException e) {
            throw new FileNotFoundException("File does not exist: " + dirPath);
        }

        FileStatus[] statusList = new FileStatus[nodeList.size()];
        int i = 0;
        for (Map node : nodeList) {
            statusList[i++] = new FileStatus(
                (int)node.get("Size"),
                (int)node.get("Type") == 1, 
                1,
                DEFAULT_BLOCK_SIZE,
                0,
                0,
                null,
                System.getProperty("user.name"),
                null,
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
            throw new FileNotFoundException("File does not exist: " + filePath);
        }

        MerkleNode node = MerkleNode.fromJSON(JSONParser.parse(resp));
        return new FileStatus(
            (long)node.size.get(),
            node.type.get() == 1,  // dir = 1, file = 2, symlink = 3
            1,
            DEFAULT_BLOCK_SIZE,
            0,
            0,
            null,
            System.getProperty("user.name"),
            null,
            new Path(filePath)
        );
    }
}
