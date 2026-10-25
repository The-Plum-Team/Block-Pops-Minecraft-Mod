package com.theplumteam.client.gui;

import com.theplumteam.client.gui.util.GuiPose;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.client.gui.style.MenuGuiScale;
import com.theplumteam.client.gui.style.PixelFont;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.style.StyledButton;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.style.UiText;
import com.theplumteam.client.gui.widget.ColorSelectionButton;
import com.theplumteam.network.SetFavoriteColorPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Screen displayed when a player joins a world for the first time.
 * Allows them to choose their favorite color which will be used for their figure box
 * in the World Players collection.
 *
 * <p>A full-screen menu in Quick Skin's style on the machine screen's scrolling stars: the title
 * in the pixel font, the explanation under it, the 16 colours as raised tiles on a dark backdrop,
 * and the Figure toggle beside the green Done button. It has its own GUI scale
 * ({@link MenuGuiScale}) and lays itself out from the canvas, between about 400x300 and 860x520.
 */
public class FavoriteColorSelectionScreen extends Screen implements MenuGuiScale.Scaled {
    private static final Logger LOGGER = LoggerFactory.getLogger(FavoriteColorSelectionScreen.class);

    // The description's two lines
    private static final String DESCRIPTION_1 = "blockpops.favorite_color.description.1";
    private static final String DESCRIPTION_2 = "blockpops.favorite_color.description.2";

    private static final int GRID_COLUMNS = 4; // 4x4 grid
    private static final int GRID_ROWS = 4;
    private static final int MAX_TILE_SIZE = 88;
    private static final int MIN_TILE_SIZE = 16;
    /** Room between the backdrop's edge and the tiles. */
    private static final int BACKDROP_PADDING = 8;
    private static final int BACKDROP_ALPHA = 0xB0;
    /** Room between the title, the description, the backdrop and the buttons. */
    private static final int SECTION_GAP = 8;
    private static final int BUTTON_GAP = 6;
    private static final int MIN_TOGGLE_WIDTH = 96;
    /** The tile size the preview offsets below were tuned at. */
    private static final int PREVIEW_TUNED_SIZE = 72;

    @Nullable
    private PopBlockColor selectedColor = null;
    private Button doneButton;
    private Button toggleFigureButton;
    private boolean showFigureInBox = true;
    private final List<ColorSelectionButton> colorButtons = new ArrayList<>();

    // Layout, from the canvas size
    private float titleScale = 1.0f;
    private float titleMiddleY;
    private int descriptionY;
    private int backdropLeft;
    private int backdropTop;
    private int backdropRight;
    private int backdropBottom;

    // Transformation values for box rendering
    private float rotationX = 342.3f;  // Default rotation
    private float rotationY = 335.9f;
    private float rotationZ = 0.0f;
    private float scale = 1.2f;
    private float offsetX = 198.0f;
    private float offsetY = -134.0f;
    private float offsetZ = 0.0f;

    // How far the fitted preview shrank from the tuned one. The preview offsets were tuned
    // at that tile size and must shrink with it to keep each box inside its tile.
    private float previewFit = 1.0f;

    // The screen to go back to on closing: the machine menu that opened this one, or none when
    // the command did
    @Nullable
    private final Screen parent;

    public FavoriteColorSelectionScreen() {
        this(null);
    }

    public FavoriteColorSelectionScreen(@Nullable Screen parent) {
        super(Component.translatable("blockpops.favorite_color.title"));
        this.parent = parent;
        BlockPopsMod.logDebug("FavoriteColorSelectionScreen created");
    }

    @Override
    public void onClose() {
        if (this.minecraft != null && this.parent != null) {
            this.minecraft.setScreen(this.parent);
        } else {
            super.onClose();
        }
    }

