package com.theplumteam.client.gui;

import com.theplumteam.client.QuickSkinClient;
import com.mojang.authlib.GameProfile;
//? if >=1.21 {
/*import com.theplumteam.client.ClientSkinRegistration;
*///? }
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.blockentity.FigureBlockEntity;
import com.theplumteam.client.gui.style.DialogFrame;
import com.theplumteam.client.gui.style.DialogScreen;
import com.theplumteam.client.gui.style.MenuGuiScale;
import com.theplumteam.client.gui.style.PixelFont;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.style.StyledButton;
import com.theplumteam.client.gui.style.StyledSlider;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.style.UiText;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.network.FigurePositionPacket;
import com.theplumteam.util.PlayerSkins;
import com.theplumteam.util.SkinModelDetector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-platform screen for adjusting figure position, hitbox, and logo settings.
 * Used in dev mode only.
 *
 * <p>Opened on its own from a block in the world, it is a dialog in the menus' style over the
 * world, at a menu's own scale ({@link MenuGuiScale}) so its columns keep their room whatever GUI
 * Scale the player picked: a column of sliders each for the figure, the hitbox and the logo, with
 * fine-tune buttons beside each slider, and the copy, reset and Done buttons along the bottom.
 */
public class FigurePositionScreen extends DialogScreen implements MenuGuiScale.Scaled {
    private static final Logger LOGGER = LoggerFactory.getLogger(FigurePositionScreen.class);

    private static final int PADDING = DialogFrame.PADDING;
    private static final int ROW_HEIGHT = 24;
    private static final int CONTROL_HEIGHT = 20;
    private static final int FINE_WIDTH = 16;
    private static final int FINE_GAP = 2;
    private static final int COLUMN_GAP = 10;
    private static final int PREFERRED_COLUMN_WIDTH = 200;
    /** The most sliders in one column: the hitbox's offsets and scales. */
    private static final int SLIDER_ROWS = 6;
    private static final int SKIN_LINE_HEIGHT = 14;
    private static final int SECTION_HEIGHT = 14;
    private static final int FOOTER_HEIGHT = 22;
    private static final int FOOTER_GAP = 6;
    /** Narrower than this, the footer's buttons take two rows. */
    private static final int MIN_FOOTER_BUTTON_WIDTH = 84;

    // Layout, from the dialog's bounds
    private int innerLeft;
    private int innerWidth;
    private int columnWidth;
    private int sectionTop;
    private int footerY;

    // Cache for UI skin lookups
    private static final Map<UUID, GameProfile> profileCache = new ConcurrentHashMap<>();
    private static final Set<UUID> registeredSkins = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final BlockPos blockPos;
    private double offsetX;
    private double offsetY;
    private double offsetZ;
    private double scale;
    private double hitboxOffsetX;
    private double hitboxOffsetY;
    private double hitboxOffsetZ;
    private double hitboxScaleX;
    private double hitboxScaleY;
    private double hitboxScaleZ;
    private Double logoPositionX;
    private Double logoPositionY;
    private Double logoPositionZ;
    private Double logoScaleX;
    private Double logoScaleY;
    private Double logoScaleZ;
    // A plush block has no box around it: no logo, and its resting placement is all zeros.
    private final boolean plush;

    /** The screen for a plush block: the figure and hitbox columns, without the logo. */
    public static FigurePositionScreen forPlush(BlockPos blockPos, double offsetX, double offsetY, double offsetZ, double scale,
                                                double hitboxOffsetX, double hitboxOffsetY, double hitboxOffsetZ,
                                                double hitboxScaleX, double hitboxScaleY, double hitboxScaleZ) {
        return new FigurePositionScreen(blockPos, offsetX, offsetY, offsetZ, scale,
                hitboxOffsetX, hitboxOffsetY, hitboxOffsetZ, hitboxScaleX, hitboxScaleY, hitboxScaleZ,
                null, null, null, null, null, null, true);
    }

