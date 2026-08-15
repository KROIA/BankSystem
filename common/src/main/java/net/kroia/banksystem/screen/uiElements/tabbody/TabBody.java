package net.kroia.banksystem.screen.uiElements.tabbody;

import net.kroia.banksystem.screen.custom.CompanyManagementScreen;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.minecraft.client.Minecraft;

/**
 * Task #51 (v2.1.0, spec §0.5) — base class for CompanyManagementScreen tab bodies.
 *
 * <p>Layout discipline: NO {@code LayoutGrid} / {@code VerticalListView} for the tab
 * body itself (LayoutGrid silently drops children past its row count, and TabElement's
 * deferred child attachment prevents layout application on non-selected tabs). Every
 * subclass overrides {@link #layoutChanged()} and calls {@code setBounds(x, y, w, h)}
 * on each child explicitly — the same pattern as
 * {@code BankAccountManagementScreen.updateLayout}.
 */
public abstract class TabBody extends GuiElement {

    protected static final int PADDING = 5;
    protected static final int ROW_HEIGHT = 20;
    protected static final int ROW_SPACING = 4;
    protected static final int SECTION_SPACING = 8;
    protected static final int LABEL_WIDTH = 90;

    protected final CompanyManagementScreen screen;

    protected TabBody(CompanyManagementScreen screen) {
        super();
        this.screen = screen;
    }

    @Override
    protected void render() {}

    /** Spec §7 — called by the screen when fresh async info lands. Default: no-op
     *  (the screen rebuilds tab bodies on info arrival anyway). */
    public void onInfoUpdated() {}

    /** Marshal an async continuation onto the render thread. */
    protected static void onClientThread(Runnable r) {
        Minecraft.getInstance().execute(r);
    }

    /** Formats a long with thousand separators (spec §1.3). */
    protected static String formatNumber(long v) {
        return String.format("%,d", v);
    }
}
