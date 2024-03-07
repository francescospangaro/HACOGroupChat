package org.HACO;

import org.HACO.packets.Message;
import org.HACO.packets.MessageGUI;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class ChatPanel {
    private JPanel panel;
    private JTextArea msgArea;
    private JButton sendButton;
    private JButton newChatButton;
    private JComboBox<ChatRoom> chatRooms;
    private JButton deleteButton;
    private JButton disconnectReconnectButton;
    private JList<String> msgList;
    private JLabel usernameLabel;
    private JLabel portLabel;
    private JLabel connectedLabel;
    private JTextField delayTime;
    private JList<String> connectedList;
    private JLabel chatLable;
    private JPanel left;
    private volatile Peer peer;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    private DefaultListModel<String> msgListModel = new DefaultListModel<>();

    public ChatPanel(JFrame frame, String discovery, String user, int port) {
        //Want to create a new Group for chatting
        newChatButton.addActionListener(e -> executorService.execute(() -> {
            System.out.println(peer);
            CreateRoomDialog dialog = new CreateRoomDialog(peer.getIps().keySet());

            if (dialog.isConfirmed()) {
                Set<String> users = dialog.getSelectedUsers();
                users.add(peer.getId());
                String name = dialog.getRoomName();

                //Request the creation of the room with this name and with all the users
                peer.createRoom(name, users);
            }
        }));

        //Want to send a message to a ChatRoom
        sendButton.addActionListener(e -> executorService.execute(() -> {
            String msg = msgArea.getText();
            //Get the ChatRoom selected by the user in which he wants to send the msg
            ChatRoom chat = (ChatRoom) chatRooms.getSelectedItem();

            int delay;
            try {
                delay = Integer.parseInt(delayTime.getText());
                if (delay < 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException ignored) {
                delay = 0;
            }

            peer.sendMessage(msg, chat, delay);

            SwingUtilities.invokeLater(() -> msgArea.setText(""));
        }));


        deleteButton.addActionListener(e -> executorService.execute(() -> {
            ChatRoom toDelete = (ChatRoom) chatRooms.getSelectedItem();
            System.out.println("Deleting room " + toDelete.getId());
            peer.deleteRoom(toDelete);
        }));

        disconnectReconnectButton.addActionListener(e ->
                setConnected(!peer.isConnected())
        );

        chatRooms.addItemListener(e -> {
            if (e.getID() == ItemEvent.ITEM_STATE_CHANGED) {
                if (chatRooms.getItemCount() > 0 && chatRooms.getSelectedItem() != null) {
                    chatLable.setText("Chat: " + ((ChatRoom) chatRooms.getSelectedItem()).getName());
                    msgListModel.clear();

                    msgListModel.addAll(0, ((ChatRoom) chatRooms.getSelectedItem()).getReceivedMsgs().stream()
                            .map(Message::toString)
                            .collect(Collectors.toList()));
                } else {
                    msgListModel.clear();
                }
            }
        });

        Dimension colDim = new Dimension(Math.max(300, panel.getWidth() / 3), -1);
        left.setMinimumSize(colDim);
        left.setPreferredSize(colDim);
        left.setMaximumSize(colDim);
        panel.revalidate();
        panel.repaint();

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent windowEvent) {
                peer.close();
                executorService.shutdownNow();
            }
        });
        panel.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                super.componentResized(e);
                var d = new Dimension(Math.max(300, e.getComponent().getWidth() / 3), -1);
                left.setMinimumSize(d);
                left.setPreferredSize(d);
                left.setMaximumSize(d);
                panel.revalidate();
                panel.repaint();
            }
        });

        sendButton.setEnabled(false);
        deleteButton.setEnabled(false);
        disconnectReconnectButton.setEnabled(false);
        newChatButton.setEnabled(false);

        delayTime.setColumns(10);

        portLabel.setText(String.valueOf(port));
        usernameLabel.setText(user);

        msgList.setModel(msgListModel);

        DefaultListModel<String> connectedModelList = new DefaultListModel<>();
        connectedList.setModel(connectedModelList);

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            e.printStackTrace();
            JOptionPane.showMessageDialog(JOptionPane.getRootFrame(),
                    "Unexpected error. The application will terminate.\n" + e.getMessage(),
                    "Fatal error",
                    JOptionPane.ERROR_MESSAGE);

            //Try to disconnect before killing
            if (peer != null && peer.isConnected()) {
                peer.close();
            }

            System.exit(-1);
        });

        executorService.execute(() -> {
            peer = new Peer(discovery, user, port, evt -> SwingUtilities.invokeLater(() -> {
                if (evt.getPropertyName().equals("ADD_ROOM")) {
                    ChatRoom chat = (ChatRoom) evt.getNewValue();
                    System.out.println("Room " + chat + " added in gui");
                    chatRooms.addItem(chat);
                    sendButton.setEnabled(true);
                    deleteButton.setEnabled(true);
                } else if (evt.getPropertyName().equals("DEL_ROOM")) {
                    ChatRoom chat = (ChatRoom) evt.getOldValue();
                    System.out.println("Room " + chat + " removed from gui");
                    chatRooms.removeItem(chat);
                    if (chatRooms.getItemCount() == 0) {
                        sendButton.setEnabled(false);
                        deleteButton.setEnabled(false);
                    }
                }
            }), evt -> SwingUtilities.invokeLater(() -> {
                if (evt.getPropertyName().equals("USER_CONNECTED")) {
                    connectedModelList.addElement((String) evt.getNewValue());
                } else {
                    System.out.println("Removing " + evt.getOldValue() + " " + connectedModelList.removeElement(evt.getOldValue()));
                }
            }), evt -> {
                if (evt.getPropertyName().equals("ADD_MSG")) {
                    System.out.println("Msg added in gui");

                    MessageGUI mgui = (MessageGUI) evt.getNewValue();

                    if (((ChatRoom) chatRooms.getSelectedItem()).getId().equals(mgui.chatRoom().getId())) {
                        SwingUtilities.invokeLater(() -> msgListModel.addElement(mgui.message().toString()));
                    }
                }
            });
            System.out.println("started " + peer);
            SwingUtilities.invokeLater(() -> {
                connectedLabel.setText("Connected");
                connectedLabel.setForeground(new Color(0, 153, 51));
                disconnectReconnectButton.setEnabled(true);
                newChatButton.setEnabled(true);
            });
        });
    }

    private void setConnected(boolean connected) {
        if (connected) {
            disconnectReconnectButton.setEnabled(false);
            executorService.execute(() -> {
                connectedLabel.setText("Connecting...");
                connectedLabel.setForeground(Color.yellow);
                peer.start();
                SwingUtilities.invokeLater(() -> {
                    connectedLabel.setText("Connected");
                    connectedLabel.setForeground(new Color(0, 153, 51));

                    disconnectReconnectButton.setText("Disconnect");
                    disconnectReconnectButton.setEnabled(true);
                });
            });
        } else {
            disconnectReconnectButton.setEnabled(false);
            executorService.execute(() -> {
                peer.disconnect();
                SwingUtilities.invokeLater(() -> {
                    connectedLabel.setText("Disconnected");
                    connectedLabel.setForeground(Color.red);

                    disconnectReconnectButton.setText("Reconnect");
                    disconnectReconnectButton.setEnabled(true);
                });
            });
        }
    }

    public JPanel getRootPanel() {
        return panel;
    }
}
