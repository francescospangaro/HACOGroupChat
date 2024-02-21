package org.HACO;

import javax.swing.*;
import java.awt.*;

public class Main {


    public static void main(String[] args) {
        System.out.println("Hello world!");
        Client c = new Client();

        JFrame frame = new JFrame();
        frame.setContentPane(new Test().panel1);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setSize(d.width / 2, d.height / 2);
        frame.setVisible(true);

    }

}
