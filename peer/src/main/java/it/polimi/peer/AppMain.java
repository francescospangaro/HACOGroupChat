package it.polimi.peer;

import it.polimi.peer.gui.ConnectionPanel;
import org.slf4j.bridge.SLF4JBridgeHandler;

import javax.swing.*;
import java.awt.*;

/**
 * Main class which starts the app
 *
 * @implNote it is important that this class does not declare any static logger fields,
 * as it might trigger log4j initialization before {@link #main(String[])} has the
 * possibility to set the {@code log4j.configurationFile} system property to the
 * correct file
 */
public class AppMain {

    public static void main(String[] args) {
        // Configure log4j file if none is already set
        if (System.getProperty("log4j.configurationFile") == null)
            System.setProperty("log4j.configurationFile", "log4j2-peer.xml");

        // add SLF4JBridgeHandler to j.u.l's root logger
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();


        JFrame frame = new JFrame();
        String discoveryAddr = "localhost";
        if (args.length > 0)
            discoveryAddr = args[0];
        frame.setContentPane(new ConnectionPanel(frame, discoveryAddr).getRootPanel());
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setSize(d.width / 2, d.height / 2);
        frame.setMinimumSize(new Dimension(700, 500));
        frame.setVisible(true);
    }
}