    @Override
    protected void init() {
        if (this.minecraft != null) {
            MenuGuiScale.enter(this.minecraft, this);
        }
        super.init();
        colorButtons.clear();

        int margin = Math.max(8, this.height / 36);
        int spacing = Math.max(4, this.height / 60);
        int buttonHeight = Mth.clamp(this.height / 14, 20, 30);

        // The title's capitals, then the two lines of the description
        this.titleScale = Mth.clamp(this.height / 250.0f, 1.0f, 2.0f);
        int titleHeight = Math.round(PixelFont.CAPITALS_HEIGHT * PixelFont.crispScale(this.titleScale));
        int descriptionHeight = 2 * this.font.lineHeight + 2;
        int headerHeight = titleHeight + SECTION_GAP + descriptionHeight;

        // The tiles take what the header and the buttons leave, never more than the width allows
        int availableHeight = this.height - 2 * margin - headerHeight - buttonHeight
                - 2 * SECTION_GAP - 2 * BACKDROP_PADDING;
        int availableWidth = this.width - 2 * margin - 2 * BACKDROP_PADDING;
        int tileSize = Math.min((availableHeight - (GRID_ROWS - 1) * spacing) / GRID_ROWS,
                (availableWidth - (GRID_COLUMNS - 1) * spacing) / GRID_COLUMNS);
        tileSize = Mth.clamp(tileSize, MIN_TILE_SIZE, MAX_TILE_SIZE);
        // Each tile draws its preview at 60% of its size; the offsets scale by the same ratio.
        this.previewFit = (int) (tileSize * 0.6f) / (float) (int) (PREVIEW_TUNED_SIZE * 0.6f);
        int gridWidth = GRID_COLUMNS * tileSize + (GRID_COLUMNS - 1) * spacing;
        int gridHeight = GRID_ROWS * tileSize + (GRID_ROWS - 1) * spacing;

        // Everything centred on the canvas when the tiles leave room to spare
        int contentHeight = headerHeight + SECTION_GAP + gridHeight + 2 * BACKDROP_PADDING + SECTION_GAP + buttonHeight;
        int top = Math.max(margin, (this.height - contentHeight) / 2);
        this.titleMiddleY = top + titleHeight / 2.0f;
        this.descriptionY = top + titleHeight + SECTION_GAP;
        int gridX = (this.width - gridWidth) / 2;
        int gridY = top + headerHeight + SECTION_GAP + BACKDROP_PADDING;
        this.backdropLeft = gridX - BACKDROP_PADDING;
        this.backdropTop = gridY - BACKDROP_PADDING;
        this.backdropRight = gridX + gridWidth + BACKDROP_PADDING;
        this.backdropBottom = gridY + gridHeight + BACKDROP_PADDING;

        // Create 4x4 grid of color buttons
        PopBlockColor[] colors = PopBlockColor.values();
        for (int i = 0; i < colors.length; i++) {
            PopBlockColor color = colors[i];
            int row = i / GRID_COLUMNS;
            int col = i % GRID_COLUMNS;
            int x = gridX + (col * (tileSize + spacing));
            int y = gridY + (row * (tileSize + spacing));

            ColorSelectionButton colorButton = new ColorSelectionButton(x, y, tileSize, color, this);
            colorButton.setSelected(color == selectedColor);
            this.addRenderableWidget(colorButton);
            colorButtons.add(colorButton);
        }

        // Bottom buttons, as wide as the backdrop: Toggle Figure on the left, Done on the right
        int rowWidth = Math.min(this.width - 2 * margin, Math.max(200, backdropRight - backdropLeft));
        int rowX = (this.width - rowWidth) / 2;
        int buttonsY = backdropBottom + SECTION_GAP;
        int toggleButtonWidth = Math.max(MIN_TOGGLE_WIDTH, (int) (rowWidth * 0.38f));

        StyledButton toggle = new StyledButton(rowX, buttonsY, toggleButtonWidth, buttonHeight,
                Component.translatable(showFigureInBox ? "blockpops.favorite_color.figure_on" : "blockpops.favorite_color.figure_off"), button -> {
            showFigureInBox = !showFigureInBox;
            button.setMessage(Component.translatable(showFigureInBox ? "blockpops.favorite_color.figure_on" : "blockpops.favorite_color.figure_off"));
            ((StyledButton) button).setSelected(showFigureInBox);
            updateFigureVisibility();
        }).icon(PixelIcon.PLAYER);
        // The bright border marks the figure as shown
        toggle.setSelected(showFigureInBox);
        toggleFigureButton = toggle;
        this.addRenderableWidget(toggleFigureButton);

        int doneButtonX = rowX + toggleButtonWidth + BUTTON_GAP;
        doneButton = new StyledButton(doneButtonX, buttonsY, rowX + rowWidth - doneButtonX, buttonHeight,
                CommonComponents.GUI_DONE, button -> {
            if (selectedColor != null) {
                BlockPopsMod.logDebug("Player confirmed favorite color choice: {}", selectedColor.getSerializedName());
                // Send packet to server using cross-platform networking
                SetFavoriteColorPacket packet = new SetFavoriteColorPacket(selectedColor.getSerializedName());
                packet.sendToServer();
                this.onClose();
            }
        }).tone(StyledButton.Tone.PRIMARY).pixelLabel();

        // Disabled until a colour is chosen; a rebuild keeps the choice
        doneButton.active = selectedColor != null;
        this.addRenderableWidget(doneButton);

        // Initialize button transforms
        updateButtonTransforms();
        if (!showFigureInBox) {
            updateFigureVisibility();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.minecraft != null && MenuGuiScale.reapply(this.minecraft, this)) {
            rebuildWidgets();
        }
    }

