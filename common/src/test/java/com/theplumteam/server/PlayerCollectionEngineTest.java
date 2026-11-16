package com.theplumteam.server;

import com.theplumteam.remote.VerifiedStore;
import com.theplumteam.remote.player.PlayerFetcher;
import com.theplumteam.remote.player.PlayerHead;
import com.theplumteam.remote.player.PlayerOrigin.Origin;
import com.theplumteam.remote.player.PlayerPaths;
import com.theplumteam.remote.player.PlayerRevocations;
import com.theplumteam.remote.player.PlayerStore;
import com.theplumteam.server.PlayerCollectionEngine.Condition;
import com.theplumteam.server.PlayerCollectionEngine.Line;
import com.theplumteam.server.PlayerCollectionEngine.Notice;
import com.theplumteam.server.PlayerCollectionEngine.NoticeKind;
import com.theplumteam.server.PlayerCollectionEngine.Outcome;
import com.theplumteam.server.PlayerCollectionEngine.Reason;
import com.theplumteam.server.PlayerCollectionEngine.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.theplumteam.remote.player.PlayerOrigin.CODE;
import static com.theplumteam.remote.player.PlayerOrigin.ID;
import static com.theplumteam.remote.player.PlayerOrigin.KEYS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A world shows the player collections added to it for as long as the signed documents allow,
 * each on the revision the world is pinned to. The origin is a map in memory.
 */
class PlayerCollectionEngineTest {
    @TempDir
    Path cache;
    @TempDir
    Path world;

    private final List<String> log = new ArrayList<>();

    private PlayerCollectionEngine engine(Origin origin) {
        return engine(origin, cache, true);
    }

    private PlayerCollectionEngine engine(Origin origin, Path cacheRoot, boolean online) {
        PlayerFetcher fetcher = new PlayerFetcher(origin, new PlayerStore(new VerifiedStore(cacheRoot)), KEYS,
                () -> origin.now);
        return new PlayerCollectionEngine(fetcher, new PlayerCollectionSettings(world, log::add), () -> origin.now,
                online, log::add);
    }

    private String settings() throws IOException {
        return Files.readString(world.resolve("blockpops-player-collections.json"));
    }

    private static Line line(PlayerCollectionEngine engine) {
        assertEquals(1, engine.lines().size());
        return engine.lines().get(0);
    }

    @Test
    void anAddedCodeIsPinnedShownAndStillThereWithNoNetwork(@TempDir Path otherCache) throws IOException {
        Origin origin = new Origin();
        origin.status(true);
        String index = origin.publish(5, 7, "Mis figuras", "Steve");
        PlayerCollectionEngine engine = engine(origin);
        engine.start();
        assertTrue(engine.view().isEmpty());

        assertEquals(new Result(Outcome.ADDED, "Mis figuras", 5), engine.add(CODE));
        assertEquals(1, engine.view().size());
        assertEquals(5, engine.view().get(0).entry().rev());
        assertEquals("Mis figuras", engine.view().get(0).revision().collection().getName());
        assertArrayEquals(origin.files.get(PlayerPaths.head(CODE)), engine.view().get(0).head());
        assertEquals("{\"schema\":1,\"entries\":[{\"id\":\"" + ID + "\",\"code\":\"" + CODE + "\",\"rev\":5,\"index\":\""
                + index + "\"}]}", settings());
        assertEquals(new Line(engine.view().get(0).entry(), Condition.LOADED, "Mis figuras", "Steve", null, 0),
                line(engine));
        assertEquals(new Result(Outcome.ALREADY_ENABLED, "Mis figuras", 5), engine.add(CODE));
        assertTrue(engine.takeNotices().isEmpty());

        // The world opens again with no network at all, from what was verified
        origin.down = true;
        origin.asked.clear();
        PlayerCollectionEngine offline = engine(origin);
        offline.start();
        assertEquals(1, offline.view().size());
        assertArrayEquals(origin.files.get(PlayerPaths.head(CODE)), offline.view().get(0).head());
        assertTrue(origin.asked.isEmpty());
        // And stays through checks that fail
        offline.pollStatus();
        offline.pollHeads();
        assertEquals(1, offline.view().size());
        assertTrue(offline.takeNotices().isEmpty());

        // A game that kept nothing shows nothing until it has verified everything itself
        PlayerCollectionEngine fresh = engine(origin, otherCache, true);
        fresh.start();
        assertTrue(fresh.view().isEmpty());
        assertEquals(Condition.WAITING, line(fresh).condition());
        fresh.pollStatus();
        fresh.pollHeads();
        assertTrue(fresh.view().isEmpty());
        origin.down = false;
        fresh.pollHeads();
        assertEquals(1, fresh.view().size());
        assertEquals(Condition.LOADED, line(fresh).condition());
    }

