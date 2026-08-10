package com.theplumteam.e2e.scenario;

import com.theplumteam.block.PopBlockColor;
import com.theplumteam.blockentity.ClawMachineBlockEntity;
import com.theplumteam.client.gui.CollectionSelectionScreen;
import com.theplumteam.client.gui.FavoriteColorSelectionScreen;
import com.theplumteam.client.gui.SettingsScreen;
import com.theplumteam.client.gui.widget.ColorSelectionButton;
import com.theplumteam.client.gui.widget.LinkButton;
import com.theplumteam.e2e.E2EHarness;
import com.theplumteam.e2e.Scenario;
import com.theplumteam.e2e.Step;
import com.theplumteam.e2e.VanillaShim;
import com.theplumteam.e2e.generated.ScenarioContract.ScenarioId;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureDefinition;
import dev.architectury.platform.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Field;
import java.net.URL;
import java.util.List;

public final class UiRegressionScenario implements Scenario {
    private static final String PREFIX = "ui-regression.client_a.";
    private static final BlockPos CLAW_POSITION = new BlockPos(0, -60, 3);

    @Override
    public ScenarioId id() {
        return ScenarioId.UI_REGRESSION;
    }

    @Override
    public List<Step> build(Minecraft minecraft) {
        return List.of(
                Step.of("favorite_color_prompt")
                        .minTicks(5)
                        .settleTicks(10)
                        .timeoutTicks(600)
                        .ready(() -> readyScreen(
                                minecraft, FavoriteColorSelectionScreen.class, 18))
                        .screenshot(PREFIX + "favorite_color_prompt.png")
                        .assertion(() -> validateOrganicFavoritePrompt(minecraft)),
                Step.of("favorite_color_selected")
                        .action(() -> ((FavoriteColorSelectionScreen) minecraft.screen)
                                .setSelectedColor(PopBlockColor.PURPLE))
                        .minTicks(10)
                        .settleTicks(5)
                        .ready(() -> favoriteSelectionReady(minecraft))
                        .screenshot(PREFIX + "favorite_color_selected.png")
                        .assertion(() -> favoriteSelectionReady(minecraft)
                                ? Step.Result.pass("purple is selected and Done is enabled")
                                : Step.Result.fail("favorite-color selection state did not commit")),
                Step.of("favorite_color_applied")
                        .action(() -> {
                            if (!pressFavoriteDone(minecraft)) {
                                throw new IllegalStateException("real Done button could not be pressed");
                            }
                        })
                        .minTicks(20)
                        .settleTicks(10)
                        .timeoutTicks(600)
                        .ready(() -> minecraft.screen == null && worldPlayerIsPurple(minecraft))
                        .screenshot(PREFIX + "favorite_color_applied.png")
                        .assertion(() -> worldPlayerIsPurple(minecraft)
                                ? Step.Result.pass(
                                        "server round trip synchronized Alice's purple World Players figure")
                                : Step.Result.fail(
                                        "favorite color did not reach the synchronized World Players collection")),
                Step.of("collection_screen_initial")
                        .action(() -> {
                            if (!VanillaShim.useBlock(minecraft, CLAW_POSITION)) {
                                throw new IllegalStateException(
                                        "real claw-machine interaction could not be dispatched");
                            }
                        })
                        .minTicks(30)
                        .settleTicks(10)
                        .timeoutTicks(600)
                        .ready(() -> readyScreen(
                                minecraft, CollectionSelectionScreen.class, 8))
                        .screenshot(PREFIX + "collection_screen_initial.png")
                        .assertion(() -> validateCollectionScreen(minecraft)),
                Step.of("settings_modal")
                        .action(() -> {
                            if (!pressSettingsLink(minecraft)) {
                                throw new IllegalStateException(
                                        "production Settings link could not be pressed");
                            }
                        })
                        .minTicks(20)
                        .settleTicks(10)
                        .ready(() -> readyScreen(minecraft, SettingsScreen.class, 4))
                        .screenshot(PREFIX + "settings_modal.png")
                        .assertion(() -> validateSettingsScreen(minecraft)));
    }

