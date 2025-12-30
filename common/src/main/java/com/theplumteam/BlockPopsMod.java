package com.theplumteam;

import com.theplumteam.network.ModNetworking;
import com.theplumteam.registry.ModBlockEntities;
import com.theplumteam.registry.ModBlocks;
import com.theplumteam.registry.ModCreativeTabs;
import com.theplumteam.registry.ModItems;
import com.theplumteam.server.ServerTickHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BlockPopsMod {
    public static final String MOD_ID = "blockpops";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static void init() {
        LOGGER.info("Initializing BlockPops mod");

        // Initialize cross-platform networking
        ModNetworking.init();

        // Initialize server tick handler for token management
        ServerTickHandler.init();

        LOGGER.info("BlockPops mod initialization complete");
    }
}
