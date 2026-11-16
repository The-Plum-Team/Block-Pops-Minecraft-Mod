package com.theplumteam.client.worldcontent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What ties a draft to the collection it was published as, kept beside the drafts. */
class PersonalLinksTest {
    private static final String ID = "p_k3v7q2m5x4ab";
    private static final String CODE = "maple-orbit-42";

    @TempDir
    Path game;

    private Path file() {
        return game.resolve("blockpops").resolve("creator").resolve("links.json");
    }

    @Test
    void aLinkAndTheAcceptedTermsAreKeptBetweenRuns() throws IOException {
        PersonalLinks links = PersonalLinks.load(game);
        assertTrue(links.link("w-mias").isEmpty());
        assertEquals(0, links.terms());
        assertTrue(links.acceptTerms(1));
        assertTrue(links.put("w-mias", new PersonalLinks.Link(ID, CODE, 3)));
        assertEquals("{\"schema\":1,\"terms\":1,\"links\":{\"w-mias\":{\"id\":\"" + ID + "\",\"code\":\"" + CODE + "\",\"rev\":3}}}",
                Files.readString(file()));

        PersonalLinks again = PersonalLinks.load(game);
        assertEquals(new PersonalLinks.Link(ID, CODE, 3), again.link("w-mias").orElseThrow());
        assertEquals(1, again.terms());
        // Terms once accepted stay accepted at the highest version, and a link can be forgotten
        assertTrue(again.acceptTerms(0));
        assertEquals(1, again.terms());
        assertTrue(again.remove("w-mias"));
        assertTrue(PersonalLinks.load(game).link("w-mias").isEmpty());
        // Only a draft is linked, and only to a player collection by its code
        assertFalse(again.put("p_aaaaaaaaaaaa", new PersonalLinks.Link(ID, CODE, 1)));
        assertThrows(IllegalArgumentException.class, () -> new PersonalLinks.Link("w-x", CODE, 1));
        assertThrows(IllegalArgumentException.class, () -> new PersonalLinks.Link(ID, "willow-media", 1));
        assertThrows(IllegalArgumentException.class, () -> new PersonalLinks.Link(ID, CODE, 0));
    }

    @Test
    void aFileThatDoesNotHoldTogetherLinksNothingAndIsSetAside() throws IOException {
        Files.createDirectories(file().getParent());
        String[] refused = {
            "not json",
            "{\"schema\":2,\"terms\":1,\"links\":{}}",
            "{\"schema\":1,\"terms\":1,\"links\":{},\"more\":true}",
            "{\"schema\":1,\"terms\":1,\"links\":{\"../../x\":{\"id\":\"" + ID + "\",\"code\":\"" + CODE + "\",\"rev\":1}}}",
            "{\"schema\":1,\"terms\":1,\"links\":{\"w-mias\":{\"id\":\"" + ID + "\",\"code\":\"" + CODE + "\",\"rev\":1,"
                    + "\"url\":\"https://example.org\"}}}",
            "{\"schema\":1,\"terms\":1,\"links\":{\"w-mias\":{\"id\":\"w-other\",\"code\":\"" + CODE + "\",\"rev\":1}}}",
            "{\"schema\":1,\"terms\":1.5,\"links\":{}}",
        };
        for (String text : refused) {
            Files.writeString(file(), text);
            PersonalLinks links = PersonalLinks.load(game);
            assertTrue(links.link("w-mias").isEmpty(), text);
            assertEquals(0, links.terms(), text);
            assertFalse(Files.exists(file()), text);
            assertEquals(text, Files.readString(file().resolveSibling("links.json.corrupt")));
        }
    }
}
