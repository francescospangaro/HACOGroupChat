package it.polimi.peer.gui;

import javax.swing.*;
import java.awt.*;

public class RightArrowBubble extends MessageBubble {
    private int arrowSize = 12;
    private int padding = 1;

    public RightArrowBubble(String msg) {
        super(msg);
        msgPanelLayout.setHorizontalGroup(
                msgPanelLayout.createParallelGroup(GroupLayout.Alignment.LEADING)
                        .addGroup(msgPanelLayout.createSequentialGroup()
                                .addGap(10, 10, 10)
                                .addComponent(this.content)
                                .addContainerGap(20, Short.MAX_VALUE))
        );
    }

    @Override
    protected void paintComponent(final Graphics g) {
        final Graphics2D g2d = (Graphics2D) g;
        g2d.setColor(new Color(0.5f, 0.5f, 1f));
        int bottomLineY = getHeight() - 2 * padding;
        int width = getWidth() - arrowSize - padding * 2;
        g2d.setRenderingHints(new RenderingHints(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON));
        g2d.fillRoundRect(padding, padding, width, bottomLineY, radius, radius);
        Polygon arrow = new Polygon();
        arrow.addPoint(width, 6);
        arrow.addPoint(width + arrowSize, 10);
        arrow.addPoint(width, 14);
        g2d.fill(arrow);
    }
}
