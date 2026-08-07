package net.kroia.banksystem.screen.widgets;

import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.base.GuiElement;

/**
 * Task #47 follow-up (v2.0.8) — Reusable single-row company button used by
 * {@link CompanySelectionScreen}. Renders the company display name; when the
 * option is marked disabled the row grays out and stops accepting clicks but
 * still shows the description as a hover tooltip anchored BELOW the cursor.
 * <p>
 * Enabled rows fire {@code onClick} on the rising edge (mouse release).
 */
public class CompanySelectionButton extends Button {

    public CompanySelectionButton(CompanySelectionScreen.CompanyOption option, Runnable onClick) {
        super(option.displayName());
        setHeight(20);
        if (option.enabled()) {
            setOnRisingEdge(onClick);
        } else {
            setClickable(false);
            setTextColor(0xFF808080);
        }
        String description = option.description();
        if (description != null && !description.isEmpty()) {
            // Anchor the tooltip at its TOP edge, offset from the cursor —
            // Alignment.TOP + positive mouse offset renders it BELOW the cursor,
            // centered horizontally on the mouse X.
            setHoverTooltipSupplier(() -> description);
            setHoverTooltipMousePositionAlignment(GuiElement.Alignment.TOP);
        }
    }
}
