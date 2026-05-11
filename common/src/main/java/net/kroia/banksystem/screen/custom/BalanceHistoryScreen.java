package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.data.table.record.BalanceHistoryRecord;
import net.kroia.banksystem.screen.widgets.BalanceHistoryChart;
import net.kroia.banksystem.util.BankSystemGuiScreen;
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

public class BalanceHistoryScreen extends BankSystemGuiScreen {

    private static final int[] SERIES_COLORS = {
            ColorUtilities.getRGB(63, 255, 139),
            ColorUtilities.getRGB(255, 113, 108),
            ColorUtilities.getRGB(100, 149, 237),
            ColorUtilities.getRGB(255, 215, 0),
            ColorUtilities.getRGB(186, 85, 211),
            ColorUtilities.getRGB(0, 206, 209),
            ColorUtilities.getRGB(255, 165, 0),
            ColorUtilities.getRGB(144, 238, 144),
            ColorUtilities.getRGB(255, 105, 180),
            ColorUtilities.getRGB(176, 196, 222),
    };

    private static final String CUSTOM_DATA_KEY = "balanceHistory";
    private static final String DISABLED_ITEMS_KEY = "disabledItems";

    private record ToggleRow(GuiElement element, String name) {}

    private final int accountNumber;
    private final BalanceHistoryChart chart;
    private final TextBox searchField;
    private final VerticalListView toggleListView;
    private final Label titleLabel;

    private CompoundTag userCustomData;
    private final Set<Short> disabledItems = new HashSet<>();
    private final Map<Short, BalanceHistoryChart.LineSeries> seriesMap = new LinkedHashMap<>();
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

        int colorIndex = 0;
        double scaleFactor = BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;

        for (Map.Entry<Short, List<BalanceHistoryRecord>> entry : grouped.entrySet()) {
            short itemId = entry.getKey();
            List<BalanceHistoryRecord> itemRecords = entry.getValue();

            int color = SERIES_COLORS[colorIndex % SERIES_COLORS.length];
            String name = getItemName(itemId);

            BalanceHistoryChart.LineSeries series = new BalanceHistoryChart.LineSeries(name, color);
            series.visible = !disabledItems.contains(itemId);
            for (BalanceHistoryRecord r : itemRecords) {
                double totalBalance = (r.balance() + r.lockedBalance()) / scaleFactor;
                series.points.add(new BalanceHistoryChart.DataPoint(r.time(), totalBalance));
            }
            chart.addSeries(series);
            seriesMap.put(itemId, series);

            ItemStack itemStack = getItemStack(itemId);
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
            int inactiveColor = ColorUtilities.getRGB(60, 60, 60);
            Button toggleButton = new Button(name);
            toggleButton.setBackgroundColor(series.visible ? activeColor : inactiveColor);
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

    private void onSearchChanged(String text) {
        String query = text.trim().toLowerCase();
        toggleListView.removeChilds();
        for (ToggleRow row : toggleRows) {
            if (query.isEmpty() || row.name().contains(query)) {
                toggleListView.addChild(row.element());
            }
        }
    }
}
