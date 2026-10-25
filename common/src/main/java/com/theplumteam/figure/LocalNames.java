package com.theplumteam.figure;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The names collection data gives collections and figures, in the player's language when its
 * language file names them: {@code collection.blockpops.<collection>},
 * {@code figure.blockpops.<collection>.<figure>}, and {@code ...<figure>.<n>} for a figure's
 * n-th alternative skin. Any other name stays as the data writes it, so a language names only
 * the characters it knows by another name, such as Spain's "Rey Hielo".
 */
public final class LocalNames {
    private LocalNames() {
    }

    public static MutableComponent collection(FigureCollection collection) {
        return Component.translatableWithFallback("collection.blockpops." + collection.getId(), collection.getName());
    }

    public static MutableComponent figure(String collectionId, FigureDefinition figure) {
        return Component.translatableWithFallback("figure.blockpops." + collectionId + "." + figure.getId(),
                figure.getName());
    }

    /** The name of a figure's alternative skin {@code skin}, counted from 1. */
    public static MutableComponent alternative(String collectionId, FigureDefinition figure, int skin) {
        return Component.translatableWithFallback("figure.blockpops." + collectionId + "." + figure.getId() + "." + skin,
                figure.getAlternatives().get(skin - 1).name());
    }
}
