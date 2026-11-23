package net.kroia.banksystem.screen.uiElements;

import net.kroia.modutilities.gui.elements.Label;

import java.util.ArrayList;
import java.util.List;

/**
 * BS-241 — a {@link Label} that word-wraps its text to the label width (and breaks at {@code \n}),
 * drawn from the top left. Lines beyond the label height are clipped by the frame, so size it for the text.
 */
public class WrappedLabel extends Label {

    public WrappedLabel(String text) {
        super(text);
    }

    @Override
    public void render() {
        int pad = getPadding();
        int y = pad;
        for (String line : wrap(getText(), getWidth() - 2 * pad)) {
            drawText(line, pad, y);
            y += getTextHeight() + 1;
        }
    }

    private List<String> wrap(String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                String next = line.isEmpty() ? word : line + " " + word;
                if (!line.isEmpty() && maxWidth > 0 && getTextWidth(next) > maxWidth) {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(next);
                }
            }
            out.add(line.toString());
        }
        return out;
    }
}
