package net.kroia.banksystem.screen.custom;

// TODO_ART Task #47 (v2.0.8) — reusing bank_upload background PNG; dedicated art pending.

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.minecraft.entity.custom.ShareStamperBlockEntity;
import net.kroia.banksystem.minecraft.menu.custom.ShareStamperContainerMenu;
import net.kroia.banksystem.networking.entity.StampSharesRequest;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * Task #47 (v2.0.8) — MVP screen. Renders the two BE slots, player inventory, and a
 * few control buttons (Stamp, mode toggle, hopper redeem toggle). Progress arrow is
 * drawn as a colored rectangle proportional to {@code stampProgress/200}.
 */
public class ShareStamperScreen extends AbstractContainerScreen<ShareStamperContainerMenu> {

    // TODO_ART placeholder — reuse existing bank upload GUI background PNG.
    private static final ResourceLocation BG = ResourceLocation.fromNamespaceAndPath(
            BankSystemMod.MOD_ID, "textures/gui/bank_upload.png");

    private Button stampBtn;
    private Button modeBtn;
    private Button hopperBtn;

    public ShareStamperScreen(ShareStamperContainerMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
    }

    @Override
    protected void init() {
        super.init();
        int x = this.leftPos + 4;
        int y = this.topPos - 22;
        stampBtn = Button.builder(Component.translatable("gui.banksystem.share_stamper.stamp_button")
                        .append(Component.literal(" " + Math.max(1, menu.getQueuedStamps() + 1))),
                b -> StampSharesRequest.send(menu.getBlockPos(),
                        StampSharesRequest.Op.QUEUE_STAMPS, 1, false))
                .bounds(x, y, 60, 20).build();
        modeBtn = Button.builder(modeLabel(),
                b -> {
                    int next = menu.getModeOrdinal() == 0 ? 1 : 0;
                    StampSharesRequest.send(menu.getBlockPos(),
                            StampSharesRequest.Op.SET_MODE, next, false);
                })
                .bounds(x + 62, y, 54, 20).build();
        hopperBtn = Button.builder(Component.translatable("gui.banksystem.share_stamper.hopper_redeem"),
                b -> StampSharesRequest.send(menu.getBlockPos(),
                        StampSharesRequest.Op.TOGGLE_HOPPER_REDEEM, 0, false))
                .bounds(x + 118, y, 50, 20).build();
        addRenderableWidget(stampBtn);
        addRenderableWidget(modeBtn);
        addRenderableWidget(hopperBtn);
    }

    private Component modeLabel() {
        return Component.translatable(menu.getModeOrdinal() == 1
                ? "gui.banksystem.share_stamper.mode.redeem"
                : "gui.banksystem.share_stamper.mode.stamp");
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (modeBtn != null) modeBtn.setMessage(modeLabel());
    }

    @Override
    protected void renderBg(GuiGraphics g, float partial, int mx, int my) {
        int x = this.leftPos;
        int y = this.topPos;
        g.blit(BG, x, y, 0, 0, this.imageWidth, this.imageHeight);

        // progress arrow: rectangle between input(56,35) and output(116,35).
        int prog = menu.getStampProgress();
        int total = ShareStamperBlockEntity.STAMP_TICKS_PER_CYCLE;
        int w = Math.min(24, Math.max(0, prog * 24 / total));
        g.fill(x + 78, y + 40, x + 78 + w, y + 44, 0xFFFFD700);

        // bind prompt overlay
        if (menu.getBoundCompanyId() < 0) {
            g.drawString(this.font,
                    Component.translatable("gui.banksystem.share_stamper.bind_prompt"),
                    x + 8, y + 6, 0xAA0000, false);
        } else {
            long ti = menu.getTotalIssued();
            long ms = menu.getMaxSupply();
            g.drawString(this.font,
                    Component.translatable("gui.banksystem.share_stamper.supply", ti, ms),
                    x + 8, y + 6, 0x404040, false);
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        this.renderBackground(g, mx, my, partial);
        super.render(g, mx, my, partial);
        this.renderTooltip(g, mx, my);
    }
}
