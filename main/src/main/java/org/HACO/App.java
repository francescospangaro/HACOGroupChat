package org.HACO;

import javax.swing.*;
import java.awt.*;
import java.util.Set;

public class App {
    private JPanel panel1;
    private JTextArea msgArea;
    private JButton Send;
    private JButton newButton;
    private JComboBox chatRooms;
    private JButton deleteButton;
    private JButton disconnectButton;
    private JList<String> msgList;
    private volatile Client c;

    public App() {
        newButton.addActionListener(e -> {
            System.out.println(c);
            CreateRoom dialog = new CreateRoom(c.getIps().keySet());
            if (dialog.isConfirmed()) {
                Set<String> users = dialog.getSelectedUsers();
                String name = dialog.getRoomName();
                c.createRoom(name, users);
            }
        });
        Send.addActionListener(e -> {
            String msg = msgArea.getText();
            ChatRoom chat = (ChatRoom) chatRooms.getSelectedItem();
            c.sendMessage(msg, chat);
        });
    }

    public void start() {
        System.out.println("Hello world!");

        JFrame frame = new JFrame();
        frame.setContentPane(panel1);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setSize(d.width / 2, d.height / 2);
        frame.setVisible(true);

        int port = -1;
        do {
            try {
                port = Integer.parseInt(JOptionPane.showInputDialog(frame, "Insert a port", 12345));
            } catch (NumberFormatException ignored) {
                System.err.println(ignored);
            }
        } while (port <= 0);

        String id = JOptionPane.showInputDialog(frame, "Insert an id");

        DefaultListModel msgListModel = new DefaultListModel();
        msgList.setModel(msgListModel);


        c = new Client(id, port, evt -> {
            System.out.println("Rooms changed");
            chatRooms.removeAllItems();
            Set<ChatRoom> chats = (Set<ChatRoom>) evt.getNewValue();
            chats.forEach(chat -> SwingUtilities.invokeLater(() -> chatRooms.addItem(chat)));
        }, evt -> {
            System.out.println("msg changed");
            if (evt.getPropertyName().equals("ADD_MSG")) {
                msgListModel.addElement(evt.getNewValue());
            }
        });
        System.out.println("started " + c);

    }

    public static void main(String[] args) {
        new App().start();
    }
}
