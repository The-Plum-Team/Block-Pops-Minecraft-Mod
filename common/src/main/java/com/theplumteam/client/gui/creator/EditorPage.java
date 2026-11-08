package com.theplumteam.client.gui.creator;

import com.theplumteam.client.gui.creator.ui.UiInput;
import com.theplumteam.client.gui.creator.ui.UiNode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;

/**
 * One tab of the collection editor, drawn in its content panel, beside the sidebar that picks
 * the tab. A page reads and changes the editor's
 * {@link com.theplumteam.client.worldcontent.CreatorDraft} and tells the editor after each change
 * through {@link CreatorEditorScreen#changed()}, which updates the live previews, the sidebar's
 * marks and status, and Publish. The page lives as long as the editor, so it keeps its nodes
 * across the editor's rebuilds and adds them again in {@link #build}.
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

    /** Whether the page has drop zones of its own, which files dropped on the window can reach. */
    default boolean takesFiles() {
        return false;
    }

    /** The page's keyboard shortcuts, asked after the editor's own. */
    default boolean shortcut(UiInput.Key key) {
        return false;
    }

    /**
     * A key pressed in one of the page's text fields, offered to the page before the field, as
     * Tab moving on from a field to a control of the page that is not a text field: true when
     * the page used it. Escape never reaches it: the editor takes the keyboard from the field.
     */
    default boolean fieldKey(EditBox field, UiInput.Key key) {
        return false;
    }

    /**
     * Escape, once no menu, dialog or field holds it: the page goes back one step of its own,
     * such as out of a sub-view, and answers true, or answers false to let the editor leave.
     */
    default boolean escape() {
        return false;
    }

    /** The editor is closing: the page lets go of anything it holds outside the screen. */
    default void close() {
    }
}
