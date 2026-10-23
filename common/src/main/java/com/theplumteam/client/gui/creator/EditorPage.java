package com.theplumteam.client.gui.creator;

import com.theplumteam.client.gui.creator.ui.UiInput;
import com.theplumteam.client.gui.creator.ui.UiNode;
import net.minecraft.client.gui.GuiGraphics;

/**
 * One tab of the collection editor, drawn in the area under the editor's top bar and tabs. A
 * page reads and changes the editor's {@link com.theplumteam.client.worldcontent.CreatorDraft}
 * and tells the editor after each change through {@link CreatorEditorScreen#changed()}, which
 * updates the live previews, the unsaved count and the Save button. The page lives as long as
 * the editor, so it keeps its nodes across the editor's rebuilds and adds them again in
 * {@link #build}.
 */
interface EditorPage {
    /**
     * Adds the page's nodes and text fields to the editor for the area it may use, in screen
     * units. Runs on every rebuild of the editor, so it reads the draft as it is now.
     */
    void build(UiNode.Rect area);

    /** Draws the page's own labels, above its text fields and below its nodes. */
    default void drawContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    /**
     * The draft changed without the page, as an undo or a sync does, or through the page itself;
     * the page updates what its nodes show. The editor rebuilds after an undo or a sync, so this
     * only has to cover what a rebuild does not.
     */
    default void draftChanged() {
    }

    /** The editor is showing another tab; the page stops anything it plays. */
    default void hide() {
    }

    /** Runs once per client tick while the page is shown. */
    default void tick() {
    }

    /** Whether the page has drop zones, so the editor's status row says files can be dropped. */
    default boolean takesFiles() {
        return false;
    }

    /** The page's keyboard shortcuts, asked after the editor's own. */
    default boolean shortcut(UiInput.Key key) {
        return false;
    }

    /** The editor is closing: the page lets go of anything it holds outside the screen. */
    default void close() {
    }
}
