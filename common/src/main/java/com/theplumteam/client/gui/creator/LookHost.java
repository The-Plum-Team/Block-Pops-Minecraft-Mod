package com.theplumteam.client.gui.creator;

import com.theplumteam.client.gui.creator.ui.SidebarScreen;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.worldcontent.CreatorDraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * What the Look page ({@link LookPage}) needs of the screen that shows it: the collection editor,
 * for a collection that exists, or the home's form for a new one ({@link CreateForm}), which
 * shows the same page so a collection is given its look where it is given its name.
 */
interface LookHost {
    /** The screen the page's nodes, menus and popovers go on. */
    SidebarScreen screen();

    /** The draft the page reads and changes. */
    CreatorDraft draft();

    /** The preview slot the host keeps the draft's preview in. */
    int previewSlot();

    <T extends UiNode> T add(T node);

    EditBox field(String name, int x, int y, int width, int maxLength, Component hint, String value,
                  Consumer<String> responder);

    /** Gives a node that draws models its share of the screen's budget; called while building. */
    <T extends SidebarScreen.Budgeted> T budgetNode(T node);

    /** The content panel's bounds, which the page's popovers stay inside. */
    UiNode.Rect contentPanel();

    /** The page changed the draft: the host shows it in its preview and in what it allows. */
    void changed();

    /** The page needs other rows than it was built with. */
    void rebuildPage();

    /** The operator moved on within the page, which is when an editor sends the files that wait. */
    void navigated();

    /** Why the box cannot be saved as it is, or null. */
    @Nullable
    Component boxProblem();

    /** Whether Enter in a sub-view of the page presses the host's main button. */
    boolean footerIsPrimary();

    /** A value dragged or stepped was let go, which ends its run of changes. */
    void numberReleased();

    /**
     * The height of what the host shows of its own between the name's field and the sections,
     * such as the form's id and its message, or 0 for none: the page then shows the name's
     * problem there itself.
     */
    default int underNameHeight(boolean narrow) {
        return 0;
    }

    /** Adds what {@link #underNameHeight} made room for, from {@code y} down. */
    default void buildUnderName(int x, int y, int width) {
    }

    /** The least height of the narrow page's view, which a host with less room of its own lowers. */
    default int narrowViewMin(int usual) {
        return usual;
    }
}