    @Test
    void aNewRevisionIsToldOnceAndUsedWhenAsked() throws IOException {
        Origin origin = new Origin();
        origin.status(true);
        origin.publish(5, 7, "Mis figuras", "Steve");
        PlayerCollectionEngine engine = engine(origin);
        engine.start();
        engine.add(CODE);
        engine.pollHeads();
        assertTrue(engine.takeNotices().isEmpty());
        assertEquals(new Result(Outcome.UP_TO_DATE, "Mis figuras", 5), engine.update(CODE));

        String next = origin.publish(6, 8, "Mis figuras 2", "Steve");
        engine.pollHeads();
        assertEquals(List.of(new Notice(NoticeKind.NEW_VERSION, "Mis figuras", null, 6, 5)), engine.takeNotices());
        assertEquals(5, engine.view().get(0).entry().rev());
        // Clients get the head as it is now, and the revision the world is pinned to
        assertArrayEquals(origin.files.get(PlayerPaths.head(CODE)), engine.view().get(0).head());
        assertEquals(6, line(engine).newest());
        engine.pollHeads();
        assertTrue(engine.takeNotices().isEmpty());

        assertEquals(new Result(Outcome.UPDATED, "Mis figuras 2", 6), engine.update(CODE));
        assertEquals(6, engine.view().get(0).entry().rev());
        assertEquals("Mis figuras 2", engine.view().get(0).revision().collection().getName());
        assertTrue(settings().contains("\"rev\":6,\"index\":\"" + next + "\"}"));
        assertEquals(0, line(engine).newest());
        assertEquals(new Result(Outcome.UP_TO_DATE, "Mis figuras 2", 6), engine.update(CODE));
        assertEquals(new Result(Outcome.NOT_ENABLED, "birch-comet-10", 0), engine.update("birch-comet-10"));
    }

    @Test
    void aFollowingCollectionMovesToEachNewRevisionByItself() throws IOException {
        Origin origin = new Origin();
        origin.status(true);
        origin.publish(5, 7, "Mis figuras", "Steve");
        PlayerCollectionEngine engine = engine(origin);
        engine.start();
        engine.add(CODE);
        assertEquals(new Result(Outcome.FOLLOWING, "Mis figuras", 5), engine.follow(CODE, true));
        assertTrue(settings().endsWith(",\"follow\":true}]}"));

        origin.publish(6, 8, "Mis figuras 2", "Steve");
        engine.pollHeads();
        assertEquals(6, engine.view().get(0).entry().rev());
        assertTrue(engine.view().get(0).entry().follow());
        assertTrue(engine.takeNotices().isEmpty());

        // A revision the list in force names is not moved to
        origin.publish(7, 9, "Mis figuras 3", "Steve");
        origin.status(true, List.of(), List.of(new PlayerRevocations.Revision(ID, 7, true)));
        engine.pollStatus();
        engine.pollHeads();
        assertEquals(6, engine.view().get(0).entry().rev());

        assertEquals(new Result(Outcome.PINNED, "Mis figuras 2", 6), engine.follow(CODE, false));
        assertFalse(settings().contains("follow"));
    }

    @Test
    void whatIsTakenOutOfUseStopsBeingShownAndComesBackWhenRestored() throws IOException {
        Origin origin = new Origin();
        origin.status(true);
        String index = origin.publish(5, 7, "Mis figuras", "Steve");
        PlayerCollectionEngine engine = engine(origin);
        engine.start();
        engine.add(CODE);
        assertTrue(Files.isDirectory(cache.resolve("p/c/" + ID)));

        // Quarantined: the list names it and the head says why
        origin.status(true, new PlayerRevocations.Collection(ID, false));
        origin.head(new PlayerHead(8, origin.now, CODE, ID, PlayerHead.State.QUARANTINED, 5, null,
                PlayerHead.Reason.POLICY));
        engine.pollStatus();
        assertTrue(engine.view().isEmpty());
        assertEquals(List.of(new Notice(NoticeKind.RETIRED, "Mis figuras", Reason.REVIEW, 0, 5)), engine.takeNotices());
        assertEquals(Condition.RETIRED, line(engine).condition());
        assertEquals(Reason.REVIEW, line(engine).reason());
        assertFalse(Files.exists(cache.resolve("p/c/" + ID)));
        // It stays in the world's list, and is not told again
        assertTrue(settings().contains(ID));
        engine.pollStatus();
        engine.pollHeads();
        assertTrue(engine.takeNotices().isEmpty());
        // Nor does a world opened meanwhile show it
        PlayerCollectionEngine reopened = engine(origin);
        reopened.start();
        assertTrue(reopened.view().isEmpty());
        assertEquals(Condition.RETIRED, line(reopened).condition());

        // Restored: it is back on the pinned revision without anybody acting
        origin.status(true);
        origin.head(new PlayerHead(9, origin.now, CODE, ID, PlayerHead.State.LIVE, 5, index, null));
        engine.pollStatus();
        engine.pollHeads();
        assertEquals(1, engine.view().size());
        assertEquals(5, engine.view().get(0).entry().rev());
        assertTrue(engine.takeNotices().isEmpty());

        // Deleted by its owner
        origin.status(true, new PlayerRevocations.Collection(ID, true));
        origin.head(new PlayerHead(10, origin.now, CODE, ID, PlayerHead.State.REMOVED, 5, null,
                PlayerHead.Reason.OWNER));
        engine.pollStatus();
        assertTrue(engine.view().isEmpty());
        assertEquals(List.of(new Notice(NoticeKind.RETIRED, "Mis figuras", Reason.OWNER, 0, 5)), engine.takeNotices());
        assertEquals(new Result(Outcome.UNAVAILABLE, "Mis figuras", 0), engine.update(CODE));
    }

