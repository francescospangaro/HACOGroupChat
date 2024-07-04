package it.polimi.peer.gui;

import javax.swing.*;
import java.awt.*;

public class CloseChatBubble extends MessageBubble {
    private int padding = 3;

    public CloseChatBubble(String sender, String msg) {
        super(STR."\{sender} \{msg}");
        content.setFont(content.getFont().deriveFont(Font.BOLD));

        msgPanelLayout.setHorizontalGroup(
                msgPanelLayout.createSequentialGroup()
                        .addGap(15, 15, 15)
                        .addComponent(this.content)
                        .addContainerGap(GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
        );
    }

    @Override
    protected void paintComponent(final Graphics g) {
        final Graphics2D g2d = (Graphics2D) g;
        g2d.setColor(new Color(169, 169, 169));
        int width = getWidth() - 2 * padding;
        int bottomLineY = getHeight() - 2 * padding;
        g2d.setRenderingHints(new RenderingHints(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON));
        g2d.fillRoundRect(padding, padding, width, bottomLineY, radius, radius);
    }
}