    /**
     * Update all color buttons with current transformation values
     */
    private void updateButtonTransforms() {
        for (ColorSelectionButton button : colorButtons) {
            button.setTransforms(rotationX, rotationY, rotationZ, scale,
                    offsetX * previewFit, offsetY * previewFit, offsetZ * previewFit);
        }
    }

    /**
     * Update all color buttons with current figure visibility setting
     */
    private void updateFigureVisibility() {
        for (ColorSelectionButton button : colorButtons) {
            button.setShowFigure(showFigureInBox);
        }
    }

    /**
     * Called when a color is selected
     */
    public void setSelectedColor(PopBlockColor color) {
        this.selectedColor = color;
        this.doneButton.active = true; // Enable Done button

        // Update button visual states
        for (ColorSelectionButton button : colorButtons) {
            button.setSelected(button.getColor() == color);
        }

        LOGGER.debug("Selected color: {}", color.getSerializedName());
    }

    @Override
    public void removed() {
        super.removed();
        // Put back the scale of the player's options
        if (this.minecraft != null) {
            MenuGuiScale.restore(this.minecraft);
        }
    }

    //? if >=1.21 {
    /*@Override
    public void renderBackground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // This screen draws its own opaque background. The vanilla pass would
        // blur those stars when Screen.render draws the widgets in 1.21 to 1.21.5.
    }
    *///? }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Render animated starry background
        renderBackgroundEffects(graphics, partialTick);

        // The dark backdrop behind the tiles, as Quick Skin's import zone
        Surfaces.backdrop(graphics, backdropLeft, backdropTop, backdropRight, backdropBottom, BACKDROP_ALPHA);

        // Render widgets (buttons)
        super.render(graphics, mouseX, mouseY, partialTick);

        // Render title and description on top
        renderTitleAndDescription(graphics);
    }

    /**
     * Render animated starry background
     */
    private void renderBackgroundEffects(GuiGraphics graphics, float partialTick) {
        // 1. Fill with black background as a base layer
        // Modified to force default black background (ignoring ClientConfig which may carry over
        // colors from the Claw Machine/Collection screen).
        int bgColor = 0xFF000000;
        graphics.fill(0, 0, this.width, this.height, bgColor);

        //? if <1.21.5 {
        // Finish buffered base fills before the immediate textured quad draws.
        GuiPose.flush(graphics);
        //? }

        // 2. Render the moving star pattern, in the star colour and opacity of the client config
        CollectionSelectionScreen.drawStarPattern(graphics, this.width, this.height, partialTick);

        // 3. Close the background layer so the panel and its text draw above it
        GuiPose.nextStratum(graphics);
    }

    /**
     * Render the title and description text
     */
    private void renderTitleAndDescription(GuiGraphics graphics) {
        int centerX = this.width / 2;
        PixelFont.drawCentered(graphics, this.title, centerX, titleMiddleY, this.width - 16,
                titleScale, Surfaces.TEXT, true);

        // Description (2 lines)
        UiText.drawCentered(graphics, this.font, Component.translatable(DESCRIPTION_1).getString(), centerX, descriptionY, Surfaces.TEXT_DIM);
        UiText.drawCentered(graphics, this.font, Component.translatable(DESCRIPTION_2).getString(), centerX,
                descriptionY + this.font.lineHeight + 2, Surfaces.TEXT_DIM);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // Prevent closing with ESC - player must make a choice
        return false;
    }

}
