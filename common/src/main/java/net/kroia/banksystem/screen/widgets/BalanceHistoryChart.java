package net.kroia.banksystem.screen.widgets;

import net.kroia.modutilities.ColorUtilities;
import net.kroia.modutilities.gui.InputConstants;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.geometry.Rectangle;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.awt.Point;
import java.text.SimpleDateFormat;
import java.util.*;

public class BalanceHistoryChart extends GuiElement {

    private static final int KEY_SPACE = 32;

    public record DataPoint(long time, double value) {}

    public static class LineSeries {
        public final String name;
        public int color;
        public boolean visible = true;
        public final List<DataPoint> points = new ArrayList<>();

        public LineSeries(String name, int color) {
            this.name = name;
            this.color = color;
        }

        public LineSeries() {
            this("", 0xFFFFFFFF);
        }
    }

    private static final int COLOR_GRID = ColorUtilities.getRGB(32, 32, 32, 32);
    private static final int COLOR_ZERO = ColorUtilities.getRGB(32, 32, 32, 64);
    private static final int COLOR_FRAME = ColorUtilities.getRGB(80, 80, 80);
    private static final SimpleDateFormat DAY_FORMAT = new SimpleDateFormat("dd.", Locale.getDefault());
    private static final SimpleDateFormat MONTH_FORMAT = new SimpleDateFormat(" MMM ", Locale.getDefault());
    private static final SimpleDateFormat YEAR_FORMAT = new SimpleDateFormat("yyyy", Locale.getDefault());
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private final List<LineSeries> seriesList = new ArrayList<>();
    private final Rectangle canvasRect = new Rectangle(1, 1, 0, 0);
    private final Rectangle canvasScissorRect = new Rectangle(1, 1, 0, 0);

    private double viewX = 0, viewY = 0, viewWidth = 1, viewHeight = 1;

    private final Point lastDragMousePos = new Point();
    private boolean dragging = false;
    private int maxValueLabelWidth = 0;
    private int maxTimeLabelHeight = 0;
    private LineSeries highlightedSeries = null;
    private LineSeries pinnedSeries = null;
    private int pinnedSeriesIndex = -1;
    private final Map<GuiElement, LineSeries> hoverBindings = new LinkedHashMap<>();
    private final Map<String, Integer> pendingHoverBindingIds = new LinkedHashMap<>();
    private boolean hoverBindingsResolved = false;

    public BalanceHistoryChart() {
        setTextFontScale(0.8f);
    }

    // ── Serialization for display block sync ──

    @Override
    public SyncCategory getSyncCategory() {
        return SyncCategory.INPUT;
    }

    @Override
    public CompoundTag serializeState() {
        CompoundTag tag = super.serializeState();
        tag.putDouble("viewX", viewX);
        tag.putDouble("viewY", viewY);
        tag.putDouble("viewW", viewWidth);
        tag.putDouble("viewH", viewHeight);
        tag.putInt("pinnedIdx", pinnedSeriesIndex);

        ListTag seriesTag = new ListTag();
        for (LineSeries s : seriesList) {
            CompoundTag st = new CompoundTag();
            st.putString("name", s.name);
            st.putInt("color", s.color);
            st.putBoolean("visible", s.visible);
            int count = s.points.size();
            long[] times = new long[count];
            long[] values = new long[count];
            for (int i = 0; i < count; i++) {
                DataPoint p = s.points.get(i);
                times[i] = p.time();
                values[i] = Double.doubleToRawLongBits(p.value());
            }
            st.putLongArray("times", times);
            st.putLongArray("values", values);
            seriesTag.add(st);
        }
        tag.put("series", seriesTag);

        ListTag hoverTag = new ListTag();
        for (var entry : hoverBindings.entrySet()) {
            String elId = entry.getKey().getId();
            int idx = seriesList.indexOf(entry.getValue());
            if (elId != null && idx >= 0) {
                CompoundTag ht = new CompoundTag();
                ht.putString("id", elId);
                ht.putInt("idx", idx);
                hoverTag.add(ht);
            }
        }
        if (!hoverTag.isEmpty()) {
            tag.put("hoverBindings", hoverTag);
        }

        return tag;
    }

