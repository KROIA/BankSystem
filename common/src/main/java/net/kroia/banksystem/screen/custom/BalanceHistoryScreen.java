package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.data.table.record.BalanceHistoryRecord;
import net.kroia.banksystem.screen.widgets.BalanceHistoryChart;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.banksystem.util.ItemColorUtil;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.Tag;
import net.kroia.modutilities.ColorUtilities;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.ItemView;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.TextBox;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/**
 * Client screen showing an interactive balance history chart for a bank account.
 * <p>
 * Opened from the Bank Terminal via the "History" button. Displays a
 * {@link BalanceHistoryChart} with one line per item type, plus an optional
 * "Total Wealth" line when an {@link net.kroia.banksystem.api.ItemPriceProvider}
 * is registered.
 * <p>
 * <b>Filter persistence:</b> Disabled item IDs are stored in the user's
 * {@code customData} CompoundTag under {@code balanceHistory.disabledItems}
 * (a ListTag of ShortTags). On open, the screen fetches the user's custom data
 * via {@code getUserCustomData()}, reads the disabled set, and applies it to
 * series visibility. On each toggle click, the updated set is saved back to the
 * server via {@code updateUserCustomData()}.
 * <p>
 * <b>Item colors:</b> Line colors are derived from each item's texture by
 * sampling the sprite pixels and averaging the RGB values. Saturation is boosted
 * 50% and minimum brightness enforced so muted textures remain distinguishable.
 * Colors are cached per ItemID for the session.
 */
public class BalanceHistoryScreen extends BankSystemGuiScreen {

    // NBT keys for filter persistence inside User.customData
    private static final String CUSTOM_DATA_KEY = "balanceHistory";
    private static final String DISABLED_ITEMS_KEY = "disabledItems";

    private record ToggleRow(GuiElement element, String name) {}

    private final int accountNumber;
    private final BalanceHistoryChart chart;
    private final TextBox searchField;
    private final VerticalListView toggleListView;
    private final Label titleLabel;

    /** The full user custom data tag, fetched on open and updated on each toggle. */
    private CompoundTag userCustomData;
    /** Set of item IDs whose chart lines are hidden. Persisted in userCustomData. */
    private final Set<Short> disabledItems = new HashSet<>();
    private final Map<Short, BalanceHistoryChart.LineSeries> seriesMap = new LinkedHashMap<>();
    /** Wealth toggle row — always at top of list, not affected by search filtering. */
    private GuiElement wealthRow = null;
    /** Item toggle rows — filtered by the search field. */
    private final List<ToggleRow> toggleRows = new ArrayList<>();

    public BalanceHistoryScreen(Screen parent, int accountNumber) {
        super(Component.literal("Balance History"), parent);
        this.accountNumber = accountNumber;

        titleLabel = new Label("Balance History - Account #" + accountNumber);
        titleLabel.setTextFontScale(1.0f);
        addElement(titleLabel);

        chart = new BalanceHistoryChart();
        addElement(chart);

        searchField = new TextBox();
        searchField.setOnTextChanged(this::onSearchChanged);
        addElement(searchField);

        toggleListView = new VerticalListView();
        LayoutGrid toggleLayout = new LayoutGrid();
        toggleLayout.stretchX = true;
        toggleLayout.columns = 1;
        toggleListView.setLayout(toggleLayout);
        addElement(toggleListView);

        fetchAccountName();
        loadUserSettings();
    }

    @Override
    protected void updateLayout(Gui gui) {
        int p = 5;
        int toggleWidth = Math.max(80, getWidth() / 5);
        int titleHeight = 15;

        int searchHeight = 14;
        int toggleX = getWidth() - toggleWidth - p;
        int toggleTop = p + titleHeight + p;

        titleLabel.setBounds(p, p, getWidth() - 2 * p, titleHeight);
        chart.setBounds(p, toggleTop, toggleX - 2 * p, getHeight() - titleHeight - 3 * p);
        searchField.setBounds(toggleX, toggleTop, toggleWidth, searchHeight);
        toggleListView.setBounds(toggleX, toggleTop + searchHeight + p, toggleWidth, getHeight() - toggleTop - searchHeight - 2 * p);
    }

    private void fetchAccountName() {
        getBankManager().getBankAccountDataAsync(accountNumber).thenAccept(data ->
                Minecraft.getInstance().execute(() -> {
                    if (data != null) {
                        titleLabel.setText("Balance History - " + data.accountName + " (#" + accountNumber + ")");
                    }
                })
        );
    }

    // ── Filter persistence ──
    // Disabled items are stored in User.customData as:
    //   customData -> "balanceHistory" (CompoundTag) -> "disabledItems" (ListTag<ShortTag>)
    // Loaded once on screen open via getUserCustomData() ARRS request.
    // Saved on each toggle via updateUserCustomData() ARRS request.

