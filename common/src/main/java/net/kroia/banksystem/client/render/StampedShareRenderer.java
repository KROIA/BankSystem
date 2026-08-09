package net.kroia.banksystem.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.ShareVisuals;
import net.kroia.banksystem.client.cache.ShareVisualCache;
import net.kroia.banksystem.client.company.ClientSymbolRegistry;
import net.kroia.banksystem.client.company.SharePresetRegistry;
import net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;

/**
 * v2.0.9 — runtime layered renderer for the stamped share item (replaces the
 * generated per-combination model set, which grew quadratically with the symbol
 * count).
 *
 * <p>The item model ({@code models/item/stamped_share.json}) is
 * {@code builtin/entity}, so vanilla routes every display context (GUI, hand,
 * ground, item frame, head) through the platform-registered custom item renderer,
 * which delegates here. This method draws, in order:
 * <ol>
 *   <li>the card — {@code blank_share} (unstamped) or {@code stamped_share}
 *       (stamped, tinted with {@link ShareVisuals#getBaseTint()}) as front + back
 *       quads plus per-pixel edge extrusion, matching vanilla flat-item geometry
 *       (1/16 thick, centered);</li>
 *   <li>the background symbol — 16×16 glyph as a decal covering the upper half of
 *       the card (1 glyph px = 1 card px), tinted with the bg layer tint;</li>
 *   <li>the foreground symbol — same glyph size drawn at half scale
 *       (1 glyph px = 1/2 card px), centered on the bg symbol, fg layer tint.</li>
 * </ol>
 *
 * <p>Adding a symbol only requires its 16×16 texture under
 * {@code textures/item/share_symbol/} (auto-stitched into the blocks atlas by the
 * vanilla directory sprite source) plus a {@link SharePresetRegistry} entry —
 * no model files. Additional customization layers are code-only.
 *
 * <p>Visuals resolve {@link ShareVisualPreview} (live editor state) before
 * {@link ShareVisualCache}; a cache miss draws the plain card for the frame and
 * schedules {@link ShareVisualCache#tryLookup(int)} to self-heal.
 */
public final class StampedShareRenderer {

