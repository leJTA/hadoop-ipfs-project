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

# Spark Configuration

Edit `$SPARK_HOME/conf/spark-defaults.conf` and add the following lines :

```
spark.hadoop.fs.ipfs.impl                    org.damas.hadoop.ipfs.IPFSFileSystem
spark.hadoop.fs.AbstractFileSystem.ipfs.impl org.damas.hadoop.ipfs.IPFSAbstractFileSystem
```

# Verification

Launch the ipfs daemon and verify that hadoop ipfs filesystem is working with the following command :

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

Test the hadoop wordcount example with the following command (*make sure that the `/output` does not already exist*) :

```shell
hadoop jar $HADOOP_HOME/share/hadoop/mapreduce/hadoop-mapreduce-examples*.jar wordcount http://127.0.0.1:5001/ipfs/QmSnuWmxptJZdLJpKRarxBMS2Ju2oANVrgbr2xWbie9b2D/README.txt /output
```

Then print the result with :

```shell
hadood fs -cat /output/*
```