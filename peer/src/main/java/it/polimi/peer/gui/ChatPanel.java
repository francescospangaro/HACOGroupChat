package it.polimi.peer.gui;

import it.polimi.messages.CloseMessage;
import it.polimi.peer.ChatRoom;
import it.polimi.peer.PeerController;
import it.polimi.peer.PeerNetManager;
import it.polimi.peer.exceptions.DiscoveryUnreachableException;
import it.polimi.peer.utility.MessageGUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatPanel {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatPanel.class);

    private static final int MAX_MSG_SIZE = 1000;

    private JPanel panel;
    private JTextArea msgArea;
    private JButton sendButton;
    private JButton newChatButton;
    private JComboBox<ChatRoom> chatRooms;
    private JButton deleteButton;
    private JButton disconnectReconnectButton;
    private JList<MessageBubble> msgList;
    private JLabel usernameLabel;
    private JLabel portLabel;
    private JLabel connectedLabel;
    private JList<String> connectedList;
    private JLabel chatLabel;
    private JPanel left;
    private JCheckBox detailedViewCheckBox;
    private JButton delaysButton;
    private volatile PeerNetManager peerNetManager;
    private volatile PeerController peerController;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    private final DefaultListModel<MessageBubble> msgListModel = new DefaultListModel<>();

    public ChatPanel(JFrame frame, String discovery, String user, int port) {
        //Want to create a new Group for chatting
        newChatButton.addActionListener(_ -> executorService.execute(() -> {
            CreateRoomDialog dialog = new CreateRoomDialog(peerNetManager.getIps().keySet());

            if (dialog.isConfirmed()) {
                Set<String> users = dialog.getSelectedUsers();
                users.add(peerNetManager.getId());
                String name = dialog.getRoomName();

                //Request the creation of the room with this name and with all the users
                peerController.createRoom(name, users);
            }
        }));

        //Want to send a message to a ChatRoom
        sendButton.addActionListener(_ -> executorService.execute(this::send));

        msgArea.registerKeyboardAction(_ -> {
            if (sendButton.isEnabled()) {
                executorService.execute(this::send);
            }
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK), JComponent.WHEN_FOCUSED);

        deleteButton.addActionListener(_ -> {
            ChatRoom toDelete = (ChatRoom) chatRooms.getSelectedItem();
            assert toDelete != null;
            if (toDelete.isClosed())
                deleteRoom(toDelete);
            else {
                executorService.execute(() -> {
                    peerController.closeRoom(toDelete);
                    SwingUtilities.invokeLater(() -> deleteButton.setText("Delete"));
                });
            }
        });

        disconnectReconnectButton.addActionListener(_ ->
                setConnected(!peerNetManager.isConnected())
        );

        chatRooms.addItemListener(e -> {
            if (e.getID() == ItemEvent.ITEM_STATE_CHANGED) {
                if (chatRooms.getItemCount() > 0 && chatRooms.getSelectedItem() != null) {
                    ChatRoom chat = (ChatRoom) chatRooms.getSelectedItem();
                    chatLabel.setText(STR."Chat: \{(chat).getName()}");
                    msgListModel.clear();
                    msgListModel.addAll((chat).getReceivedMsgs().stream().map(m -> {
                        if (m instanceof CloseMessage)
                            return new CloseChatBubble(m.sender(), detailedViewCheckBox.isSelected() ? m.toDetailedString() : m.toString());
                        else if (m.sender().equals(user))
                            return new RightArrowBubble(detailedViewCheckBox.isSelected() ? m.toDetailedString() : m.toString());
                        else
                            return new LeftArrowBubble(m.sender(), detailedViewCheckBox.isSelected() ? m.toDetailedString() : m.toString());
                    }).toList());
                    msgList.ensureIndexIsVisible(msgListModel.size() - 1);

                    sendButton.setEnabled(!chat.isClosed());
                    deleteButton.setEnabled(true);
                    if (chat.isClosed())
                        deleteButton.setText("Delete");
                    else
                        deleteButton.setText("Close");
                } else {
                    sendButton.setEnabled(false);
                    deleteButton.setEnabled(false);
                    chatLabel.setText("Chat: -");
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

        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent windowEvent) {
                if (peerNetManager != null) {
                    try {
                        peerNetManager.close();
                        executorService.shutdownNow();
                        System.exit(0);
                    } catch (DiscoveryUnreachableException e) {
                        JOptionPane.showMessageDialog(JOptionPane.getRootFrame(),
                                "Failed to disconnect: discovery unreachable",
                                "Error",
                                JOptionPane.ERROR_MESSAGE);
                    }
                }
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

        portLabel.setText(String.valueOf(port));
        usernameLabel.setText(user);

        msgList.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> value);
        msgList.setModel(msgListModel);

        DefaultListModel<String> connectedModelList = new DefaultListModel<>();
        connectedList.setModel(connectedModelList);

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            LOGGER.error(STR."Unexpected error in thread \{t}. The application will terminate", e);
            JOptionPane.showMessageDialog(JOptionPane.getRootFrame(),
                    STR."""
                        Unexpected error. The application will terminate.
                        \{e}""",
                    "Fatal error",
                    JOptionPane.ERROR_MESSAGE);

            //Try to disconnect before killing
            if (peerNetManager != null && peerNetManager.isConnected()) {
                try {
                    peerNetManager.close();
                } catch (DiscoveryUnreachableException ex) {
                    LOGGER.error("Can't reach the discovery server", ex);
                }
            }

            System.exit(-1);
        });

        executorService.execute(() -> {
            try {
                peerNetManager = new PeerNetManager(discovery, user, port, evt -> SwingUtilities.invokeLater(() -> {
                    if (evt.getPropertyName().equals("ADD_ROOM")) {
                        ChatRoom chat = (ChatRoom) evt.getNewValue();
                        LOGGER.trace(STR."Room \{chat} added in gui");
                        chatRooms.addItem(chat);
                    }
                }), evt -> SwingUtilities.invokeLater(() -> {
                    if (evt.getPropertyName().equals("USER_CONNECTED")) {
                        connectedModelList.addElement((String) evt.getNewValue());
                    } else {
                        connectedModelList.removeElement(evt.getOldValue());
                    }
                }), evt -> {
                    if (evt.getPropertyName().equals("ADD_MSG")) {
                        MessageGUI mgui = (MessageGUI) evt.getNewValue();

                        LOGGER.info(STR."Msg \{mgui} added in gui");

                        if (((ChatRoom) chatRooms.getSelectedItem()).getId().equals(mgui.chatRoom().getId())) {
                            SwingUtilities.invokeLater(() -> {
                                if (mgui.message() instanceof CloseMessage cm)
                                    msgListModel.addElement(new CloseChatBubble(cm.sender(), detailedViewCheckBox.isSelected() ? cm.toDetailedString() : cm.toString()));
                                else if (mgui.message().sender().equals(user))
                                    msgListModel.addElement(new RightArrowBubble(detailedViewCheckBox.isSelected() ? mgui.message().toDetailedString() : mgui.message().toString()));
                                else
                                    msgListModel.addElement(new LeftArrowBubble(mgui.message().sender(), detailedViewCheckBox.isSelected() ? mgui.message().toDetailedString() : mgui.message().toString()));
                                msgList.ensureIndexIsVisible(msgListModel.size() - 1);

                                if (mgui.message() instanceof CloseMessage) {
                                    sendButton.setEnabled(false);
                                    deleteButton.setText("Delete");
                                }
                            });
                        }

                    }
                });
            } catch (IOException e) {
                JOptionPane.showMessageDialog(JOptionPane.getRootFrame(),
                        STR."""
                            Can't establish the connection. The application will terminate.
                            \{e}""",
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
                System.exit(-1);
            }
            LOGGER.info(STR."Started \{peerNetManager}");
            peerController = peerNetManager.getController();
            SwingUtilities.invokeLater(() -> {
                connectedLabel.setText("Connected");
                connectedLabel.setForeground(new Color(0, 153, 51));
                disconnectReconnectButton.setEnabled(true);
                newChatButton.setEnabled(true);
            });
        });
        detailedViewCheckBox.addActionListener(_ -> {
            if (chatRooms.getItemCount() > 0 && chatRooms.getSelectedItem() != null) {
                msgListModel.clear();
                msgListModel.addAll(((ChatRoom) chatRooms.getSelectedItem()).getReceivedMsgs().stream().map(m -> {
                    if (m instanceof CloseMessage)
                        return new CloseChatBubble(m.sender(), detailedViewCheckBox.isSelected() ? m.toDetailedString() : m.toString());
                    else if (m.sender().equals(user))
                        return new RightArrowBubble(detailedViewCheckBox.isSelected() ? m.toDetailedString() : m.toString());
                    else
                        return new LeftArrowBubble(m.sender(), detailedViewCheckBox.isSelected() ? m.toDetailedString() : m.toString());
                }).toList());
            }
        });

        delaysButton.addActionListener(_ -> {
            DegradeConnectionsDialog dialog = new DegradeConnectionsDialog(peerNetManager.getIps().keySet(), peerController.getDegradedConnections());

            if (dialog.isConfirmed()) {
                peerController.resetDegradedConnections();
                peerController.degradeConnections(dialog.getDelays());
            }
        });
    }

    private void send() {
        String msg = msgArea.getText().trim();
        System.out.println(msg.length());
        if (msg.length() > MAX_MSG_SIZE) {
            ArrayList<String> temp = new ArrayList<>();
            temp.add("Your message is too long, split on the '>>>' mark\n");
            for (int i = 0; i < msg.length(); i += MAX_MSG_SIZE) {
                String tempMsg = STR.">>> \{msg.substring(i, Math.min(i + MAX_MSG_SIZE, msg.length()))}\n";
                temp.add(tempMsg);
            }
            StringBuilder toPrint = new StringBuilder();
            temp.forEach(toPrint::append);
            SwingUtilities.invokeLater(() -> msgArea.setText(toPrint.toString()));
        } else {
            SwingUtilities.invokeLater(() -> msgArea.setText(""));

            if (msg.isEmpty())
                return;

            //Get the ChatRoom selected by the user in which he wants to send the msg
            ChatRoom chat = (ChatRoom) chatRooms.getSelectedItem();

            assert chat != null;
            peerController.sendMessage(msg, chat);
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void deleteRoom(ChatRoom chat) {
        peerController.deleteRoom(chat);
        chatRooms.removeItem(chat);
        peerNetManager.deletedChat(chat.getId());
        LOGGER.trace(STR."Room \{chat} removed from gui");
    }

    private void setConnected(boolean connected) {
        if (connected) {
            disconnectReconnectButton.setEnabled(false);
            connectedLabel.setText("Connecting...");
            connectedLabel.setForeground(Color.yellow);
            executorService.execute(() -> {
                try {
                    peerNetManager.start();
                    peerController = peerNetManager.getController();
                    SwingUtilities.invokeLater(() -> {
                        connectedLabel.setText("Connected");
                        connectedLabel.setForeground(new Color(0, 153, 51));

                        disconnectReconnectButton.setText("Disconnect");
                        disconnectReconnectButton.setEnabled(true);
                    });
                } catch (IOException e) {
                    JOptionPane.showMessageDialog(JOptionPane.getRootFrame(),
                            STR."""
                                Can't establish the connection.
                                \{e}""",
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                    SwingUtilities.invokeLater(() -> {
                        connectedLabel.setText("Disconnected");
                        connectedLabel.setForeground(Color.red);

                        disconnectReconnectButton.setText("Reconnect");
                        disconnectReconnectButton.setEnabled(true);
                    });
                }
            });
        } else {
            disconnectReconnectButton.setEnabled(false);
            executorService.execute(() -> {
                try {
                    peerNetManager.disconnect();
                    SwingUtilities.invokeLater(() -> {
                        connectedLabel.setText("Disconnected");
                        connectedLabel.setForeground(Color.red);

                        disconnectReconnectButton.setText("Reconnect");
                        disconnectReconnectButton.setEnabled(true);
                    });
                } catch (DiscoveryUnreachableException e) {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(JOptionPane.getRootFrame(),
                                "Failed to disconnect: discovery unreachable",
                                "Error",
                                JOptionPane.ERROR_MESSAGE);
                        disconnectReconnectButton.setEnabled(true);
                    });
                }
            });
        }
    }

    public JPanel getRootPanel() {
        return panel;
    }
}
