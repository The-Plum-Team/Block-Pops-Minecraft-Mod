package com.theplumteam.client.gui;

import com.theplumteam.forge.BlockPopsModForge;
import com.theplumteam.network.FigurePositionPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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

    public FigurePositionScreen(BlockPos blockPos, double offsetX, double offsetY, double offsetZ, double scale,
                                double hitboxOffsetX, double hitboxOffsetY, double hitboxOffsetZ,
                                Double logoPositionX, Double logoPositionY, Double logoPositionZ,
                                Double logoScaleX, Double logoScaleY) {
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
        LOGGER.info("FigurePositionScreen opened at {} with offsets: X={}, Y={}, Z={}, Scale={}, HitboxOffsets: X={}, Y={}, Z={}, LogoPos: X={}, Y={}, Z={}, LogoScale: X={}, Y={}",
                    blockPos, offsetX, offsetY, offsetZ, scale, hitboxOffsetX, hitboxOffsetY, hitboxOffsetZ,
                    logoPositionX, logoPositionY, logoPositionZ, logoScaleX, logoScaleY);
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
        int startY = 40; // Start higher to fit all controls
        int sliderWidth = 160; // Narrower to make room for +/- buttons

        // === FIGURE CONTROLS ===

        // X Offset Slider (-1 to 1)
        this.sliderX = new AbstractSliderButton(centerX - 100, startY, sliderWidth, 20,
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
        addFineTuneButtons(centerX - 100 + sliderWidth + 2, startY,
            () -> { offsetX = Math.max(-1.0, offsetX - 0.001); sendUpdate(); },
            () -> { offsetX = Math.min(1.0, offsetX + 0.001); sendUpdate(); });

        // Y Offset Slider (-1 to 1)
        this.sliderY = new AbstractSliderButton(centerX - 100, startY + 30, sliderWidth, 20,
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
        addFineTuneButtons(centerX - 100 + sliderWidth + 2, startY + 30,
            () -> { offsetY = Math.max(-1.0, offsetY - 0.001); sendUpdate(); },
            () -> { offsetY = Math.min(1.0, offsetY + 0.001); sendUpdate(); });

        // Z Offset Slider (-1 to 1)
        this.sliderZ = new AbstractSliderButton(centerX - 100, startY + 60, sliderWidth, 20,
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
        addFineTuneButtons(centerX - 100 + sliderWidth + 2, startY + 60,
            () -> { offsetZ = Math.max(-1.0, offsetZ - 0.001); sendUpdate(); },
            () -> { offsetZ = Math.min(1.0, offsetZ + 0.001); sendUpdate(); });

        // Scale Slider (0.1 to 2.0)
        this.sliderScale = new AbstractSliderButton(centerX - 100, startY + 90, sliderWidth, 20,
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
        addFineTuneButtons(centerX - 100 + sliderWidth + 2, startY + 90,
            () -> { scale = Math.max(0.1, scale - 0.001); sendUpdate(); },
            () -> { scale = Math.min(2.0, scale + 0.001); sendUpdate(); });

        // === HITBOX CONTROLS ===

        // Hitbox X Offset Slider (-1 to 1)
        this.sliderHitboxX = new AbstractSliderButton(centerX - 100, startY + 130, sliderWidth, 20,
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
        addFineTuneButtons(centerX - 100 + sliderWidth + 2, startY + 130,
            () -> { hitboxOffsetX = Math.max(-1.0, hitboxOffsetX - 0.001); sendUpdate(); },
            () -> { hitboxOffsetX = Math.min(1.0, hitboxOffsetX + 0.001); sendUpdate(); });

        // Hitbox Y Offset Slider (-1 to 1)
        this.sliderHitboxY = new AbstractSliderButton(centerX - 100, startY + 160, sliderWidth, 20,
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
        addFineTuneButtons(centerX - 100 + sliderWidth + 2, startY + 160,
            () -> { hitboxOffsetY = Math.max(-1.0, hitboxOffsetY - 0.001); sendUpdate(); },
            () -> { hitboxOffsetY = Math.min(1.0, hitboxOffsetY + 0.001); sendUpdate(); });

        // Hitbox Z Offset Slider (-1 to 1)
        this.sliderHitboxZ = new AbstractSliderButton(centerX - 100, startY + 190, sliderWidth, 20,
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
        addFineTuneButtons(centerX - 100 + sliderWidth + 2, startY + 190,
            () -> { hitboxOffsetZ = Math.max(-1.0, hitboxOffsetZ - 0.001); sendUpdate(); },
            () -> { hitboxOffsetZ = Math.min(1.0, hitboxOffsetZ + 0.001); sendUpdate(); });

        // === LOGO CONTROLS ===

        // Logo X Position Slider (Depth: forward-back) (-10 to 10 model units)
        double logoX = logoPositionX != null ? logoPositionX : 0.0;
        this.sliderLogoX = new AbstractSliderButton(centerX - 100, startY + 220, sliderWidth, 20,
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
        addFineTuneButtons(centerX - 100 + sliderWidth + 2, startY + 220,
            () -> { logoPositionX = Math.max(-10.0, (logoPositionX != null ? logoPositionX : 0.0) - 0.001); sendUpdate(); },
            () -> { logoPositionX = Math.min(10.0, (logoPositionX != null ? logoPositionX : 0.0) + 0.001); sendUpdate(); });

        // Logo Y Position Slider (Vertical: up-down) (-10 to 10 model units)
        double logoY = logoPositionY != null ? logoPositionY : 0.0;
        this.sliderLogoY = new AbstractSliderButton(centerX - 100, startY + 250, sliderWidth, 20,
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
        addFineTuneButtons(centerX - 100 + sliderWidth + 2, startY + 250,
            () -> { logoPositionY = Math.max(-10.0, (logoPositionY != null ? logoPositionY : 0.0) - 0.001); sendUpdate(); },
            () -> { logoPositionY = Math.min(10.0, (logoPositionY != null ? logoPositionY : 0.0) + 0.001); sendUpdate(); });

        // Logo Z Position Slider (Horizontal: left-right) (-10 to 10 model units)
        double logoZ = logoPositionZ != null ? logoPositionZ : 0.0;
        this.sliderLogoZ = new AbstractSliderButton(centerX - 100, startY + 280, sliderWidth, 20,
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
        addFineTuneButtons(centerX - 100 + sliderWidth + 2, startY + 280,
            () -> { logoPositionZ = Math.max(-10.0, (logoPositionZ != null ? logoPositionZ : 0.0) - 0.001); sendUpdate(); },
            () -> { logoPositionZ = Math.min(10.0, (logoPositionZ != null ? logoPositionZ : 0.0) + 0.001); sendUpdate(); });

        // Logo X Scale Slider (0.5 to 10)
        double scaleX = logoScaleX != null ? logoScaleX : 5.0;
        this.sliderLogoScaleX = new AbstractSliderButton(centerX - 100, startY + 310, sliderWidth, 20,
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
        addFineTuneButtons(centerX - 100 + sliderWidth + 2, startY + 310,
            () -> { logoScaleX = Math.max(0.5, (logoScaleX != null ? logoScaleX : 5.0) - 0.001); sendUpdate(); },
            () -> { logoScaleX = Math.min(10.0, (logoScaleX != null ? logoScaleX : 5.0) + 0.001); sendUpdate(); });

        // Logo Y Scale Slider (0.5 to 10)
        double scaleY = logoScaleY != null ? logoScaleY : 5.0;
        this.sliderLogoScaleY = new AbstractSliderButton(centerX - 100, startY + 340, sliderWidth, 20,
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
        addFineTuneButtons(centerX - 100 + sliderWidth + 2, startY + 340,
            () -> { logoScaleY = Math.max(0.5, (logoScaleY != null ? logoScaleY : 5.0) - 0.001); sendUpdate(); },
            () -> { logoScaleY = Math.min(10.0, (logoScaleY != null ? logoScaleY : 5.0) + 0.001); sendUpdate(); });

        // === COPY BUTTONS ===
        int copyButtonY = startY + 370;
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
            String data = String.format("Logo: X=%.3f Y=%.3f Z=%.3f Width=%.3f Height=%.3f",
                logoPositionX != null ? logoPositionX : 0.0,
                logoPositionY != null ? logoPositionY : 0.0,
                logoPositionZ != null ? logoPositionZ : 0.0,
                logoScaleX != null ? logoScaleX : 5.0,
                logoScaleY != null ? logoScaleY : 5.0);
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

        // Draw current values
        int centerX = this.width / 2;
        int startY = this.height / 2 - 80;
        guiGraphics.drawString(this.font, "Adjust the sliders to position the figure", centerX - 80, startY, 0xAAAAAA);
    }

    @Override
    public void removed() {
        super.removed();
        // Updates are already sent in real-time by the sliders
    }

    private void sendUpdate() {
        // Send packet to server with new values
        LOGGER.info("Sending update - Position: {}, Offsets: X={}, Y={}, Z={}, Scale={}, HitboxOffsets: X={}, Y={}, Z={}, LogoPos: X={}, Y={}, Z={}, LogoScale: X={}, Y={}",
                    blockPos, offsetX, offsetY, offsetZ, scale, hitboxOffsetX, hitboxOffsetY, hitboxOffsetZ,
                    logoPositionX, logoPositionY, logoPositionZ, logoScaleX, logoScaleY);
        FigurePositionPacket packet = new FigurePositionPacket(blockPos, offsetX, offsetY, offsetZ, scale,
                                                               hitboxOffsetX, hitboxOffsetY, hitboxOffsetZ,
                                                               logoPositionX, logoPositionY, logoPositionZ,
                                                               logoScaleX, logoScaleY);
        BlockPopsModForge.NETWORK_CHANNEL.sendToServer(packet);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
