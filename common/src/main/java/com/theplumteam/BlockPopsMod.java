package com.theplumteam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BlockPopsMod {
    public static final String MOD_ID = "blockpops";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static void init() {
        LOGGER.info("Initializing BlockPops mod");
        // Platform-specific initialization happens in platform modules
    }
}