    public FigurePositionScreen(BlockPos blockPos, double offsetX, double offsetY, double offsetZ, double scale,
                                double hitboxOffsetX, double hitboxOffsetY, double hitboxOffsetZ,
                                double hitboxScaleX, double hitboxScaleY, double hitboxScaleZ,
                                Double logoPositionX, Double logoPositionY, Double logoPositionZ,
                                Double logoScaleX, Double logoScaleY, Double logoScaleZ) {
        this(blockPos, offsetX, offsetY, offsetZ, scale,
                hitboxOffsetX, hitboxOffsetY, hitboxOffsetZ, hitboxScaleX, hitboxScaleY, hitboxScaleZ,
                logoPositionX, logoPositionY, logoPositionZ, logoScaleX, logoScaleY, logoScaleZ, false);
    }

    private FigurePositionScreen(BlockPos blockPos, double offsetX, double offsetY, double offsetZ, double scale,
                                 double hitboxOffsetX, double hitboxOffsetY, double hitboxOffsetZ,
                                 double hitboxScaleX, double hitboxScaleY, double hitboxScaleZ,
                                 Double logoPositionX, Double logoPositionY, Double logoPositionZ,
                                 Double logoScaleX, Double logoScaleY, Double logoScaleZ, boolean plush) {
        super(Component.literal(plush ? "Adjust Plush & Hitbox" : "Adjust Figure, Hitbox & Logo"), null);
        this.plush = plush;
        this.blockPos = blockPos;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.scale = scale;
        this.hitboxOffsetX = hitboxOffsetX;
        this.hitboxOffsetY = hitboxOffsetY;
        this.hitboxOffsetZ = hitboxOffsetZ;
        this.hitboxScaleX = hitboxScaleX;
        this.hitboxScaleY = hitboxScaleY;
        this.hitboxScaleZ = hitboxScaleZ;
        this.logoPositionX = logoPositionX;
        this.logoPositionY = logoPositionY;
        this.logoPositionZ = logoPositionZ;
        this.logoScaleX = logoScaleX;
        this.logoScaleY = logoScaleY;
        this.logoScaleZ = logoScaleZ;
    }

    @Override
    protected DialogFrame.Accent accent() {
        return DialogFrame.Accent.EDIT;
    }

    @Override
    protected String[] icon() {
        return PixelIcon.WRENCH;
    }

    private int columns() {
        return plush ? 2 : 3;
    }

    @Override
    protected int preferredWidth() {
        return columns() * PREFERRED_COLUMN_WIDTH + (columns() - 1) * COLUMN_GAP + 2 * PADDING;
    }

    @Override
    protected int preferredHeight() {
        int width = Math.min(preferredWidth(), this.width - 16);
        int footerRows = footerRows(width - 2 * PADDING);
        return DialogFrame.HEADER_HEIGHT + PADDING + (plush ? 0 : SKIN_LINE_HEIGHT) + SECTION_HEIGHT
                + SLIDER_ROWS * ROW_HEIGHT + FOOTER_GAP + footerRows * (FOOTER_HEIGHT + 4) - 4
                + PADDING + 2 + Surfaces.LIP_HEIGHT;
    }

    /** The footer's buttons: the copies, Reset All and Done. */
    private int footerCount() {
        return (plush ? 2 : 3) + 2;
    }

    /** One row of footer buttons when they all keep their room in it, else two. */
    private int footerRows(int innerWidth) {
        int count = footerCount();
        return (innerWidth - (count - 1) * FOOTER_GAP) / count >= MIN_FOOTER_BUTTON_WIDTH ? 1 : 2;
    }

    /** A footer button in the given slot of a row of {@code count} buttons spread over the dialog. */
    private StyledButton footerButton(int slot, int row, int count, String label, Button.OnPress onPress) {
        int width = (innerWidth - (count - 1) * FOOTER_GAP) / count;
        int x = innerLeft + slot * (width + FOOTER_GAP);
        // The last button takes the rounding, so the row ends at the dialog's padding
        if (slot == count - 1) {
            width = innerLeft + innerWidth - x;
        }
        return new StyledButton(x, footerY + row * (FOOTER_HEIGHT + 4), width, FOOTER_HEIGHT,
                Component.literal(label), onPress);
    }

    private void addFineTuneButtons(int x, int y, Runnable decrementAction, Runnable incrementAction) {
        this.addRenderableWidget(new StyledButton(x, y, FINE_WIDTH, CONTROL_HEIGHT, Component.literal("-"), button -> {
            decrementAction.run();
            this.rebuildWidgets();
        }).lipHeight(2));
        this.addRenderableWidget(new StyledButton(x + FINE_WIDTH + FINE_GAP, y, FINE_WIDTH, CONTROL_HEIGHT,
                Component.literal("+"), button -> {
            incrementAction.run();
            this.rebuildWidgets();
        }).lipHeight(2));
    }

