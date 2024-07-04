package it.polimi.peer.gui;

import javax.swing.*;
import java.awt.*;

public class LeftArrowBubble extends MessageBubble {
    private int arrowSize = 12;
    private int padding = 1;

    public LeftArrowBubble(String sender, String msg) {
        super(sender, msg);
        msgPanelLayout.setHorizontalGroup(
                msgPanelLayout.createParallelGroup(GroupLayout.Alignment.LEADING)
                        .addGroup(msgPanelLayout.createSequentialGroup()
                                .addGap(20, 20, 20)
                                .addComponent(this.content)
                                .addContainerGap(10, Short.MAX_VALUE))
                        .addGroup(msgPanelLayout.createSequentialGroup()
                                .addGap(20, 20, 20)
                                .addComponent(this.title)
                                .addContainerGap(10, Short.MAX_VALUE))
        );
    }

    @Override
    protected void paintComponent(final Graphics g) {
        final Graphics2D g2d = (Graphics2D) g;
        g2d.setColor(new Color(0.5f, 0.8f, 1f));
        int x = padding + arrowSize;
        int width = getWidth() - arrowSize - padding * 2;
        int bottomLineY = getHeight() - padding * 2;
        g2d.setRenderingHints(new RenderingHints(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON));
        g2d.fillRoundRect(x, padding, width, bottomLineY, radius, radius);
        Polygon arrow = new Polygon();
        arrow.addPoint(20, 6);
        arrow.addPoint(0, 10);
        arrow.addPoint(20, 14);
        g2d.fill(arrow);
    }
}

