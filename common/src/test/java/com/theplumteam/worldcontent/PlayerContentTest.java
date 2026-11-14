package com.theplumteam.worldcontent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.theplumteam.remote.RemoteException;
import com.theplumteam.remote.StrictJson;
import com.theplumteam.remote.player.ContentError;
import com.theplumteam.remote.player.JsonOut;
import com.theplumteam.remote.player.PlayerRules;
import com.theplumteam.remote.player.UploadIntent;
import com.theplumteam.worldcontent.WorldCollectionRecord.BoxFace;
import com.theplumteam.worldcontent.WorldCollectionRecord.Figure;
import com.theplumteam.worldcontent.WorldCollectionRecord.Kind;
import com.theplumteam.worldcontent.WorldCollectionRecord.LogoLayout;
import com.theplumteam.worldcontent.WorldCollectionRecord.Rarity;
import com.theplumteam.worldcontent.WorldCollectionRecord.RarityWeights;
import com.theplumteam.worldcontent.WorldCollectionRecord.Uv;
import com.theplumteam.worldcontent.WorldCollectionRecord.Variant;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static com.theplumteam.worldcontent.WorldCollectionRecordTest.BLOBS;
import static com.theplumteam.worldcontent.WorldCollectionRecordTest.LOGO;
import static com.theplumteam.worldcontent.WorldCollectionRecordTest.SKIN;
import static com.theplumteam.worldcontent.WorldCollectionRecordTest.plain;
import static com.theplumteam.worldcontent.WorldCollectionRecordTest.sha;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What players publish is held to more than what an operator uploads: bounded numbers, one
 * spelling of every file and record, and names of listed characters. And an image is known
 * again by how it looks.
 */
class PlayerContentTest {
    private static final String ID = "p_k3v7q2m5x4ab";
    private static final String GEO = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"description\":"
            + "{\"identifier\":\"geometry.x\",\"texture_width\":64,\"texture_height\":32},\"bones\":[{\"name\":\"root\","
            + "\"pivot\":[0,0,0],\"cubes\":[{\"origin\":[-4,0,-4],\"size\":[8,8,8],\"uv\":[0,0]}]}]}]}";
    private static final String ANIMATION = "{\"format_version\":\"1.8.0\",\"animations\":{\"Pose_Stand\":"
            + "{\"bones\":{\"root\":{\"rotation\":[0,0,0]}}}}}";

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private interface Action {
        void run() throws Exception;
    }

    private static ContentError refusal(Action action) {
        return assertThrows(WorldContentException.class, action::run).code();
    }

    // ---- Numbers

    @Test
    void aPublishedDocumentsNumbersAreShortAndNearOne() throws RemoteException {
        for (String good : new String[] {"0", "-0", "1", "-12.5", "123456789012345678901234", "1e6", "1E-6", "1.5e+6",
            "0.000001", "1e06", "-1.25E-06"}) {
            assertTrue(StrictJson.isPlayerNumber(good), good);
            assertEquals(1, StrictJson.parsePlayerObject(utf8("{\"a\":" + good + "}"), 1024).size(), good);
        }
        for (String bad : new String[] {"1234567890123456789012345", "1e7", "1E-7", "1e+7", "1e400", "1e-400",
            "1e2147483648", "0.0000000000000000000000001", "1e10", "1e000007"}) {
            assertFalse(StrictJson.isPlayerNumber(bad), bad);
            assertThrows(RemoteException.class, () -> StrictJson.parsePlayerObject(utf8("{\"a\":[" + bad + "]}"), 1024),
                    bad);
        }
        // Everything else the game reads keeps the numbers it always took
        assertEquals(1, StrictJson.parseObject(utf8("{\"a\":1234567890123456789012345e7}"), 1024).size());
        // And the rest of strict JSON holds for a published document too
        assertThrows(RemoteException.class, () -> StrictJson.parsePlayerObject(utf8("{\"a\":1,\"a\":2}"), 1024));
        assertThrows(RemoteException.class, () -> StrictJson.parsePlayerObject(utf8("{\"a\":NaN}"), 1024));
        assertThrows(RemoteException.class, () -> StrictJson.parsePlayerObject(utf8("{\"a\":1}"), 4));
    }

