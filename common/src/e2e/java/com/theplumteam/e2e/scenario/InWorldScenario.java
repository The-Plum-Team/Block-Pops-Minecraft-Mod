package com.theplumteam.e2e.scenario;

import com.theplumteam.block.PopBlockColor;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.client.gui.CollectionSelectionScreen;
import com.theplumteam.client.gui.FavoriteColorSelectionScreen;
import com.theplumteam.e2e.Scenario;
import com.theplumteam.e2e.Step;
import com.theplumteam.e2e.VanillaShim;
import com.theplumteam.e2e.generated.ScenarioContract.ScenarioId;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.item.BlockEntityItemData;
import com.theplumteam.registry.ModItems;
import com.theplumteam.util.TagReads;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * The mod as a player meets it in the world: a box drawn from the claw machine, that box in
 * hand and placed, the claw machine in hand, and one wall per collection with every figure.
 *
 * The walls come from the test server's data pack (e2e/packaged_runtime.py), which builds them
 * with the same layout this class walks to: collections with figures sorted by id, wall i at
 * x = WALL_ORIGIN_X + i * WALL_SPACING on z = WALL_Z, figure k at column k % 9, row k / 9.
 */
public final class InWorldScenario implements Scenario {
    private static final String PREFIX = "in-world.client_a.";
    private static final BlockPos CLAW_POSITION = new BlockPos(0, -60, 3);

    // The drawn box is placed on the grass away from the claw machine and the walls.
    private static final BlockPos BOX_FLOOR = new BlockPos(10, -61, 10);
    private static final BlockPos BOX_POSITION = BOX_FLOOR.above();
    private static final double GROUND_Y = -60.0;

    private static final int WALL_COLUMNS = 9;
    private static final int WALL_ORIGIN_X = -70;
    private static final int WALL_SPACING = 14;
    private static final int WALL_Z = -20;
    // Nine columns fill about 80% of the frame at the default field of view; the neighbouring
    // walls stay out of it.
    private static final double WALL_VIEW_DISTANCE = 4.6;

    // The server rejects a single movement of more than ten blocks as "moved too quickly".
    private static final double MAX_STEP = 8.0;
    private static final double EYE_HEIGHT = 1.62;

    @Override
    public ScenarioId id() {
        return ScenarioId.IN_WORLD;
    }

