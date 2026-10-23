package com.theplumteam.client.gui.widget;

import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Scrollable list widget for displaying figures with 3D models
 */
public class FigureListWidget extends BoundedSelectionList<FigureEntry> {
    // A plush is about twice as wide as a figure and some carry horns or fins above the head
    private static final float PLUSH_PREVIEW_SCALE = 0.6f;

    @Nullable
    private FigureCollection currentCollection;
    // The row that carries a heading and so sits lower, with every row after it; -1 without one
    private int headingRow = -1;
    // The scroll a draw's light wants, eased toward every frame; NaN while nothing is followed
    private double followTarget = Double.NaN;
    private long lastFollowNanos;
    // How fast the list closes on that scroll: the share left shrinks by e every 1/rate seconds
    private static final double FOLLOW_RATE = 12.0;

    // The cells' size and how many make a row; the machine screen fits them to its width
    private int cellSize = FigureEntry.FIGURE_SIZE;
    private int columns = 4;

    // Which figures count as discovered, asked with "collection:figure"
    private Predicate<String> discovered = FigureEntry.DEFAULT_DISCOVERED;
    // Which version each figure's cell shows, by "collection:figure", shared with every row
    private Map<String, Integer> shownSkins = new HashMap<>();

    // Configuration values
    private float modelScale = 1.0f;
    private float xRotation = 0.0f;
    private float yRotation = 150.0f;
    private float zRotation = 0.0f;
    private float xOffset = 0.0f;
    private float yOffset = 15.0f;
    private float zOffset = 0.0f;

    public FigureListWidget(Minecraft mc, int width, int height, int y, int entryHeight) {
        super(mc, width, height, y, y + height, entryHeight);
    }

    /**
     * The cells' size and how many make a row, for the next {@link #setCollection}. The list's
     * entry height, given at construction, holds one row of cells and its gap.
     */
    public void setGrid(int cellSize, int columns) {
        this.cellSize = cellSize;
        this.columns = Math.max(1, columns);
    }

    /** The width a row of {@code columns} cells of {@code cellSize} takes. */
    public static int rowWidth(int cellSize, int columns) {
        return columns * cellSize + (columns - 1) * FigureEntry.GRID_SPACING;
    }

    public void setCollection(@Nullable FigureCollection collection) {
        this.currentCollection = collection;
        this.clearEntries();
        this.headingRow = -1;

        if (collection != null) {
            // The specials follow the regular figures, on rows of their own
            addRows(collection, collection.getRegularFigures(), null);
            addRows(collection, collection.getSpecialFigures(), "Specials");
        }

        // Reset scroll to top when collection changes
        this.setScrollAmount(0.0);
    }

    // The heading, if any, is written above the first of the rows
    private void addRows(FigureCollection collection, List<FigureDefinition> figures, @Nullable String heading) {
        // Group figures into rows of the grid's columns
        List<FigureDefinition> currentRow = new java.util.ArrayList<>();
        for (int i = 0; i < figures.size(); i++) {
            currentRow.add(figures.get(i));

            // When the row is full or the figures run out, create a row entry
            if (currentRow.size() == columns || i == figures.size() - 1) {
                FigureEntry entry = new FigureEntry(currentRow, collection.getId());
                entry.setGrid(cellSize, columns);
                entry.setConfiguration(modelScale, xRotation, yRotation, zRotation, xOffset, yOffset, zOffset);
                entry.setPreviewScale(collection.isPlush() ? PLUSH_PREVIEW_SCALE : 1.0f);
                entry.setDiscovered(discovered);
                entry.setShownSkins(shownSkins);
                if (heading != null) {
                    entry.setHeading(heading);
                    this.headingRow = this.children().size();
                    heading = null;
                }
                this.addEntry(entry);
                currentRow = new java.util.ArrayList<>();
            }
        }
    }

