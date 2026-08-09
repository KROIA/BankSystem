package net.kroia.banksystem.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.kroia.banksystem.api.company.IShareIconRenderer;
import net.kroia.banksystem.banking.company.ShareVisuals;
import net.kroia.banksystem.client.cache.CompanyInfoCache;
import net.kroia.banksystem.client.cache.ShareVisualCache;
import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * Task #50 (v2.0.8) — provides the {@link IShareIconRenderer} implementation that
 * renders the stamped-share {@link ItemStack} for a given {@link ItemID} into an
 * arbitrary screen-space rect via {@link GuiGraphics#renderItem} (card, tint and
 * symbol layers drawn by {@link StampedShareRenderer} through the item's custom
 * renderer) — this is the concrete implementation returned from
 * {@code BankSystemAPI.getShareIconRenderer()}.
 *
 * <p>Client only. On dedicated servers this class is never loaded — the public API
 * accessor returns {@code null}, matching the interface contract (see
 * {@code IShareIconRenderer} Javadoc).
 */
public final class BankSystemColorHandlers {

    /** Concrete renderer instance handed out via the public API. Client-side only. */
    public static final IShareIconRenderer SHARE_ICON_RENDERER = new IShareIconRenderer() {
        @Override
        public void renderInto(GuiGraphics gfx, int x, int y, int w, int h, ItemID itemId) {
            if (gfx == null || itemId == null || !itemId.isValid()) return;
            ItemStack template = ItemIDManager.getItemStack(itemId);
            if (template.isEmpty()) return;
            if (template.getItem() != BankSystemItems.STAMPED_SHARE.get()) return;
            if (w <= 0 || h <= 0) return;

            Integer companyId = StampedShareItem.getCompanyId(template);
            ShareVisuals visuals = companyId != null
                    ? ShareVisualCache.getVisualsOrPlaceholder(companyId)
                    : null;

            // Monogram fallback (Option A, v2.0.8): when no preset icon is chosen,
            // paint a tinted badge with the first ~2 initials of the company's
            // display name (or Company.name from CompanyInfoCache). Gives every
            // company a distinctive glyph without depending on 30 art assets.
            if (visuals != null && (visuals.getFgLayer().symbolId() == null || visuals.getFgLayer().symbolId().isBlank())) {
                String initials = resolveInitials(companyId, visuals);
                int tint = 0xFF000000 | (visuals.getBgLayer().tint() & 0xFFFFFF);
                paintMonogramBadge(gfx, x, y, w, h, tint, initials);
                return;
            }

            // Preset path — render the tinted item stack (tint via ItemColor handler).
            PoseStack pose = gfx.pose();
            pose.pushPose();
            pose.translate(x, y, 0.0f);
            float sx = w / 16.0f;
            float sy = h / 16.0f;
            pose.scale(sx, sy, 1.0f);
            gfx.renderItem(template, 0, 0);
            gfx.renderItemDecorations(Minecraft.getInstance().font, template, 0, 0);
            pose.popPose();
        }
    };

    /**
     * Resolve the display initials for a company: prefer the user-set display name;
     * fall back to the internal {@code Company.name}. Splits on whitespace and takes
     * the first letter of up to 2 words; if the name is a single word, take its
     * first 2 letters. Uppercased. Never returns null / empty.
     */
    /** Task #53 (v2.0.8) — public delegate used by {@link StampedShareBadgePainter}. */
    public static String resolveMonogramInitials(Integer companyId, ShareVisuals visuals) {
        return resolveInitials(companyId, visuals);
    }

    private static String resolveInitials(Integer companyId, ShareVisuals visuals) {
        String source = visuals != null ? visuals.getDisplayName() : null;
        if (source == null || source.isBlank()) {
            if (companyId != null) {
                CompanyInfoCache.Snapshot snap = CompanyInfoCache.get(companyId);
                if (snap != null) source = snap.name();
            }
        }
        if (source == null || source.isBlank()) return "?";
        String cleaned = source.trim();
        String[] parts = cleaned.split("\\s+");
        StringBuilder out = new StringBuilder();
        if (parts.length >= 2) {
            out.append(Character.toUpperCase(parts[0].charAt(0)));
            out.append(Character.toUpperCase(parts[1].charAt(0)));
        } else {
            String w = parts[0];
            out.append(Character.toUpperCase(w.charAt(0)));
            if (w.length() >= 2) out.append(Character.toUpperCase(w.charAt(1)));
        }
        return out.toString();
    }

    /**
     * Paint a filled badge in {@code tintARGB} with {@code initials} centered on top,
     * text color chosen for contrast (white on dark tints, black on light). A subtle
     * 1-pixel darker border frames the badge so it reads as a chip even on similar
     * backgrounds.
     */
    private static void paintMonogramBadge(GuiGraphics gfx, int x, int y, int w, int h,
                                           int tintARGB, String initials) {
        int r = (tintARGB >> 16) & 0xFF;
        int g = (tintARGB >>  8) & 0xFF;
        int b =  tintARGB        & 0xFF;
        // Perceptual luminance (Rec. 601). Threshold ~140 reads well for both.
        int luma = (299 * r + 587 * g + 114 * b) / 1000;
        int textColor = luma > 140 ? 0xFF202020 : 0xFFF0F0F0;
        int border = darken(tintARGB, 0.55f);

        gfx.fill(x, y, x + w, y + h, tintARGB);
        // 1-pixel inset border for definition.
        gfx.fill(x,         y,         x + w,     y + 1,     border);
        gfx.fill(x,         y + h - 1, x + w,     y + h,     border);
        gfx.fill(x,         y,         x + 1,     y + h,     border);
        gfx.fill(x + w - 1, y,         x + w,     y + h,     border);

        Font font = Minecraft.getInstance().font;
        int textW = font.width(initials);
        int textH = font.lineHeight;
        float scale = Math.min((w - 4f) / Math.max(textW, 1), (h - 4f) / Math.max(textH, 1));
        if (scale <= 0.0f) return;

        PoseStack pose = gfx.pose();
        pose.pushPose();
        float cx = x + w / 2.0f;
        float cy = y + h / 2.0f;
        pose.translate(cx, cy, 0.0f);
        pose.scale(scale, scale, 1.0f);
        pose.translate(-textW / 2.0f, -textH / 2.0f, 0.0f);
        gfx.drawString(font, initials, 0, 0, textColor, false);
        pose.popPose();
    }

    private static int darken(int argb, float factor) {
        int a = (argb >> 24) & 0xFF;
        int r = (int) (((argb >> 16) & 0xFF) * factor);
        int g = (int) (((argb >>  8) & 0xFF) * factor);
        int b = (int) ( (argb        & 0xFF) * factor);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private BankSystemColorHandlers() {}
}
