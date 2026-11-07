package com.theplumteam.client.worldcontent;

import com.theplumteam.server.config.ServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * Connects the client to a {@link FakeServer} before each test and leaves it as it found it: no
 * World collections, no uploads, runs or savers, and the game's own clock and connection.
 */
abstract class CreatorTestBase {
    FakeServer server;

    @BeforeEach
    void connect() {
        quietServerConfig();
        server = new FakeServer();
        ClientWorldUploads.host = server;
        ClientWorldUploads.reset();
        ClientWorldState.clear();
    }

    @AfterEach
    void disconnect() {
        ClientWorldUploads.reset();
        ClientWorldState.clear();
        ClientWorldUploads.host = ClientWorldUploads.GAME;
    }

    /** One client tick, as the game runs it. */
    void tick() {
        ClientWorldUploads.tick();
        CreatorAutosave.tick();
    }

    /**
     * {@code BlockPopsMod.logDebug} asks the server config whether to log, and the config's path
     * comes from the loader, which the tests do not have: they get the config a new server writes,
     * debug logging off.
     */
    private static void quietServerConfig() {
        try {
            Field instance = ServerConfig.class.getDeclaredField("instance");
            instance.setAccessible(true);
            if (instance.get(null) == null) {
                Constructor<ServerConfig> constructor = ServerConfig.class.getDeclaredConstructor();
                constructor.setAccessible(true);
                instance.set(null, constructor.newInstance());
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