    /**
     * Decides which figures the list shows as discovered, asked with "collection:figure", in
     * place of the client's own record, as the creator's machine replica shows a collection to a
     * new player or a collector; null goes back to that record.
     */
    public void setDiscovered(@Nullable Predicate<String> discovered) {
        this.discovered = discovered != null ? discovered : FigureEntry.DEFAULT_DISCOVERED;
        for (FigureEntry entry : children()) {
            entry.setDiscovered(this.discovered);
        }
    }

    /**
     * Shares a map of which version each figure's cell shows, by "collection:figure", as the
     * machine menu does so its cells keep their versions when it makes the list again.
     */
    public void setShownSkins(Map<String, Integer> shownSkins) {
        this.shownSkins = shownSkins;
        for (FigureEntry entry : children()) {
            entry.setShownSkins(shownSkins);
        }
    }

    /** Whether the list shows a figure, named "collection:figure", as discovered. */
    public boolean isDiscovered(String figureKey) {
        return discovered.test(figureKey);
    }

    public void updateConfiguration(float modelScale, float xRotation, float yRotation, float zRotation,
                                   float xOffset, float yOffset, float zOffset) {
        this.modelScale = modelScale;
        this.xRotation = xRotation;
        this.yRotation = yRotation;
        this.zRotation = zRotation;
        this.xOffset = xOffset;
        this.yOffset = yOffset;
        this.zOffset = zOffset;

        // Update all entries
        for (FigureEntry entry : children()) {
            entry.setConfiguration(modelScale, xRotation, yRotation, zRotation, xOffset, yOffset, zOffset);
        }
    }

    public float getModelScale() {
        return modelScale;
    }

    public float getXRotation() {
        return xRotation;
    }

    public float getYRotation() {
        return yRotation;
    }

    public float getZRotation() {
        return zRotation;
    }

    public float getXOffset() {
        return xOffset;
    }

    public float getYOffset() {
        return yOffset;
    }

    public float getZOffset() {
        return zOffset;
    }

    @Nullable
    public FigureCollection getCurrentCollection() {
        return currentCollection;
    }

    /** The figures of every row, top to bottom. */
    public List<List<FigureDefinition>> figureRows() {
        List<List<FigureDefinition>> rows = new java.util.ArrayList<>();
        for (FigureEntry row : this.children()) {
            rows.add(row.getFigures());
        }
        return rows;
    }

    /**
     * Glides the list until this row sits in the middle of the view. The list eases toward it a
     * little every frame instead of jumping, until {@link #stopFollowing} lets it go.
     */
    public void followRow(int index) {
        if (Double.isNaN(followTarget)) {
            lastFollowNanos = System.nanoTime();
        }
        followTarget = rowOffset(index) + this.itemHeight / 2.0 - (this.y1 - this.y0) / 2.0;
    }

    public void stopFollowing() {
        followTarget = Double.NaN;
    }

    private void easeTowardFollowTarget() {
        if (Double.isNaN(followTarget)) {
            return;
        }
        long now = System.nanoTime();
        double seconds = Math.min(0.1, (now - lastFollowNanos) / 1.0e9);
        lastFollowNanos = now;
        double current = scroll();
        this.setScrollAmount(current + (followTarget - current) * (1.0 - Math.exp(-seconds * FOLLOW_RATE)));
    }

    /** The row holding this figure, or null. */
    @Nullable
    public FigureEntry rowOf(String figureId) {
        for (FigureEntry row : this.children()) {
            if (row.getFigures().stream().anyMatch(figure -> figure.getId().equals(figureId))) {
                return row;
            }
        }
        return null;
    }

    // Where a row starts within the scrolled content, in unscaled units like the scroll amount
    private int rowOffset(int index) {
        return 4 + this.headerHeight + index * this.itemHeight
                + (headingRow >= 0 && index >= headingRow ? FigureEntry.HEADING_SPACE : 0);
    }

    private double scroll() {
        //? if >=1.21.2 {
        /*return this.scrollAmount();
        *///? } else {
        return this.getScrollAmount();
        //? }
    }

