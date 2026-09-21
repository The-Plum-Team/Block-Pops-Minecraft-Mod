package com.theplumteam.client.gui;

import com.mojang.authlib.GameProfile;
//? if >=1.21 {
/*import com.theplumteam.client.ClientSkinRegistration;
*///? }
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.blockentity.FigureBlockEntity;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.network.FigurePositionPacket;
import com.theplumteam.util.PlayerSkins;
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

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-platform screen for adjusting figure position, hitbox, and logo settings.
 * Used in dev mode only.
 */
public class FigurePositionScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger(FigurePositionScreen.class);

    // Cache for UI skin lookups
    private static final Map<UUID, GameProfile> profileCache = new ConcurrentHashMap<>();
    private static final Set<UUID> registeredSkins = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // QuickSkin Reflection Helper
    private static boolean checkedQuickSkin = false;
    private static boolean quickSkinAvailable = false;
    private static java.lang.reflect.Method getSkinLocationMethod;
    private static Object playerAppearanceServiceInstance;

    private static ResourceLocation getQuickSkinLocation(UUID uuid) {
        if (!checkedQuickSkin) {
            try {
                Class<?> serviceClass = Class.forName("com.quickskin.mod.client.services.PlayerAppearanceService");
                java.lang.reflect.Method getInstanceMethod = serviceClass.getMethod("getInstance");
                playerAppearanceServiceInstance = getInstanceMethod.invoke(null);
                getSkinLocationMethod = serviceClass.getMethod("getSkinLocation", UUID.class);
                quickSkinAvailable = true;
            } catch (Exception e) {
                quickSkinAvailable = false;
            }
            checkedQuickSkin = true;
        }

        if (quickSkinAvailable && playerAppearanceServiceInstance != null) {
            try {
                return (ResourceLocation) getSkinLocationMethod.invoke(playerAppearanceServiceInstance, uuid);
            } catch (Exception e) {
                // Ignore
            }
        }
        return null;
    }

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

    public FigurePositionScreen(BlockPos blockPos, double offsetX, double offsetY, double offsetZ, double scale,
                                double hitboxOffsetX, double hitboxOffsetY, double hitboxOffsetZ,
                                double hitboxScaleX, double hitboxScaleY, double hitboxScaleZ,
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

    private void addFineTuneButtons(int x, int y, Runnable decrementAction, Runnable incrementAction) {
        int buttonWidth = 18;
        this.addRenderableWidget(Button.builder(Component.literal("-"), button -> {
            decrementAction.run();
            this.rebuildWidgets();
        }).bounds(x, y, buttonWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("+"), button -> {
            incrementAction.run();
            this.rebuildWidgets();
        }).bounds(x + buttonWidth + 2, y, buttonWidth, 20).build());
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int startY = 50;
        int sliderWidth = 140;
        int columnSpacing = 200;

        int col1X = centerX - columnSpacing - 70;
        int col2X = centerX - 70;
        int col3X = centerX + columnSpacing - 70;

        // === COLUMN 1: FIGURE CONTROLS ===
        this.addRenderableWidget(new AbstractSliderButton(col1X, startY, sliderWidth, 20,
                Component.literal("X Offset: " + String.format("%.2f", offsetX)), (offsetX + 1.0) / 2.0) {
            @Override protected void updateMessage() { offsetX = (this.value * 2.0) - 1.0; this.setMessage(Component.literal("X Offset: " + String.format("%.2f", offsetX))); sendUpdate(); }
            @Override protected void applyValue() { offsetX = (this.value * 2.0) - 1.0; sendUpdate(); }
        });
        addFineTuneButtons(col1X + sliderWidth + 2, startY, () -> { offsetX = Math.max(-1.0, offsetX - 0.001); sendUpdate(); }, () -> { offsetX = Math.min(1.0, offsetX + 0.001); sendUpdate(); });

        this.addRenderableWidget(new AbstractSliderButton(col1X, startY + 25, sliderWidth, 20,
                Component.literal("Y Offset: " + String.format("%.2f", offsetY)), (offsetY + 1.0) / 2.0) {
            @Override protected void updateMessage() { offsetY = (this.value * 2.0) - 1.0; this.setMessage(Component.literal("Y Offset: " + String.format("%.2f", offsetY))); sendUpdate(); }
            @Override protected void applyValue() { offsetY = (this.value * 2.0) - 1.0; sendUpdate(); }
        });
        addFineTuneButtons(col1X + sliderWidth + 2, startY + 25, () -> { offsetY = Math.max(-1.0, offsetY - 0.001); sendUpdate(); }, () -> { offsetY = Math.min(1.0, offsetY + 0.001); sendUpdate(); });

        this.addRenderableWidget(new AbstractSliderButton(col1X, startY + 50, sliderWidth, 20,
                Component.literal("Z Offset: " + String.format("%.2f", offsetZ)), (offsetZ + 1.0) / 2.0) {
            @Override protected void updateMessage() { offsetZ = (this.value * 2.0) - 1.0; this.setMessage(Component.literal("Z Offset: " + String.format("%.2f", offsetZ))); sendUpdate(); }
            @Override protected void applyValue() { offsetZ = (this.value * 2.0) - 1.0; sendUpdate(); }
        });
        addFineTuneButtons(col1X + sliderWidth + 2, startY + 50, () -> { offsetZ = Math.max(-1.0, offsetZ - 0.001); sendUpdate(); }, () -> { offsetZ = Math.min(1.0, offsetZ + 0.001); sendUpdate(); });

        this.addRenderableWidget(new AbstractSliderButton(col1X, startY + 75, sliderWidth, 20,
                Component.literal("Scale: " + String.format("%.2f", scale)), (scale - 0.1) / 1.9) {
            @Override protected void updateMessage() { scale = 0.1 + (this.value * 1.9); this.setMessage(Component.literal("Scale: " + String.format("%.2f", scale))); sendUpdate(); }
            @Override protected void applyValue() { scale = 0.1 + (this.value * 1.9); sendUpdate(); }
        });
        addFineTuneButtons(col1X + sliderWidth + 2, startY + 75, () -> { scale = Math.max(0.1, scale - 0.001); sendUpdate(); }, () -> { scale = Math.min(2.0, scale + 0.001); sendUpdate(); });

        // === COLUMN 2: HITBOX CONTROLS ===
        this.addRenderableWidget(new AbstractSliderButton(col2X, startY, sliderWidth, 20,
                Component.literal("Hitbox X: " + String.format("%.2f", hitboxOffsetX)), (hitboxOffsetX + 1.0) / 2.0) {
            @Override protected void updateMessage() { hitboxOffsetX = (this.value * 2.0) - 1.0; this.setMessage(Component.literal("Hitbox X: " + String.format("%.2f", hitboxOffsetX))); sendUpdate(); }
            @Override protected void applyValue() { hitboxOffsetX = (this.value * 2.0) - 1.0; sendUpdate(); }
        });
        addFineTuneButtons(col2X + sliderWidth + 2, startY, () -> { hitboxOffsetX = Math.max(-1.0, hitboxOffsetX - 0.001); sendUpdate(); }, () -> { hitboxOffsetX = Math.min(1.0, hitboxOffsetX + 0.001); sendUpdate(); });

        this.addRenderableWidget(new AbstractSliderButton(col2X, startY + 25, sliderWidth, 20,
                Component.literal("Hitbox Y: " + String.format("%.2f", hitboxOffsetY)), (hitboxOffsetY + 1.0) / 2.0) {
            @Override protected void updateMessage() { hitboxOffsetY = (this.value * 2.0) - 1.0; this.setMessage(Component.literal("Hitbox Y: " + String.format("%.2f", hitboxOffsetY))); sendUpdate(); }
            @Override protected void applyValue() { hitboxOffsetY = (this.value * 2.0) - 1.0; sendUpdate(); }
        });
        addFineTuneButtons(col2X + sliderWidth + 2, startY + 25, () -> { hitboxOffsetY = Math.max(-1.0, hitboxOffsetY - 0.001); sendUpdate(); }, () -> { hitboxOffsetY = Math.min(1.0, hitboxOffsetY + 0.001); sendUpdate(); });

        this.addRenderableWidget(new AbstractSliderButton(col2X, startY + 50, sliderWidth, 20,
                Component.literal("Hitbox Z: " + String.format("%.2f", hitboxOffsetZ)), (hitboxOffsetZ + 1.0) / 2.0) {
            @Override protected void updateMessage() { hitboxOffsetZ = (this.value * 2.0) - 1.0; this.setMessage(Component.literal("Hitbox Z: " + String.format("%.2f", hitboxOffsetZ))); sendUpdate(); }
            @Override protected void applyValue() { hitboxOffsetZ = (this.value * 2.0) - 1.0; sendUpdate(); }
        });
        addFineTuneButtons(col2X + sliderWidth + 2, startY + 50, () -> { hitboxOffsetZ = Math.max(-1.0, hitboxOffsetZ - 0.001); sendUpdate(); }, () -> { hitboxOffsetZ = Math.min(1.0, hitboxOffsetZ + 0.001); sendUpdate(); });

        this.addRenderableWidget(new AbstractSliderButton(col2X, startY + 75, sliderWidth, 20,
                Component.literal("Hitbox Scale X: " + String.format("%.2f", hitboxScaleX)), (hitboxScaleX - 0.5) / 1.5) {
            @Override protected void updateMessage() { hitboxScaleX = 0.5 + (this.value * 1.5); this.setMessage(Component.literal("Hitbox Scale X: " + String.format("%.2f", hitboxScaleX))); sendUpdate(); }
            @Override protected void applyValue() { hitboxScaleX = 0.5 + (this.value * 1.5); sendUpdate(); }
        });
        addFineTuneButtons(col2X + sliderWidth + 2, startY + 75, () -> { hitboxScaleX = Math.max(0.5, hitboxScaleX - 0.01); sendUpdate(); }, () -> { hitboxScaleX = Math.min(2.0, hitboxScaleX + 0.01); sendUpdate(); });

        this.addRenderableWidget(new AbstractSliderButton(col2X, startY + 100, sliderWidth, 20,
                Component.literal("Hitbox Scale Y: " + String.format("%.2f", hitboxScaleY)), (hitboxScaleY - 0.5) / 1.5) {
            @Override protected void updateMessage() { hitboxScaleY = 0.5 + (this.value * 1.5); this.setMessage(Component.literal("Hitbox Scale Y: " + String.format("%.2f", hitboxScaleY))); sendUpdate(); }
            @Override protected void applyValue() { hitboxScaleY = 0.5 + (this.value * 1.5); sendUpdate(); }
        });
        addFineTuneButtons(col2X + sliderWidth + 2, startY + 100, () -> { hitboxScaleY = Math.max(0.5, hitboxScaleY - 0.01); sendUpdate(); }, () -> { hitboxScaleY = Math.min(2.0, hitboxScaleY + 0.01); sendUpdate(); });

        this.addRenderableWidget(new AbstractSliderButton(col2X, startY + 125, sliderWidth, 20,
                Component.literal("Hitbox Scale Z: " + String.format("%.2f", hitboxScaleZ)), (hitboxScaleZ - 0.5) / 1.5) {
            @Override protected void updateMessage() { hitboxScaleZ = 0.5 + (this.value * 1.5); this.setMessage(Component.literal("Hitbox Scale Z: " + String.format("%.2f", hitboxScaleZ))); sendUpdate(); }
            @Override protected void applyValue() { hitboxScaleZ = 0.5 + (this.value * 1.5); sendUpdate(); }
        });
        addFineTuneButtons(col2X + sliderWidth + 2, startY + 125, () -> { hitboxScaleZ = Math.max(0.5, hitboxScaleZ - 0.01); sendUpdate(); }, () -> { hitboxScaleZ = Math.min(2.0, hitboxScaleZ + 0.01); sendUpdate(); });

        // === COLUMN 3: LOGO CONTROLS ===
        double logoX = logoPositionX != null ? logoPositionX : 0.0;
        this.addRenderableWidget(new AbstractSliderButton(col3X, startY, sliderWidth, 20,
                Component.literal("Logo X (Depth): " + String.format("%.2f", logoX)), (logoX + 10.0) / 20.0) {
            @Override protected void updateMessage() { logoPositionX = (this.value * 20.0) - 10.0; this.setMessage(Component.literal("Logo X (Depth): " + String.format("%.2f", logoPositionX))); sendUpdate(); }
            @Override protected void applyValue() { logoPositionX = (this.value * 20.0) - 10.0; sendUpdate(); }
        });
        addFineTuneButtons(col3X + sliderWidth + 2, startY, () -> { logoPositionX = Math.max(-10.0, (logoPositionX != null ? logoPositionX : 0.0) - 0.001); sendUpdate(); }, () -> { logoPositionX = Math.min(10.0, (logoPositionX != null ? logoPositionX : 0.0) + 0.001); sendUpdate(); });

        double logoY = logoPositionY != null ? logoPositionY : 0.0;
        this.addRenderableWidget(new AbstractSliderButton(col3X, startY + 25, sliderWidth, 20,
                Component.literal("Logo Y (Vertical): " + String.format("%.2f", logoY)), (logoY + 10.0) / 20.0) {
            @Override protected void updateMessage() { logoPositionY = (this.value * 20.0) - 10.0; this.setMessage(Component.literal("Logo Y (Vertical): " + String.format("%.2f", logoPositionY))); sendUpdate(); }
            @Override protected void applyValue() { logoPositionY = (this.value * 20.0) - 10.0; sendUpdate(); }
        });
        addFineTuneButtons(col3X + sliderWidth + 2, startY + 25, () -> { logoPositionY = Math.max(-10.0, (logoPositionY != null ? logoPositionY : 0.0) - 0.001); sendUpdate(); }, () -> { logoPositionY = Math.min(10.0, (logoPositionY != null ? logoPositionY : 0.0) + 0.001); sendUpdate(); });

        double logoZ = logoPositionZ != null ? logoPositionZ : 0.0;
        this.addRenderableWidget(new AbstractSliderButton(col3X, startY + 50, sliderWidth, 20,
                Component.literal("Logo Z (Horizontal): " + String.format("%.2f", logoZ)), (logoZ + 10.0) / 20.0) {
            @Override protected void updateMessage() { logoPositionZ = (this.value * 20.0) - 10.0; this.setMessage(Component.literal("Logo Z (Horizontal): " + String.format("%.2f", logoPositionZ))); sendUpdate(); }
            @Override protected void applyValue() { logoPositionZ = (this.value * 20.0) - 10.0; sendUpdate(); }
        });
        addFineTuneButtons(col3X + sliderWidth + 2, startY + 50, () -> { logoPositionZ = Math.max(-10.0, (logoPositionZ != null ? logoPositionZ : 0.0) - 0.001); sendUpdate(); }, () -> { logoPositionZ = Math.min(10.0, (logoPositionZ != null ? logoPositionZ : 0.0) + 0.001); sendUpdate(); });

        double scaleX = logoScaleX != null ? logoScaleX : 5.0;
        this.addRenderableWidget(new AbstractSliderButton(col3X, startY + 75, sliderWidth, 20,
                Component.literal("Logo Width (X): " + String.format("%.2f", scaleX)), (scaleX - 0.5) / 9.5) {
            @Override protected void updateMessage() { logoScaleX = 0.5 + (this.value * 9.5); this.setMessage(Component.literal("Logo Width (X): " + String.format("%.2f", logoScaleX))); sendUpdate(); }
            @Override protected void applyValue() { logoScaleX = 0.5 + (this.value * 9.5); sendUpdate(); }
        });
        addFineTuneButtons(col3X + sliderWidth + 2, startY + 75, () -> { logoScaleX = Math.max(0.5, (logoScaleX != null ? logoScaleX : 5.0) - 0.001); sendUpdate(); }, () -> { logoScaleX = Math.min(10.0, (logoScaleX != null ? logoScaleX : 5.0) + 0.001); sendUpdate(); });

        double scaleY = logoScaleY != null ? logoScaleY : 5.0;
        this.addRenderableWidget(new AbstractSliderButton(col3X, startY + 100, sliderWidth, 20,
                Component.literal("Logo Height (Y): " + String.format("%.2f", scaleY)), (scaleY - 0.5) / 9.5) {
            @Override protected void updateMessage() { logoScaleY = 0.5 + (this.value * 9.5); this.setMessage(Component.literal("Logo Height (Y): " + String.format("%.2f", logoScaleY))); sendUpdate(); }
            @Override protected void applyValue() { logoScaleY = 0.5 + (this.value * 9.5); sendUpdate(); }
        });
        addFineTuneButtons(col3X + sliderWidth + 2, startY + 100, () -> { logoScaleY = Math.max(0.5, (logoScaleY != null ? logoScaleY : 5.0) - 0.001); sendUpdate(); }, () -> { logoScaleY = Math.min(10.0, (logoScaleY != null ? logoScaleY : 5.0) + 0.001); sendUpdate(); });

        double scaleZ = logoScaleZ != null ? logoScaleZ : 1.0;
        this.addRenderableWidget(new AbstractSliderButton(col3X, startY + 125, sliderWidth, 20,
                Component.literal("Logo Depth (Z): " + String.format("%.2f", scaleZ)), (scaleZ - 0.5) / 9.5) {
            @Override protected void updateMessage() { logoScaleZ = 0.5 + (this.value * 9.5); this.setMessage(Component.literal("Logo Depth (Z): " + String.format("%.2f", logoScaleZ))); sendUpdate(); }
            @Override protected void applyValue() { logoScaleZ = 0.5 + (this.value * 9.5); sendUpdate(); }
        });
        addFineTuneButtons(col3X + sliderWidth + 2, startY + 125, () -> { logoScaleZ = Math.max(0.5, (logoScaleZ != null ? logoScaleZ : 1.0) - 0.001); sendUpdate(); }, () -> { logoScaleZ = Math.min(10.0, (logoScaleZ != null ? logoScaleZ : 1.0) + 0.001); sendUpdate(); });

        // === COPY BUTTONS ===
        int copyButtonY = startY + 160;
        int copyButtonWidth = 95;

        this.addRenderableWidget(Button.builder(Component.literal("Copy Figure"), button -> {
            String data = String.format("Figure: X=%.3f Y=%.3f Z=%.3f Scale=%.3f", offsetX, offsetY, offsetZ, scale);
            minecraft.keyboardHandler.setClipboard(data);
        }).bounds(centerX - 100, copyButtonY, copyButtonWidth, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Copy Hitbox"), button -> {
            String data = String.format("Hitbox: X=%.3f Y=%.3f Z=%.3f", hitboxOffsetX, hitboxOffsetY, hitboxOffsetZ);
            minecraft.keyboardHandler.setClipboard(data);
        }).bounds(centerX + 5, copyButtonY, copyButtonWidth, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Copy Logo"), button -> {
            String data = String.format("Logo: X=%.3f Y=%.3f Z=%.3f Width=%.3f Height=%.3f Depth=%.3f",
                    logoPositionX != null ? logoPositionX : 0.0, logoPositionY != null ? logoPositionY : 0.0, logoPositionZ != null ? logoPositionZ : 0.0,
                    logoScaleX != null ? logoScaleX : 5.0, logoScaleY != null ? logoScaleY : 5.0, logoScaleZ != null ? logoScaleZ : 1.0);
            minecraft.keyboardHandler.setClipboard(data);
        }).bounds(centerX - 45, copyButtonY + 25, copyButtonWidth, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Reset All"), button -> {
            offsetX = 0.0; offsetY = 0.1; offsetZ = 0.0; scale = 1.0;
            hitboxOffsetX = -0.03; hitboxOffsetY = 0.0; hitboxOffsetZ = -0.06;
            logoPositionX = null; logoPositionY = null; logoPositionZ = null;
            logoScaleX = null; logoScaleY = null; logoScaleZ = null;
            this.rebuildWidgets();
            sendUpdate();
        }).bounds(centerX - 100, copyButtonY + 50, 95, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> this.onClose())
                .bounds(centerX + 5, copyButtonY + 50, 95, 20).build());
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        //? if >=1.21 {
        /*this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        *///? } else {
        this.renderBackground(guiGraphics);
        //? }
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFFFF);

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

        guiGraphics.drawCenteredString(this.font, skinTypeText, this.width / 2, 30, skinTypeColor);

        int centerX = this.width / 2;
        int columnSpacing = 200;
        int headerY = 35;

        guiGraphics.drawCenteredString(this.font, "FIGURE", centerX - columnSpacing, headerY, 0xFFFFD700);
        guiGraphics.drawCenteredString(this.font, "HITBOX", centerX, headerY, 0xFF00FF00);
        guiGraphics.drawCenteredString(this.font, "LOGO", centerX + columnSpacing, headerY, 0xFF00BFFF);
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
            ResourceLocation quickSkinLoc = getQuickSkinLocation(figure.getPlayerUUID());
            if (quickSkinLoc != null) return quickSkinLoc;

            GameProfile profile = profileCache.computeIfAbsent(figure.getPlayerUUID(), uuid -> new GameProfile(uuid, figure.getName()));
            if (registeredSkins.add(figure.getPlayerUUID())) {
                //? if >=1.21 {
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
