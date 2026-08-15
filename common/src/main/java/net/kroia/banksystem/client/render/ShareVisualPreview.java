package net.kroia.banksystem.client.render;

import net.kroia.banksystem.banking.company.ShareVisuals;

/**
 * v2.1.0 — client-side preview override for the share visual editor.
 *
 * <p>While the {@code ShareVisualEditorScreen} is open it publishes the current
 * (unsaved) editor state here. {@link StampedShareRenderer} consults this override
 * BEFORE {@link net.kroia.banksystem.client.cache.ShareVisualCache}, so a rendered
 * stamped share of the edited company shows the live edits — that is what drives the
 * item preview on the editor screen. Cleared when the editor closes.
 *
 * <p>Render-thread writes, render-thread reads — no synchronization needed; fields
 * are volatile only to be safe against tooling that reads off-thread.
 */
public final class ShareVisualPreview {

    private static volatile Integer previewCompanyId = null;
    private static volatile ShareVisuals previewVisuals = null;

    private ShareVisualPreview() {}

    public static void set(int companyId, ShareVisuals visuals) {
        previewVisuals = visuals;
        previewCompanyId = companyId;
    }

    public static void clear() {
        previewCompanyId = null;
        previewVisuals = null;
    }

    /** @return the preview visuals for this company, or {@code null} if no preview is active. */
    public static ShareVisuals get(int companyId) {
        Integer id = previewCompanyId;
        return (id != null && id == companyId) ? previewVisuals : null;
    }
}