    @Override
    public void deserializeState(CompoundTag tag) {
        super.deserializeState(tag);
        if (tag.contains("viewX")) {
            viewX = tag.getDouble("viewX");
            viewY = tag.getDouble("viewY");
            viewWidth = tag.getDouble("viewW");
            viewHeight = tag.getDouble("viewH");
        }
        pinnedSeriesIndex = tag.getInt("pinnedIdx");

        if (tag.contains("series")) {
            seriesList.clear();
            ListTag seriesTag = tag.getList("series", 10);
            for (int i = 0; i < seriesTag.size(); i++) {
                CompoundTag st = seriesTag.getCompound(i);
                LineSeries s = new LineSeries(st.getString("name"), st.getInt("color"));
                s.visible = st.getBoolean("visible");
                long[] times = st.getLongArray("times");
                long[] values = st.getLongArray("values");
                int count = Math.min(times.length, values.length);
                for (int j = 0; j < count; j++) {
                    s.points.add(new DataPoint(times[j], Double.longBitsToDouble(values[j])));
                }
                seriesList.add(s);
            }
            pinnedSeries = (pinnedSeriesIndex >= 0 && pinnedSeriesIndex < seriesList.size())
                    ? seriesList.get(pinnedSeriesIndex) : null;
        }
        pendingHoverBindingIds.clear();
        hoverBindingsResolved = false;
        if (tag.contains("hoverBindings")) {
            ListTag hoverTag = tag.getList("hoverBindings", 10);
            for (int i = 0; i < hoverTag.size(); i++) {
                CompoundTag ht = hoverTag.getCompound(i);
                pendingHoverBindingIds.put(ht.getString("id"), ht.getInt("idx"));
            }
        }
        markDirty();
    }

    @Override
    public List<GuiElement> getSerializableChildren() {
        return List.of();
    }

    // ── Data management ──

    public void addSeries(LineSeries series) {
        seriesList.add(series);
    }

    public void clearSeries() {
        seriesList.clear();
        pinnedSeries = null;
        pinnedSeriesIndex = -1;
    }

    public List<LineSeries> getSeries() {
        return seriesList;
    }

    public void setHighlightedSeries(LineSeries series) {
        this.highlightedSeries = series;
    }

    public void bindHoverElement(GuiElement element, LineSeries series) {
        hoverBindings.put(element, series);
    }

    public void clearHoverBindings() {
        hoverBindings.clear();
        pendingHoverBindingIds.clear();
        hoverBindingsResolved = false;
    }

    private void resolvePendingHoverBindings() {
        hoverBindingsResolved = true;
        if (getRoot() == null || seriesList.isEmpty()) return;
        for (GuiElement el : getRoot().getElements()) {
            String elId = el.getId();
            if (elId != null && pendingHoverBindingIds.containsKey(elId)) {
                int idx = pendingHoverBindingIds.get(elId);
                if (idx >= 0 && idx < seriesList.size()) {
                    hoverBindings.put(el, seriesList.get(idx));
                }
            }
        }
        pendingHoverBindingIds.clear();
    }

    public void setPinnedSeries(LineSeries series) {
        this.pinnedSeries = series;
        this.pinnedSeriesIndex = seriesList.indexOf(series);
    }

    public double getViewX() { return viewX; }
    public double getViewY() { return viewY; }
    public double getViewWidth() { return viewWidth; }
    public double getViewHeight() { return viewHeight; }

    public void setView(double x, double y, double width, double height) {
        this.viewX = x;
        this.viewY = y;
        this.viewWidth = width;
        this.viewHeight = height;
        clampView();
        markDirty();
    }

    public boolean isAtPresent() {
        long maxTime = Long.MIN_VALUE;
        for (LineSeries s : seriesList) {
            if (s.points.isEmpty()) continue;
            maxTime = Math.max(maxTime, s.points.get(s.points.size() - 1).time());
        }
        if (maxTime == Long.MIN_VALUE) return true;
        return (viewX + viewWidth) >= maxTime - 1000;
    }

    public void scrollToLatestData() {
        long maxTime = Long.MIN_VALUE;
        for (LineSeries s : seriesList) {
            if (s.points.isEmpty()) continue;
            maxTime = Math.max(maxTime, s.points.get(s.points.size() - 1).time());
        }
        if (maxTime == Long.MIN_VALUE) return;
        viewX = maxTime - viewWidth;
        clampView();
        markDirty();
    }

    public void scrollToPresent() {
        long now = System.currentTimeMillis();
        if (viewWidth <= 0) viewWidth = 3_600_000;
        viewX = now - viewWidth;
        markDirty();
    }