    private static Step.Result validateOrganicFavoritePrompt(Minecraft minecraft) {
        Step.Result screen = screenTitleAndWidgets(
                minecraft,
                FavoriteColorSelectionScreen.class,
                "Choose Your Favorite Color",
                18);
        if (!screen.pass()) {
            return screen;
        }
        if (Platform.isDevelopmentEnvironment()) {
            return Step.Result.fail("packaged runtime incorrectly reports a development environment");
        }
        long colors = minecraft.screen.children().stream()
                .filter(ColorSelectionButton.class::isInstance)
                .count();
        if (colors != 16) {
            return Step.Result.fail("favorite-color prompt has " + colors + " color controls");
        }
        try {
            Button done = (Button) field(
                    FavoriteColorSelectionScreen.class, "doneButton").get(minecraft.screen);
            Button figure = (Button) field(
                    FavoriteColorSelectionScreen.class, "toggleFigureButton").get(minecraft.screen);
            if (done.active || !"Figure: ON".equals(figure.getMessage().getString())) {
                return Step.Result.fail("favorite-color initial button state is wrong");
            }
        } catch (ReflectiveOperationException failure) {
            return Step.Result.fail("could not inspect favorite-color controls: " + failure);
        }
        return productionSourceSeparated(FavoriteColorSelectionScreen.class)
                ? Step.Result.pass(
                        "organic first-join production prompt has 16 colors and correct controls")
                : Step.Result.fail("screen and harness did not load from separate mod containers");
    }

    private static Step.Result validateCollectionScreen(Minecraft minecraft) {
        Step.Result screen = screenTitleAndWidgets(
                minecraft,
                CollectionSelectionScreen.class,
                "Claw Machine Configuration",
                8);
        if (!screen.pass()) {
            return screen;
        }
        if (!(minecraft.level.getBlockEntity(CLAW_POSITION)
                instanceof ClawMachineBlockEntity claw)) {
            return Step.Result.fail("packaged fixture has no real claw-machine block entity");
        }
        if (!"onepiece".equals(claw.getCollectionId())) {
            return Step.Result.fail(
                    "claw-machine collection NBT did not synchronize: " + claw.getCollectionId());
        }
        if (!CollectionRegistry.hasCollection("onepiece")
                || CollectionRegistry.getAllCollections().isEmpty()) {
            return Step.Result.fail("packaged collection data is absent");
        }
        List<AbstractWidget> widgets = visibleWidgets(minecraft);
        if (!hasWidgetMessage(widgets, "Done")
                || !hasWidgetMessage(widgets, "Use Regular Token")
                || !hasWidgetMessage(widgets, "Use Guaranteed Token")) {
            return Step.Result.fail("claw-machine token/footer controls are missing or mislabeled");
        }
        return productionSourceSeparated(CollectionSelectionScreen.class)
                ? Step.Result.pass(
                        "real block interaction opened the production collection and token controls")
                : Step.Result.fail("collection screen came from the harness container");
    }

    private static Step.Result validateSettingsScreen(Minecraft minecraft) {
        Step.Result screen = screenTitleAndWidgets(
                minecraft, SettingsScreen.class, "Settings", 4);
        if (!screen.pass()) {
            return screen;
        }
        List<AbstractWidget> widgets = visibleWidgets(minecraft);
        boolean developVisible = hasWidgetMessage(widgets, "Develop");
        if (Platform.isDevelopmentEnvironment() || developVisible) {
            return Step.Result.fail("development-only settings leaked into a packaged runtime");
        }
        boolean serverTab = hasWidgetMessage(widgets, "Server");
        boolean close = hasWidgetMessage(widgets, "Close");
        boolean changeTime = hasWidgetMessage(widgets, "Change time");
        boolean resetHour = widgets.stream()
                .map(widget -> widget.getMessage().getString())
                .anyMatch(message -> message.startsWith("Guaranteed Token Reset Hour ("));
        if (!serverTab || !close || !changeTime || !resetHour) {
            return Step.Result.fail("packaged server settings controls are missing or mislabeled");
        }
        return productionSourceSeparated(SettingsScreen.class)
                ? Step.Result.pass(
                        "production server tab, reset slider, and footer are visible; Develop is absent")
                : Step.Result.fail("settings screen came from the harness container");
    }

