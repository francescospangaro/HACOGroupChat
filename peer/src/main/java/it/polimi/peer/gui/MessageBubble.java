package it.polimi.peer.gui;

import javax.swing.*;
import java.awt.*;

public class MessageBubble extends JPanel {
    protected JLabel title;
    protected JTextArea content;
    protected GroupLayout msgPanelLayout;
    protected int radius = 10;


    public MessageBubble(String title, String content) {
        this.title = new JLabel(title);
        this.content = new JTextArea(content);
        this.content.setBackground(new Color(0, 0, 0, 0));
        this.content.setOpaque(false);

        msgPanelLayout = new GroupLayout(this);
        setLayout(msgPanelLayout);

        msgPanelLayout.setVerticalGroup(
                msgPanelLayout.createSequentialGroup()
                        .addGap(2, 2, 2)
                        .addComponent(this.title)
                        .addGap(2, 2, 2)
                        .addComponent(this.content)
                        .addContainerGap(GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
        );
    }

    public MessageBubble(String content) {
        this.content = new JTextArea(content);
        this.content.setBackground(new Color(0, 0, 0, 0));
        this.content.setOpaque(false);

        msgPanelLayout = new GroupLayout(this);
        setLayout(msgPanelLayout);

        msgPanelLayout.setVerticalGroup(
                msgPanelLayout.createSequentialGroup()
                        .addGap(6, 6, 6)
                        .addComponent(this.content)
                        .addContainerGap(GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
        );
    }
}
