# hadoop-ipfs-project

IPFS filesystem implementation for Hadoop.

# Build

Clone this repository and build it, ensuring that the runtime dependencies are automatically copied:

```shell
git clone https://gitlab.inria.fr/ajeatsat/hadoop-ipfs-project.git
cd hadoop-ipfs-project
mvn package dependency:copy-dependencies -DincludeScope=runtime -DskipTests
```

It is also possible to build with maven docker image :

```shell
docker volume create --name maven-repo
docker run -it --rm \
    -v maven-repo:/root/.m2 \
    -v $PWD:/usr/src/hadoop-ipfs-project \
    -w /usr/src/hadoop-ipfs-project \
    maven \
    mvn package dependency:copy-dependencies -DincludeScope=runtime -DskipTests
```

# Installation

Copy the following JAR files into `$HADOOP_HOME/share/hadoop/common/lib`:

```shell
hadoop-ipfs-project/hadoop-ipfs/target/hadoop-ipfs-0.1-SNAPSHOT.jar
hadoop-ipfs-project/hadoop-ipfs/target/dependency/java-ipfs-http-client-1.5.1.jar
hadoop-ipfs-project/hadoop-ipfs/target/dependency/java-cid-1.4.0.jar
hadoop-ipfs-project/hadoop-ipfs/target/dependency/java-multibase-1.3.0.jar
hadoop-ipfs-project/hadoop-ipfs/target/dependency/java-multiaddr-1.5.0.jar
hadoop-ipfs-project/hadoop-ipfs/target/dependency/java-multihash-1.4.0.jar
```

*Note: dependency versions may change depending on the IPFS client version.*

# Hadoop configuration

Edit `$HADOOP_HOME/etc/hadoop/core-site.xml` and add the following properties:
```xml
<property>
    <name>fs.ipfs.impl</name>
    <value>org.damas.hadoop.ipfs.IPFSFileSystem</value>
    <description>IPFS filesystem</description>
</property>

<!-- If the resource manager is YARN, an AbstractFileSystem is required -->
<property>
    <name>fs.AbstractFileSystem.ipfs.impl</name>
    <value>org.damas.hadoop.ipfs.IPFSAbstractFileSystem</value>
    <description>IPFS filesystem</description>
</property>
```

*Note: IPFS Should never be used as the default filesystem, as Hadoop will not work with our current implementation of the IPFS filesystem.*

# Spark Configuration

Copy the JAR files mentionned earlier into `$SPARK_HOME/jars`.

Edit `$SPARK_HOME/conf/spark-defaults.conf` and add the following lines :

```
spark.hadoop.fs.ipfs.impl                    org.damas.hadoop.ipfs.IPFSFileSystem
spark.hadoop.fs.AbstractFileSystem.ipfs.impl org.damas.hadoop.ipfs.IPFSAbstractFileSystem
```

# Verification

Beforhand, make sure that the IPFS daemon is running. The command to start the daemon is as follows :

```shell
ipfs daemon
```

Verify that hadoop ipfs filesystem is working with the following command :

```shell
hadoop fs -ls ipfs://127.0.0.1:5001/ipfs/QmSnuWmxptJZdLJpKRarxBMS2Ju2oANVrgbr2xWbie9b2D
```

The result should look like this :

```
-rw-rw-rw-   0       1139 1970-01-01 01:00 ipfs://127.0.0.1:5001/ipfs/QmS...9b2D/README.txt
-rw-rw-rw-   0        235 1970-01-01 01:00 ipfs://127.0.0.1:5001/ipfs/QmS...9b2D/_Metadata.json
drwxrwxrwx   -          0 1970-01-01 01:00 ipfs://127.0.0.1:5001/ipfs/QmS...9b2D/albums
-rw-rw-rw-   0       4013 1970-01-01 01:00 ipfs://127.0.0.1:5001/ipfs/QmS...9b2D/apolloarchivr.py
-rw-rw-rw-   0       9203 1970-01-01 01:00 ipfs://127.0.0.1:5001/ipfs/QmS...9b2D/build_frontend_index.py
drwxrwxrwx   -          0 1970-01-01 01:00 ipfs://127.0.0.1:5001/ipfs/QmS...9b2D/frontend
```

*Note: The only information provided by IFPS regarding the files is their `name`, `type` and `size`; `access rights`, `modification date` and `creation date` are therefore set to default values.*

# Test

## From IPFS to default filesystem

Here data are read from `IPFS` and output data are written to the default filesystem, in our case `HDFS`.

Test the hadoop wordcount example with the following command (*make sure that the folder `/output` does not already exist in HDFS*) :

```shell
hadoop jar $HADOOP_HOME/share/hadoop/mapreduce/hadoop-mapreduce-examples*.jar \
    wordcount \
    ipfs://127.0.0.1:5001/ipfs/QmSnuWmxptJZdLJpKRarxBMS2Ju2oANVrgbr2xWbie9b2D/README.txt \
    /output
```

Then print the result with :

```shell
hadood fs -cat /output/*
```

## From IPFS to IPFS

Here data a read from `IPFS` and the output are also written to `IPFS`.

Test the hadoop wordcount example with the following command :

```shell
hadoop jar $HADOOP_HOME/share/hadoop/mapreduce/hadoop-mapreduce-examples*.jar \
    wordcount \
    ipfs://127.0.0.1:5001/ipfs/QmSnuWmxptJZdLJpKRarxBMS2Ju2oANVrgbr2xWbie9b2D/README.txt \
    ipfs://127.0.0.1:5001/output
```

*Note that here we have specified the full URI of the output directory in order to specify the file system to be used.*

Once the job is complete, output files are pushed to IPFS. Then the list of their [CIDs](https://docs.ipfs.tech/concepts/content-addressing/) is stored in the folder `/_cids.out/` of the default filesystem. The content of the folder, should look like this:

```shell
Found 2 items
-rw-r--r--   3 user supergroup         47 2026-07-27 11:37 /_cids.out/_SUCCESS.cid
-rw-r--r--   3 user supergroup         47 2026-07-27 11:37 /_cids.out/part-r-00000.cid
```

Print all the CIDs using the command :

```shell
hadoop fs -cat /_cids.out/*
```

and then get the content of each file using :

```shell
hadoop fs -cat ipfs://127.0.0.1:5001/ipfs/<cid>
# or ipfs cat <cid>
```

To remove delete a file uploaded to `IPFS`, unpin it :
```shell
ipfs pin rm <cid>
ipfs repo gc # garbage collect unpinned files
```