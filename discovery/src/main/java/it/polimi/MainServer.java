package it.polimi;

import org.slf4j.bridge.SLF4JBridgeHandler;

/**
 * Main class which starts the discovery
 *
 * @implNote it is important that this class does not declare any static logger fields,
 * as it might trigger log4j initialization before {@link #main(String[])} has the
 * possibility to set the {@code log4j.configurationFile} system property to the
 * correct file
 */
public class MainServer {

    public static void main(String[] args) {
        // Configure log4j file if none is already set
        if (System.getProperty("log4j.configurationFile") == null)
            System.setProperty("log4j.configurationFile", "log4j2-discovery.xml");

        // add SLF4JBridgeHandler to j.u.l's root logger
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();


        DiscoveryServer s = new DiscoveryServer();
        s.start();
    }
}
