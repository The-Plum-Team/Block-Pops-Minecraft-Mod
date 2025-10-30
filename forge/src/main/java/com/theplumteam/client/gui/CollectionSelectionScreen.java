package com.theplumteam.client.gui;

import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.forge.BlockPopsModForge;
import com.theplumteam.network.ClawMachineCollectionPacket;
import com.theplumteam.network.DropBoxPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class CollectionSelectionScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger(CollectionSelectionScreen.class);

    private final BlockPos blockPos;
    private String selectedCollectionId;
    private final List<FigureCollection> collections;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_SPACING = 25;
    private static final int BUTTON_WIDTH = 200;

    public CollectionSelectionScreen(BlockPos blockPos, String currentCollectionId) {
        super(Component.literal("Select Collection"));
        this.blockPos = blockPos;
        this.selectedCollectionId = currentCollectionId;
        this.collections = new ArrayList<>(CollectionRegistry.getAllCollections());
        LOGGER.info("CollectionSelectionScreen opened at {} with current collection: {}",
                    blockPos, currentCollectionId);
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int startY = this.height / 2 - (collections.size() * BUTTON_SPACING / 2) - 30;

        // Add a button for each collection
        for (int i = 0; i < collections.size(); i++) {
            FigureCollection collection = collections.get(i);
            int buttonY = startY + (i * BUTTON_SPACING);

            String buttonLabel = collection.getName();
            // Add indicator if this is the currently selected collection
            if (collection.getId().equals(selectedCollectionId)) {
                buttonLabel = "> " + buttonLabel + " <";
            }

            final String collectionId = collection.getId();
            this.addRenderableWidget(Button.builder(Component.literal(buttonLabel), button -> {
                selectedCollectionId = collectionId;
                sendUpdate();
                this.rebuildWidgets(); // Rebuild to update button labels
            }).bounds(centerX - BUTTON_WIDTH / 2, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        }

        // Drop Box and Done buttons at the bottom
        int buttonsY = startY + (collections.size() * BUTTON_SPACING) + 10;

        // Drop Box button (left side)
        this.addRenderableWidget(Button.builder(Component.literal("Drop a box from the collection"), button -> {
            if (selectedCollectionId != null && !selectedCollectionId.isEmpty()) {
                LOGGER.info("Requesting box drop for collection: {}", selectedCollectionId);
                DropBoxPacket packet = new DropBoxPacket(blockPos, selectedCollectionId);
                BlockPopsModForge.NETWORK_CHANNEL.sendToServer(packet);
            }
        }).bounds(centerX - BUTTON_WIDTH / 2 - 5, buttonsY, BUTTON_WIDTH, BUTTON_HEIGHT).build());

        // Done button (right side)
        this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> {
            this.onClose();
        }).bounds(centerX - BUTTON_WIDTH / 2 - 5, buttonsY + BUTTON_SPACING, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // Draw title
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);

        // Draw instruction text
        int centerX = this.width / 2;
        int instructionY = this.height / 2 - (collections.size() * BUTTON_SPACING / 2) - 50;
        guiGraphics.drawCenteredString(this.font, "Select a collection for this Claw Machine",
                                       centerX, instructionY, 0xAAAAAA);

        // Show currently selected collection
        if (selectedCollectionId != null && !selectedCollectionId.isEmpty()) {
            CollectionRegistry.getCollection(selectedCollectionId).ifPresent(collection -> {
                int selectedY = instructionY + 15;
                guiGraphics.drawCenteredString(this.font,
                    "Selected: " + collection.getName() + " (" + collection.getFigures().size() + " figures)",
                    centerX, selectedY, 0x55FF55);
            });
        }
    }

    private void sendUpdate() {
        LOGGER.info("Sending collection update - Position: {}, Collection ID: {}",
                    blockPos, selectedCollectionId);
        ClawMachineCollectionPacket packet = new ClawMachineCollectionPacket(blockPos, selectedCollectionId);
        BlockPopsModForge.NETWORK_CHANNEL.sendToServer(packet);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