    @Override
    protected void init() {
        // A menu's scale first: the dialog sizes itself from the canvas
        if (this.minecraft != null) {
            MenuGuiScale.enter(this.minecraft, this);
        }
        super.init();
    }

    @Override
    public void removed() {
        super.removed();
        // Put back the scale of the player's options
        if (this.minecraft != null) {
            MenuGuiScale.restore(this.minecraft);
        }
    }

    @Override
    protected void initDialog() {
        this.innerLeft = dialogX + PADDING;
        this.innerWidth = dialogWidth - 2 * PADDING;
        this.columnWidth = (innerWidth - (columns() - 1) * COLUMN_GAP) / columns();
        this.sectionTop = bodyTop() + PADDING + (plush ? 0 : SKIN_LINE_HEIGHT);
        int startY = sectionTop + SECTION_HEIGHT;
        int sliderWidth = columnWidth - 2 * (FINE_WIDTH + FINE_GAP);

        int col1X = innerLeft;
        int col2X = col1X + columnWidth + COLUMN_GAP;
        int col3X = col2X + columnWidth + COLUMN_GAP;

        // === COLUMN 1: FIGURE CONTROLS ===
        this.addRenderableWidget(new StyledSlider(col1X, startY, sliderWidth, CONTROL_HEIGHT,
                Component.literal("X Offset: " + String.format("%.2f", offsetX)), (offsetX + 1.0) / 2.0) {
            @Override protected void updateMessage() { offsetX = (this.value * 2.0) - 1.0; this.setMessage(Component.literal("X Offset: " + String.format("%.2f", offsetX))); sendUpdate(); }
            @Override protected void applyValue() { offsetX = (this.value * 2.0) - 1.0; sendUpdate(); }
        });
        addFineTuneButtons(col1X + sliderWidth + FINE_GAP, startY, () -> { offsetX = Math.max(-1.0, offsetX - 0.001); sendUpdate(); }, () -> { offsetX = Math.min(1.0, offsetX + 0.001); sendUpdate(); });

        this.addRenderableWidget(new StyledSlider(col1X, startY + ROW_HEIGHT, sliderWidth, CONTROL_HEIGHT,
                Component.literal("Y Offset: " + String.format("%.2f", offsetY)), (offsetY + 1.0) / 2.0) {
            @Override protected void updateMessage() { offsetY = (this.value * 2.0) - 1.0; this.setMessage(Component.literal("Y Offset: " + String.format("%.2f", offsetY))); sendUpdate(); }
            @Override protected void applyValue() { offsetY = (this.value * 2.0) - 1.0; sendUpdate(); }
        });
        addFineTuneButtons(col1X + sliderWidth + FINE_GAP, startY + ROW_HEIGHT, () -> { offsetY = Math.max(-1.0, offsetY - 0.001); sendUpdate(); }, () -> { offsetY = Math.min(1.0, offsetY + 0.001); sendUpdate(); });

        this.addRenderableWidget(new StyledSlider(col1X, startY + 2 * ROW_HEIGHT, sliderWidth, CONTROL_HEIGHT,
                Component.literal("Z Offset: " + String.format("%.2f", offsetZ)), (offsetZ + 1.0) / 2.0) {
            @Override protected void updateMessage() { offsetZ = (this.value * 2.0) - 1.0; this.setMessage(Component.literal("Z Offset: " + String.format("%.2f", offsetZ))); sendUpdate(); }
            @Override protected void applyValue() { offsetZ = (this.value * 2.0) - 1.0; sendUpdate(); }
        });
        addFineTuneButtons(col1X + sliderWidth + FINE_GAP, startY + 2 * ROW_HEIGHT, () -> { offsetZ = Math.max(-1.0, offsetZ - 0.001); sendUpdate(); }, () -> { offsetZ = Math.min(1.0, offsetZ + 0.001); sendUpdate(); });

        this.addRenderableWidget(new StyledSlider(col1X, startY + 3 * ROW_HEIGHT, sliderWidth, CONTROL_HEIGHT,
                Component.literal("Scale: " + String.format("%.2f", scale)), (scale - 0.1) / 1.9) {
            @Override protected void updateMessage() { scale = 0.1 + (this.value * 1.9); this.setMessage(Component.literal("Scale: " + String.format("%.2f", scale))); sendUpdate(); }
            @Override protected void applyValue() { scale = 0.1 + (this.value * 1.9); sendUpdate(); }
        });
        addFineTuneButtons(col1X + sliderWidth + FINE_GAP, startY + 3 * ROW_HEIGHT, () -> { scale = Math.max(0.1, scale - 0.001); sendUpdate(); }, () -> { scale = Math.min(2.0, scale + 0.001); sendUpdate(); });

        // === COLUMN 2: HITBOX CONTROLS ===
        this.addRenderableWidget(new StyledSlider(col2X, startY, sliderWidth, CONTROL_HEIGHT,
                Component.literal("Hitbox X: " + String.format("%.2f", hitboxOffsetX)), (hitboxOffsetX + 1.0) / 2.0) {
            @Override protected void updateMessage() { hitboxOffsetX = (this.value * 2.0) - 1.0; this.setMessage(Component.literal("Hitbox X: " + String.format("%.2f", hitboxOffsetX))); sendUpdate(); }
            @Override protected void applyValue() { hitboxOffsetX = (this.value * 2.0) - 1.0; sendUpdate(); }
        });
        addFineTuneButtons(col2X + sliderWidth + FINE_GAP, startY, () -> { hitboxOffsetX = Math.max(-1.0, hitboxOffsetX - 0.001); sendUpdate(); }, () -> { hitboxOffsetX = Math.min(1.0, hitboxOffsetX + 0.001); sendUpdate(); });

        this.addRenderableWidget(new StyledSlider(col2X, startY + ROW_HEIGHT, sliderWidth, CONTROL_HEIGHT,
                Component.literal("Hitbox Y: " + String.format("%.2f", hitboxOffsetY)), (hitboxOffsetY + 1.0) / 2.0) {
            @Override protected void updateMessage() { hitboxOffsetY = (this.value * 2.0) - 1.0; this.setMessage(Component.literal("Hitbox Y: " + String.format("%.2f", hitboxOffsetY))); sendUpdate(); }
            @Override protected void applyValue() { hitboxOffsetY = (this.value * 2.0) - 1.0; sendUpdate(); }
        });
        addFineTuneButtons(col2X + sliderWidth + FINE_GAP, startY + ROW_HEIGHT, () -> { hitboxOffsetY = Math.max(-1.0, hitboxOffsetY - 0.001); sendUpdate(); }, () -> { hitboxOffsetY = Math.min(1.0, hitboxOffsetY + 0.001); sendUpdate(); });

        this.addRenderableWidget(new StyledSlider(col2X, startY + 2 * ROW_HEIGHT, sliderWidth, CONTROL_HEIGHT,
                Component.literal("Hitbox Z: " + String.format("%.2f", hitboxOffsetZ)), (hitboxOffsetZ + 1.0) / 2.0) {
            @Override protected void updateMessage() { hitboxOffsetZ = (this.value * 2.0) - 1.0; this.setMessage(Component.literal("Hitbox Z: " + String.format("%.2f", hitboxOffsetZ))); sendUpdate(); }
            @Override protected void applyValue() { hitboxOffsetZ = (this.value * 2.0) - 1.0; sendUpdate(); }
        });
        addFineTuneButtons(col2X + sliderWidth + FINE_GAP, startY + 2 * ROW_HEIGHT, () -> { hitboxOffsetZ = Math.max(-1.0, hitboxOffsetZ - 0.001); sendUpdate(); }, () -> { hitboxOffsetZ = Math.min(1.0, hitboxOffsetZ + 0.001); sendUpdate(); });

        this.addRenderableWidget(new StyledSlider(col2X, startY + 3 * ROW_HEIGHT, sliderWidth, CONTROL_HEIGHT,
                Component.literal("Hitbox Scale X: " + String.format("%.2f", hitboxScaleX)), (hitboxScaleX - 0.5) / 1.5) {
            @Override protected void updateMessage() { hitboxScaleX = 0.5 + (this.value * 1.5); this.setMessage(Component.literal("Hitbox Scale X: " + String.format("%.2f", hitboxScaleX))); sendUpdate(); }
            @Override protected void applyValue() { hitboxScaleX = 0.5 + (this.value * 1.5); sendUpdate(); }
        });
        addFineTuneButtons(col2X + sliderWidth + FINE_GAP, startY + 3 * ROW_HEIGHT, () -> { hitboxScaleX = Math.max(0.5, hitboxScaleX - 0.01); sendUpdate(); }, () -> { hitboxScaleX = Math.min(2.0, hitboxScaleX + 0.01); sendUpdate(); });

        this.addRenderableWidget(new StyledSlider(col2X, startY + 4 * ROW_HEIGHT, sliderWidth, CONTROL_HEIGHT,
                Component.literal("Hitbox Scale Y: " + String.format("%.2f", hitboxScaleY)), (hitboxScaleY - 0.5) / 1.5) {
            @Override protected void updateMessage() { hitboxScaleY = 0.5 + (this.value * 1.5); this.setMessage(Component.literal("Hitbox Scale Y: " + String.format("%.2f", hitboxScaleY))); sendUpdate(); }
            @Override protected void applyValue() { hitboxScaleY = 0.5 + (this.value * 1.5); sendUpdate(); }
        });
        addFineTuneButtons(col2X + sliderWidth + FINE_GAP, startY + 4 * ROW_HEIGHT, () -> { hitboxScaleY = Math.max(0.5, hitboxScaleY - 0.01); sendUpdate(); }, () -> { hitboxScaleY = Math.min(2.0, hitboxScaleY + 0.01); sendUpdate(); });

        this.addRenderableWidget(new StyledSlider(col2X, startY + 5 * ROW_HEIGHT, sliderWidth, CONTROL_HEIGHT,
                Component.literal("Hitbox Scale Z: " + String.format("%.2f", hitboxScaleZ)), (hitboxScaleZ - 0.5) / 1.5) {
            @Override protected void updateMessage() { hitboxScaleZ = 0.5 + (this.value * 1.5); this.setMessage(Component.literal("Hitbox Scale Z: " + String.format("%.2f", hitboxScaleZ))); sendUpdate(); }
            @Override protected void applyValue() { hitboxScaleZ = 0.5 + (this.value * 1.5); sendUpdate(); }
        });
        addFineTuneButtons(col2X + sliderWidth + FINE_GAP, startY + 5 * ROW_HEIGHT, () -> { hitboxScaleZ = Math.max(0.5, hitboxScaleZ - 0.01); sendUpdate(); }, () -> { hitboxScaleZ = Math.min(2.0, hitboxScaleZ + 0.01); sendUpdate(); });

        // === COLUMN 3: LOGO CONTROLS ===
        if (!plush) {
            double logoX = logoPositionX != null ? logoPositionX : 0.0;
            this.addRenderableWidget(new StyledSlider(col3X, startY, sliderWidth, CONTROL_HEIGHT,
                    Component.literal("Logo X (Depth): " + String.format("%.2f", logoX)), (logoX + 10.0) / 20.0) {
                @Override protected void updateMessage() { logoPositionX = (this.value * 20.0) - 10.0; this.setMessage(Component.literal("Logo X (Depth): " + String.format("%.2f", logoPositionX))); sendUpdate(); }
                @Override protected void applyValue() { logoPositionX = (this.value * 20.0) - 10.0; sendUpdate(); }
            });
            addFineTuneButtons(col3X + sliderWidth + FINE_GAP, startY, () -> { logoPositionX = Math.max(-10.0, (logoPositionX != null ? logoPositionX : 0.0) - 0.001); sendUpdate(); }, () -> { logoPositionX = Math.min(10.0, (logoPositionX != null ? logoPositionX : 0.0) + 0.001); sendUpdate(); });

            double logoY = logoPositionY != null ? logoPositionY : 0.0;
            this.addRenderableWidget(new StyledSlider(col3X, startY + ROW_HEIGHT, sliderWidth, CONTROL_HEIGHT,
                    Component.literal("Logo Y (Vertical): " + String.format("%.2f", logoY)), (logoY + 10.0) / 20.0) {
                @Override protected void updateMessage() { logoPositionY = (this.value * 20.0) - 10.0; this.setMessage(Component.literal("Logo Y (Vertical): " + String.format("%.2f", logoPositionY))); sendUpdate(); }
                @Override protected void applyValue() { logoPositionY = (this.value * 20.0) - 10.0; sendUpdate(); }
            });
            addFineTuneButtons(col3X + sliderWidth + FINE_GAP, startY + ROW_HEIGHT, () -> { logoPositionY = Math.max(-10.0, (logoPositionY != null ? logoPositionY : 0.0) - 0.001); sendUpdate(); }, () -> { logoPositionY = Math.min(10.0, (logoPositionY != null ? logoPositionY : 0.0) + 0.001); sendUpdate(); });

            double logoZ = logoPositionZ != null ? logoPositionZ : 0.0;
            this.addRenderableWidget(new StyledSlider(col3X, startY + 2 * ROW_HEIGHT, sliderWidth, CONTROL_HEIGHT,
                    Component.literal("Logo Z (Horizontal): " + String.format("%.2f", logoZ)), (logoZ + 10.0) / 20.0) {
                @Override protected void updateMessage() { logoPositionZ = (this.value * 20.0) - 10.0; this.setMessage(Component.literal("Logo Z (Horizontal): " + String.format("%.2f", logoPositionZ))); sendUpdate(); }
                @Override protected void applyValue() { logoPositionZ = (this.value * 20.0) - 10.0; sendUpdate(); }
            });
            addFineTuneButtons(col3X + sliderWidth + FINE_GAP, startY + 2 * ROW_HEIGHT, () -> { logoPositionZ = Math.max(-10.0, (logoPositionZ != null ? logoPositionZ : 0.0) - 0.001); sendUpdate(); }, () -> { logoPositionZ = Math.min(10.0, (logoPositionZ != null ? logoPositionZ : 0.0) + 0.001); sendUpdate(); });

            double scaleX = logoScaleX != null ? logoScaleX : 5.0;
            this.addRenderableWidget(new StyledSlider(col3X, startY + 3 * ROW_HEIGHT, sliderWidth, CONTROL_HEIGHT,
                    Component.literal("Logo Width (X): " + String.format("%.2f", scaleX)), (scaleX - 0.5) / 9.5) {
                @Override protected void updateMessage() { logoScaleX = 0.5 + (this.value * 9.5); this.setMessage(Component.literal("Logo Width (X): " + String.format("%.2f", logoScaleX))); sendUpdate(); }
                @Override protected void applyValue() { logoScaleX = 0.5 + (this.value * 9.5); sendUpdate(); }
            });
            addFineTuneButtons(col3X + sliderWidth + FINE_GAP, startY + 3 * ROW_HEIGHT, () -> { logoScaleX = Math.max(0.5, (logoScaleX != null ? logoScaleX : 5.0) - 0.001); sendUpdate(); }, () -> { logoScaleX = Math.min(10.0, (logoScaleX != null ? logoScaleX : 5.0) + 0.001); sendUpdate(); });

            double scaleY = logoScaleY != null ? logoScaleY : 5.0;
            this.addRenderableWidget(new StyledSlider(col3X, startY + 4 * ROW_HEIGHT, sliderWidth, CONTROL_HEIGHT,
                    Component.literal("Logo Height (Y): " + String.format("%.2f", scaleY)), (scaleY - 0.5) / 9.5) {
                @Override protected void updateMessage() { logoScaleY = 0.5 + (this.value * 9.5); this.setMessage(Component.literal("Logo Height (Y): " + String.format("%.2f", logoScaleY))); sendUpdate(); }
                @Override protected void applyValue() { logoScaleY = 0.5 + (this.value * 9.5); sendUpdate(); }
            });
            addFineTuneButtons(col3X + sliderWidth + FINE_GAP, startY + 4 * ROW_HEIGHT, () -> { logoScaleY = Math.max(0.5, (logoScaleY != null ? logoScaleY : 5.0) - 0.001); sendUpdate(); }, () -> { logoScaleY = Math.min(10.0, (logoScaleY != null ? logoScaleY : 5.0) + 0.001); sendUpdate(); });

            double scaleZ = logoScaleZ != null ? logoScaleZ : 1.0;
            this.addRenderableWidget(new StyledSlider(col3X, startY + 5 * ROW_HEIGHT, sliderWidth, CONTROL_HEIGHT,
                    Component.literal("Logo Depth (Z): " + String.format("%.2f", scaleZ)), (scaleZ - 0.5) / 9.5) {
                @Override protected void updateMessage() { logoScaleZ = 0.5 + (this.value * 9.5); this.setMessage(Component.literal("Logo Depth (Z): " + String.format("%.2f", logoScaleZ))); sendUpdate(); }
                @Override protected void applyValue() { logoScaleZ = 0.5 + (this.value * 9.5); sendUpdate(); }
            });
            addFineTuneButtons(col3X + sliderWidth + FINE_GAP, startY + 5 * ROW_HEIGHT, () -> { logoScaleZ = Math.max(0.5, (logoScaleZ != null ? logoScaleZ : 1.0) - 0.001); sendUpdate(); }, () -> { logoScaleZ = Math.min(10.0, (logoScaleZ != null ? logoScaleZ : 1.0) + 0.001); sendUpdate(); });
        }

        // === FOOTER: COPY, RESET AND DONE ===
        int footerRows = footerRows(innerWidth);
        this.footerY = bodyBottom() - PADDING - footerRows * (FOOTER_HEIGHT + 4) + 4;
        int copyCount = plush ? 2 : 3;
        int firstRowCount = footerRows == 1 ? footerCount() : copyCount;

        this.addRenderableWidget(footerButton(0, 0, firstRowCount, "Copy Figure", button -> {
            String data = String.format("Figure: X=%.3f Y=%.3f Z=%.3f Scale=%.3f", offsetX, offsetY, offsetZ, scale);
            minecraft.keyboardHandler.setClipboard(data);
        }));

        this.addRenderableWidget(footerButton(1, 0, firstRowCount, "Copy Hitbox", button -> {
            String data = String.format("Hitbox: X=%.3f Y=%.3f Z=%.3f", hitboxOffsetX, hitboxOffsetY, hitboxOffsetZ);
            minecraft.keyboardHandler.setClipboard(data);
        }));

        if (!plush) this.addRenderableWidget(footerButton(2, 0, firstRowCount, "Copy Logo", button -> {
            String data = String.format("Logo: X=%.3f Y=%.3f Z=%.3f Width=%.3f Height=%.3f Depth=%.3f",
                    logoPositionX != null ? logoPositionX : 0.0, logoPositionY != null ? logoPositionY : 0.0, logoPositionZ != null ? logoPositionZ : 0.0,
                    logoScaleX != null ? logoScaleX : 5.0, logoScaleY != null ? logoScaleY : 5.0, logoScaleZ != null ? logoScaleZ : 1.0);
            minecraft.keyboardHandler.setClipboard(data);
        }));

        // Reset All and Done end the first row, or share the second
        int resetSlot = footerRows == 1 ? copyCount : 0;
        int resetRow = footerRows == 1 ? 0 : 1;
        int resetRowCount = footerRows == 1 ? firstRowCount : 2;
        this.addRenderableWidget(footerButton(resetSlot, resetRow, resetRowCount, "Reset All", button -> {
            offsetX = 0.0; offsetY = plush ? 0.0 : 0.1; offsetZ = 0.0; scale = 1.0;
            hitboxOffsetX = plush ? 0.0 : -0.03; hitboxOffsetY = 0.0; hitboxOffsetZ = plush ? 0.0 : -0.06;
            if (plush) { hitboxScaleX = 1.0; hitboxScaleY = 1.0; hitboxScaleZ = 1.0; }
            logoPositionX = null; logoPositionY = null; logoPositionZ = null;
            logoScaleX = null; logoScaleY = null; logoScaleZ = null;
            this.rebuildWidgets();
            sendUpdate();
        }).tone(StyledButton.Tone.DANGER));

        this.addRenderableWidget(footerButton(resetSlot + 1, resetRow, resetRowCount, "Done", button -> this.onClose())
                .tone(StyledButton.Tone.PRIMARY).pixelLabel().labelScale(DialogFrame.BUTTON_LABEL_SCALE));
    }