    @Test
    void everythingGoesWhenLoadingIsTurnedOffAndIsToldOnce() {
        Origin origin = new Origin();
        origin.status(true);
        origin.publish(5, 7, "Mis figuras", "Steve");
        PlayerCollectionEngine engine = engine(origin);
        engine.start();
        engine.add(CODE);

        origin.status(false);
        engine.pollStatus();
        assertTrue(engine.view().isEmpty());
        assertEquals(List.of(new Notice(NoticeKind.ALL_OFF, "", Reason.OFF, 0, 0)), engine.takeNotices());
        assertEquals(Reason.OFF, line(engine).reason());
        engine.pollStatus();
        engine.pollHeads();
        assertTrue(engine.takeNotices().isEmpty());
        assertEquals(new Result(Outcome.OFF, "", 0), engine.add("birch-comet-10"));

        origin.status(true);
        engine.pollStatus();
        engine.pollHeads();
        assertEquals(1, engine.view().size());
    }

    @Test
    void aPinThatIsWithdrawnOrAgedOutOffersTheNewestRevision() throws IOException {
        Origin origin = new Origin();
        origin.status(true);
        String first = origin.publish(5, 7, "Mis figuras", "Steve");
        PlayerCollectionEngine engine = engine(origin);
        engine.start();
        engine.add(CODE);
        origin.publish(6, 8, "Mis figuras 2", "Steve");

        // Withdrawn by its owner: the list names the revision alone
        origin.status(true, List.of(), List.of(new PlayerRevocations.Revision(ID, 5, false)));
        engine.pollStatus();
        assertTrue(engine.view().isEmpty());
        assertEquals(List.of(new Notice(NoticeKind.PIN_GONE, "Mis figuras", null, 6, 5)), engine.takeNotices());
        assertEquals(new Line(line(engine).entry(), Condition.PIN_GONE, "Mis figuras", null, null, 6), line(engine));
        engine.pollHeads();
        assertTrue(engine.takeNotices().isEmpty());
        assertEquals(new Result(Outcome.UPDATED, "Mis figuras 2", 6), engine.update(CODE));
        assertEquals(6, engine.view().get(0).entry().rev());

        // Aged out of what the service keeps: its index is no longer published
        origin.publish(7, 9, "Mis figuras 3", "Steve");
        String second = line(engine).entry().index();
        origin.files.remove(PlayerPaths.index(ID, second));
        engine.pollHeads();
        assertTrue(engine.view().isEmpty());
        assertEquals(List.of(new Notice(NoticeKind.PIN_GONE, "Mis figuras 2", null, 7, 6)), engine.takeNotices());
        assertEquals(new Result(Outcome.UPDATED, "Mis figuras 3", 7), engine.update(CODE));
        assertEquals(1, engine.view().size());
        assertFalse(first.equals(second));
    }

