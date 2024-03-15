package it.polimi.discovery;

public class MainServer {

    public static void main(String[] args) {
        DiscoveryServer s = new DiscoveryServer();
        System.out.println("Running discovery server...");
        s.run();
    }
}
