package net.kroia.banksystem.minecraft.entity.custom;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.data.filter.EqualityFilter;
import net.kroia.banksystem.data.table.BalanceHistoryManager;
import net.kroia.banksystem.data.table.record.BalanceHistoryRecord;
import net.kroia.banksystem.minecraft.entity.BankSystemEntities;
import net.kroia.banksystem.util.ItemColorUtil;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ColorUtilities;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.display.AbstractDisplayBlockEntity;
import net.kroia.modutilities.gui.display.ContentBuilder;
import net.kroia.modutilities.gui.display.DisplayConfig;
import net.kroia.modutilities.gui.elements.Frame;
import net.kroia.modutilities.gui.elements.ItemView;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.banksystem.screen.widgets.BalanceHistoryChart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public class BalanceHistoryDisplayBlockEntity extends AbstractDisplayBlockEntity {

    private static final int MAX_LEGEND_ITEMS = 15;
    private static final int LEGEND_ENTRY_SIZE = 20;
    private static final int LEGEND_WIDTH = 24;
    private static final int MARGIN = 6;

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            256, 256, 2, 4096,
            -14.0f / 16.0f + 0.001f,
            facing -> switch (facing) {
                case NORTH -> Block.box(0, 0, 14, 16, 16, 16);
                case SOUTH -> Block.box(0, 0, 0, 16, 16, 2);
                case EAST  -> Block.box(0, 0, 0, 2, 16, 16);
                case WEST  -> Block.box(14, 0, 0, 16, 16, 16);
                default -> Block.box(0, 0, 14, 16, 16, 16);
            },
            1, 0
    );

    private static BankSystemModBackend.Instances BACKEND;

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND = backend;
    }

    private int tickCounter = 0;
    private boolean dataPending = false;
    private volatile List<BalanceHistoryRecord> pendingRecords = null;
    private BalanceHistoryChart chart;
    private Label statusLabel;
    private final List<Frame> legendFrames = new ArrayList<>();
    private final List<ItemView> legendIcons = new ArrayList<>();
    private CompoundTag pendingChartState = null;
    private String pendingStatusText = null;
    private ListTag pendingLegendData = null;

    public BalanceHistoryDisplayBlockEntity(BlockPos pos, BlockState blockState) {
        super(BankSystemEntities.BALANCE_HISTORY_DISPLAY_BLOCK_ENTITY.get(), pos, blockState);
    }

    @Override
    public DisplayConfig getDisplayConfig() {
        return DISPLAY_CONFIG;
    }

    @Override
    public ContentBuilder getContentBuilder() {
        return BalanceHistoryDisplayBlockEntity::buildContent;
    }

    @Override
    public String getChannelId() {
        return "11";
    }

    @Override
    protected void wireCallbacks(Gui gui) {
        chart = null;
        statusLabel = null;
        legendFrames.clear();
        legendIcons.clear();
        for (var el : gui.getElements()) {
            if (el instanceof BalanceHistoryChart c) chart = c;
            if (el instanceof Label l && l.getId() != null && l.getId().equals("status"))
                statusLabel = l;
            if (el instanceof Frame f && f.getId() != null && f.getId().startsWith("lf_"))
                legendFrames.add(f);
            if (el instanceof ItemView iv && iv.getId() != null && iv.getId().startsWith("li_"))
                legendIcons.add(iv);
        }
        legendFrames.sort(Comparator.comparingInt(a -> parseIndex(a.getId())));
        legendIcons.sort(Comparator.comparingInt(a -> parseIndex(a.getId())));

        applyPendingState();
    }

    private static int parseIndex(String id) {
        int u = id.lastIndexOf('_');
        if (u < 0) return 0;
        try { return Integer.parseInt(id.substring(u + 1)); }
        catch (NumberFormatException e) { return 0; }
    }

    @Override
    protected void onControllerTick() {
        List<BalanceHistoryRecord> records = pendingRecords;
        if (records != null) {
            pendingRecords = null;
            dataPending = false;
            applyData(records);
        }

        tickCounter++;
        if (tickCounter % 1200 == 0 || tickCounter == 1) {
            requestData();
        }
        if (chart != null && tickCounter % 20 == 0) {
            chart.scrollToPresent();
            syncToClientPublic();
        }
    }

    @Override
    protected void saveCustomData(CompoundTag tag, HolderLookup.Provider registries) {
        if (chart != null && !chart.getSeries().isEmpty()) {
            tag.put("chartState", chart.serializeState());
        }
        if (statusLabel != null) {
            tag.putString("statusText", statusLabel.getText());
        }
        ListTag legend = new ListTag();
        int count = Math.min(legendFrames.size(), legendIcons.size());
        for (int i = 0; i < count; i++) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("color", legendFrames.get(i).getOutlineColor());
            entry.putBoolean("visible", legendFrames.get(i).isOutlineEnabled());
            ItemStack stack = legendIcons.get(i).getItemStack();
            if (stack != null && !stack.isEmpty()) {
                entry.putString("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            }
            legend.add(entry);
        }
        tag.put("legend", legend);
    }

    @Override
    protected void loadCustomData(CompoundTag tag, HolderLookup.Provider registries) {
        pendingChartState = tag.contains("chartState") ? tag.getCompound("chartState") : null;
        pendingStatusText = tag.contains("statusText") ? tag.getString("statusText") : null;
        pendingLegendData = tag.contains("legend") ? tag.getList("legend", 10) : null;
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
    }

    @Override
    public void onInputSynced() {}

    private void applyPendingState() {
        if (pendingChartState != null && chart != null) {
            chart.deserializeState(pendingChartState);
            pendingChartState = null;
        }
        if (pendingStatusText != null && statusLabel != null) {
            statusLabel.setText(pendingStatusText);
            pendingStatusText = null;
        }
        if (pendingLegendData != null) {
            int count = Math.min(pendingLegendData.size(), Math.min(legendFrames.size(), legendIcons.size()));
            for (int i = 0; i < count; i++) {
                CompoundTag entry = pendingLegendData.getCompound(i);
                Frame f = legendFrames.get(i);
                f.setOutlineColor(entry.getInt("color"));
                f.setEnableOutline(entry.getBoolean("visible"));
                f.setEnableBackground(entry.getBoolean("visible"));
                if (entry.contains("item")) {
                    var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(entry.getString("item")));
                    if (item != Items.AIR) {
                        legendIcons.get(i).setItemStack(new ItemStack(item));
                    }
                }
            }
            for (int i = count; i < legendFrames.size(); i++) {
                legendFrames.get(i).setEnableOutline(false);
                legendFrames.get(i).setEnableBackground(false);
                legendIcons.get(i).setItemStack(null);
            }
            pendingLegendData = null;
        }
    }

    private void requestData() {
        if (BACKEND == null || BACKEND.BALANCE_HISTORY_MANAGER == null) return;
        if (dataPending) return;
        dataPending = true;

        BalanceHistoryManager mgr = BACKEND.BALANCE_HISTORY_MANAGER;
        mgr.getHistory(
                Optional.empty(),
                Optional.of(new EqualityFilter(1)),
                Optional.empty(),
                0
        ).thenAccept(records -> {
            pendingRecords = (records != null) ? records : List.of();
        });
    }

    private void applyData(List<BalanceHistoryRecord> records) {
        if (chart == null) return;

        if (records.isEmpty()) {
            chart.clearSeries();
            clearLegend(0);
            if (statusLabel != null) statusLabel.setText("Status: no data");
            syncToClientPublic();
            return;
        }

        Map<Short, List<BalanceHistoryRecord>> grouped = new LinkedHashMap<>();
        for (BalanceHistoryRecord r : records) {
            if (r.itemId() == BalanceHistoryRecord.WEALTH_ITEM_ID) continue;
            grouped.computeIfAbsent(r.itemId(), k -> new ArrayList<>()).add(r);
        }

        List<Map.Entry<Short, List<BalanceHistoryRecord>>> sortedItems = new ArrayList<>(grouped.entrySet());
        sortedItems.sort((a, b) -> {
            long balA = a.getValue().isEmpty() ? 0 : a.getValue().get(a.getValue().size() - 1).balance();
            long balB = b.getValue().isEmpty() ? 0 : b.getValue().get(b.getValue().size() - 1).balance();
            return Long.compare(balB, balA);
        });

        boolean firstLoad = chart.getSeries().isEmpty();
        chart.clearSeries();
        double scale = BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;

        int ci = 0;
        for (var entry : sortedItems) {
            short itemId = entry.getKey();
            List<BalanceHistoryRecord> itemRecords = entry.getValue();
            int color = ItemColorUtil.getColor(itemId, ci);
            String name = getItemDisplayName(itemId);

            BalanceHistoryChart.LineSeries s = new BalanceHistoryChart.LineSeries(name, color);
            for (BalanceHistoryRecord r : itemRecords) {
                s.points.add(new BalanceHistoryChart.DataPoint(r.time(),
                        (r.balance() + r.lockedBalance()) / scale));
            }
            chart.addSeries(s);

            if (ci < legendFrames.size()) {
                Frame f = legendFrames.get(ci);
                f.setOutlineColor(color);
                f.setBackgroundColor(ColorUtilities.getRGB(
                        (color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, 40));
                f.setEnableOutline(true);
                f.setEnableBackground(true);
            }
            if (ci < legendIcons.size()) {
                ItemID id = new ItemID(itemId);
                legendIcons.get(ci).setItemStack(id.getStack());
            }
            ci++;
        }

        clearLegend(ci);

        if (firstLoad) {
            chart.autoCenterView();
        }
        chart.scrollToPresent();

        if (statusLabel != null) {
            statusLabel.setText("Status: " + sortedItems.size() + " items");
        }

        syncToClientPublic();
    }

    private void clearLegend(int fromIndex) {
        for (int i = fromIndex; i < legendFrames.size(); i++) {
            legendFrames.get(i).setEnableOutline(false);
            legendFrames.get(i).setEnableBackground(false);
        }
        for (int i = fromIndex; i < legendIcons.size(); i++) {
            legendIcons.get(i).setItemStack(null);
        }
    }

    private static String getItemDisplayName(short itemId) {
        ItemID id = new ItemID(itemId);
        String name = id.getName();
        return name != null ? name : "Item#" + itemId;
    }

    private static void buildContent(Gui gui, int w, int h) {
        int y = MARGIN;
        int legendX = w - MARGIN - LEGEND_WIDTH;
        int chartWidth = legendX - MARGIN * 2;

        Label title = new Label("Balance History - Account #1");
        title.setBounds(0, y, w, 14);
        title.setAlignment(GuiElement.Alignment.CENTER);
        title.setTextColor(ColorUtilities.getRGB(200, 220, 255));
        gui.addElement(title);
        y += 18;

        Label status = new Label("Status: loading...");
        status.setId("status");
        status.setBounds(MARGIN, y, chartWidth, 10);
        status.setAlignment(GuiElement.Alignment.LEFT);
        status.setTextColor(ColorUtilities.getRGB(170, 170, 190));
        gui.addElement(status);
        y += 14;

        int chartHeight = h - y - MARGIN;
        BalanceHistoryChart chart = new BalanceHistoryChart();
        chart.setId("chart");
        chart.setBounds(MARGIN, y, chartWidth, chartHeight);
        gui.addElement(chart);

        int legendY = y;
        int legendEntryPad = 2;
        for (int i = 0; i < MAX_LEGEND_ITEMS; i++) {
            int entryY = legendY + i * LEGEND_ENTRY_SIZE;
            if (entryY + LEGEND_ENTRY_SIZE > h - MARGIN) break;

            Frame frame = new Frame(legendX, entryY, LEGEND_WIDTH, LEGEND_ENTRY_SIZE - legendEntryPad);
            frame.setId("lf_" + i);
            frame.setEnableOutline(false);
            frame.setEnableBackground(false);
            gui.addElement(frame);

            int iconPad = (LEGEND_WIDTH - 16) / 2;
            int iconYPad = (LEGEND_ENTRY_SIZE - legendEntryPad - 16) / 2;
            ItemView icon = new ItemView(legendX + iconPad, entryY + iconYPad, 16, 16);
            icon.setId("li_" + i);
            icon.setShowTooltip(true);
            gui.addElement(icon);
        }
    }
}
