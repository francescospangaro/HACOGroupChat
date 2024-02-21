package org.HACO.discovery;

public class Main {

    public static void main(String[] args) {
        DiscoveryServer s = new DiscoveryServer();
        System.out.println("Running discovery server...");
        s.run();
    }
}