    @Test
    void requestsThatCannotBeMetAreAnsweredAsSuch(@TempDir Path otherCache) throws IOException {
        Origin origin = new Origin();
        origin.status(true);
        origin.publish(5, 7, "Mis figuras", "Steve");
        PlayerCollectionEngine engine = engine(origin);
        engine.start();
        assertEquals(new Result(Outcome.UNKNOWN_CODE, "", 0), engine.add("birch-comet-10"));
        assertEquals(new Result(Outcome.NOT_ENABLED, CODE, 0), engine.remove(CODE));
        assertEquals(new Result(Outcome.NOT_ENABLED, CODE, 0), engine.follow(CODE, true));

        // Where nothing is ever fetched, nothing is added
        assertEquals(new Result(Outcome.OFF, "", 0), engine(origin, otherCache, false).add(CODE));
        // The origin out of reach: nothing is added, and the log says why
        origin.down = true;
        origin.now += 3600;
        assertEquals(new Result(Outcome.FAILED, "", 0), engine.add(CODE));
        assertTrue(log.get(log.size() - 1).contains(CODE));
        origin.down = false;

        assertEquals(Outcome.ADDED, engine.add(CODE).outcome());
        assertEquals(new Result(Outcome.REMOVED, "Mis figuras", 0), engine.remove(ID));
        assertTrue(engine.view().isEmpty());
        assertTrue(engine.lines().isEmpty());
        assertEquals("{\"schema\":1,\"entries\":[]}", settings());
        assertFalse(Files.exists(cache.resolve("p/c/" + ID)));
        assertFalse(Files.exists(cache.resolve("p/verified/heads/" + ID + ".json")));
        assertEquals(Outcome.ADDED, engine.add(CODE).outcome());
        assertEquals(new Result(Outcome.REMOVED, "Mis figuras", 0), engine.remove(CODE));

        // A world with as many as it may have takes no more
        StringBuilder full = new StringBuilder("{\"schema\":1,\"entries\":[");
        for (int i = 0; i < PlayerCollectionSettings.MAX_ENTRIES; i++) {
            char letter = (char) ('a' + i % 26);
            full.append(i == 0 ? "" : ",").append("{\"id\":\"p_aaaaaaaaaa").append(letter).append(i < 26 ? 'a' : 'b')
                    .append("\",\"code\":\"word").append(letter).append("-word").append(i < 26 ? "a" : "b")
                    .append("-10\",\"rev\":1,\"index\":\"").append("0".repeat(64)).append("\"}");
        }
        Files.writeString(world.resolve("blockpops-player-collections.json"), full.append("]}"));
        PlayerCollectionEngine crowded = engine(origin);
        crowded.start();
        assertEquals(PlayerCollectionSettings.MAX_ENTRIES, crowded.lines().size());
        assertEquals(new Result(Outcome.FULL, "", 0), crowded.add(CODE));
    }

    @Test
    void aListThatDoesNotHoldTogetherIsSetAsideWhole() throws IOException {
        Path file = world.resolve("blockpops-player-collections.json");
        String entry = "{\"id\":\"" + ID + "\",\"code\":\"" + CODE + "\",\"rev\":5,\"index\":\"" + "0".repeat(64) + "\"";
        String[] refused = {
            "not json",
            "{\"schema\":2,\"entries\":[]}",
            "{\"schema\":1,\"entries\":[],\"more\":1}",
            "{\"schema\":1,\"entries\":[" + entry + ",\"follow\":false}]}",
            "{\"schema\":1,\"entries\":[" + entry + ",\"url\":\"https://example.org\"}]}",
            "{\"schema\":1,\"entries\":[" + entry + "}," + entry + "}]}",
            "{\"schema\":1,\"entries\":[" + entry.replace(ID, "w-mine") + "}]}",
            "{\"schema\":1,\"entries\":[" + entry.replace(CODE, "willow-media") + "}]}",
            "{\"schema\":1,\"entries\":[" + entry.replace("\"rev\":5", "\"rev\":5.0") + "}]}",
            "{\"schema\":1,\"entries\":[" + entry.replace("\"rev\":5", "\"rev\":0") + "}]}",
        };
        for (String text : refused) {
            log.clear();
            Files.writeString(file, text);
            PlayerCollectionSettings settings = new PlayerCollectionSettings(world, log::add);
            assertTrue(settings.entries().isEmpty(), text);
            assertEquals(1, log.size(), text);
            assertFalse(Files.exists(file), text);
            assertEquals(text, Files.readString(world.resolve("blockpops-player-collections.json.corrupt")));
        }
        // What it writes it reads back, byte for byte
        log.clear();
        String kept = "{\"schema\":1,\"entries\":[" + entry + ",\"follow\":true}]}";
        Files.writeString(file, kept);
        PlayerCollectionSettings settings = new PlayerCollectionSettings(world, log::add);
        assertEquals(1, settings.entries().size());
        assertTrue(settings.put(settings.entries().get(0)));
        assertArrayEquals(kept.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
        assertTrue(log.isEmpty());
        assertNull(settings.byCode("birch-comet-10").orElse(null));
    }
}