    public void autoCenterView() {
        long minTime = Long.MAX_VALUE;
        long maxTime = Long.MIN_VALUE;
        double minVal = Double.MAX_VALUE;
        double maxVal = -Double.MAX_VALUE;
        boolean hasData = false;

        for (LineSeries s : seriesList) {
            if (!s.visible || s.points.isEmpty()) continue;
            for (DataPoint p : s.points) {
                minTime = Math.min(minTime, p.time());
                maxTime = Math.max(maxTime, p.time());
                minVal = Math.min(minVal, p.value());
                maxVal = Math.max(maxVal, p.value());
                hasData = true;
            }
        }
        if (!hasData) return;

        long timeRange = maxTime - minTime;
        if (timeRange <= 0) timeRange = 60_000;
        double valRange = maxVal - minVal;
        if (valRange <= 0) valRange = 100;

        viewWidth = timeRange;
        viewX = minTime;
        viewHeight = valRange * 1.2;
        viewY = Math.max(-viewHeight * 0.05, minVal - valRange * 0.1);
    }

    // ── Rendering ──

    @Override
    protected void renderBackground() {
        super.renderBackground();
        if (seriesList.isEmpty()) return;

        if (!hoverBindingsResolved && !pendingHoverBindingIds.isEmpty()) {
            resolvePendingHoverBindings();
        }

        highlightedSeries = null;
        for (var entry : hoverBindings.entrySet()) {
            if (entry.getKey().isMouseOver()) {
                highlightedSeries = entry.getValue();
                break;
            }
        }

        updateCanvasRect();

        enableScissor(canvasScissorRect);
        int zeroY = toCanvasSpaceY(0);
        if (zeroY >= canvasRect.y && zeroY <= canvasRect.y + canvasRect.height) {
            drawRect(canvasRect.x, zeroY, canvasRect.width, 1, COLOR_ZERO);
        }

        renderHorizontalGrid();
        disableScissor();
        renderVerticalGrid();

        enableScissor(canvasScissorRect);
        renderLines();
        disableScissor();

        drawFrame(canvasRect, COLOR_FRAME, 1);
    }

    @Override
    protected void render() {}

    @Override
    protected void layoutChanged() {}

    private void updateCanvasRect() {
        canvasRect.x = 1;
        canvasRect.y = 1;
        canvasRect.width = Math.max(2, getWidth() - maxValueLabelWidth - 10);
        canvasRect.height = Math.max(2, getHeight() - maxTimeLabelHeight - 5);
        canvasScissorRect.x = canvasRect.x + 1;
        canvasScissorRect.y = canvasRect.y + 1;
        canvasScissorRect.width = Math.max(1, canvasRect.width - 1);
        canvasScissorRect.height = Math.max(1, canvasRect.height - 1);
    }

    private void renderHorizontalGrid() {
        int targetLines = 8;
        if (viewHeight <= 0) return;
        double rawStep = viewHeight / targetLines;

        double magnitude = Math.pow(10, Math.floor(Math.log10(rawStep)));
        double normalized = rawStep / magnitude;
        double niceStep;
        if (normalized < 1.5) niceStep = magnitude;
        else if (normalized < 3.5) niceStep = 2 * magnitude;
        else if (normalized < 7.5) niceStep = 5 * magnitude;
        else niceStep = 10 * magnitude;

        double firstLine = Math.floor(viewY / niceStep) * niceStep;
        double topValue = viewY + viewHeight;
        int localMaxWidth = 0;

        scissorPause();
        for (double val = firstLine; val <= topValue; val += niceStep) {
            int yPos = toCanvasSpaceY(val);
            if (yPos < canvasRect.y || yPos > canvasRect.y + canvasRect.height) continue;
            String label = formatValue(val);
            int textWidth = getTextWidth(label);
            localMaxWidth = Math.max(localMaxWidth, textWidth);
            drawText(label, canvasRect.x + canvasRect.width + 5, yPos - getTextHeight() / 2);
            drawRect(canvasRect.x, yPos, canvasRect.width, 1, COLOR_GRID);
        }
        scissorResume();
        maxValueLabelWidth = localMaxWidth + 10;
    }