    @Override
    public List<Step> build(Minecraft minecraft) {
        List<Step> steps = new ArrayList<>();
        steps.add(Step.of("favorite_color_prompt_open")
                .minTicks(5)
                .timeoutTicks(600)
                .ready(() -> minecraft.screen instanceof FavoriteColorSelectionScreen)
                .assertion(() -> Step.Result.pass("first-join favorite-color prompt opened")));
        steps.add(Step.of("favorite_color_dismissed")
                .action(() -> {
                    ((FavoriteColorSelectionScreen) minecraft.screen).setSelectedColor(PopBlockColor.PURPLE);
                    if (!pressField(minecraft, FavoriteColorSelectionScreen.class, "doneButton")) {
                        throw new IllegalStateException("real Done button could not be pressed");
                    }
                })
                .minTicks(10)
                .timeoutTicks(600)
                .ready(() -> minecraft.screen == null)
                .assertion(() -> minecraft.screen == null
                        ? Step.Result.pass("favorite color chosen through the production prompt")
                        : Step.Result.fail("favorite-color prompt stayed open")));
        steps.add(Step.of("claw_machine_opened")
                .action(() -> {
                    if (!VanillaShim.useBlock(minecraft, CLAW_POSITION)) {
                        throw new IllegalStateException("real claw-machine interaction could not be dispatched");
                    }
                })
                .minTicks(20)
                .timeoutTicks(600)
                .ready(() -> minecraft.screen instanceof CollectionSelectionScreen)
                .assertion(() -> minecraft.screen instanceof CollectionSelectionScreen
                        ? Step.Result.pass("real block interaction opened the claw machine")
                        : Step.Result.fail("claw machine screen did not open")));
        steps.add(Step.of("regular_token_used")
                .action(() -> {
                    if (!pressLabel(minecraft, "Use Regular Token")) {
                        throw new IllegalStateException("Use Regular Token was absent or disabled");
                    }
                })
                .minTicks(10)
                .timeoutTicks(600)
                .ready(() -> minecraft.screen == null && heldFigureId(minecraft) != null)
                .assertion(() -> validateDrawnBox(minecraft)));
        steps.add(Step.of("box_in_hand")
                .action(() -> {
                    VanillaShim.hideGui(minecraft, false);
                    VanillaShim.pose(minecraft, 0.5, -59.0, 0.5, 0.0f, 0.0f);
                })
                .minTicks(10)
                .settleTicks(20)
                .ready(() -> heldFigureId(minecraft) != null)
                .screenshot(PREFIX + "box_in_hand.png")
                .assertion(() -> heldFigureId(minecraft) != null
                        ? Step.Result.pass("the drawn box is held in the main hand")
                        : Step.Result.fail("the drawn box is not in hand")));
        steps.add(Step.of("box_placement_position")
                .minTicks(2)
                .ready(() -> walkTo(minecraft, new Vec3(10.5, GROUND_Y, 8.5), 0.0f, 45.0f))
                .assertion(() -> Step.Result.pass("standing north of the placement spot, facing south")));
        steps.add(Step.of("box_placed")
                .action(() -> {
                    if (!VanillaShim.placeOnTop(minecraft, BOX_FLOOR)) {
                        throw new IllegalStateException("the held box could not be placed");
                    }
                })
                .minTicks(10)
                .ready(() -> placedFigureId(minecraft) != null)
                .assertion(() -> validatePlacedBox(minecraft)));
        steps.add(boxView(minecraft, "box_placed_front", new Vec3(10.5, GROUND_Y, 9.3)));
        steps.add(boxView(minecraft, "box_placed_side", new Vec3(11.7, GROUND_Y, 10.5)));
        steps.add(boxView(minecraft, "box_placed_above", new Vec3(11.4, GROUND_Y + 0.8, 9.4)));
        steps.add(Step.of("claw_machine_in_hand")
                .action(() -> {
                    VanillaShim.hideGui(minecraft, false);
                    VanillaShim.creativeSetHotbar(
                            minecraft, 0, new ItemStack(ModItems.CLAW_MACHINE_BLOCK_ITEM.get()));
                })
                .minTicks(5)
                .settleTicks(20)
                .ready(() -> walkTo(minecraft, new Vec3(10.5, GROUND_Y, 8.5), -90.0f, 0.0f)
                        && minecraft.player.getMainHandItem().is(ModItems.CLAW_MACHINE_BLOCK_ITEM.get()))
                .screenshot(PREFIX + "claw_machine_in_hand.png")
                .assertion(() -> minecraft.player.getMainHandItem().is(ModItems.CLAW_MACHINE_BLOCK_ITEM.get())
                        ? Step.Result.pass("the claw machine is held in the main hand")
                        : Step.Result.fail("the claw machine is not in hand")));
        steps.add(Step.of("claw_machine_third_person")
                .action(() -> {
                    VanillaShim.hideGui(minecraft, true);
                    VanillaShim.cameraType(minecraft, CameraType.THIRD_PERSON_FRONT);
                })
                .minTicks(5)
                .settleTicks(20)
                .ready(() -> minecraft.options.getCameraType() == CameraType.THIRD_PERSON_FRONT)
                .screenshot(PREFIX + "claw_machine_third_person.png")
                .assertion(() -> minecraft.options.getCameraType() == CameraType.THIRD_PERSON_FRONT
                        && minecraft.player.getMainHandItem().is(ModItems.CLAW_MACHINE_BLOCK_ITEM.get())
                        ? Step.Result.pass("third-person view of the player holding the claw machine")
                        : Step.Result.fail("third-person view or held claw machine is missing")));
        List<String> collections = showcaseCollections();
        for (int index = 0; index < collections.size(); index++) {
            steps.add(collectionWall(minecraft, collections.get(index), index));
        }
        return steps;
    }

    /** Every collection with figures, sorted by id: the walls the data pack builds. */
    static List<String> showcaseCollections() {
        return CollectionRegistry.getAllCollections().stream()
                .filter(collection -> !collection.getFigures().isEmpty())
                .map(FigureCollection::getId)
                .filter(id -> !"world_players".equals(id))
                .sorted()
                .toList();
    }

    private static Step boxView(Minecraft minecraft, String step, Vec3 feet) {
        Vec3 target = Vec3.atBottomCenterOf(BOX_POSITION).add(0.0, 0.44, 0.0);
        return Step.of(step)
                .action(() -> {
                    VanillaShim.hideGui(minecraft, true);
                    VanillaShim.cameraType(minecraft, CameraType.FIRST_PERSON);
                })
                .minTicks(5)
                .settleTicks(20)
                .ready(() -> walkTo(minecraft, feet, target))
                .screenshot(PREFIX + step + ".png")
                .assertion(() -> placedFigureId(minecraft) != null
                        ? Step.Result.pass("placed box framed from " + step.substring("box_placed_".length()))
                        : Step.Result.fail("the placed box disappeared"));
    }

    private static Step collectionWall(Minecraft minecraft, String collection, int index) {
        int origin = WALL_ORIGIN_X + index * WALL_SPACING;
        int figures = figures(collection).size();
        int rows = (figures + WALL_COLUMNS - 1) / WALL_COLUMNS;
        // Rows fill from the left, so a collection shorter than a row is centred on its boxes.
        double centerX = origin + Math.min(figures, WALL_COLUMNS) / 2.0;
        Vec3 feet = new Vec3(centerX, GROUND_Y, WALL_Z + 0.5 + WALL_VIEW_DISTANCE);
        Vec3 target = new Vec3(centerX, GROUND_Y + rows / 2.0, WALL_Z + 0.5);
        String step = "collection_" + collection;
        return Step.of(step)
                .action(() -> {
                    VanillaShim.hideGui(minecraft, true);
                    VanillaShim.cameraType(minecraft, CameraType.FIRST_PERSON);
                })
                .minTicks(5)
                .settleTicks(30)
                .timeoutTicks(600)
                .ready(() -> walkTo(minecraft, feet, target)
                        && validateWall(minecraft, collection, origin).pass())
                .screenshot(PREFIX + step + ".png")
                .assertion(() -> validateWall(minecraft, collection, origin));
    }

