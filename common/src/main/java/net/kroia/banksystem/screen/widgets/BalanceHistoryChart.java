package net.kroia.banksystem.screen.widgets;

import net.kroia.banksystem.util.BankSystemGuiElement;
import net.kroia.modutilities.ColorUtilities;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.geometry.Rectangle;
import org.lwjgl.glfw.GLFW;

import java.awt.Point;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Interactive line chart widget for visualizing balance history over time.
 * <p>
 * Supports multiple overlapping line series with distinct colors, mouse-driven
 * pan/zoom on both axes, and three rendering tiers:
 * <ol>
 *   <li><b>Normal series</b> — drawn first at 1.5px, dimmed to 40% alpha when a highlight is active</li>
 *   <li><b>Highlighted series</b> — drawn second at 3.0px with 1.4x brightness (set via hover bindings)</li>
 *   <li><b>Pinned series</b> — drawn last (always on top) at 2.0px, never dimmed by highlights</li>
 * </ol>
 * <p>
 * The view uses {@code double} precision for all coordinates to avoid floating-point
 * jitter with epoch-millisecond timestamps (~13 digits).
 * <p>
 * <b>Controls:</b>
 * <ul>
 *   <li>Mouse drag — pan</li>
 *   <li>Scroll wheel — zoom both axes (Shift = Y-only, Ctrl = X-only)</li>
 *   <li>Space — auto-center to fit all visible data</li>
 * </ul>
 */
public class BalanceHistoryChart extends BankSystemGuiElement {

    public record DataPoint(long time, double value) {}

    public static class LineSeries {
        public final String name;
        public final int color;
        public boolean visible = true;
        public final List<DataPoint> points = new ArrayList<>();

        public LineSeries(String name, int color) {
            this.name = name;
            this.color = color;
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

    // View window in world/data space (double precision for epoch millis accuracy)
    private double viewX = 0, viewY = 0, viewWidth = 1, viewHeight = 1;

    private final Point lastDragMousePos = new Point();
    private boolean dragging = false;
    private int maxValueLabelWidth = 0;
    private int maxTimeLabelHeight = 0;
    private LineSeries highlightedSeries = null;
    private LineSeries pinnedSeries = null;
    // Maps external GUI elements to series — checked each frame to resolve hover highlight
    private final Map<GuiElement, LineSeries> hoverBindings = new LinkedHashMap<>();

    public BalanceHistoryChart() {
        setTextFontScale(0.8f);
    }

    public void addSeries(LineSeries series) {
        seriesList.add(series);
    }

    public void clearSeries() {
        seriesList.clear();
    }

    public List<LineSeries> getSeries() {
        return seriesList;
    }

    public void setHighlightedSeries(LineSeries series) {
        this.highlightedSeries = series;
    }

    /**
     * Binds an external GUI element (e.g. a toggle row) to a series.
     * Each frame, the chart checks if any bound element is hovered and
     * highlights the corresponding series. This avoids cross-frame timing
     * issues that cause flickering.
     */
    public void bindHoverElement(GuiElement element, LineSeries series) {
        hoverBindings.put(element, series);
    }

    public void clearHoverBindings() {
        hoverBindings.clear();
    }

    /**
     * Sets a series to be pinned — always drawn on top of all other series,
     * never dimmed by hover highlights. Used for the "Total Wealth" line.
     */
    public void setPinnedSeries(LineSeries series) {
        this.pinnedSeries = series;
    }

    /**
     * Fits the view to show all visible data points exactly within the canvas.
     * X axis spans the full data time range with no future padding.
     * Y axis gets 10% padding above and below for readability.
     */
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
        viewY = Math.max(0, minVal - valRange * 0.1);
        viewHeight = valRange * 1.2;
    }

    // ── Rendering ──

    @Override
    protected void renderBackground() {
        super.renderBackground();
        if (seriesList.isEmpty()) return;

        // Resolve hover highlight from bound elements within this frame
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
        canvasScissorRect.width = Math.max(1, canvasRect.width - 2);
        canvasScissorRect.height = Math.max(1, canvasRect.height - 2);
    }

    /**
     * Draws Y-axis labels and horizontal gridlines using "nice" step rounding
     * (1, 2, 5 × 10^n) to produce clean label values.
     */
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

    /**
     * Draws X-axis time labels and vertical gridlines.
     * Shows date transitions (year/month/day) only when they change.
     */
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

    /**
     * Renders all line series in three tiers:
     * 1. Normal series (dimmed if a highlight is active)
     * 2. Highlighted series (bright, thick, drawn on top of normal)
     * 3. Pinned series (always on top, never dimmed)
     */
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

    /**
     * Draws a single series as connected line segments with joint fillers at
     * direction changes. Consecutive same-Y points are merged into single
     * horizontal segments. Joint fillers are skipped when the color has alpha
     * to prevent double-blend artifacts.
     */
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

            // Fill joint gaps only at Y-direction changes and only for opaque colors
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

    /**
     * Collapses runs of consecutive same-Y screen-space points into just the
     * first and last point, reducing draw calls for flat balance periods.
     */
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

        // Horizontal zoom (hold Ctrl to lock to vertical-only)
        if (!isKeyPressed(GLFW.GLFW_KEY_LEFT_CONTROL)) {
            double oldWidth = viewWidth;
            viewWidth = Math.max(1000, viewWidth * zoomFactor);
            double mouseNormX = (mouseWorldX - viewX) / oldWidth;
            viewX = mouseWorldX - mouseNormX * viewWidth;
            consumed = true;
        }

        // Vertical zoom (hold Shift to lock to horizontal-only)
        if (!isKeyPressed(GLFW.GLFW_KEY_LEFT_SHIFT)) {
            double oldHeight = viewHeight;
            viewHeight = Math.max(0.1, viewHeight * zoomFactor);
            double mouseNormY = (mouseWorldY - viewY) / oldHeight;
            viewY = Math.max(0, mouseWorldY - mouseNormY * viewHeight);
            consumed = true;
        }

        clampView();
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

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && (mouseX != lastDragMousePos.x || mouseY != lastDragMousePos.y)) {
            int dx = lastDragMousePos.x - mouseX;
            int dy = lastDragMousePos.y - mouseY;
            lastDragMousePos.x = mouseX;
            lastDragMousePos.y = mouseY;

            double worldDeltaX = dx * viewWidth / canvasRect.width;
            double worldDeltaY = dy * viewHeight / canvasRect.height;

            viewX += worldDeltaX;
            viewY = Math.max(0, viewY - worldDeltaY);
            clampView();
            return true;
        }
        return false;
    }

    @Override
    protected boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_SPACE) {
            autoCenterView();
            return true;
        }
        return false;
    }

    // ── View clamping ──

    /**
     * Constrains the view so no future time or pre-data time is visible.
     * Width is capped to the actual data range; edges are clamped to
     * [minTime, maxTime].
     */
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

        if (viewWidth > dataRange) {
            viewWidth = dataRange;
        }

        if (viewX < minTime) {
            viewX = minTime;
        }
        if (viewX + viewWidth > maxTime) {
            viewX = maxTime - viewWidth;
        }

        if (viewY < 0) viewY = 0;
    }

    // ── Coordinate conversion (double precision to avoid epoch-millis jitter) ──

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
