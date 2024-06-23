package it.polimi.peer.gui;

import javax.swing.*;
import java.awt.*;

public class MessageBubble extends JPanel {
    protected JLabel sender;
    protected JTextArea msg;
    protected GroupLayout msgPanelLayout;

    public MessageBubble(String sender, String msg) {
        this.sender = new JLabel(sender);
        this.msg = new JTextArea(msg);
        this.msg.setBackground(new Color(0, 0, 0, 0));
        this.msg.setOpaque(false);

        msgPanelLayout = new GroupLayout(this);
        setLayout(msgPanelLayout);

        msgPanelLayout.setVerticalGroup(
                msgPanelLayout.createSequentialGroup()
                        .addGap(2, 2, 2)
                        .addComponent(this.sender)
                        .addGap(2, 2, 2)
                        .addComponent(this.msg)
                        .addContainerGap(GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
        );
    }
}
