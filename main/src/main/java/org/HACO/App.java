package org.HACO;

import javax.swing.*;
import java.awt.*;

public class App {
    private JPanel panel1;
    private JTextArea textArea1;
    private JTextArea textArea2;
    private JButton Send;
    private JButton newButton;
    private JComboBox comboBox1;
    private JButton deleteButton;
    private JButton disconnectButton;

    public static void main(String[] args) {
        System.out.println("Hello world!");
        Client c = new Client();

        JFrame frame = new JFrame();
        frame.setContentPane(new App().panel1);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setSize(d.width / 2, d.height / 2);
        frame.setVisible(true);

    }

}
