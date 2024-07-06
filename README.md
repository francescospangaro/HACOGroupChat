# HACOGroupChat
A highly available, causally ordered group chat for the Distributed Systems course A.Y. 2023/2024.  
  
Implement a `distributed` group chat application.  
Users can create and delete rooms. For each room, the set of participants is specified at creation time and is never modified.  
Users can post new messages for a room they are participating to.  
Within each room, messages should be delivered in causal order.  
The application should be `fully distributed`, meaning that user clients should exchange
messages without relying on any centralized server.  
The application should be `highly available`, meaning that users should be able to use the
chat (read and write messages) even if they are temporarily disconnected from the network.  
The project can be implemented as a real distributed application (for example, in Java) or it
can be simulated using OmNet++.  
  
**Assumptions**  
• Clients are reliable but they can join and leave the network at any time. Network failures and partitions may happen. 

**Our assumptions**  
• The Discovery server node is always reachable by all peers.  
• Message packets are not bigger than 2000 bytes each.  
  
## Running the application

The application requires an installation of Java 21. Newer versions cannot be used
as preview features were used which may have been changed in Java 22. 

The jars can be found in the
[releases](https://github.com/francescospangaro/HACOGroupChat/releases) section.

The discovery server needs to be run from terminal using the following command:

```shell
java --enable-preview -jar HACOGroupChat-discovery-1.0
```

The distributed application needs to be run from terminal using the following command:

```shell
java --enable-preview -jar HACOGroupChat-peer-1.0.jar
```
  

The pdf of the spec is available [here.](docs/Projects_assignment_23_24_UPDATE.pdf)

We chose to implement the application with a real distributed application using Java.  