    private void loadUserSettings() {
        getBankManager().getUserCustomData().thenAccept(customData ->
                Minecraft.getInstance().execute(() -> {
                    userCustomData = customData;
                    readDisabledItems(customData);
                    requestData();
                })
        );
    }

    private void requestData() {
        getBankManager().requestBalanceHistory(accountNumber).thenAccept(this::onDataReceived);
    }

    private void onDataReceived(List<BalanceHistoryRecord> records) {
        if (records == null || records.isEmpty()) return;
        Minecraft.getInstance().execute(() -> applyData(records));
    }

    private void applyData(List<BalanceHistoryRecord> records) {
        Map<Short, List<BalanceHistoryRecord>> grouped = new LinkedHashMap<>();
        for (BalanceHistoryRecord r : records) {
            grouped.computeIfAbsent(r.itemId(), k -> new ArrayList<>()).add(r);
        }

        chart.clearSeries();
        chart.clearHoverBindings();
        toggleListView.removeChilds();
        seriesMap.clear();
        toggleRows.clear();
        wealthRow = null;

        int colorIndex = 0;
        double scaleFactor = BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;

        // Wealth series (from ItemPriceProvider) — added first, pinned on top
        List<BalanceHistoryRecord> wealthRecords = grouped.remove(BalanceHistoryRecord.WEALTH_ITEM_ID);
        if (wealthRecords != null && !wealthRecords.isEmpty()) {
            addWealthSeries(wealthRecords, scaleFactor);
        }

        for (Map.Entry<Short, List<BalanceHistoryRecord>> entry : grouped.entrySet()) {
            short itemId = entry.getKey();
            List<BalanceHistoryRecord> itemRecords = entry.getValue();

            ItemStack itemStack = getItemStack(itemId);
            int color = ItemColorUtil.getColor(itemId, itemStack, colorIndex);
            String name = getItemName(itemId);

            BalanceHistoryChart.LineSeries series = new BalanceHistoryChart.LineSeries(name, color);
            series.visible = !disabledItems.contains(itemId);
            for (BalanceHistoryRecord r : itemRecords) {
                double totalBalance = (r.balance() + r.lockedBalance()) / scaleFactor;
                series.points.add(new BalanceHistoryChart.DataPoint(r.time(), totalBalance));
            }
            chart.addSeries(series);
            seriesMap.put(itemId, series);

            GuiElement row = new GuiElement(0, 0, 100, 18) {
                @Override protected void render() {}
                @Override protected void layoutChanged() {
                    for (var child : getChilds()) {
                        if (child instanceof ItemView iv) {
                            iv.setBounds(1, 1, 16, 16);
                        } else if (child instanceof Button btn) {
                            btn.setBounds(19, 0, getWidth() - 19, 18);
                        }
                    }
                }
            };
            row.setEnableBackground(false);
            row.setEnableOutline(false);

            ItemView itemView = new ItemView();
            itemView.setItemStack(itemStack);
            itemView.setShowTooltip(true);
            row.addChild(itemView);

            chart.bindHoverElement(row, series);

            int activeColor = ColorUtilities.setAlpha(color, 0.8f);
            int hoverColor = ColorUtilities.setBrightness(color, 1.4f);
            int inactiveColor = ColorUtilities.getRGB(60, 60, 60);
            Button toggleButton = new Button(name);
            toggleButton.setBackgroundColor(series.visible ? activeColor : inactiveColor);
            toggleButton.setHoverColor(hoverColor);
            final short fItemId = itemId;
            final BalanceHistoryChart.LineSeries toggleSeries = series;
            toggleButton.setOnFallingEdge(() -> {
                toggleSeries.visible = !toggleSeries.visible;
                toggleButton.setBackgroundColor(toggleSeries.visible ? activeColor : inactiveColor);
                if (toggleSeries.visible) {
                    disabledItems.remove(fItemId);
                } else {
                    disabledItems.add(fItemId);
                }
                saveUserSettings();
            });
            row.addChild(toggleButton);

            String searchableName = buildSearchableName(name, itemStack);
            toggleRows.add(new ToggleRow(row, searchableName));
            toggleListView.addChild(row);
            colorIndex++;
        }

        chart.autoCenterView();
    }

