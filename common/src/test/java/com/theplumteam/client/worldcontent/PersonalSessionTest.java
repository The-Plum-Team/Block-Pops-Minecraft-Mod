package com.theplumteam.client.worldcontent;

import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.BlobType;
import com.theplumteam.worldcontent.JdkSha256;
import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentStatus.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The creator's saving with a player's own disk at the other end: what it sends is applied to a
 * store in the game folder by the code a server applies it with, and nothing goes to the server.
 */
class PersonalSessionTest extends CreatorTestBase {
    private static final UUID PLAYER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @TempDir
    Path game;

    private final Set<String> loaded = new HashSet<>();
    private final Set<String> released = new HashSet<>();

    @AfterEach
    void close() {
        PersonalSession.close();
        PersonalSession.assetsHost = PersonalSession.GAME_ASSETS;
    }

    private boolean open() {
        // No renderer here: a file counts as loaded as soon as the session asks for it
        PersonalSession.assetsHost = new PersonalSession.Assets() {
            @Override
            public void load(String sha, BlobInfo info, byte[] bytes, ClientWorldAssets.Listener listener) {
                loaded.add(sha);
                listener.loaded(sha);
            }

            @Override
            public void release(Set<String> shas) {
                released.addAll(shas);
            }
        };
        return PersonalSession.open(game, PLAYER);
    }

    private static FilePicker.LocalFile skin(String name, int shade) throws Exception {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                image.setRGB(x, y, 0xFF000000 | (x * 4) << 16 | (y * 4) << 8 | shade);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        byte[] bytes = out.toByteArray();
        return new FilePicker.LocalFile(name + ".png", BlobType.PNG, bytes, JdkSha256.of(bytes), BlobType.PNG.validate(bytes));
    }

    // Sends what the creator would and runs the game's ticks until the answer is in
    private ClientWorldUploads.Outcome send(List<FilePicker.LocalFile> files, WorldCollectionEdit edit) {
        List<ClientWorldUploads.Outcome> outcomes = new ArrayList<>();
        assertTrue(ClientWorldUploads.submit(files, edit, null, false, outcomes::add));
        for (int ticks = 0; ticks < 200 && outcomes.isEmpty(); ticks++) {
            PersonalSession.tick();
            tick();
        }
        assertEquals(1, outcomes.size(), "the save was never answered");
        return outcomes.get(0);
    }

    @Test
    void aDraftIsMadeFilledAndKeptOnThisComputerAlone() throws Exception {
        assertFalse(CreatorSource.isPersonal());
        assertTrue(open());
        assertTrue(CreatorSource.isPersonal());
        assertTrue(CreatorSource.isReady());
        assertTrue(CreatorSource.records().isEmpty());
        int before = CreatorSource.revision();

        assertTrue(send(List.of(), new WorldCollectionEdit.CreateDraft("mine", "Mis figuras")).ok());
        WorldCollectionRecord draft = CreatorSource.record("w-mine");
        assertNotNull(draft);
        assertTrue(draft.draft());
        assertNotEquals(before, CreatorSource.revision());

        // A figure with its skin: the file goes first, then the edit that names it, as in any save
        FilePicker.LocalFile skin = skin("uno", 0);
        assertTrue(send(List.of(skin), new WorldCollectionEdit.PutFigure("w-mine", "uno", "Uno", 0, skin.sha(), "", "", 2)).ok());
        assertEquals(1, CreatorSource.record("w-mine").figures().size());
        assertEquals(skin.info(), CreatorSource.blob(skin.sha()));
        assertFalse(CreatorSource.isMissing(skin.sha()));
        assertTrue(CreatorSource.isLoaded(skin.sha()));
        assertTrue(loaded.contains(skin.sha()));
        assertArrayEquals(skin.bytes(), CreatorSource.personalBytes(skin.sha()));
        assertTrue(CreatorSource.quotaUsedKiB() > 0);
        // The same file again is not stored twice, and an edit the rules refuse is refused as a server refuses it
        assertTrue(send(List.of(skin), new WorldCollectionEdit.PutFigure("w-mine", "dos", "Dos", 0, skin.sha(), "", "", 2)).ok());
        ClientWorldUploads.Outcome refused = send(List.of(), new WorldCollectionEdit.PutFigure("w-mine", "tres", "Tres", 0,
                "0".repeat(64), "", "", 2));
        assertFalse(refused.ok());
        assertEquals(Result.MISSING_ASSET, refused.status().result());
        assertEquals(2, CreatorSource.record("w-mine").figures().size());
        // Nothing went to the server the player is on
        assertTrue(server.edits.isEmpty());

        // It is all on disk, below the game folder and nowhere else, and is there when the creator opens again
        try (Stream<Path> files = Files.walk(game)) {
            assertTrue(files.allMatch(file -> file.startsWith(game.resolve("blockpops").resolve("creator")) || file.equals(game)
                    || file.equals(game.resolve("blockpops"))));
        }
        PersonalSession.close();
        assertFalse(CreatorSource.isPersonal());
        assertNull(CreatorSource.record("w-mine"));
        assertTrue(released.contains(skin.sha()));
        loaded.clear();
        assertTrue(open());
        assertEquals("Mis figuras", CreatorSource.record("w-mine").name());
        assertEquals(2, CreatorSource.record("w-mine").figures().size());
        assertTrue(loaded.contains(skin.sha()));

        // A draft can be deleted for good, by naming it twice
        assertTrue(send(List.of(), new WorldCollectionEdit.Purge("w-mine", "w-mine")).ok());
        assertNull(CreatorSource.record("w-mine"));
        // And a datapack is the server's to import, not a draft's
        assertFalse(send(List.of(), new WorldCollectionEdit.ImportPack("other", "blockpops", "x", true)).ok());
    }

    @Test
    void theCreatorsSavingGoesBackToTheServerWhenTheDraftsAreClosed() {
        assertTrue(open());
        PersonalSession.close();
        // The stand-in server of these tests is no longer the host; the game's own is, as before any session
        assertEquals(ClientWorldUploads.GAME, ClientWorldUploads.host);
        ClientWorldUploads.host = server;
        assertTrue(ClientWorldUploads.edit(new WorldCollectionEdit.SetHolographic("w-col", false), outcome -> { }));
        assertEquals(1, server.edits.size());
        server.answer(Result.OK);
        // A session is not opened over a World save that is still on its way
        assertTrue(ClientWorldUploads.edit(new WorldCollectionEdit.SetHolographic("w-col", true), outcome -> { }));
        assertFalse(open());
        assertFalse(CreatorSource.isPersonal());
        server.answer(Result.OK);
        assertTrue(open());
    }
}
