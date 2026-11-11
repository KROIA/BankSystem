package net.kroia.banksystem.screen.custom;

import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.general.ModSettingsRequest;
import net.kroia.banksystem.screen.uiElements.AskPopupScreen;
import net.kroia.banksystem.util.BankSystemGuiElement;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.CheckBox;
import net.kroia.modutilities.gui.elements.CloseButton;
import net.kroia.modutilities.gui.elements.FeatureToggleSection;
import net.kroia.modutilities.gui.elements.ItemView;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.TextBox;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.elements.base.ListView;
import net.kroia.modutilities.gui.layout.LayoutVertical;
import net.kroia.modutilities.gui.screens.CreativeModeItemSelectionScreen;
import net.kroia.modutilities.setting.Setting;
import net.kroia.modutilities.setting.SettingsGroup;
import net.kroia.modutilities.setting.SettingsStore;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Master-only admin screen for editing the master server's {@code settings.json}
 * in-game. Opened from the {@code BankSystemSettingScreen} (the management window,
 * {@code /banksystem manage}) via the "Mod Settings" button, which is visible only
 * when connected to the master server; the server additionally enforces BankSystem
 * admin + master status in {@link ModSettingsRequest}.
 * <p>
 * The screen never reads client-side settings — on open it fetches the current
 * values from the master (GET), and "Apply" sends the edited values (SET); the
 * server validates/clamps, persists to settings.json (targeted settings-only save)
 * and returns the confirmed state, which is re-displayed.
 * <p>
 * Fields are built generically from {@link BankSystemModSettings#getEditableGroups()}:
 * Booleans become checkboxes, Integer/Long/Float become numeric text boxes,
 * ItemStacks get an item view + creative-inventory item picker, {@code List<String>}
 * becomes a comma-separated text box and Strings become a plain text box.
 * Exception: the Integrations group is laid out by hand as collapsible on/off
 * sections ({@link #buildIntegrationSections}); new settings there must be added
 * explicitly.
 * <p>
 * <b>DEVELOPER NOTE — when adding a new setting:</b>
 * <ol>
 *   <li>You MUST also add a matching editing field to this screen. Settings of a
 *       supported type (Boolean/Integer/Long/Float/String/ItemStack/
 *       List&lt;String&gt;) inside a registered group appear automatically via
 *       the generic row builder — still add the label/tooltip lang keys
 *       ({@code gui.banksystem.mod_settings_screen.setting.<Group>.<NAME>[.tooltip]})
 *       and verify the field renders and applies correctly. Unsupported types need
 *       a dedicated editor row.</li>
 *   <li>If the new setting is only read ONCE (at startup / world load /
 *       construction and cached for the server lifetime), mark the field as
 *       restart-required by adding {@code "<Group>.<NAME>"} to
 *       {@link #RESTART_REQUIRED_SETTINGS}.</li>
 *   <li>Consider whether the value must be propagated to slave servers after a
 *       change — see the per-group propagation decision documented in
 *       {@link ModSettingsRequest#handleOnMasterServer}.</li>
 * </ol>
 */
public class ModSettingsScreen extends BankSystemGuiScreen {

    private static class Texts {
        private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".mod_settings_screen.";
        public static final Component TITLE = Component.translatable(PREFIX + "title");
        public static final Component APPLY = Component.translatable(PREFIX + "apply");
        public static final Component RELOAD = Component.translatable(PREFIX + "reload");
        public static final Component DEFAULTS = Component.translatable(PREFIX + "defaults");
        public static final Component DEFAULTS_TOOLTIP = Component.translatable(PREFIX + "defaults.tooltip");
        public static final Component SELECT_ITEM = Component.translatable(PREFIX + "select_item");
        public static final Component RESTART_REQUIRED = Component.translatable(PREFIX + "restart_required");
        public static final Component RESTART_REQUIRED_TOOLTIP = Component.translatable(PREFIX + "restart_required.tooltip");
        public static final Component STATUS_LOADING = Component.translatable(PREFIX + "status.loading");
        public static final Component STATUS_LOADED = Component.translatable(PREFIX + "status.loaded");
        public static final Component STATUS_SAVED = Component.translatable(PREFIX + "status.saved");
        public static final Component STATUS_FAILED = Component.translatable(PREFIX + "status.failed");

        /** Heading label of a settings group ("Utilities", "Player", "ServerBank", "Audit", "Integrations"). */
        public static Component group(String groupName) {
            return Component.translatable(PREFIX + "group." + groupName);
        }
        /** Display label of one setting. */
        public static Component setting(String groupName, String settingName) {
            return Component.translatable(PREFIX + "setting." + groupName + "." + settingName);
        }
        /** Hover tooltip of one setting. */
        public static Component settingTooltip(String groupName, String settingName) {
            return Component.translatable(PREFIX + "setting." + groupName + "." + settingName + ".tooltip");
        }
        /** Description text of an Integrations section (one label per "\n"-separated line). */
        public static Component settingDescription(String groupName, String settingName) {
            return Component.translatable(PREFIX + "setting." + groupName + "." + settingName + ".description");
        }
    }

    private static final int ELEMENT_HEIGHT = 20;
    private static final int STATUS_COLOR_OK = 0xFF40D040;
    private static final int STATUS_COLOR_ERROR = 0xFFF04040;
    private static final int STATUS_COLOR_NEUTRAL = 0xFFC0C0C0;
    private static final int RESTART_MARKER_COLOR = 0xFFFFAA30;
    private static final int GROUP_HEADING_COLOR = 0xFFFFD966;

    /** Reflective type of the {@code List<String>} component-id list settings. */
    private static final Type STRING_LIST_TYPE = new TypeToken<List<String>>() {}.getType();

    /**
     * Settings that accept negative integers (e.g. -1 for "unlimited").
     * The editor uses a signed regex for these instead of the positive-only default.
     */
    private static final Set<String> SIGNED_INTEGER_SETTINGS = Set.of(
            "Player.MAX_BANK_ACCOUNTS_PER_PLAYER",
            "Player.MAX_COMPANIES_PER_PLAYER"
    );

    /**
     * Settings ("&lt;GroupName&gt;.&lt;SETTING_NAME&gt;") whose value is only read once at
     * startup and cached/applied for the server lifetime — editing them requires a
     * server restart to take effect. Verified consumption points:
     * <ul>
     *   <li>{@code ServerBank.ADDITIONAL_VOLATILE_COMPONENTS} /
     *       {@code ServerBank.ADDITIONAL_DEPOSIT_GATED_COMPONENTS}: applied to
     *       {@code VolatileItemComponents} once during world load
     *       ({@code BankSystemDataHandler.applyVolatileComponentSettings()}) and
     *       evaluated by the startup ItemID merge guard. Runtime changes to ItemID
     *       identity are explicitly forbidden mid-session (same rule as the
     *       datapack-reload rejection), so the edit only takes effect — and is only
     *       safety-checked by the merge guard — at the next restart.</li>
     *   <li>{@code ServerBank.CONFIRM_ITEMID_MERGE} /
     *       {@code ServerBank.CONFIRM_ITEMID_REPAIR}: ONE-SHOT startup confirmation
     *       flags, consumed by the merge/repair guards during world load and
     *       automatically reset to false after use. Editing them here is exactly how
     *       an admin approves a pending merge/repair without editing settings.json —
     *       set to true, apply, then restart the server once.</li>
     *   <li>{@code Integrations.PLACEHOLDERS_ENABLED}: checked once at server start
     *       (placeholder registration is skipped when off).</li>
     *   <li>{@code Integrations.TOP_COUNT}: read once when the placeholders are
     *       registered during server startup (it fixes the number of
     *       {@code top_<n>} slots). The other Integrations settings are read live.</li>
     * </ul>
     * All other registered settings are read live at their point of use (autosave /
     * snapshot timers and logging flags per call or tick, starting balance per user
     * creation, block tick intervals per block tick).
     */
    private static final Set<String> RESTART_REQUIRED_SETTINGS = Set.of(
            "ServerBank.ADDITIONAL_VOLATILE_COMPONENTS",
            "ServerBank.ADDITIONAL_DEPOSIT_GATED_COMPONENTS",
            "ServerBank.CONFIRM_ITEMID_MERGE",
            "ServerBank.CONFIRM_ITEMID_REPAIR",
            "ServerBank.CONFIRM_ITEMID_REMOVE_MISSING",
            "Integrations.PLACEHOLDERS_ENABLED",
            "Integrations.TOP_COUNT"
    );

    /**
     * Binds one GUI editor widget to one {@link Setting} of the local working copy.
     * {@link #loadFromSetting()} writes setting → widget, {@link #storeToSetting()}
     * writes widget → setting (invalid input keeps the current setting value).
     */
    private interface FieldBinding {
        void loadFromSetting();
        void storeToSetting();
    }

    /**
     * Local working copy of the server settings. Populated from the master's GET
     * response, edited through the field bindings and serialized as the SET payload.
     * Never persisted locally — the master remains the single source of truth.
     */
    private final BankSystemModSettings workingCopy = new BankSystemModSettings();
    private final List<FieldBinding> bindings = new ArrayList<>();
    private boolean dirty = false;          // unsaved field edits
    private boolean closeAfterSave = false; // Save chosen in the unsaved-changes popup

    private final Label titleLabel;
    private final CloseButton closeButton;
    private final ListView listView;
    private final Label statusLabel;
    private final Button defaultsButton;
    private final Button reloadButton;
    private final Button applyButton;

    /**
     * Creates the screen and immediately fetches the current settings from the master.
     *
     * @param parent the screen to return to when this screen is closed
     *               (normally the BankSystemSettingScreen)
     */
    public ModSettingsScreen(BankSystemGuiScreen parent) {
        super(Texts.TITLE, parent);

        // Render this screen smaller than the BankSystemGuiScreen default (0.8):
        // a lower GUI scale enlarges the logical content area (getWidth()/getHeight()
        // grow by 1/scale), giving the many settings rows substantially more usable space.
        setGuiScale(0.65f);

        titleLabel = new Label(Texts.TITLE.getString());
        titleLabel.setAlignment(Label.Alignment.LEFT);

        closeButton = new CloseButton(this::onClose);

        listView = new VerticalListView();
        LayoutVertical layout = new LayoutVertical();
        layout.stretchX = true;
        layout.stretchY = false;
        listView.setLayout(layout);

        statusLabel = new Label("");
        statusLabel.setAlignment(Label.Alignment.LEFT);

        defaultsButton = new Button(Texts.DEFAULTS.getString(), this::onDefaults);
        defaultsButton.setHoverTooltipSupplier(Texts.DEFAULTS_TOOLTIP::getString);
        defaultsButton.setHoverTooltipFontScale(BankSystemGuiElement.hoverToolTipFontSize);
        // The button sits in the bottom-right corner: anchor the cursor at the
        // tooltip's BOTTOM-RIGHT corner so it extends left and up, staying inside
        // the window.
        defaultsButton.setHoverTooltipMousePositionAlignment(GuiElement.Alignment.BOTTOM_RIGHT);

        reloadButton = new Button(Texts.RELOAD.getString(), this::loadFromServer);
        applyButton = new Button(Texts.APPLY.getString(), this::onApply);

        buildRows();

        addElement(titleLabel);
        addElement(closeButton);
        addElement(listView);
        addElement(statusLabel);
        addElement(defaultsButton);
        addElement(reloadButton);
        addElement(applyButton);

        loadFromServer();
    }

    @Override
    protected void updateLayout(Gui gui) {
        int p = BankSystemGuiElement.padding;
        int s = BankSystemGuiElement.spacing;
        int w = getWidth() - 2 * p;

        closeButton.setBounds(getWidth() - p - 20, p, 20, 20);
        titleLabel.setBounds(p, p, w - 20 - s, ELEMENT_HEIGHT);

        int bottomY = getHeight() - p - ELEMENT_HEIGHT;
        int buttonW = Math.max(60, w / 8);
        applyButton.setBounds(getWidth() - p - buttonW, bottomY, buttonW, ELEMENT_HEIGHT);
        reloadButton.setBounds(applyButton.getLeft() - s - buttonW, bottomY, buttonW, ELEMENT_HEIGHT);
        defaultsButton.setBounds(reloadButton.getLeft() - s - buttonW, bottomY, buttonW, ELEMENT_HEIGHT);
        statusLabel.setBounds(p, bottomY, defaultsButton.getLeft() - s - p, ELEMENT_HEIGHT);

        int listTop = titleLabel.getBottom() + s;
        listView.setBounds(p, listTop, w, bottomY - s - listTop);
    }

    // ------------------------------------------------------------------
    // Row construction
    // ------------------------------------------------------------------

    /**
     * Builds one heading row per group and one editor row per setting, generically
     * from the working copy's editable groups.
     */
    private void buildRows() {
        for (SettingsGroup group : workingCopy.getEditableGroups()) {
            Label heading = new Label(Texts.group(group.getName()).getString());
            heading.setAlignment(Label.Alignment.LEFT);
            heading.setTextColor(GROUP_HEADING_COLOR);
            heading.setHeight(ELEMENT_HEIGHT);
            listView.addChild(heading);

            if (group == workingCopy.INTEGRATIONS) {
                buildIntegrationSections(workingCopy.INTEGRATIONS);
                continue;
            }
            for (Setting<?> setting : group.getAllSettings()) {
                listView.addChild(new SettingRow(group, setting));
            }
        }
    }

    /**
     * Task #119: the Integrations group is laid out by hand as one collapsible
     * {@link FeatureToggleSection} per integration; the header check box is the
     * integration's on/off setting.
     */
    private void buildIntegrationSections(BankSystemModSettings.Integrations group) {
        FeatureToggleSection placeholders = addSection(group, group.PLACEHOLDERS_ENABLED);
        for (Setting<?> setting : List.of(group.PLAYER_REFRESH_MS, group.SERVER_REFRESH_MS, group.TOP_COUNT, group.MONEY_FORMAT))
            placeholders.addChild(new SettingRow(group, setting));
        addSection(group, group.SYNC_ADMIN_FLAG_WITH_PERMISSION);
        addSection(group, group.NUMISMATICS_ENABLED);
        addSection(group, group.LIGHTMANS_CURRENCY_ENABLED);
        addSection(group, group.DISCORD_ENABLED);
    }

    /** Adds a section titled with the toggle's setting label, its description lines and a binding for the toggle. */
    private FeatureToggleSection addSection(SettingsGroup group, Setting<Boolean> toggle) {
        FeatureToggleSection section = new FeatureToggleSection(Texts.setting(group.getName(), toggle.getName()).getString());
        // Header marker replaces the SettingRow marker for the toggle itself (static, like RESTART_REQUIRED_SETTINGS).
        section.setRestartRequired(RESTART_REQUIRED_SETTINGS.contains(group.getName() + "." + toggle.getName()));
        section.setRestartMarkerText(Texts.RESTART_REQUIRED, Texts.RESTART_REQUIRED_TOOLTIP); // BankSystem wording; harmless while hidden
        // Also fires on refreshFields(); handleResponse clears dirty after that (same as CheckBox).
        section.setOnFeatureToggled(enabled -> dirty = true);
        // Labels do not wrap: the lang text carries its own line breaks.
        for (String line : Texts.settingDescription(group.getName(), toggle.getName()).getString().split("\n")) {
            Label label = new Label(line);
            label.setTextColor(STATUS_COLOR_NEUTRAL);
            section.addChild(label);
        }
        bindings.add(new FieldBinding() {
            @Override public void loadFromSetting() {
                section.setFeatureEnabled(Boolean.TRUE.equals(toggle.get()));
            }
            @Override public void storeToSetting() {
                toggle.set(section.isFeatureEnabled());
            }
        });
        listView.addChild(section);
        return section;
    }

    /**
     * A single "label | editor [| restart marker]" row for one setting.
     * Creates the appropriate editor widget for the setting's value type and
     * registers a {@link FieldBinding} for it.
     */
    private final class SettingRow extends BankSystemGuiElement {
        private final Label nameLabel;
        private CheckBox checkBox = null;
        private TextBox textBox = null;
        private ItemView itemView = null;
        private Button selectItemButton = null;
        private Label unsupportedLabel = null;
        private Label restartMarker = null;

        SettingRow(SettingsGroup group, Setting<?> setting) {
            setEnableBackground(false);
            setHeight(ELEMENT_HEIGHT + spacing);

            String tooltip = Texts.settingTooltip(group.getName(), setting.getName()).getString();

            nameLabel = new Label(Texts.setting(group.getName(), setting.getName()).getString());
            nameLabel.setAlignment(Label.Alignment.LEFT);
            nameLabel.setHoverTooltipSupplier(() -> tooltip);
            nameLabel.setHoverTooltipFontScale(hoverToolTipFontSize);
            addChild(nameLabel);

            createEditor(group.getName(), setting, tooltip);
            // User edits mark the screen dirty. CheckBox has no user-only hook, so its
            // state callback also fires on refreshFields(); handleResponse clears dirty after that.
            if (textBox != null) textBox.setOnTextChanged(text -> dirty = true);
            if (checkBox != null) checkBox.setOnStateChanged(checked -> dirty = true);

            if (RESTART_REQUIRED_SETTINGS.contains(group.getName() + "." + setting.getName())) {
                // Clearly visible warning-colored marker + explanatory hover tooltip
                // (the "restart" symbol is part of the lang string).
                restartMarker = new Label(Texts.RESTART_REQUIRED.getString());
                restartMarker.setAlignment(Label.Alignment.RIGHT);
                restartMarker.setTextColor(RESTART_MARKER_COLOR);
                restartMarker.setHoverTooltipSupplier(Texts.RESTART_REQUIRED_TOOLTIP::getString);
                restartMarker.setHoverTooltipFontScale(hoverToolTipFontSize);
                // The marker sits at the far right edge of the screen: anchor the
                // cursor at the tooltip's TOP-RIGHT corner so the tooltip body
                // extends to the LEFT and stays inside the window.
                restartMarker.setHoverTooltipMousePositionAlignment(Alignment.TOP_RIGHT);
                addChild(restartMarker);
            }
        }

        /**
         * Creates the editor widget matching the setting's value type and registers
         * the corresponding {@link FieldBinding} (unchecked casts are safe because
         * the widget was chosen from {@code setting.getType()}).
         */
        @SuppressWarnings("unchecked")
        private void createEditor(String groupName, Setting<?> setting, String tooltip) {
            Type type = setting.getType();

            if (type == Boolean.class) {
                Setting<Boolean> boolSetting = (Setting<Boolean>) setting;
                checkBox = new CheckBox("");
                checkBox.setHoverTooltipSupplier(() -> tooltip);
                checkBox.setHoverTooltipFontScale(hoverToolTipFontSize);
                // Editors stretch to the right screen edge — anchor tooltips at the
                // cursor's TOP-RIGHT so they extend left and stay inside the window.
                checkBox.setHoverTooltipMousePositionAlignment(Alignment.TOP_RIGHT);
                addChild(checkBox);
                bindings.add(new FieldBinding() {
                    @Override public void loadFromSetting() {
                        checkBox.setChecked(Boolean.TRUE.equals(boolSetting.get()));
                    }
                    @Override public void storeToSetting() {
                        boolSetting.set(checkBox.isChecked());
                    }
                });
            }
            else if (type == Integer.class || type == Long.class) {
                boolean signed = SIGNED_INTEGER_SETTINGS.contains(groupName + "." + setting.getName());
                textBox = new TextBox();
                textBox.setMatchRegex(TextBox.createRegex_onlyNumerical(true, signed, 14, 0));
                textBox.setHoverTooltipSupplier(() -> tooltip);
                textBox.setHoverTooltipFontScale(hoverToolTipFontSize);
                // Editors stretch to the right screen edge — tooltip extends left.
                textBox.setHoverTooltipMousePositionAlignment(Alignment.TOP_RIGHT);
                addChild(textBox);
                if (type == Integer.class) {
                    Setting<Integer> intSetting = (Setting<Integer>) setting;
                    bindings.add(new FieldBinding() {
                        @Override public void loadFromSetting() {
                            textBox.setText(intSetting.get() != null ? intSetting.get() : 0);
                        }
                        @Override public void storeToSetting() {
                            try { intSetting.set(textBox.getInt()); }
                            catch (Exception e) { /* invalid input — keep the current value */ }
                        }
                    });
                } else {
                    Setting<Long> longSetting = (Setting<Long>) setting;
                    bindings.add(new FieldBinding() {
                        @Override public void loadFromSetting() {
                            textBox.setText(longSetting.get() != null ? longSetting.get() : 0L);
                        }
                        @Override public void storeToSetting() {
                            try { longSetting.set(textBox.getLong()); }
                            catch (Exception e) { /* invalid input — keep the current value */ }
                        }
                    });
                }
            }
            else if (type == Float.class) {
                Setting<Float> floatSetting = (Setting<Float>) setting;
                textBox = new TextBox();
                // Positive decimals with up to 4 decimal digits.
                textBox.setMatchRegex(TextBox.createRegex_onlyNumerical(true, false, 10, 4));
                textBox.setHoverTooltipSupplier(() -> tooltip);
                textBox.setHoverTooltipFontScale(hoverToolTipFontSize);
                // Editors stretch to the right screen edge — tooltip extends left.
                textBox.setHoverTooltipMousePositionAlignment(Alignment.TOP_RIGHT);
                addChild(textBox);
                bindings.add(new FieldBinding() {
                    @Override public void loadFromSetting() {
                        float v = floatSetting.get() != null ? floatSetting.get() : 0f;
                        textBox.setText(String.valueOf(v));
                    }
                    @Override public void storeToSetting() {
                        try { floatSetting.set((float) textBox.getDouble()); }
                        catch (Exception e) { /* invalid input — keep the current value */ }
                    }
                });
            }
            else if (type == ItemStack.class) {
                // No registered BankSystem setting uses ItemStack today; the branch is
                // kept so future ItemStack settings appear automatically (spec parity
                // with the StockMarket implementation).
                Setting<ItemStack> stackSetting = (Setting<ItemStack>) setting;
                itemView = new ItemView();
                itemView.setShowTooltip(true);
                addChild(itemView);
                // Opens the vanilla creative inventory as an item picker; clicking an
                // item selects it (count forced to 1) and returns to this screen.
                selectItemButton = new Button(Texts.SELECT_ITEM.getString(), () -> {
                    CreativeModeItemSelectionScreen selectionScreen = new CreativeModeItemSelectionScreen(
                            clickedStack -> {
                                ItemStack copy = clickedStack.copy();
                                copy.setCount(1);
                                stackSetting.set(copy);
                                itemView.setItemStack(copy);
                                dirty = true;
                                net.kroia.modutilities.gui.client.ClientCompat.setScreen(ModSettingsScreen.this);
                            },
                            () -> net.kroia.modutilities.gui.client.ClientCompat.setScreen(ModSettingsScreen.this));
                    net.kroia.modutilities.gui.client.ClientCompat.setScreen(selectionScreen);
                });
                selectItemButton.setHoverTooltipSupplier(() -> tooltip);
                selectItemButton.setHoverTooltipFontScale(hoverToolTipFontSize);
                // Editors stretch to the right screen edge — tooltip extends left.
                selectItemButton.setHoverTooltipMousePositionAlignment(Alignment.TOP_RIGHT);
                addChild(selectItemButton);
                bindings.add(new FieldBinding() {
                    @Override public void loadFromSetting() {
                        ItemStack v = stackSetting.get();
                        itemView.setItemStack(v != null ? v : ItemStack.EMPTY);
                    }
                    @Override public void storeToSetting() {
                        // The setting is already updated on selection; re-assert from the
                        // displayed stack for robustness.
                        ItemStack shown = itemView.getItemStack();
                        if (shown != null && !shown.isEmpty()) stackSetting.set(shown);
                    }
                });
            }
            else if (type.equals(STRING_LIST_TYPE)) {
                // Component-type-id lists (ADDITIONAL_VOLATILE_COMPONENTS /
                // ADDITIONAL_DEPOSIT_GATED_COMPONENTS): edited as a comma-separated
                // list. The server additionally trims/dedupes on apply (sanitize()).
                Setting<List<String>> listSetting = (Setting<List<String>>) setting;
                textBox = new TextBox();
                textBox.setHoverTooltipSupplier(() -> tooltip);
                textBox.setHoverTooltipFontScale(hoverToolTipFontSize);
                textBox.setHoverTooltipMousePositionAlignment(Alignment.TOP_RIGHT);
                addChild(textBox);
                bindings.add(new FieldBinding() {
                    @Override public void loadFromSetting() {
                        List<String> v = listSetting.get();
                        textBox.setText(v == null ? "" : String.join(", ", v));
                    }
                    @Override public void storeToSetting() {
                        List<String> entries = new ArrayList<>();
                        for (String entry : Arrays.asList(textBox.getText().split(","))) {
                            String trimmed = entry.trim();
                            if (!trimmed.isEmpty()) entries.add(trimmed);
                        }
                        listSetting.set(entries);
                    }
                });
            }
            else if (type == String.class) {
                // Free-text settings (e.g. Integrations.MONEY_FORMAT): stored as typed;
                // the server validates on apply.
                Setting<String> stringSetting = (Setting<String>) setting;
                textBox = new TextBox();
                textBox.setHoverTooltipSupplier(() -> tooltip);
                textBox.setHoverTooltipFontScale(hoverToolTipFontSize);
                textBox.setHoverTooltipMousePositionAlignment(Alignment.TOP_RIGHT);
                addChild(textBox);
                bindings.add(new FieldBinding() {
                    @Override public void loadFromSetting() {
                        String v = stringSetting.get();
                        textBox.setText(v == null ? "" : v);
                    }
                    @Override public void storeToSetting() {
                        stringSetting.set(textBox.getText());
                    }
                });
            }
            else {
                // Defensive fallback for future settings with unsupported types:
                // show the raw value read-only instead of hiding the setting.
                unsupportedLabel = new Label(String.valueOf(setting.get()));
                unsupportedLabel.setAlignment(Label.Alignment.LEFT);
                unsupportedLabel.setTextColor(STATUS_COLOR_NEUTRAL);
                addChild(unsupportedLabel);
                bindings.add(new FieldBinding() {
                    @Override public void loadFromSetting() {
                        unsupportedLabel.setText(String.valueOf(setting.get()));
                    }
                    @Override public void storeToSetting() { /* read-only */ }
                });
            }
        }

        @Override
        protected void render() {}

        @Override
        protected void layoutChanged() {
            int w = getWidth() - 2 * padding;
            int labelW = (int) (w * 0.45);
            int markerW = restartMarker != null ? (int) (w * 0.2) : 0;
            int editorX = padding + labelW + spacing;
            int editorW = w - labelW - spacing - (markerW > 0 ? markerW + spacing : 0);

            nameLabel.setBounds(padding, padding / 2, labelW, ELEMENT_HEIGHT);

            if (checkBox != null)
                checkBox.setBounds(editorX, padding / 2, editorW, ELEMENT_HEIGHT);
            if (textBox != null)
                textBox.setBounds(editorX, padding / 2, editorW, ELEMENT_HEIGHT);
            if (itemView != null) {
                itemView.setBounds(editorX, padding / 2, ELEMENT_HEIGHT, ELEMENT_HEIGHT);
                selectItemButton.setBounds(itemView.getRight() + spacing, padding / 2,
                        editorW - ELEMENT_HEIGHT - spacing, ELEMENT_HEIGHT);
            }
            if (unsupportedLabel != null)
                unsupportedLabel.setBounds(editorX, padding / 2, editorW, ELEMENT_HEIGHT);
            if (restartMarker != null)
                restartMarker.setBounds(padding + w - markerW, padding / 2, markerW, ELEMENT_HEIGHT);
        }
    }

    // ------------------------------------------------------------------
    // Server communication
    // ------------------------------------------------------------------

    /**
     * Fetches the current settings from the master server (GET) and fills all
     * editor fields with the response.
     */
    private void loadFromServer() {
        setStatus(Texts.STATUS_LOADING.getString(), STATUS_COLOR_NEUTRAL);
        applyButton.setClickable(false);
        ModSettingsRequest.InputData input = new ModSettingsRequest.InputData(ModSettingsRequest.Action.GET, "");
        BankSystemNetworking.MOD_SETTINGS_REQUEST.sendRequestToServer(input)
                .exceptionally(t -> null) // Issue #85: timeout or lost link shows STATUS_FAILED
                .thenAccept(output -> Minecraft.getInstance().execute(() -> handleResponse(output, Texts.STATUS_LOADED.getString())));
    }

    /**
     * Sends all edited values to the master server (SET). The server validates,
     * clamps, persists to settings.json and returns the confirmed state, which is
     * re-displayed (so any clamped value becomes immediately visible).
     */
    private void onApply() {
        for (FieldBinding binding : bindings)
            binding.storeToSetting();

        String payload = new SettingsStore().toJsonString(workingCopy.getEditableGroups());
        setStatus(Texts.STATUS_LOADING.getString(), STATUS_COLOR_NEUTRAL);
        applyButton.setClickable(false);
        ModSettingsRequest.InputData input = new ModSettingsRequest.InputData(ModSettingsRequest.Action.SET, payload);
        BankSystemNetworking.MOD_SETTINGS_REQUEST.sendRequestToServer(input)
                .exceptionally(t -> null)
                .thenAccept(output -> Minecraft.getInstance().execute(() -> handleResponse(output, Texts.STATUS_SAVED.getString())));
    }

    /**
     * Resets all FIELDS to the compile-time default values. Nothing is sent to the
     * server until the admin presses Apply.
     */
    private void onDefaults() {
        for (SettingsGroup group : workingCopy.getEditableGroups())
            group.setToDefaultValue();
        refreshFields();
        dirty = true;
        setStatus("", STATUS_COLOR_NEUTRAL);
    }

    @Override
    public void onClose() {
        if (dirty) {
            AskPopupScreen.openUnsavedChangesPopup(this,
                    () -> { dirty = false; onClose(); },
                    () -> { closeAfterSave = true; onApply(); });
            return;
        }
        super.onClose();
    }

    /**
     * Applies a GET/SET response: loads the confirmed server state into the working
     * copy, refreshes all fields and updates the status label.
     *
     * @param output        the server response
     * @param successStatus status text shown when the response reports success
     */
    private void handleResponse(ModSettingsRequest.OutputData output, String successStatus) {
        applyButton.setClickable(true);
        boolean closeNow = closeAfterSave;
        closeAfterSave = false;
        if (output == null) {
            setStatus(Texts.STATUS_FAILED.getString(), STATUS_COLOR_ERROR);
            return;
        }
        if (output.settingsJson() != null && !output.settingsJson().isEmpty()) {
            try {
                new SettingsStore().fromJson(workingCopy.getEditableGroups(),
                        JsonParser.parseString(output.settingsJson()));
                refreshFields();
            } catch (Exception e) {
                error("Failed to parse mod settings response", e);
                setStatus(Texts.STATUS_FAILED.getString(), STATUS_COLOR_ERROR);
                return;
            }
        }
        if (output.success()) {
            dirty = false; // fields now show the confirmed server state
            setStatus(successStatus, STATUS_COLOR_OK);
            if (closeNow) onClose();
        } else {
            String msg = output.message() == null || output.message().isEmpty()
                    ? Texts.STATUS_FAILED.getString() : output.message();
            setStatus(msg, STATUS_COLOR_ERROR);
        }
    }

    /** Writes all working-copy values into their editor widgets. */
    private void refreshFields() {
        for (FieldBinding binding : bindings)
            binding.loadFromSetting();
    }

    private void setStatus(String text, int color) {
        statusLabel.setText(text);
        statusLabel.setTextColor(color);
    }
}
