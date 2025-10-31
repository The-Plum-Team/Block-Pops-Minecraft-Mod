package com.theplumteam.capability;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Provides and manages the PlayerDiscovery capability instance.
 * This is attached to player entities to track their discovered figures.
 */
public class PlayerDiscoveryProvider implements ICapabilityProvider, INBTSerializable<CompoundTag> {
    public static final Capability<IPlayerDiscovery> PLAYER_DISCOVERY = CapabilityManager.get(new CapabilityToken<>(){});

    private final IPlayerDiscovery playerDiscovery = new PlayerDiscovery();
    private final LazyOptional<IPlayerDiscovery> lazyOptional = LazyOptional.of(() -> playerDiscovery);

    @NotNull
    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == PLAYER_DISCOVERY) {
            return lazyOptional.cast();
        }
        return LazyOptional.empty();
    }

    @Override
    public CompoundTag serializeNBT() {
        if (playerDiscovery instanceof PlayerDiscovery) {
            return ((PlayerDiscovery) playerDiscovery).serializeNBT();
        }
        return new CompoundTag();
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        if (playerDiscovery instanceof PlayerDiscovery) {
            ((PlayerDiscovery) playerDiscovery).deserializeNBT(nbt);
        }
    }

    /**
     * Invalidate the lazy optional when the provider is no longer needed.
     * This should be called when the entity is removed.
     */
    public void invalidate() {
        lazyOptional.invalidate();
    }
}