    @Override
    protected void renderDialog(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Detect and display skin model type
        String skinTypeText = "Skin Type: Unknown";
        int skinTypeColor = 0xFFAAAAAA;

        if (minecraft != null && minecraft.level != null) {
            BlockEntity blockEntity = minecraft.level.getBlockEntity(blockPos);
            FigureDefinition figureDefinition = null;
            int skinIndex = 0;

            if (blockEntity instanceof BoxBlockEntity boxBlockEntity && boxBlockEntity.hasFigure()) {
                figureDefinition = boxBlockEntity.getFigureDefinition();
                skinIndex = boxBlockEntity.getAlternativeSkinIndex();
            } else if (blockEntity instanceof FigureBlockEntity figureBlockEntity && figureBlockEntity.hasFigure()) {
                figureDefinition = figureBlockEntity.getFigureDefinition();
                skinIndex = figureBlockEntity.getAlternativeSkinIndex();
            }

            if (figureDefinition != null) {
                try {
                    ResourceLocation texture = getTextureForFigure(figureDefinition, skinIndex);
                    if (texture != null) {
                        SkinModelDetector.SkinModel skinModel = SkinModelDetector.detectSkinModel(texture);
                        if (skinModel == SkinModelDetector.SkinModel.SLIM) {
                            skinTypeText = "Skin Type: SLIM (Alex)";
                            skinTypeColor = 0xFFFF6B9D;
                        } else {
                            skinTypeText = "Skin Type: CLASSIC (Steve)";
                            skinTypeColor = 0xFF5DADE2;
                        }
                    } else {
                        skinTypeText = "Skin Type: No Texture";
                    }
                } catch (Exception e) {
                    skinTypeText = "Skin Type: Detection Error";
                    skinTypeColor = 0xFFFF0000;
                }
            } else {
                skinTypeText = "Skin Type: No Figure";
            }
        }

        int centerX = dialogX + dialogWidth / 2;
        if (!plush) UiText.drawCentered(graphics, this.font, skinTypeText, centerX, bodyTop() + PADDING, skinTypeColor);

        // Each column's name in the pixel font, centred over its sliders and fine-tune buttons
        float headerMiddle = sectionTop + PixelFont.CAPITALS_HEIGHT / 2.0f;
        drawColumnHeader(graphics, 0, plush ? "PLUSH" : "FIGURE", headerMiddle, 0xFFFFD700);
        drawColumnHeader(graphics, 1, "HITBOX", headerMiddle, 0xFF00FF00);
        if (!plush) drawColumnHeader(graphics, 2, "LOGO", headerMiddle, 0xFF00BFFF);
    }