    private void renderVerticalGrid() {
        int targetLines = 8;
        if (viewWidth <= 0) return;
        double rawStep = viewWidth / targetLines;

        double magnitude = Math.pow(10, Math.floor(Math.log10(rawStep)));
        double normalized = rawStep / magnitude;
        double niceStep;
        if (normalized < 1.5) niceStep = magnitude;
        else if (normalized < 3.5) niceStep = 2 * magnitude;
        else if (normalized < 7.5) niceStep = 5 * magnitude;
        else niceStep = 10 * magnitude;

        double firstLine = Math.floor(viewX / niceStep) * niceStep;
        double rightTime = viewX + viewWidth;
        int canvasBottom = canvasRect.y + canvasRect.height;
        int localMaxHeight = 0;

        Date lastDate = new Date(0);
        for (double t = firstLine; t <= rightTime; t += niceStep) {
            int xPos = toCanvasSpaceX((long) t);
            if (xPos < canvasRect.x || xPos > canvasRect.x + canvasRect.width) continue;

            drawRect(xPos, canvasRect.y, 1, canvasRect.height, COLOR_GRID);

            Date current = new Date((long) t);
            String[] labels = timestampToLabels(current, lastDate);
            lastDate = current;

            int textHeight = getTextHeight();
            for (int j = 0; j < labels.length; j++) {
                int yPos = canvasBottom + 2 + (j * textHeight);
                int tw = getTextWidth(labels[j]);
                drawText(labels[j], xPos - tw / 2, yPos);
                localMaxHeight = Math.max(localMaxHeight, (j + 1) * textHeight + 5);
            }
        }
        maxTimeLabelHeight = localMaxHeight;
    }

    private void renderLines() {
        for (LineSeries series : seriesList) {
            if (!series.visible || series.points.size() < 2) continue;
            if (series == highlightedSeries || series == pinnedSeries) continue;
            float thickness = 1.5f;
            int color = (highlightedSeries != null) ? ColorUtilities.setAlpha(series.color, 0.4f) : series.color;
            renderSeries(series, thickness, color);
        }
        if (highlightedSeries != null && highlightedSeries.visible && highlightedSeries.points.size() >= 2) {
            int brightColor = ColorUtilities.setBrightness(highlightedSeries.color, 1.4f);
            renderSeries(highlightedSeries, 3.0f, brightColor);
        }
        if (pinnedSeries != null && pinnedSeries.visible && pinnedSeries.points.size() >= 2) {
            renderSeries(pinnedSeries, 2.0f, pinnedSeries.color);
        }
    }

    private void renderSeries(LineSeries series, float thickness, int color) {
        List<int[]> pts = buildOptimizedPoints(series);
        if (pts.size() < 2) return;

        boolean opaque = (color >>> 24) == 0xFF;
        int jointSize = Math.max(1, (int) thickness);
        int halfJoint = jointSize / 2;

        for (int i = 1; i < pts.size(); i++) {
            int[] prev = pts.get(i - 1);
            int[] curr = pts.get(i);
            drawLine(prev[0], prev[1], curr[0], curr[1], thickness, color);

            if (opaque && i >= 2) {
                int[] pp = pts.get(i - 2);
                int dy1 = prev[1] - pp[1];
                int dy2 = curr[1] - prev[1];
                if ((dy1 > 0) != (dy2 > 0) && dy1 != 0 && dy2 != 0) {
                    drawRect(prev[0] - halfJoint, prev[1] - halfJoint, jointSize, jointSize, color);
                }
            }
        }
    }

    private List<int[]> buildOptimizedPoints(LineSeries series) {
        List<int[]> pts = new ArrayList<>();
        for (int i = 0; i < series.points.size(); i++) {
            DataPoint p = series.points.get(i);
            int x = toCanvasSpaceX(p.time());
            int y = toCanvasSpaceY(p.value());
            int size = pts.size();
            if (size >= 2 && pts.get(size - 1)[1] == y && pts.get(size - 2)[1] == y) {
                pts.get(size - 1)[0] = x;
            } else {
                pts.add(new int[]{x, y});
            }
        }
        return pts;
    }

    // ── Input handling ──

    @Override
    protected boolean mouseScrolledOverElement(double delta) {
        double zoomFactor = (delta > 0) ? 0.9 : 1.1;
        double mouseWorldX = fromCanvasSpaceX(getMouseX());
        double mouseWorldY = fromCanvasSpaceY(getMouseY());
        boolean consumed = false;

        if (!isKeyPressed(InputConstants.KEY_LEFT_CONTROL)) {
            double oldWidth = viewWidth;
            viewWidth = Math.max(1000, viewWidth * zoomFactor);
            double mouseNormX = (mouseWorldX - viewX) / oldWidth;
            viewX = mouseWorldX - mouseNormX * viewWidth;
            consumed = true;
        }

        if (!isKeyPressed(InputConstants.KEY_LEFT_SHIFT)) {
            double oldHeight = viewHeight;
            viewHeight = Math.max(0.1, viewHeight * zoomFactor);
            double mouseNormY = (mouseWorldY - viewY) / oldHeight;
            viewY = mouseWorldY - mouseNormY * viewHeight;
            consumed = true;
        }

        clampView();
        if (consumed) markDirty();
        return consumed;
    }