    private static List<AbstractWidget> visibleWidgets(Minecraft minecraft) {
        return minecraft.screen.children().stream()
                .filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast)
                .filter(widget -> widget.visible)
                .toList();
    }

    private static boolean hasWidgetMessage(List<AbstractWidget> widgets, String message) {
        return widgets.stream()
                .anyMatch(widget -> message.equals(widget.getMessage().getString()));
    }

    private static boolean readyScreen(
            Minecraft minecraft, Class<? extends Screen> type, int minimumChildren) {
        return type.isInstance(minecraft.screen)
                && minecraft.screen.width >= 640
                && minecraft.screen.height >= 360
                && minecraft.screen.children().size() >= minimumChildren;
    }

    private static Step.Result screenTitleAndWidgets(
            Minecraft minecraft,
            Class<? extends Screen> type,
            String title,
            int minimumChildren) {
        if (!readyScreen(minecraft, type, minimumChildren)) {
            return Step.Result.fail("screen type, dimensions, or widget inventory is wrong");
        }
        if (!title.equals(minecraft.screen.getTitle().getString())) {
            return Step.Result.fail(
                    "screen title is wrong: " + minecraft.screen.getTitle().getString());
        }
        return Step.Result.pass("production screen title and widgets are present");
    }

    private static boolean favoriteSelectionReady(Minecraft minecraft) {
        if (!(minecraft.screen instanceof FavoriteColorSelectionScreen screen)) {
            return false;
        }
        try {
            Field selected = field(FavoriteColorSelectionScreen.class, "selectedColor");
            Field done = field(FavoriteColorSelectionScreen.class, "doneButton");
            return selected.get(screen) == PopBlockColor.PURPLE
                    && done.get(screen) instanceof Button button
                    && button.active;
        } catch (ReflectiveOperationException failure) {
            return false;
        }
    }

    private static boolean pressFavoriteDone(Minecraft minecraft) {
        if (!(minecraft.screen instanceof FavoriteColorSelectionScreen screen)) {
            return false;
        }
        try {
            return VanillaShim.press(
                    field(FavoriteColorSelectionScreen.class, "doneButton").get(screen));
        } catch (ReflectiveOperationException failure) {
            return false;
        }
    }

    private static boolean worldPlayerIsPurple(Minecraft minecraft) {
        if (minecraft.player == null) {
            return false;
        }
        return CollectionRegistry.getCollection("world_players")
                .stream()
                .flatMap(collection -> collection.getFigures().stream())
                .filter(figure -> minecraft.player.getUUID().equals(figure.getPlayerUUID()))
                .map(FigureDefinition::getFavoriteColor)
                .anyMatch(PopBlockColor.PURPLE::equals);
    }

    private static boolean pressSettingsLink(Minecraft minecraft) {
        if (!(minecraft.screen instanceof CollectionSelectionScreen)) {
            return false;
        }
        try {
            Field texture = field(LinkButton.class, "texture");
            for (Object child : minecraft.screen.children()) {
                if (child instanceof LinkButton link
                        && texture.get(link) instanceof ResourceLocation location
                        && "blockpops:textures/gui/settings_icon.png".equals(location.toString())) {
                    return VanillaShim.press(link);
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return false;
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static boolean productionSourceSeparated(Class<?> productionClass) {
        URL production = productionClass.getResource(productionClass.getSimpleName() + ".class");
        URL harness = E2EHarness.class.getResource("E2EHarness.class");
        String productionContainer = containerIdentity(production);
        String harnessContainer = containerIdentity(harness);
        return productionContainer != null
                && harnessContainer != null
                && !productionContainer.equals(harnessContainer);
    }

    private static String containerIdentity(URL resource) {
        if (resource == null) {
            return null;
        }
        String external = resource.toExternalForm();
        int archiveBoundary = external.indexOf("!/");
        if (archiveBoundary < 0) {
            return null;
        }
        String container = external.substring(0, archiveBoundary);
        return container.toLowerCase(java.util.Locale.ROOT).contains(".jar")
                ? container
                : null;
    }
}