    public int left() {
        return this.x0;
    }

    public int top() {
        return this.y0;
    }

    public int right() {
        return this.x1;
    }

    public int bottom() {
        return this.y1;
    }

    @Override
    //? if >=1.21 {
    /*public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    *///? } else {
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //? }
        easeTowardFollowTarget();
        // Only the rows drawn now take clicks on their cells
        for (FigureEntry row : children()) {
            row.forgetCells();
        }
        //? if >=1.21 {
        /*super.renderWidget(graphics, mouseX, mouseY, partialTick);
        *///? } else {
        super.render(graphics, mouseX, mouseY, partialTick);
        //? }
    }

    @Override
    public int getRowWidth() {
        return this.width - 8;
    }

    @Override
    //? if >=1.21.2 {
    /*public int getRowTop(int index) {
    *///? } else {
    protected int getRowTop(int index) {
    //? }
        // The rows after a heading sit lower by the heading's room
        //? if >=1.21.2 {
        /*return this.y0 + 4 - (int)this.scrollAmount() + index * this.itemHeight + this.headerHeight
                + (headingRow >= 0 && index >= headingRow ? FigureEntry.HEADING_SPACE : 0);
        *///? } else {
        return this.y0 + 4 - (int)this.getScrollAmount() + index * this.itemHeight + this.headerHeight
                + (headingRow >= 0 && index >= headingRow ? FigureEntry.HEADING_SPACE : 0);
        //? }
    }

    @Override
    //? if >=1.21.2 {
    /*protected int contentHeight() {
    *///? } else {
    protected int getMaxPosition() {
    //? }
        return this.headerHeight + this.getItemCount() * this.itemHeight
                + (headingRow >= 0 ? FigureEntry.HEADING_SPACE : 0);
    }

    @Override
    //? if >=1.21.2 {
    /*protected int scrollBarX() {
    *///? } else {
    protected int getScrollbarPosition() {
    //? }
        return this.x1 - 6;
    }

    @Override
    protected void renderBackground(GuiGraphics graphics) {
        // Don't render default background
    }

    @Override
    //? if >=26 {
    /*public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent bpEvent, boolean bpDouble) {
        double mouseX = bpEvent.x();
        double mouseY = bpEvent.y();
        int button = bpEvent.button();
        if (button == 0 && showNextVersion(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(mouseX, mouseY, bpEvent.buttonInfo()), bpDouble);
    }
    *///? } else {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && showNextVersion(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    //? }

    // A click on the cell of a figure with versions shows its next one. The rows say where their
    // cells were drawn, since a row after the heading sits lower than the list's own reckoning.
    private boolean showNextVersion(double mouseX, double mouseY) {
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }
        for (FigureEntry row : children()) {
            if (row.showNextVersion(mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    @Override
    //? if >=1.21 {
    /*public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double amount) {
    *///? } else {
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
    //? }
        // Smooth scrolling: use fixed pixel amount instead of itemHeight-based
        // We use 20 pixels per scroll tick for smooth, precise control
        //? if >=1.21.2 {
        /*this.setScrollAmount(this.scrollAmount() - amount * 20.0);
        *///? } else {
        this.setScrollAmount(this.getScrollAmount() - amount * 20.0);
        //? }
        return true;
    }

    @Override
    //? if >=26 {
    /*public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent bpEvent, double dragX, double dragY) {
        double mouseX = bpEvent.x();
        double mouseY = bpEvent.y();
        int button = bpEvent.button();
        // Use virtual coordinates directly - screen already transforms mouse coords
        return super.mouseDragged(new net.minecraft.client.input.MouseButtonEvent(mouseX, mouseY, bpEvent.buttonInfo()), dragX, dragY);
    }
    *///? } else {
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        // Use virtual coordinates directly - screen already transforms mouse coords
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }
    //? }
}
