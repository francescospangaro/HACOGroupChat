package org.HACO;

import javax.swing.*;
import java.awt.*;

public class AppMain {

    public static void main(String[] args) {
        JFrame frame = new JFrame();
        frame.setContentPane(new ConnectionPanel(frame).getRootPanel());
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setSize(d.width / 2, d.height / 2);
        frame.setMinimumSize(new Dimension(700, 500));
        frame.setVisible(true);
    }
}