    /**
     * Adds the "Total Wealth" series (gold line, pinned on top).
     * Only present when an ItemPriceProvider is registered.
     * The wealth row stays at the top of the toggle list and is not
     * affected by the search filter.
     */
    private void addWealthSeries(List<BalanceHistoryRecord> wealthRecords, double scaleFactor) {
        int wealthColor = ColorUtilities.getRGB(255, 215, 0);
        String wealthName = "Total Wealth";
        short wealthId = BalanceHistoryRecord.WEALTH_ITEM_ID;

        BalanceHistoryChart.LineSeries wealthSeries = new BalanceHistoryChart.LineSeries(wealthName, wealthColor);
        wealthSeries.visible = !disabledItems.contains(wealthId);
        for (BalanceHistoryRecord r : wealthRecords) {
            wealthSeries.points.add(new BalanceHistoryChart.DataPoint(r.time(), r.balance() / scaleFactor));
        }
        chart.addSeries(wealthSeries);
        chart.setPinnedSeries(wealthSeries);
        seriesMap.put(wealthId, wealthSeries);

        wealthRow = new GuiElement(0, 0, 100, 18) {
            @Override protected void render() {}
            @Override protected void layoutChanged() {
                for (var child : getChilds()) {
                    if (child instanceof Button btn) {
                        btn.setBounds(0, 0, getWidth(), 18);
                    }
                }
            }
        };
        wealthRow.setEnableBackground(false);
        wealthRow.setEnableOutline(false);

        chart.bindHoverElement(wealthRow, wealthSeries);

        int wealthActive = ColorUtilities.setAlpha(wealthColor, 0.8f);
        int wealthHover = ColorUtilities.setBrightness(wealthColor, 1.4f);
        int wealthInactive = ColorUtilities.getRGB(60, 60, 60);
        Button wealthToggle = new Button(wealthName);
        wealthToggle.setBackgroundColor(wealthSeries.visible ? wealthActive : wealthInactive);
        wealthToggle.setHoverColor(wealthHover);
        wealthToggle.setOnFallingEdge(() -> {
            wealthSeries.visible = !wealthSeries.visible;
            wealthToggle.setBackgroundColor(wealthSeries.visible ? wealthActive : wealthInactive);
            if (wealthSeries.visible) disabledItems.remove(wealthId);
            else disabledItems.add(wealthId);
            saveUserSettings();
        });
        wealthRow.addChild(wealthToggle);
        toggleListView.addChild(wealthRow);
    }

    /** Reads the disabled item set from the user's custom data NBT. */
    private void readDisabledItems(CompoundTag customData) {
        disabledItems.clear();
        if (customData == null || !customData.contains(CUSTOM_DATA_KEY)) return;
        CompoundTag historyTag = customData.getCompound(CUSTOM_DATA_KEY);
        if (!historyTag.contains(DISABLED_ITEMS_KEY)) return;
        ListTag list = historyTag.getList(DISABLED_ITEMS_KEY, Tag.TAG_SHORT);
        for (Tag tag : list) {
            if (tag instanceof ShortTag shortTag) {
                disabledItems.add(shortTag.getAsShort());
            }
        }
    }

    /**
     * Persists the current disabled item set to the server.
     * Writes to customData["balanceHistory"]["disabledItems"] as a ListTag of ShortTags,
     * then sends the full customData via updateUserCustomData() ARRS request.
     */
    private void saveUserSettings() {
        if (userCustomData == null) userCustomData = new CompoundTag();
        CompoundTag historyTag = new CompoundTag();
        ListTag list = new ListTag();
        for (short id : disabledItems) {
            list.add(ShortTag.valueOf(id));
        }
        historyTag.put(DISABLED_ITEMS_KEY, list);
        userCustomData.put(CUSTOM_DATA_KEY, historyTag);
        getBankManager().updateUserCustomData(userCustomData);
    }

    private String getItemName(short itemId) {
        ItemStack stack = getItemStack(itemId);
        if (stack != null && !stack.isEmpty()) {
            return stack.getHoverName().getString();
        }
        return "Item #" + itemId;
    }

    private ItemStack getItemStack(short itemId) {
        ItemID id = new ItemID(itemId);
        return id.getStack();
    }

    /**
     * Builds a search string from the item's display name + all tag paths.
     * Allows searching by tag (e.g. "log" matches items tagged minecraft:logs).
     */
    private String buildSearchableName(String displayName, ItemStack stack) {
        StringBuilder sb = new StringBuilder(displayName.toLowerCase());
        if (stack != null && !stack.isEmpty()) {
            stack.getTags().forEach(tagKey -> {
                String path = tagKey.location().getPath();
                sb.append(' ').append(path.replace('_', ' ').replace('/', ' '));
            });
        }
        return sb.toString();
    }

    /**
     * Filters the toggle list by search query. The wealth row is always
     * kept at the top regardless of the query. Item rows are matched
     * against their searchable name (display name + tag paths).
     */
    private void onSearchChanged(String text) {
        String query = text.trim().toLowerCase();
        toggleListView.removeChilds();
        if (wealthRow != null) {
            toggleListView.addChild(wealthRow);
        }
        for (ToggleRow row : toggleRows) {
            if (query.isEmpty() || row.name().contains(query)) {
                toggleListView.addChild(row.element());
            }
        }
    }
}
