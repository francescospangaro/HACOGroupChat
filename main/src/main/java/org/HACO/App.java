package org.HACO;

import javax.swing.*;
import java.awt.*;
import java.util.Set;

public class App {
    private JPanel panel1;
    private JTextArea textArea2;
    private JButton Send;
    private JButton newButton;
    private JComboBox chatRomms;
    private JButton deleteButton;
    private JButton disconnectButton;
    private JList list1;

    public void start() {
        System.out.println("Hello world!");
        Client c = new Client(evt -> {
            chatRomms.removeAllItems();
            Set<ChatRoom> chats = (Set<ChatRoom>) evt.getNewValue();
            chats.forEach(chat -> chatRomms.addItem(chat));
        });

        JFrame frame = new JFrame();
        frame.setContentPane(new App().panel1);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setSize(d.width / 2, d.height / 2);
        frame.setVisible(true);

    }

    public static void main(String[] args) {
        new App().start();
    }
}
