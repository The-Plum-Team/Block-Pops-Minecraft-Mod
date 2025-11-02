package com.theplumteam.client.gui;

import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.blockentity.FigureBlockEntity;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.forge.BlockPopsModForge;
import com.theplumteam.network.FigurePositionPacket;
import com.theplumteam.util.SkinModelDetector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FigurePositionScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger(FigurePositionScreen.class);

    private final BlockPos blockPos;
    private double offsetX;
    private double offsetY;
    private double offsetZ;
    private double scale;
    private double hitboxOffsetX;
    private double hitboxOffsetY;
    private double hitboxOffsetZ;
    private Double logoPositionX;
    private Double logoPositionY;
    private Double logoPositionZ;
    private Double logoScaleX;
    private Double logoScaleY;
    private Double logoScaleZ;

    private AbstractSliderButton sliderX;
    private AbstractSliderButton sliderY;
    private AbstractSliderButton sliderZ;
    private AbstractSliderButton sliderScale;
    private AbstractSliderButton sliderHitboxX;
    private AbstractSliderButton sliderHitboxY;
    private AbstractSliderButton sliderHitboxZ;
    private AbstractSliderButton sliderLogoX;
    private AbstractSliderButton sliderLogoY;
    private AbstractSliderButton sliderLogoZ;
    private AbstractSliderButton sliderLogoScaleX;
    private AbstractSliderButton sliderLogoScaleY;
    private AbstractSliderButton sliderLogoScaleZ;

    public FigurePositionScreen(BlockPos blockPos, double offsetX, double offsetY, double offsetZ, double scale,
                                double hitboxOffsetX, double hitboxOffsetY, double hitboxOffsetZ,
                                Double logoPositionX, Double logoPositionY, Double logoPositionZ,
                                Double logoScaleX, Double logoScaleY, Double logoScaleZ) {
        super(Component.literal("Adjust Figure, Hitbox & Logo"));
        this.blockPos = blockPos;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.scale = scale;
        this.hitboxOffsetX = hitboxOffsetX;
        this.hitboxOffsetY = hitboxOffsetY;
        this.hitboxOffsetZ = hitboxOffsetZ;
        this.logoPositionX = logoPositionX;
        this.logoPositionY = logoPositionY;
        this.logoPositionZ = logoPositionZ;
        this.logoScaleX = logoScaleX;
        this.logoScaleY = logoScaleY;
        this.logoScaleZ = logoScaleZ;
        LOGGER.info("FigurePositionScreen opened at {} with offsets: X={}, Y={}, Z={}, Scale={}, HitboxOffsets: X={}, Y={}, Z={}, LogoPos: X={}, Y={}, Z={}, LogoScale: X={}, Y={}, Z={}",
                    blockPos, offsetX, offsetY, offsetZ, scale, hitboxOffsetX, hitboxOffsetY, hitboxOffsetZ,
                    logoPositionX, logoPositionY, logoPositionZ, logoScaleX, logoScaleY, logoScaleZ);
    }

    // Helper method to add fine-tune buttons next to a slider
    private void addFineTuneButtons(int x, int y, Runnable decrementAction, Runnable incrementAction) {
        int buttonWidth = 18;
        int buttonX = x;

        // Minus button
        this.addRenderableWidget(Button.builder(Component.literal("-"), button -> {
            decrementAction.run();
            this.rebuildWidgets();
        }).bounds(buttonX, y, buttonWidth, 20).build());

        // Plus button
        this.addRenderableWidget(Button.builder(Component.literal("+"), button -> {
            incrementAction.run();
            this.rebuildWidgets();
        }).bounds(buttonX + buttonWidth + 2, y, buttonWidth, 20).build());
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int startY = 50; // Start Y position for content
        int sliderWidth = 140; // Slider width
        int columnSpacing = 200; // Space between columns

        // Calculate column X positions
        int col1X = centerX - columnSpacing - 70;
        int col2X = centerX - 70;
        int col3X = centerX + columnSpacing - 70;

        // === COLUMN 1: FIGURE CONTROLS ===
        // Section header is drawn in render() method

        // X Offset Slider (-1 to 1)
        this.sliderX = new AbstractSliderButton(col1X, startY, sliderWidth, 20,
                Component.literal("X Offset: " + String.format("%.2f", offsetX)),
                (offsetX + 1.0) / 2.0) {
            @Override
            protected void updateMessage() {
                offsetX = (this.value * 2.0) - 1.0;
                this.setMessage(Component.literal("X Offset: " + String.format("%.2f", offsetX)));
                sendUpdate(); // Update in real-time
            }

            @Override
            protected void applyValue() {
                offsetX = (this.value * 2.0) - 1.0;
                sendUpdate(); // Update in real-time
            }
        };
        this.addRenderableWidget(sliderX);
        addFineTuneButtons(col1X + sliderWidth + 2, startY,
            () -> { offsetX = Math.max(-1.0, offsetX - 0.001); sendUpdate(); },
            () -> { offsetX = Math.min(1.0, offsetX + 0.001); sendUpdate(); });

        // Y Offset Slider (-1 to 1)
        this.sliderY = new AbstractSliderButton(col1X, startY + 25, sliderWidth, 20,
                Component.literal("Y Offset: " + String.format("%.2f", offsetY)),
                (offsetY + 1.0) / 2.0) {
            @Override
            protected void updateMessage() {
                offsetY = (this.value * 2.0) - 1.0;
                this.setMessage(Component.literal("Y Offset: " + String.format("%.2f", offsetY)));
                sendUpdate(); // Update in real-time
            }

            @Override
            protected void applyValue() {
                offsetY = (this.value * 2.0) - 1.0;
                sendUpdate(); // Update in real-time
            }
        };
        this.addRenderableWidget(sliderY);
        addFineTuneButtons(col1X + sliderWidth + 2, startY + 25,
            () -> { offsetY = Math.max(-1.0, offsetY - 0.001); sendUpdate(); },
            () -> { offsetY = Math.min(1.0, offsetY + 0.001); sendUpdate(); });

        // Z Offset Slider (-1 to 1)
        this.sliderZ = new AbstractSliderButton(col1X, startY + 50, sliderWidth, 20,
                Component.literal("Z Offset: " + String.format("%.2f", offsetZ)),
                (offsetZ + 1.0) / 2.0) {
            @Override
            protected void updateMessage() {
                offsetZ = (this.value * 2.0) - 1.0;
                this.setMessage(Component.literal("Z Offset: " + String.format("%.2f", offsetZ)));
                sendUpdate(); // Update in real-time
            }

            @Override
            protected void applyValue() {
                offsetZ = (this.value * 2.0) - 1.0;
                sendUpdate(); // Update in real-time
            }
        };
        this.addRenderableWidget(sliderZ);
        addFineTuneButtons(col1X + sliderWidth + 2, startY + 50,
            () -> { offsetZ = Math.max(-1.0, offsetZ - 0.001); sendUpdate(); },
            () -> { offsetZ = Math.min(1.0, offsetZ + 0.001); sendUpdate(); });

        // Scale Slider (0.1 to 2.0)
        this.sliderScale = new AbstractSliderButton(col1X, startY + 75, sliderWidth, 20,
                Component.literal("Scale: " + String.format("%.2f", scale)),
                (scale - 0.1) / 1.9) {
            @Override
            protected void updateMessage() {
                scale = 0.1 + (this.value * 1.9);
                this.setMessage(Component.literal("Scale: " + String.format("%.2f", scale)));
                sendUpdate(); // Update in real-time
            }

            @Override
            protected void applyValue() {
                scale = 0.1 + (this.value * 1.9);
                sendUpdate(); // Update in real-time
            }
        };
        this.addRenderableWidget(sliderScale);
        addFineTuneButtons(col1X + sliderWidth + 2, startY + 75,
            () -> { scale = Math.max(0.1, scale - 0.001); sendUpdate(); },
            () -> { scale = Math.min(2.0, scale + 0.001); sendUpdate(); });

        // === COLUMN 2: HITBOX CONTROLS ===
        // Section header is drawn in render() method

        // Hitbox X Offset Slider (-1 to 1)
        this.sliderHitboxX = new AbstractSliderButton(col2X, startY, sliderWidth, 20,
                Component.literal("Hitbox X: " + String.format("%.2f", hitboxOffsetX)),
                (hitboxOffsetX + 1.0) / 2.0) {
            @Override
            protected void updateMessage() {
                hitboxOffsetX = (this.value * 2.0) - 1.0;
                this.setMessage(Component.literal("Hitbox X: " + String.format("%.2f", hitboxOffsetX)));
                sendUpdate();
            }

            @Override
            protected void applyValue() {
                hitboxOffsetX = (this.value * 2.0) - 1.0;
                sendUpdate();
            }
        };
        this.addRenderableWidget(sliderHitboxX);
        addFineTuneButtons(col2X + sliderWidth + 2, startY,
            () -> { hitboxOffsetX = Math.max(-1.0, hitboxOffsetX - 0.001); sendUpdate(); },
            () -> { hitboxOffsetX = Math.min(1.0, hitboxOffsetX + 0.001); sendUpdate(); });

        // Hitbox Y Offset Slider (-1 to 1)
        this.sliderHitboxY = new AbstractSliderButton(col2X, startY + 25, sliderWidth, 20,
                Component.literal("Hitbox Y: " + String.format("%.2f", hitboxOffsetY)),
                (hitboxOffsetY + 1.0) / 2.0) {
            @Override
            protected void updateMessage() {
                hitboxOffsetY = (this.value * 2.0) - 1.0;
                this.setMessage(Component.literal("Hitbox Y: " + String.format("%.2f", hitboxOffsetY)));
                sendUpdate();
            }

            @Override
            protected void applyValue() {
                hitboxOffsetY = (this.value * 2.0) - 1.0;
                sendUpdate();
            }
        };
        this.addRenderableWidget(sliderHitboxY);
        addFineTuneButtons(col2X + sliderWidth + 2, startY + 25,
            () -> { hitboxOffsetY = Math.max(-1.0, hitboxOffsetY - 0.001); sendUpdate(); },
            () -> { hitboxOffsetY = Math.min(1.0, hitboxOffsetY + 0.001); sendUpdate(); });

        // Hitbox Z Offset Slider (-1 to 1)
        this.sliderHitboxZ = new AbstractSliderButton(col2X, startY + 50, sliderWidth, 20,
                Component.literal("Hitbox Z: " + String.format("%.2f", hitboxOffsetZ)),
                (hitboxOffsetZ + 1.0) / 2.0) {
            @Override
            protected void updateMessage() {
                hitboxOffsetZ = (this.value * 2.0) - 1.0;
                this.setMessage(Component.literal("Hitbox Z: " + String.format("%.2f", hitboxOffsetZ)));
                sendUpdate();
            }

            @Override
            protected void applyValue() {
                hitboxOffsetZ = (this.value * 2.0) - 1.0;
                sendUpdate();
            }
        };
        this.addRenderableWidget(sliderHitboxZ);
        addFineTuneButtons(col2X + sliderWidth + 2, startY + 50,
            () -> { hitboxOffsetZ = Math.max(-1.0, hitboxOffsetZ - 0.001); sendUpdate(); },
            () -> { hitboxOffsetZ = Math.min(1.0, hitboxOffsetZ + 0.001); sendUpdate(); });

        // === COLUMN 3: LOGO CONTROLS ===
        // Section header is drawn in render() method

        // Logo X Position Slider (Depth: forward-back) (-10 to 10 model units)
        double logoX = logoPositionX != null ? logoPositionX : 0.0;
        this.sliderLogoX = new AbstractSliderButton(col3X, startY, sliderWidth, 20,
                Component.literal("Logo Depth: " + String.format("%.2f", logoX)),
                (logoX + 10.0) / 20.0) {
            @Override
            protected void updateMessage() {
                logoPositionX = (this.value * 20.0) - 10.0;
                this.setMessage(Component.literal("Logo Depth: " + String.format("%.2f", logoPositionX)));
                sendUpdate();
            }

            @Override
            protected void applyValue() {
                logoPositionX = (this.value * 20.0) - 10.0;
                sendUpdate();
            }
        };
        this.addRenderableWidget(sliderLogoX);
        addFineTuneButtons(col3X + sliderWidth + 2, startY,
            () -> { logoPositionX = Math.max(-10.0, (logoPositionX != null ? logoPositionX : 0.0) - 0.001); sendUpdate(); },
            () -> { logoPositionX = Math.min(10.0, (logoPositionX != null ? logoPositionX : 0.0) + 0.001); sendUpdate(); });

        // Logo Y Position Slider (Vertical: up-down) (-10 to 10 model units)
        double logoY = logoPositionY != null ? logoPositionY : 0.0;
        this.sliderLogoY = new AbstractSliderButton(col3X, startY + 25, sliderWidth, 20,
                Component.literal("Logo Vertical: " + String.format("%.2f", logoY)),
                (logoY + 10.0) / 20.0) {
            @Override
            protected void updateMessage() {
                logoPositionY = (this.value * 20.0) - 10.0;
                this.setMessage(Component.literal("Logo Vertical: " + String.format("%.2f", logoPositionY)));
                sendUpdate();
            }

            @Override
            protected void applyValue() {
                logoPositionY = (this.value * 20.0) - 10.0;
                sendUpdate();
            }
        };
        this.addRenderableWidget(sliderLogoY);
        addFineTuneButtons(col3X + sliderWidth + 2, startY + 25,
            () -> { logoPositionY = Math.max(-10.0, (logoPositionY != null ? logoPositionY : 0.0) - 0.001); sendUpdate(); },
            () -> { logoPositionY = Math.min(10.0, (logoPositionY != null ? logoPositionY : 0.0) + 0.001); sendUpdate(); });

        // Logo Z Position Slider (Horizontal: left-right) (-10 to 10 model units)
        double logoZ = logoPositionZ != null ? logoPositionZ : 0.0;
        this.sliderLogoZ = new AbstractSliderButton(col3X, startY + 50, sliderWidth, 20,
                Component.literal("Logo Horizontal: " + String.format("%.2f", logoZ)),
                (logoZ + 10.0) / 20.0) {
            @Override
            protected void updateMessage() {
                logoPositionZ = (this.value * 20.0) - 10.0;
                this.setMessage(Component.literal("Logo Horizontal: " + String.format("%.2f", logoPositionZ)));
                sendUpdate();
            }

            @Override
            protected void applyValue() {
                logoPositionZ = (this.value * 20.0) - 10.0;
                sendUpdate();
            }
        };
        this.addRenderableWidget(sliderLogoZ);
        addFineTuneButtons(col3X + sliderWidth + 2, startY + 50,
            () -> { logoPositionZ = Math.max(-10.0, (logoPositionZ != null ? logoPositionZ : 0.0) - 0.001); sendUpdate(); },
            () -> { logoPositionZ = Math.min(10.0, (logoPositionZ != null ? logoPositionZ : 0.0) + 0.001); sendUpdate(); });

        // Logo X Scale Slider (0.5 to 10)
        double scaleX = logoScaleX != null ? logoScaleX : 5.0;
        this.sliderLogoScaleX = new AbstractSliderButton(col3X, startY + 75, sliderWidth, 20,
                Component.literal("Logo Width: " + String.format("%.2f", scaleX)),
                (scaleX - 0.5) / 9.5) {
            @Override
            protected void updateMessage() {
                logoScaleX = 0.5 + (this.value * 9.5);
                this.setMessage(Component.literal("Logo Width: " + String.format("%.2f", logoScaleX)));
                sendUpdate();
            }

            @Override
            protected void applyValue() {
                logoScaleX = 0.5 + (this.value * 9.5);
                sendUpdate();
            }
        };
        this.addRenderableWidget(sliderLogoScaleX);
        addFineTuneButtons(col3X + sliderWidth + 2, startY + 75,
            () -> { logoScaleX = Math.max(0.5, (logoScaleX != null ? logoScaleX : 5.0) - 0.001); sendUpdate(); },
            () -> { logoScaleX = Math.min(10.0, (logoScaleX != null ? logoScaleX : 5.0) + 0.001); sendUpdate(); });

        // Logo Y Scale Slider (0.5 to 10)
        double scaleY = logoScaleY != null ? logoScaleY : 5.0;
        this.sliderLogoScaleY = new AbstractSliderButton(col3X, startY + 100, sliderWidth, 20,
                Component.literal("Logo Height: " + String.format("%.2f", scaleY)),
                (scaleY - 0.5) / 9.5) {
            @Override
            protected void updateMessage() {
                logoScaleY = 0.5 + (this.value * 9.5);
                this.setMessage(Component.literal("Logo Height: " + String.format("%.2f", logoScaleY)));
                sendUpdate();
            }

            @Override
            protected void applyValue() {
                logoScaleY = 0.5 + (this.value * 9.5);
                sendUpdate();
            }
        };
        this.addRenderableWidget(sliderLogoScaleY);
        addFineTuneButtons(col3X + sliderWidth + 2, startY + 100,
            () -> { logoScaleY = Math.max(0.5, (logoScaleY != null ? logoScaleY : 5.0) - 0.001); sendUpdate(); },
            () -> { logoScaleY = Math.min(10.0, (logoScaleY != null ? logoScaleY : 5.0) + 0.001); sendUpdate(); });

        // Logo Z Scale Slider (0.5 to 10)
        double scaleZ = logoScaleZ != null ? logoScaleZ : 1.0;
        this.sliderLogoScaleZ = new AbstractSliderButton(col3X, startY + 125, sliderWidth, 20,
                Component.literal("Logo Depth Scale: " + String.format("%.2f", scaleZ)),
                (scaleZ - 0.5) / 9.5) {
            @Override
            protected void updateMessage() {
                logoScaleZ = 0.5 + (this.value * 9.5);
                this.setMessage(Component.literal("Logo Depth Scale: " + String.format("%.2f", logoScaleZ)));
                sendUpdate();
            }

            @Override
            protected void applyValue() {
                logoScaleZ = 0.5 + (this.value * 9.5);
                sendUpdate();
            }
        };
        this.addRenderableWidget(sliderLogoScaleZ);
        addFineTuneButtons(col3X + sliderWidth + 2, startY + 125,
            () -> { logoScaleZ = Math.max(0.5, (logoScaleZ != null ? logoScaleZ : 1.0) - 0.001); sendUpdate(); },
            () -> { logoScaleZ = Math.min(10.0, (logoScaleZ != null ? logoScaleZ : 1.0) + 0.001); sendUpdate(); });

        // === COPY BUTTONS ===
        int copyButtonY = startY + 160;
        int copyButtonWidth = 95;

        // Copy Figure Position
        this.addRenderableWidget(Button.builder(Component.literal("Copy Figure"), button -> {
            String data = String.format("Figure: X=%.3f Y=%.3f Z=%.3f Scale=%.3f",
                offsetX, offsetY, offsetZ, scale);
            minecraft.keyboardHandler.setClipboard(data);
        }).bounds(centerX - 100, copyButtonY, copyButtonWidth, 20).build());

        // Copy Hitbox
        this.addRenderableWidget(Button.builder(Component.literal("Copy Hitbox"), button -> {
            String data = String.format("Hitbox: X=%.3f Y=%.3f Z=%.3f",
                hitboxOffsetX, hitboxOffsetY, hitboxOffsetZ);
            minecraft.keyboardHandler.setClipboard(data);
        }).bounds(centerX + 5, copyButtonY, copyButtonWidth, 20).build());

        // Copy Logo
        this.addRenderableWidget(Button.builder(Component.literal("Copy Logo"), button -> {
            String data = String.format("Logo: X=%.3f Y=%.3f Z=%.3f Width=%.3f Height=%.3f Depth=%.3f",
                logoPositionX != null ? logoPositionX : 0.0,
                logoPositionY != null ? logoPositionY : 0.0,
                logoPositionZ != null ? logoPositionZ : 0.0,
                logoScaleX != null ? logoScaleX : 5.0,
                logoScaleY != null ? logoScaleY : 5.0,
                logoScaleZ != null ? logoScaleZ : 1.0);
            minecraft.keyboardHandler.setClipboard(data);
        }).bounds(centerX - 45, copyButtonY + 25, copyButtonWidth, 20).build());

        // Reset Button
        this.addRenderableWidget(Button.builder(Component.literal("Reset All"), button -> {
            offsetX = 0.0;
            offsetY = 0.1;
            offsetZ = 0.0;
            scale = 1.0;
            hitboxOffsetX = -0.03;
            hitboxOffsetY = 0.0;
            hitboxOffsetZ = -0.06;
            logoPositionX = null; // Reset to collection defaults
            logoPositionY = null;
            logoPositionZ = null;
            logoScaleX = null;
            logoScaleY = null;
            logoScaleZ = null;
            this.rebuildWidgets();
            sendUpdate();
        }).bounds(centerX - 100, copyButtonY + 50, 95, 20).build());

        // Done Button
        this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> {
            this.onClose();
        }).bounds(centerX + 5, copyButtonY + 50, 95, 20).build());
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // Draw title
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);

        // Detect and display skin model type
        String skinTypeText = "Skin Type: Unknown";
        int skinTypeColor = 0xAAAAAA; // Gray for unknown

        if (minecraft != null && minecraft.level != null) {
            BlockEntity blockEntity = minecraft.level.getBlockEntity(blockPos);
            LOGGER.info("Block entity: {}", blockEntity);

            // Handle both BoxBlockEntity and FigureBlockEntity
            FigureDefinition figureDefinition = null;
            int skinIndex = 0;

            if (blockEntity instanceof BoxBlockEntity boxBlockEntity) {
                LOGGER.info("Found BoxBlockEntity");
                if (boxBlockEntity.hasFigure()) {
                    figureDefinition = boxBlockEntity.getFigureDefinition();
                    skinIndex = boxBlockEntity.getAlternativeSkinIndex();
                }
            } else if (blockEntity instanceof FigureBlockEntity figureBlockEntity) {
                LOGGER.info("Found FigureBlockEntity");
                if (figureBlockEntity.hasFigure()) {
                    figureDefinition = figureBlockEntity.getFigureDefinition();
                    skinIndex = figureBlockEntity.getAlternativeSkinIndex();
                }
            }

            if (figureDefinition != null) {
                LOGGER.info("Has figure: {} (skin index: {})", figureDefinition.getId(), skinIndex);
                try {
                    // Get the texture from the figure
                    ResourceLocation texture = getTextureForFigure(figureDefinition, skinIndex);
                    LOGGER.info("Texture location: {}", texture);
                    if (texture != null) {
                        // Detect the skin model
                        SkinModelDetector.SkinModel skinModel = SkinModelDetector.detectSkinModel(texture);
                        LOGGER.info("Detected skin model: {}", skinModel);

                        if (skinModel == SkinModelDetector.SkinModel.SLIM) {
                            skinTypeText = "Skin Type: SLIM (Alex)";
                            skinTypeColor = 0xFF6B9D; // Pink for slim
                        } else {
                            skinTypeText = "Skin Type: CLASSIC (Steve)";
                            skinTypeColor = 0x5DADE2; // Blue for classic
                        }
                    } else {
                        skinTypeText = "Skin Type: No Texture";
                        LOGGER.warn("Texture is null for figure");
                    }
                } catch (Exception e) {
                    LOGGER.error("Error detecting skin type", e);
                    skinTypeText = "Skin Type: Detection Error";
                    skinTypeColor = 0xFF0000; // Red for error
                }
            } else {
                skinTypeText = "Skin Type: No Figure";
                LOGGER.info("No figure found in block entity");
            }
        }

        // Draw skin type below title
        guiGraphics.drawCenteredString(this.font, skinTypeText, this.width / 2, 30, skinTypeColor);

        // Draw column headers
        int centerX = this.width / 2;
        int columnSpacing = 200;
        int headerY = 35;

        int col1X = centerX - columnSpacing - 70;
        int col2X = centerX - 70;
        int col3X = centerX + columnSpacing - 70;

        // Column headers with color coding
        guiGraphics.drawCenteredString(this.font, "FIGURE", col1X + 70, headerY, 0xFFD700); // Gold
        guiGraphics.drawCenteredString(this.font, "HITBOX", col2X + 70, headerY, 0x00FF00); // Green
        guiGraphics.drawCenteredString(this.font, "LOGO", col3X + 70, headerY, 0x00BFFF); // Sky blue
    }

    /**
     * Helper method to get the texture for a figure definition
     */
    private ResourceLocation getTextureForFigure(FigureDefinition figure, int skinIndex) {
        try {
            if (figure == null) {
                return null;
            }

            // Check for alternative skins
            if (skinIndex > 0 && figure.hasAlternatives()) {
                int altListIndex = skinIndex - 1;
                if (altListIndex < figure.getAlternatives().size()) {
                    return figure.getAlternatives().get(altListIndex).texture();
                }
            }

            // Check if this is a player figure
            if (figure.getType() == com.theplumteam.figure.FigureType.PLAYER && figure.getPlayerUUID() != null) {
                com.mojang.authlib.GameProfile gameProfile = new com.mojang.authlib.GameProfile(figure.getPlayerUUID(), figure.getName());
                return Minecraft.getInstance().getSkinManager().getInsecureSkinLocation(gameProfile);
            }

            // Static figure
            return figure.getTexturePath();
        } catch (Exception e) {
            LOGGER.error("Error getting texture for figure", e);
            return null;
        }
    }

    @Override
    public void removed() {
        super.removed();
        // Updates are already sent in real-time by the sliders
    }

    private void sendUpdate() {
        // Send packet to server with new values
        LOGGER.info("Sending update - Position: {}, Offsets: X={}, Y={}, Z={}, Scale={}, HitboxOffsets: X={}, Y={}, Z={}, LogoPos: X={}, Y={}, Z={}, LogoScale: X={}, Y={}, Z={}",
                    blockPos, offsetX, offsetY, offsetZ, scale, hitboxOffsetX, hitboxOffsetY, hitboxOffsetZ,
                    logoPositionX, logoPositionY, logoPositionZ, logoScaleX, logoScaleY, logoScaleZ);
        FigurePositionPacket packet = new FigurePositionPacket(blockPos, offsetX, offsetY, offsetZ, scale,
                                                               hitboxOffsetX, hitboxOffsetY, hitboxOffsetZ,
                                                               logoPositionX, logoPositionY, logoPositionZ,
                                                               logoScaleX, logoScaleY, logoScaleZ);
        BlockPopsModForge.NETWORK_CHANNEL.sendToServer(packet);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