    // ---- Canonical JSON and the files' checks

    @Test
    void canonicalJsonIsTheSameTokensWithNothingBetweenThem() throws WorldContentException {
        String spaced = "{ \"a b\" : [ 1 ,\t2.50 , 1e3 ],\r\n \"c\\\" d\" : { \"e\" : \" \\\\\" , \"f\" : null } }";
        byte[] canonical = CanonicalJson.minify(utf8(spaced), 1024);
        assertEquals("{\"a b\":[1,2.50,1e3],\"c\\\" d\":{\"e\":\" \\\\\",\"f\":null}}",
                new String(canonical, StandardCharsets.UTF_8));
        // Canonical bytes are their own canonical form: the same array comes back
        assertSame(canonical, CanonicalJson.minify(canonical, 1024));
        CanonicalJson.requireCanonical(canonical);
        assertEquals(ContentError.JSON_NOT_CANONICAL, refusal(() -> CanonicalJson.requireCanonical(utf8(spaced))));
        assertEquals(ContentError.JSON_SYNTAX, refusal(() -> CanonicalJson.minify(utf8("{\"a\":1} x"), 1024)));
        assertEquals(ContentError.JSON_SYNTAX, refusal(() -> CanonicalJson.minify(utf8("{\"a\":1e9}"), 1024)));
        assertEquals(ContentError.JSON_SYNTAX, refusal(() -> CanonicalJson.minify(utf8("{\"a\":1}"), 4)));
        // A string's own spaces and escapes are tokens, kept as they are
        byte[] accents = utf8("{\"n\":\"caf\u00e9  \\u00e9 \\n\"}");
        assertSame(accents, CanonicalJson.minify(accents, 1024));
    }

    @Test
    void aPublishedModelOrAnimationIsCanonicalAndItsNumbersAreBounded() throws WorldContentException {
        BlobInfo geo = BlobType.GEO.validatePlayer(utf8(GEO));
        assertEquals(BlobType.GEO.validate(utf8(GEO)), geo);
        assertEquals(1, geo.cubes());
        BlobInfo animation = BlobType.ANIMATION.validatePlayer(utf8(ANIMATION));
        assertEquals(BlobType.ANIMATION.validate(utf8(ANIMATION)), animation);

        String spacedGeo = GEO.replace("\"bones\":", "\"bones\": ");
        assertEquals(geo.withBounds(ModelBounds.NONE).size() + 1, BlobType.GEO.validate(utf8(spacedGeo)).size());
        assertEquals(ContentError.JSON_NOT_CANONICAL, refusal(() -> BlobType.GEO.validatePlayer(utf8(spacedGeo))));
        assertEquals(ContentError.JSON_NOT_CANONICAL,
                refusal(() -> BlobType.ANIMATION.validatePlayer(utf8(ANIMATION + "\n"))));
        // A number an operator's file may hold but a published one may not
        String longNumber = GEO.replace("\"size\":[8,8,8]", "\"size\":[8,8,8.0000000000000000000000000]");
        assertEquals(1, BlobType.GEO.validate(utf8(longNumber)).cubes());
        assertEquals(ContentError.JSON_SYNTAX, refusal(() -> BlobType.GEO.validatePlayer(utf8(longNumber))));
        String exponent = ANIMATION.replace("[0,0,0]", "[0,0,1e-7]");
        assertEquals(ContentError.JSON_SYNTAX, refusal(() -> BlobType.ANIMATION.validatePlayer(utf8(exponent))));
        // The files' own rules still hold
        assertEquals(ContentError.GEO_MESH, refusal(() -> BlobType.GEO.validatePlayer(
                utf8(GEO.replace("\"pivot\":[0,0,0],", "\"pivot\":[0,0,0],\"poly_mesh\":{},")))));
        assertEquals(ContentError.ANIMATION_NO_STAND, refusal(() -> BlobType.ANIMATION.validatePlayer(
                utf8(ANIMATION.replace("Pose_Stand", "Pose_Sit")))));
    }