    private static final ResourceLocation BLANK_CARD =
            ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "item/blank_share");
    private static final ResourceLocation STAMPED_CARD =
            ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "item/stamped_share");

    /** Card thickness — identical to vanilla item/generated flat items (1/16, centered). */
    private static final float Z_BACK = 7.5f / 16.0f;
    private static final float Z_FRONT = 8.5f / 16.0f;
    /** Decal lift off the card faces — kills z-fighting, visually imperceptible. */
    private static final float DECAL_EPS = 0.002f;

    // Symbol placement in model space (x right, y up, card spans [0..1]²).
    // Kept in sync with gen_share_symbols.py's preview sheet (BG_OFF/FG_OFF).
    private static final float BG_SIZE = 16f / 32f;          // 16 glyph px @ 1 card px
    private static final float BG_X = 8f / 32f;
    private static final float BG_Y = 1f - (6f + 16f) / 32f; // top edge at card px y=6
    private static final float FG_SIZE = 16f / 64f;          // 16 glyph px @ 1/2 card px
    private static final float FG_X = 12f / 32f;
    private static final float FG_Y = 1f - (10f + 16f / 2f) / 32f;

    /**
     * Per-sprite card geometry (positions + local UVs, atlas-independent). Weak keys:
     * a resource reload creates new {@link TextureAtlasSprite} instances, so stale
     * geometry is simply never hit again and gets collected.
     */
    private static final Map<TextureAtlasSprite, List<Quad>> CARD_GEOMETRY = new WeakHashMap<>();

    /** One baked quad: 4×xyz positions, 4×uv in local sprite space (0..1), one normal. */
    private record Quad(float[] pos, float[] uv, float nx, float ny, float nz) {}

    private StampedShareRenderer() {}

    /**
     * Platform entry point (Fabric {@code DynamicItemRenderer} / NeoForge
     * {@code BlockEntityWithoutLevelRenderer#renderByItem}). The pose already carries
     * the display-context transform from the item model JSON plus the vanilla
     * {@code translate(-0.5)} — geometry below lives in [0..1] model space.
     */
    public static void render(ItemStack stack, ItemDisplayContext ctx, PoseStack poseStack,
                              MultiBufferSource buffers, int light, int overlay) {
        Function<ResourceLocation, TextureAtlasSprite> atlas =
                Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS);
        VertexConsumer vc = buffers.getBuffer(Sheets.cutoutBlockSheet());
        PoseStack.Pose pose = poseStack.last();

        Integer companyId = StampedShareItem.getCompanyId(stack);
        ShareVisuals visuals = null;
        if (companyId != null) {
            visuals = ShareVisualPreview.get(companyId);
            if (visuals == null) {
                if (ShareVisualCache.has(companyId)) {
                    visuals = ShareVisualCache.getVisualsOrPlaceholder(companyId);
                } else {
                    ShareVisualCache.tryLookup(companyId); // plain card this frame, repaints once loaded
                }
            }
        }

        TextureAtlasSprite card = atlas.apply(companyId != null ? STAMPED_CARD : BLANK_CARD);
        int cardTint = visuals != null ? opaque(visuals.getBaseTint()) : 0xFFFFFFFF;
        List<Quad> geometry = CARD_GEOMETRY.computeIfAbsent(card, StampedShareRenderer::buildCardGeometry);
        for (Quad q : geometry) {
            emit(vc, pose, q, card, cardTint, light, overlay);
        }

        if (visuals != null) {
            drawSymbol(buffers, vc, pose, atlas, visuals.getBgLayer(), BG_X, BG_Y, BG_SIZE,
                    DECAL_EPS, light, overlay);
            drawSymbol(buffers, vc, pose, atlas, visuals.getFgLayer(), FG_X, FG_Y, FG_SIZE,
                    2 * DECAL_EPS, light, overlay);
        }
    }

    /**
     * Draw one symbol layer as front + back decal quads flush on the card faces.
     * Bundled symbols use the blocks atlas (same render type as the card).
     * Server-synced dynamic symbols use a {@link net.minecraft.client.renderer.texture.DynamicTexture}
     * registered under {@code banksystem:dynamic/share_symbol/<id>} via
     * {@link net.minecraft.client.renderer.RenderType#entityCutoutNoCull}.
     */
    private static void drawSymbol(MultiBufferSource buffers, VertexConsumer atlasVc,
                                   PoseStack.Pose pose,
                                   Function<ResourceLocation, TextureAtlasSprite> atlas,
                                   ShareVisuals.ShareLayer layer,
                                   float x0, float y0, float size, float eps,
                                   int light, int overlay) {
        if (layer == null) return;
        String id = layer.symbolId();
        if (id == null || id.isBlank() || !SharePresetRegistry.isValidPresetId(id)) return;
        int tint = opaque(layer.tint());
        float x1 = x0 + size, y1 = y0 + size;
        float zf = Z_FRONT + eps, zb = Z_BACK - eps;

        if (SharePresetRegistry.isBundledId(id)) {
            // Bundled symbol: render via blocks atlas sprite.
            TextureAtlasSprite sprite = atlas.apply(ResourceLocation.fromNamespaceAndPath(
                    BankSystemMod.MOD_ID, "item/share_symbol/" + id));
            emit(atlasVc, pose, new Quad(
                    new float[]{x0, y0, zf, x1, y0, zf, x1, y1, zf, x0, y1, zf},
                    new float[]{0, 1, 1, 1, 1, 0, 0, 0}, 0, 0, 1), sprite, tint, light, overlay);
            emit(atlasVc, pose, new Quad(
                    new float[]{x0, y0, zb, x0, y1, zb, x1, y1, zb, x1, y0, zb},
                    new float[]{0, 1, 0, 0, 1, 0, 1, 1}, 0, 0, -1), sprite, tint, light, overlay);
        } else if (ClientSymbolRegistry.hasDynamicTexture(id)) {
            // Dynamic synced symbol: render via DynamicTexture registered with TextureManager.
            ResourceLocation dynRl = ClientSymbolRegistry.getDynamicRL(id);
            VertexConsumer dynVc = buffers.getBuffer(RenderType.entityCutoutNoCull(dynRl));
            emitRaw(dynVc, pose, new Quad(
                    new float[]{x0, y0, zf, x1, y0, zf, x1, y1, zf, x0, y1, zf},
                    new float[]{0, 1, 1, 1, 1, 0, 0, 0}, 0, 0, 1), tint, light, overlay);
            emitRaw(dynVc, pose, new Quad(
                    new float[]{x0, y0, zb, x0, y1, zb, x1, y1, zb, x1, y0, zb},
                    new float[]{0, 1, 0, 0, 1, 0, 1, 1}, 0, 0, -1), tint, light, overlay);
        }
        // else: symbol is in ClientSymbolRegistry but DynamicTexture not yet registered
        //       (bytes arrived, but registration is scheduled for next render tick).
        //       Render nothing — base card shows through. Resolves on the next frame.
    }

    private static void emit(VertexConsumer vc, PoseStack.Pose pose, Quad q,
                             TextureAtlasSprite sprite, int tintARGB, int light, int overlay) {
        int r = (tintARGB >> 16) & 0xFF;
        int g = (tintARGB >> 8) & 0xFF;
        int b = tintARGB & 0xFF;
        for (int i = 0; i < 4; i++) {
            vc.addVertex(pose, q.pos[3 * i], q.pos[3 * i + 1], q.pos[3 * i + 2])
                    .setColor(r, g, b, 0xFF)
                    .setUv(sprite.getU(q.uv[2 * i]), sprite.getV(q.uv[2 * i + 1]))
                    .setOverlay(overlay)
                    .setLight(light)
                    .setNormal(pose, q.nx, q.ny, q.nz);
        }
    }

    /** Like {@link #emit} but uses raw [0,1] UV directly (for DynamicTexture, not atlas sprites). */
    private static void emitRaw(VertexConsumer vc, PoseStack.Pose pose, Quad q,
                                int tintARGB, int light, int overlay) {
        int r = (tintARGB >> 16) & 0xFF;
        int g = (tintARGB >> 8) & 0xFF;
        int b = tintARGB & 0xFF;
        for (int i = 0; i < 4; i++) {
            vc.addVertex(pose, q.pos[3 * i], q.pos[3 * i + 1], q.pos[3 * i + 2])
                    .setColor(r, g, b, 0xFF)
                    .setUv(q.uv[2 * i], q.uv[2 * i + 1])
                    .setOverlay(overlay)
                    .setLight(light)
                    .setNormal(pose, q.nx, q.ny, q.nz);
        }
    }

    /**
     * Full-rect front and back faces (transparent texels are discarded by the cutout
     * shader) plus per-pixel edge extrusion equivalent to vanilla's
     * {@code ItemModelGenerator}: a side quad wherever an opaque pixel borders a
     * transparent one (or the texture edge), with runs merged along each row/column.
     */
    private static List<Quad> buildCardGeometry(TextureAtlasSprite sprite) {
        SpriteContents contents = sprite.contents();
        int w = contents.width(), h = contents.height();
        boolean[][] op = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                op[y][x] = !contents.isTransparent(0, x, y);
            }
        }
        List<Quad> quads = new ArrayList<>();
        quads.add(new Quad( // front (+z)
                new float[]{0, 0, Z_FRONT, 1, 0, Z_FRONT, 1, 1, Z_FRONT, 0, 1, Z_FRONT},
                new float[]{0, 1, 1, 1, 1, 0, 0, 0}, 0, 0, 1));
        quads.add(new Quad( // back (-z)
                new float[]{0, 0, Z_BACK, 0, 1, Z_BACK, 1, 1, Z_BACK, 1, 0, Z_BACK},
                new float[]{0, 1, 0, 0, 1, 0, 1, 1}, 0, 0, -1));

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; ) { // top edges (+y), runs along x
                if (op[y][x] && (y == 0 || !op[y - 1][x])) {
                    int x0 = x;
                    while (x < w && op[y][x] && (y == 0 || !op[y - 1][x])) x++;
                    float ym = 1f - (float) y / h, xs0 = (float) x0 / w, xs1 = (float) x / w;
                    float v = (y + 0.5f) / h;
                    quads.add(new Quad(
                            new float[]{xs0, ym, Z_FRONT, xs1, ym, Z_FRONT, xs1, ym, Z_BACK, xs0, ym, Z_BACK},
                            new float[]{xs0, v, xs1, v, xs1, v, xs0, v}, 0, 1, 0));
                } else x++;
            }
            for (int x = 0; x < w; ) { // bottom edges (-y), runs along x
                if (op[y][x] && (y == h - 1 || !op[y + 1][x])) {
                    int x0 = x;
                    while (x < w && op[y][x] && (y == h - 1 || !op[y + 1][x])) x++;
                    float ym = 1f - (float) (y + 1) / h, xs0 = (float) x0 / w, xs1 = (float) x / w;
                    float v = (y + 0.5f) / h;
                    quads.add(new Quad(
                            new float[]{xs0, ym, Z_BACK, xs1, ym, Z_BACK, xs1, ym, Z_FRONT, xs0, ym, Z_FRONT},
                            new float[]{xs0, v, xs1, v, xs1, v, xs0, v}, 0, -1, 0));
                } else x++;
            }
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; ) { // left edges (-x), runs along y
                if (op[y][x] && (x == 0 || !op[y][x - 1])) {
                    int y0 = y;
                    while (y < h && op[y][x] && (x == 0 || !op[y][x - 1])) y++;
                    float xm = (float) x / w, yLo = 1f - (float) y / h, yHi = 1f - (float) y0 / h;
                    float u = (x + 0.5f) / w, vLo = (float) y / h, vHi = (float) y0 / h;
                    quads.add(new Quad(
                            new float[]{xm, yLo, Z_BACK, xm, yLo, Z_FRONT, xm, yHi, Z_FRONT, xm, yHi, Z_BACK},
                            new float[]{u, vLo, u, vLo, u, vHi, u, vHi}, -1, 0, 0));
                } else y++;
            }
            for (int y = 0; y < h; ) { // right edges (+x), runs along y
                if (op[y][x] && (x == w - 1 || !op[y][x + 1])) {
                    int y0 = y;
                    while (y < h && op[y][x] && (x == w - 1 || !op[y][x + 1])) y++;
                    float xm = (float) (x + 1) / w, yLo = 1f - (float) y / h, yHi = 1f - (float) y0 / h;
                    float u = (x + 0.5f) / w, vLo = (float) y / h, vHi = (float) y0 / h;
                    quads.add(new Quad(
                            new float[]{xm, yLo, Z_BACK, xm, yHi, Z_BACK, xm, yHi, Z_FRONT, xm, yLo, Z_FRONT},
                            new float[]{u, vLo, u, vHi, u, vHi, u, vLo}, 1, 0, 0));
                } else y++;
            }
        }
        return quads;
    }

    /** Force full alpha — the cutout shader ignores blending; a stored 0 alpha means "no tint". */
    private static int opaque(int argb) {
        return argb | 0xFF000000;
    }
}