    private void drawColumnHeader(GuiGraphics graphics, int column, String label, float middleY, int color) {
        int left = innerLeft + column * (columnWidth + COLUMN_GAP);
        PixelFont.drawCentered(graphics, Component.literal(label), left + columnWidth / 2, middleY, columnWidth,
                1.0f, color, true);
    }

    private ResourceLocation getTextureForFigure(FigureDefinition figure, int skinIndex) {
        if (figure == null) return null;

        if (skinIndex > 0 && figure.hasAlternatives()) {
            int altListIndex = skinIndex - 1;
            if (altListIndex < figure.getAlternatives().size()) {
                return figure.getAlternatives().get(altListIndex).texture();
            }
        }

        if (figure.getType() == com.theplumteam.figure.FigureType.PLAYER && figure.getPlayerUUID() != null) {
            ResourceLocation quickSkinLoc = QuickSkinClient.playerSkin(figure.getPlayerUUID());
            if (quickSkinLoc != null) return quickSkinLoc;

            GameProfile profile = profileCache.computeIfAbsent(figure.getPlayerUUID(), uuid -> new GameProfile(uuid, figure.getName()));
            if (registeredSkins.add(figure.getPlayerUUID())) {
                //? if >=1.21.9 {
                /*// The populated profile is a new one there, so it replaces the cached one.
                ClientSkinRegistration.register(profile,
                        prepared -> profileCache.put(figure.getPlayerUUID(), prepared));
                *///? } elif >=1.21 {
                /*ClientSkinRegistration.register(profile);
                *///? } else {
                Minecraft.getInstance().getSkinManager().registerSkins(profile, (type, location, p) -> {}, false);
                //? }
            }
            return PlayerSkins.insecureTexture(profile);
        }

        return figure.getTexturePath();
    }

    private void sendUpdate() {
        // Use cross-platform networking
        new FigurePositionPacket(blockPos, offsetX, offsetY, offsetZ, scale,
                hitboxOffsetX, hitboxOffsetY, hitboxOffsetZ,
                hitboxScaleX, hitboxScaleY, hitboxScaleZ,
                logoPositionX, logoPositionY, logoPositionZ,
                logoScaleX, logoScaleY, logoScaleZ).sendToServer();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