    private static List<FigureDefinition> figures(String collection) {
        return CollectionRegistry.getCollection(collection)
                .map(FigureCollection::getFigures)
                .orElse(List.of());
    }

    private static Step.Result validateWall(Minecraft minecraft, String collection, int origin) {
        List<FigureDefinition> figures = figures(collection);
        if (figures.isEmpty()) {
            return Step.Result.fail("collection " + collection + " has no synchronized figures");
        }
        for (int position = 0; position < figures.size(); position++) {
            BlockPos pos = new BlockPos(
                    origin + position % WALL_COLUMNS, (int) GROUND_Y + position / WALL_COLUMNS, WALL_Z);
            if (!(minecraft.level.getBlockEntity(pos) instanceof BoxBlockEntity box)
                    || !collection.equals(box.getCollectionId())
                    || !figures.get(position).getId().equals(box.getFigureId())) {
                return Step.Result.fail("wall of " + collection + " is missing "
                        + figures.get(position).getId() + " at " + pos.toShortString());
            }
        }
        return Step.Result.pass("every " + figures.size() + " figure of " + collection + " stands in its box");
    }

    /**
     * Move toward the viewpoint at most MAX_STEP blocks per tick, then look at the target.
     * Returns true once the player stands on the viewpoint.
     */
    private static boolean walkTo(Minecraft minecraft, Vec3 feet, Vec3 target) {
        Vec3 eye = feet.add(0.0, EYE_HEIGHT, 0.0);
        Vec3 look = target.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(look.y, Math.hypot(look.x, look.z)));
        return walkTo(minecraft, feet, yaw, pitch);
    }

    private static boolean walkTo(Minecraft minecraft, Vec3 feet, float yaw, float pitch) {
        Vec3 current = minecraft.player.position();
        Vec3 remaining = feet.subtract(current);
        double distance = remaining.length();
        Vec3 next = distance <= MAX_STEP ? feet : current.add(remaining.scale(MAX_STEP / distance));
        VanillaShim.pose(minecraft, next.x, next.y, next.z, yaw, pitch);
        return distance <= MAX_STEP;
    }

    private static String heldFigureId(Minecraft minecraft) {
        if (minecraft.player == null) {
            return null;
        }
        return figureId(minecraft.player.getMainHandItem());
    }

    private static String figureId(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        String figure = TagReads.string(BlockEntityItemData.read(stack), "FigureId", "");
        return figure.isEmpty() ? null : figure;
    }

    private static String placedFigureId(Minecraft minecraft) {
        return minecraft.level.getBlockEntity(BOX_POSITION) instanceof BoxBlockEntity box
                && box.getFigureId() != null && !box.getFigureId().isEmpty()
                ? box.getFigureId()
                : null;
    }

    private static Step.Result validateDrawnBox(Minecraft minecraft) {
        String figure = heldFigureId(minecraft);
        if (figure == null) {
            return Step.Result.fail("the regular token did not deliver a box to the hand");
        }
        boolean known = figures("onepiece").stream().anyMatch(candidate -> figure.equals(candidate.getId()));
        return known
                ? Step.Result.pass("the regular token drew " + figure + " from the One Piece collection")
                : Step.Result.fail("the drawn box holds an unknown figure: " + figure);
    }

    private static Step.Result validatePlacedBox(Minecraft minecraft) {
        String placed = placedFigureId(minecraft);
        String held = heldFigureId(minecraft);
        if (placed == null) {
            return Step.Result.fail("no box block entity at " + BOX_POSITION.toShortString());
        }
        return placed.equals(held)
                ? Step.Result.pass("the placed box keeps the drawn figure " + placed)
                : Step.Result.fail("the placed box holds " + placed + " instead of " + held);
    }

    private static boolean pressLabel(Minecraft minecraft, String label) {
        if (minecraft.screen == null) {
            return false;
        }
        for (Object child : minecraft.screen.children()) {
            if (child instanceof Button button
                    && button.visible
                    && button.active
                    && label.equals(button.getMessage().getString())) {
                return VanillaShim.press(button);
            }
        }
        return false;
    }

    private static boolean pressField(Minecraft minecraft, Class<?> owner, String name) {
        if (!owner.isInstance(minecraft.screen)) {
            return false;
        }
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return VanillaShim.press(field.get(minecraft.screen));
        } catch (ReflectiveOperationException failure) {
            return false;
        }
    }
}