    // ---- Decoding and the fingerprint

    /** A canonical PNG of the given colour type and depth, each row with another of the five filters. */
    private static byte[] png(int width, int height, int colorType, int depth, byte[] samples, byte[] palette,
                              byte[] transparency) {
        int channels = switch (colorType) {
            case 2 -> 3;
            case 4 -> 2;
            case 6 -> 4;
            default -> 1;
        };
        int stride = (width * channels * depth + 7) / 8;
        int pixelBytes = Math.max(1, channels * depth / 8);
        byte[] raw = new byte[(stride + 1) * height];
        for (int row = 0; row < height; row++) {
            int filter = row % 5;
            raw[row * (stride + 1)] = (byte) filter;
            for (int i = 0; i < stride; i++) {
                int left = i >= pixelBytes ? samples[row * stride + i - pixelBytes] & 0xFF : 0;
                int up = row > 0 ? samples[(row - 1) * stride + i] & 0xFF : 0;
                int upLeft = row > 0 && i >= pixelBytes ? samples[(row - 1) * stride + i - pixelBytes] & 0xFF : 0;
                int estimate = left + up - upLeft;
                int byLeft = Math.abs(estimate - left);
                int byUp = Math.abs(estimate - up);
                int byUpLeft = Math.abs(estimate - upLeft);
                int predicted = switch (filter) {
                    case 1 -> left;
                    case 2 -> up;
                    case 3 -> (left + up) / 2;
                    case 4 -> byLeft <= byUp && byLeft <= byUpLeft ? left : byUp <= byUpLeft ? up : upLeft;
                    default -> 0;
                };
                raw[row * (stride + 1) + 1 + i] = (byte) (samples[row * stride + i] - predicted);
            }
        }
        Deflater deflater = new Deflater();
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (!deflater.finished()) {
            compressed.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        byte[] header = {(byte) (width >>> 24), (byte) (width >>> 16), (byte) (width >>> 8), (byte) width,
            (byte) (height >>> 24), (byte) (height >>> 16), (byte) (height >>> 8), (byte) height, (byte) depth,
            (byte) colorType, 0, 0, 0};
        chunk(out, "IHDR", header);
        if (palette != null) {
            chunk(out, "PLTE", palette);
        }
        if (transparency != null) {
            chunk(out, "tRNS", transparency);
        }
        chunk(out, "IDAT", compressed.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        int length = data.length;
        int sum = (int) crc.getValue();
        out.writeBytes(new byte[] {(byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length});
        out.writeBytes(name);
        out.writeBytes(data);
        out.writeBytes(new byte[] {(byte) (sum >>> 24), (byte) (sum >>> 16), (byte) (sum >>> 8), (byte) sum});
    }

    private static byte[] rgba(int[] argb) {
        byte[] samples = new byte[argb.length * 4];
        for (int i = 0; i < argb.length; i++) {
            samples[i * 4] = (byte) (argb[i] >> 16);
            samples[i * 4 + 1] = (byte) (argb[i] >> 8);
            samples[i * 4 + 2] = (byte) argb[i];
            samples[i * 4 + 3] = (byte) (argb[i] >>> 24);
        }
        return samples;
    }

    @Test
    void everyKindOfPngDecodesToItsPixels() throws WorldContentException {
        int width = 19;
        int height = 17;
        Random random = new Random(3);
        int[] argb = new int[width * height];
        for (int i = 0; i < argb.length; i++) {
            argb[i] = random.nextInt();
        }
        PngValidator.Image full = PngValidator.decode(png(width, height, 6, 8, rgba(argb), null, null));
        assertEquals(width, full.width());
        assertEquals(height, full.height());
        assertArrayEquals(argb, full.argb());

        // True colour without alpha, where one colour is named as fully clear
        byte[] rgb = new byte[argb.length * 3];
        int[] opaque = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            rgb[i * 3] = (byte) (argb[i] >> 16);
            rgb[i * 3 + 1] = (byte) (argb[i] >> 8);
            rgb[i * 3 + 2] = (byte) argb[i];
            opaque[i] = argb[i] | 0xFF000000;
        }
        assertArrayEquals(opaque, PngValidator.decode(png(width, height, 2, 8, rgb, null, null)).argb());
        int key = argb[5] & 0xFFFFFF;
        byte[] keyed = {0, (byte) (key >> 16), 0, (byte) (key >> 8), 0, (byte) key};
        int[] withKey = opaque.clone();
        for (int i = 0; i < withKey.length; i++) {
            if ((withKey[i] & 0xFFFFFF) == key) {
                withKey[i] = key;
            }
        }
        assertEquals(key, withKey[5]);
        assertArrayEquals(withKey, PngValidator.decode(png(width, height, 2, 8, rgb, null, keyed)).argb());

        // Grey with alpha
        byte[] greyAlpha = new byte[argb.length * 2];
        int[] greyPixels = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int grey = argb[i] & 0xFF;
            int alpha = argb[i] >>> 24;
            greyAlpha[i * 2] = (byte) grey;
            greyAlpha[i * 2 + 1] = (byte) alpha;
            greyPixels[i] = alpha << 24 | grey << 16 | grey << 8 | grey;
        }
        assertArrayEquals(greyPixels, PngValidator.decode(png(width, height, 4, 8, greyAlpha, null, null)).argb());

        // Grey and indexed pixels of 1, 2, 4 and 8 bits, packed from the high bit down
        for (int depth : new int[] {1, 2, 4, 8}) {
            int levels = 1 << depth;
            int stride = (width * depth + 7) / 8;
            byte[] packed = new byte[stride * height];
            int[] values = new int[argb.length];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int value = random.nextInt(levels);
                    values[y * width + x] = value;
                    int bit = x * depth;
                    packed[y * stride + bit / 8] |= (byte) (value << (8 - depth - bit % 8));
                }
            }
            int[] grey = new int[argb.length];
            int[] greyKeyed = new int[argb.length];
            for (int i = 0; i < grey.length; i++) {
                int level = values[i] * 255 / (levels - 1);
                grey[i] = 0xFF000000 | level << 16 | level << 8 | level;
                greyKeyed[i] = values[i] == 1 ? grey[i] & 0xFFFFFF : grey[i];
            }
            assertArrayEquals(grey, PngValidator.decode(png(width, height, 0, depth, packed, null, null)).argb(),
                    "grey " + depth);
            assertArrayEquals(greyKeyed,
                    PngValidator.decode(png(width, height, 0, depth, packed, null, new byte[] {0, 1})).argb(),
                    "keyed grey " + depth);

            byte[] palette = new byte[levels * 3];
            random.nextBytes(palette);
            // Only the first entries have an alpha of their own; the rest are opaque
            byte[] alphas = new byte[Math.max(1, levels / 2)];
            random.nextBytes(alphas);
            int[] indexed = new int[argb.length];
            for (int i = 0; i < indexed.length; i++) {
                int entry = values[i];
                int alpha = entry < alphas.length ? alphas[entry] & 0xFF : 0xFF;
                indexed[i] = alpha << 24 | (palette[entry * 3] & 0xFF) << 16 | (palette[entry * 3 + 1] & 0xFF) << 8
                        | palette[entry * 3 + 2] & 0xFF;
            }
            assertArrayEquals(indexed, PngValidator.decode(png(width, height, 3, depth, packed, palette, alphas)).argb(),
                    "indexed " + depth);
        }
        // What decodes is what validates, and nothing else
        assertEquals(ContentError.PNG_SIGNATURE, refusal(() -> PngValidator.decode(new byte[64])));
    }

    private static PngValidator.Image image(int width, int height, int[] argb) {
        return new PngValidator.Image(width, height, argb);
    }

    @Test
    void aFingerprintFollowsHowAnImageLooks() throws WorldContentException {
        // Brightness falling from left to right sets every bit; rising sets none
        int[] falling = new int[64 * 64];
        int[] rising = new int[64 * 64];
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int grey = 255 - x * 4;
                falling[y * 64 + x] = 0xFF000000 | grey << 16 | grey << 8 | grey;
                rising[y * 64 + x] = 0xFF000000 | (x * 4) << 16 | (x * 4) << 8 | x * 4;
            }
        }
        assertEquals(-1L, ImageHash.dhash(image(64, 64, falling)));
        assertEquals(0L, ImageHash.dhash(image(64, 64, rising)));
        assertEquals(64, ImageHash.distance(-1L, 0L));
        assertEquals(0, ImageHash.distance(0x1234L, 0x1234L));
        assertEquals(2, ImageHash.distance(0b1010L, 0b0110L));
        // A flat image has no brighter neighbour anywhere, and clear pixels count as white
        assertEquals(0L, ImageHash.dhash(image(16, 16, new int[256])));
        int[] clearToBlack = new int[32 * 32];
        for (int i = 0; i < clearToBlack.length; i++) {
            clearToBlack[i] = i % 32 < 16 ? 0x00000000 : 0xFF000000;
        }
        // White on the left of black: the cell the edge crosses is darker than the one before it
        // and brighter than the one after, so two bits of each row are set
        assertEquals(0x1818181818181818L, ImageHash.dhash(image(32, 32, clearToBlack)));

        // The same picture at twice the size, and with a little noise, stays within a few bits
        Random random = new Random(21);
        int[] blocks = new int[9 * 8];
        for (int i = 0; i < blocks.length; i++) {
            int grey = random.nextInt(256);
            blocks[i] = 0xFF000000 | grey << 16 | grey << 8 | grey;
        }
        int[] small = scaled(blocks, 9, 8, 8);
        int[] large = scaled(blocks, 9, 8, 16);
        long hash = ImageHash.dhash(image(72, 64, small));
        assertEquals(hash, ImageHash.dhash(image(144, 128, large)));
        int[] noisy = small.clone();
        for (int i = 0; i < noisy.length; i++) {
            int grey = Math.max(0, Math.min(255, (noisy[i] & 0xFF) + random.nextInt(7) - 3));
            noisy[i] = 0xFF000000 | grey << 16 | grey << 8 | grey;
        }
        assertTrue(ImageHash.distance(hash, ImageHash.dhash(image(72, 64, noisy))) <= 4);
        int[] other = new int[72 * 64];
        for (int i = 0; i < other.length; i++) {
            other[i] = random.nextInt() | 0xFF000000;
        }
        assertTrue(ImageHash.distance(hash, ImageHash.dhash(image(72, 64, scaled(randomBlocks(random), 9, 8, 8)))) > 8);

        // From a file: the fingerprint is of the decoded pixels, whatever the PNG's own form
        byte[] file = png(72, 64, 6, 8, rgba(small), null, null);
        assertEquals(hash, ImageHash.dhash(file));
    }

    private static int[] randomBlocks(Random random) {
        int[] blocks = new int[9 * 8];
        for (int i = 0; i < blocks.length; i++) {
            int grey = random.nextInt(256);
            blocks[i] = 0xFF000000 | grey << 16 | grey << 8 | grey;
        }
        return blocks;
    }

    private static int[] scaled(int[] pixels, int width, int height, int factor) {
        int[] out = new int[width * factor * height * factor];
        for (int y = 0; y < height * factor; y++) {
            for (int x = 0; x < width * factor; x++) {
                out[y * width * factor + x] = pixels[y / factor * width + x / factor];
            }
        }
        return out;
    }

    // ---- The record as it is published

    private static WorldCollectionRecord full() {
        Figure skin = new Figure("zombi", "Zombi", Kind.SKIN, SKIN, null, null, 0.75f, true, Rarity.LEGENDARY,
                List.of(new Variant("Verde", SKIN)), new BoxFace(false, null));
        Figure retired = new Figure("viejo", "Viejo", Kind.SKIN, SKIN, null, null, 1.0f, false);
        return new WorldCollectionRecord("w-col", "Mis figuras", "Me", 10, 20, 30, null, "red", LOGO, false, 7,
                List.of(skin, retired), false, 1_700_000_000L, "https://example.com/me", new LogoLayout(120, 4, -2),
                true, new RarityWeights(10, 5, 2, 1), false);
    }

    private static WorldCollectionRecord parsePlayer(JsonObject json) throws WorldContentException {
        return WorldCollectionRecord.parsePlayer(utf8(JsonOut.write(json)), BLOBS, ID, "Steve", 5);
    }

    @Test
    void thePublishedFormLeavesOutTheWorldsAndThePublishersKeys() throws WorldContentException {
        WorldCollectionRecord record = full();
        JsonObject published = record.toPlayerJson();
        assertEquals(Set.of("name", "background_color", "box", "logo", "figures", "logo_layout", "plush",
                "rarity_weights", "holographic"), published.keySet());
        assertEquals("{\"name\":\"Mis figuras\",\"background_color\":[10,20,30],\"box\":{\"color\":\"red\"},\"logo\":\""
                + LOGO + "\",\"figures\":[{\"id\":\"zombi\",\"name\":\"Zombi\",\"kind\":\"skin\",\"texture\":\"" + SKIN
                + "\",\"model\":null,\"animation\":null,\"scale\":0.75,\"rarity\":\"legendary\",\"variants\":[{\"name\":"
                + "\"Verde\",\"texture\":\"" + SKIN + "\"}],\"box_face\":{\"show\":false}}],\"logo_layout\":{\"scale_pct\":"
                + "120,\"offset_x\":4,\"offset_y\":-2},\"plush\":true,\"rarity_weights\":[10,5,2,1],\"holographic\":false}",
                new String(record.toPlayerUtf8(), StandardCharsets.UTF_8));
        assertEquals(Set.of("name", "background_color", "box", "logo", "figures"), plain().toPlayerJson().keySet());

        WorldCollectionRecord read = WorldCollectionRecord.parsePlayer(record.toPlayerUtf8(), BLOBS, ID, "Steve", 5);
        assertEquals(ID, read.id());
        assertEquals("Steve", read.author());
        assertEquals(5, read.revision());
        assertEquals(1, read.figures().size());
        assertTrue(read.figures().get(0).enabled());
        assertFalse(read.draft() || read.archived());
        assertEquals(0L, read.editedAt());
        assertEquals(null, read.authorUrl());
        assertArrayEquals(record.toPlayerUtf8(), read.toPlayerUtf8());
        assertEquals(record.effectiveLogoLayout(), read.effectiveLogoLayout());
        assertEquals("", WorldCollectionRecord.parsePlayer(record.toPlayerUtf8(), BLOBS, ID, "", 1).author());
        // The World form of the same record is untouched by any of this
        assertEquals(record, WorldCollectionRecord.parse(record.toUtf8(), BLOBS, AuthorLinks.RULE));
        assertThrows(IllegalArgumentException.class,
                () -> WorldCollectionRecord.parsePlayer(record.toPlayerUtf8(), BLOBS, "w-col", "Steve", 5));
        assertThrows(IllegalArgumentException.class,
                () -> WorldCollectionRecord.parsePlayer(record.toPlayerUtf8(), BLOBS, ID, "Not a name", 5));
    }

    @Test
    void aPublishedRecordHasOneSpelling() throws WorldContentException {
        String text = new String(plain().toPlayerUtf8(), StandardCharsets.UTF_8);
        for (String respelled : new String[] {text + "\n", text.replace("\"scale\":1.0", "\"scale\":1"),
            text.replace("[10,20,30]", "[10, 20, 30]"), text.replace("[10,20,30]", "[1e1,20,30]"),
            text.replace("\"name\":\"Col\"", "\"name\":\"C\\u006fl\"")}) {
            assertNotEquals(text, respelled);
            assertEquals(ContentError.JSON_NOT_CANONICAL,
                    refusal(() -> WorldCollectionRecord.parsePlayer(utf8(respelled), BLOBS, ID, "Steve", 1)), respelled);
        }
        // Keys out of order: read as the same record, but not as it would write itself
        JsonObject json = plain().toPlayerJson();
        JsonObject reordered = new JsonObject();
        reordered.add("figures", json.get("figures"));
        for (String key : List.of("name", "background_color", "box", "logo")) {
            reordered.add(key, json.get(key));
        }
        assertEquals(ContentError.JSON_NOT_CANONICAL, refusal(() -> parsePlayer(reordered)));
        assertEquals(ContentError.JSON_SYNTAX, refusal(() -> WorldCollectionRecord.parsePlayer(
                utf8(text.replace("[10,20,30]", "[10,20,30.0000000000000000000000000]")), BLOBS, ID, "Steve", 1)));
        assertEquals(ContentError.RECORD_BYTES, refusal(() -> WorldCollectionRecord.parsePlayer(
                new byte[WorldContentLimits.RECORD_BYTES + 1], BLOBS, ID, "Steve", 1)));
    }

    @Test
    void aPublishedRecordKeepsToItsOwnGrammar() {
        for (String worldKey : List.of("id", "author", "author_url", "archived", "revision", "draft", "edited_at")) {
            JsonObject json = plain().toPlayerJson();
            json.add(worldKey, full().toJson().get(worldKey));
            assertEquals(ContentError.JSON_UNKNOWN_KEY, refusal(() -> parsePlayer(json)), worldKey);
        }
        JsonObject enabled = plain().toPlayerJson();
        enabled.getAsJsonArray("figures").get(0).getAsJsonObject().addProperty("enabled", true);
        assertEquals(ContentError.JSON_UNKNOWN_KEY, refusal(() -> parsePlayer(enabled)));
        JsonObject noFigures = plain().toPlayerJson();
        noFigures.add("figures", new JsonArray());
        assertEquals(ContentError.RECORD_FIGURES, refusal(() -> parsePlayer(noFigures)));
        JsonObject missing = plain().toPlayerJson();
        missing.remove("logo");
        assertEquals(ContentError.JSON_MISSING_KEY, refusal(() -> parsePlayer(missing)));
        JsonObject noFile = plain().toPlayerJson();
        noFile.addProperty("logo", sha("absent"));
        assertEquals(ContentError.FILE_MISSING, refusal(() -> parsePlayer(noFile)));

        // Names: what a World record takes and a published one does not
        for (String name : List.of("\u00a7kcool", "a\u00a0b", "a\u00adb", "\u043f\u0440\u0438\u0432\u0435\u0442", "caf\u0065\u0301",
                "a_b")) {
            JsonObject json = plain().toPlayerJson();
            json.addProperty("name", name);
            assertEquals(ContentError.TEXT_CHARACTERS, refusal(() -> parsePlayer(json)), name);
            JsonObject figure = plain().toPlayerJson();
            figure.getAsJsonArray("figures").get(0).getAsJsonObject().addProperty("name", name);
            assertEquals(ContentError.TEXT_CHARACTERS, refusal(() -> parsePlayer(figure)), name);
        }
        for (String name : List.of(" Col", "Col ", "Mis  figuras")) {
            JsonObject json = plain().toPlayerJson();
            json.addProperty("name", name);
            assertEquals(ContentError.TEXT_SPACES, refusal(() -> parsePlayer(json)), name);
        }
        JsonObject empty = plain().toPlayerJson();
        empty.addProperty("name", "");
        assertEquals(ContentError.TEXT_EMPTY, refusal(() -> parsePlayer(empty)));
        JsonObject variant = full().toPlayerJson();
        variant.getAsJsonArray("figures").get(0).getAsJsonObject().getAsJsonArray("variants").get(0).getAsJsonObject()
                .addProperty("name", "Verde\u00a7r");
        assertEquals(ContentError.TEXT_CHARACTERS, refusal(() -> parsePlayer(variant)));
    }

    @Test
    void aPublishedCollectionNamesAtMostSoManyFiles() throws WorldContentException {
        // The most a record can name: 128 model figures of a texture, a model and poses each, 128
        // variants among them, a box and a logo. That is 514 files, two more than may be published.
        Map<String, BlobInfo> blobs = new HashMap<>();
        List<Figure> figures = new ArrayList<>();
        for (int i = 0; i < WorldContentLimits.MAX_FIGURES; i++) {
            String texture = sha("texture " + i);
            String model = sha("model " + i);
            String poses = sha("poses " + i);
            String variant = sha("variant " + i);
            blobs.put(texture, BlobInfo.png(100, 64, 64));
            blobs.put(variant, BlobInfo.png(100, 64, 64));
            blobs.put(model, BlobInfo.geo(100, 1, 64, 64, ModelBounds.NONE));
            blobs.put(poses, BlobInfo.animation(100, 1, BlobInfo.POSE_STAND));
            figures.add(new Figure("f" + i, "F" + i, Kind.MODEL, texture, model, poses, 1.0f, true, Rarity.COMMON,
                    List.of(new Variant("V" + i, variant)), null));
        }
        String box = sha("box");
        String logo = sha("logo file");
        blobs.put(box, BlobInfo.png(100, 64, 64));
        blobs.put(logo, BlobInfo.png(100, 32, 32));
        WorldCollectionRecord most = new WorldCollectionRecord("w-col", "Col", "", 1, 2, 3, box, null, logo, false, 0,
                figures);
        assertEquals(PlayerRules.MAX_BLOBS + 2, most.referencedShas().size());
        assertEquals(ContentError.BUDGET_FILES,
                refusal(() -> WorldCollectionRecord.parsePlayer(most.toPlayerUtf8(), blobs, ID, "", 1)));
        // As a World collection it is within every cap
        assertEquals(most, WorldCollectionRecord.parse(most.toUtf8(), blobs, AuthorLinks.RULE));
        WorldCollectionRecord plainBox = most.withIdentity("Col", "", 1, 2, 3, null, "red", null);
        assertEquals(PlayerRules.MAX_BLOBS, plainBox.referencedShas().size());
        assertEquals(WorldContentLimits.MAX_FIGURES,
                WorldCollectionRecord.parsePlayer(plainBox.toPlayerUtf8(), blobs, ID, "", 1).figures().size());
    }

    @Test
    void aSessionIsBoundToAnIntentsDigest() {
        UploadIntent intent = UploadIntent.list();
        assertEquals("10ab1d3a2e3bd8f5b3e2b6f0b5ab5d2ae7dc0bca2ee4f9bd3d0d3d6d2f4cb0e8".length(),
                intent.digest(JdkSha256.INSTANCE).length());
        assertEquals(JdkSha256.of(utf8("list")), intent.digest(JdkSha256.INSTANCE));
        String nonce = "0123456789abcdef0123456789abcdef";
        assertEquals("blockpops-upload-v1\n" + nonce + "\n" + intent.digest(JdkSha256.INSTANCE),
                UploadIntent.joinText(nonce, intent.digest(JdkSha256.INSTANCE)));
        assertThrows(IllegalArgumentException.class,
                () -> UploadIntent.joinText(nonce.substring(1), intent.digest(JdkSha256.INSTANCE)));
        assertThrows(IllegalArgumentException.class,
                () -> UploadIntent.joinText(nonce.toUpperCase(java.util.Locale.ROOT), intent.digest(JdkSha256.INSTANCE)));
        assertThrows(IllegalArgumentException.class, () -> UploadIntent.joinText(nonce, "list"));
    }
}