    @Override
    protected boolean mouseClickedOverElement(int button) {
        lastDragMousePos.x = getMouseX();
        lastDragMousePos.y = getMouseY();
        dragging = true;
        return true;
    }

    @Override
    protected void mouseReleased(int button) {
        dragging = false;
    }

    @Override
    protected boolean mouseDragged(int button, double deltaX, double deltaY) {
        if (!dragging) return false;
        int mouseX = getMouseX();
        int mouseY = getMouseY();

        if (button == InputConstants.MOUSE_BUTTON_LEFT && (mouseX != lastDragMousePos.x || mouseY != lastDragMousePos.y)) {
            int dx = lastDragMousePos.x - mouseX;
            int dy = lastDragMousePos.y - mouseY;
            lastDragMousePos.x = mouseX;
            lastDragMousePos.y = mouseY;

            double worldDeltaX = dx * viewWidth / canvasRect.width;
            double worldDeltaY = dy * viewHeight / canvasRect.height;

            viewX += worldDeltaX;
            viewY -= worldDeltaY;
            clampView();
            markDirty();
            return true;
        }
        return false;
    }

    @Override
    protected boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == KEY_SPACE) {
            autoCenterView();
            markDirty();
            return true;
        }
        return false;
    }

    // ── View clamping ──

    private void clampView() {
        long minTime = Long.MAX_VALUE;
        long maxTime = Long.MIN_VALUE;
        for (LineSeries s : seriesList) {
            if (s.points.isEmpty()) continue;
            minTime = Math.min(minTime, s.points.get(0).time());
            maxTime = Math.max(maxTime, s.points.get(s.points.size() - 1).time());
        }
        if (minTime == Long.MAX_VALUE) return;

        double dataRange = maxTime - minTime;
        if (dataRange <= 0) dataRange = 60_000;

        if (viewWidth > dataRange) viewWidth = dataRange;
        if (viewX < minTime) viewX = minTime;
        if (viewX + viewWidth > maxTime) viewX = maxTime - viewWidth;

        double minY = -viewHeight * 0.05;
        if (viewY < minY) viewY = minY;
    }

    // ── Coordinate conversion ──

    private int toCanvasSpaceX(long time) {
        if (viewWidth == 0) return canvasRect.x;
        return canvasRect.x + (int) (((double)(time - (long)viewX) / viewWidth) * canvasRect.width);
    }

    private int toCanvasSpaceY(double value) {
        if (viewHeight == 0) return canvasRect.y + canvasRect.height;
        return (canvasRect.y + canvasRect.height) - (int) (((value - viewY) / viewHeight) * canvasRect.height);
    }

    private double fromCanvasSpaceX(int screenX) {
        return viewX + (double)(screenX - canvasRect.x) / canvasRect.width * viewWidth;
    }

    private double fromCanvasSpaceY(int screenY) {
        return viewY + (double)(canvasRect.y + canvasRect.height - screenY) / canvasRect.height * viewHeight;
    }

    // ── Formatting ──

    private String formatValue(double value) {
        if (value >= 1_000_000_000) return String.format("%.1fB", value / 1_000_000_000);
        else if (value >= 1_000_000) return String.format("%.1fM", value / 1_000_000);
        else if (value >= 1_000) return String.format("%.1fk", value / 1_000);
        else return String.format("%.2f", value);
    }

    private String[] timestampToLabels(Date current, Date last) {
        String timeStr = TIME_FORMAT.format(current);
        String currentDay = DAY_FORMAT.format(current);
        String currentMonth = MONTH_FORMAT.format(current);
        String currentYear = YEAR_FORMAT.format(current);

        String lastDay = DAY_FORMAT.format(last);
        String lastMonth = MONTH_FORMAT.format(last);
        String lastYear = YEAR_FORMAT.format(last);

        StringBuilder dateStr = new StringBuilder();
        if (!lastYear.equals(currentYear)) dateStr.append(currentYear).append("\n");
        if (!lastMonth.equals(currentMonth)) dateStr.append(currentMonth).append("\n");
        if (!lastDay.equals(currentDay)) dateStr.append(currentDay).append("\n");

        if (dateStr.isEmpty()) return new String[]{timeStr};
        return (dateStr + timeStr).split("\n");
    }
}
