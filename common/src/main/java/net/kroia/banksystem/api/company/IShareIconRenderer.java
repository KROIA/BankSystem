package net.kroia.banksystem.api.company;

import net.kroia.banksystem.util.ItemID;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Task #50 R4 (v2.0.8) — optional renderer that paints a company's tinted preset icon
 * into a screen-space rect. Provided so downstream mods can render share icons in
 * arbitrary rectangles (row entries, orderbook headers) without re-implementing the
 * preset atlas + tint pipeline.
 *
 * <p>When {@link net.kroia.banksystem.api.BankSystemAPI#getShareIconRenderer()} returns
 * {@code null} (e.g. on dedicated servers, or when the renderer has not been wired yet)
 * callers should fall back to the vanilla {@code ItemStack} renderer for the share item.
 */
public interface IShareIconRenderer {

    /**
     * Draw the company's tinted preset icon into the given screen-space rect.
     *
     * @param gfx    the client render context.
     * @param x      top-left X in screen space.
     * @param y      top-left Y in screen space.
     * @param w      target width in pixels.
     * @param h      target height in pixels.
     * @param itemId the stamped-share {@link ItemID}. Implementations should render
     *               nothing (silently) when the ItemID does not point at a live share.
     */
    void renderInto(GuiGraphics gfx, int x, int y, int w, int h, ItemID itemId);
}
