# hadoop-ipfs-project

IPFS filesystem implementation for Hadoop.

# Installation

Clone this repository and build it:

```shell
git clone https://gitlab.inria.fr/ajeatsat/hadoop-ipfs-project.git
cd hadoop-ipfs-project
mvn package -DskipTests
```

Clone the [java-ipfs-http-client](https://github.com/ipfs-shipyard/java-ipfs-http-client) repository and build version 1.5.1, while copying runtime dependencies:

```shell
git clone https://github.com/ipfs-shipyard/java-ipfs-http-client.git
cd java-ipfs-http-client
git checkout 1.5.1
mvn package dependency:copy-dependencies -DincludeScope=runtime -DskipTests
Hadoop configuration
```

Edit `$HADOOP_HOME/etc/hadoop/core-site.xml` and add the following properties:
```xml
<property>
    <name>fs.ipfs.impl</name>
    <value>org.damas.hadoop.ipfs.IPFSFileSystem</value>
    <description>IPFS filesystem</description>
</property>

<property>
    <name>fs.defaultFS</name>
    <value>ipfs://localhost:5001</value>
</property>
```
# Dependencies

Copy the following JAR files into:

`$HADOOP_HOME/share/hadoop/common/lib`

*Note: dependency versions may change depending on the IPFS client version.*

```shell
hadoop-ipfs-project/hadoop-ipfs/target/hadoop-ipfs-0.1-SNAPSHOT.jar
java-ipfs-http-client/target/java-ipfs-http-client-1.5.1.jar
java-ipfs-http-client/target/dependency/java-cid-1.4.0.jar
java-ipfs-http-client/target/dependency/java-multibase-1.3.0.jar
java-ipfs-http-client/target/dependency/java-multiaddr-1.5.0.jar
java-ipfs-http-client/target/dependency/java-multihash-1.4.0.jar
```