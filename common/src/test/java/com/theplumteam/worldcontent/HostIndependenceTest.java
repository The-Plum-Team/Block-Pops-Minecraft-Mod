package com.theplumteam.worldcontent;

import com.google.gson.JsonObject;
import com.theplumteam.remote.CoreText;
import com.theplumteam.remote.JsonNumber;
import com.theplumteam.remote.RemoteException;
import com.theplumteam.remote.StrictJson;
import com.theplumteam.remote.player.ContentError;
import com.theplumteam.remote.player.JsonOut;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the content core does for itself, instead of leaving it to its host, gives exactly what
 * the JDK gives: so the game behaves as it did, and a host that is not the JDK agrees with it.
 */
class HostIndependenceTest {
    private static String jdkDecode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    @Test
    void utf8IsDecodedAsTheJdkDecodesIt() {
        // Every code point, encoded by the JDK
        StringBuilder all = new StringBuilder();
        for (int cp = 0; cp <= 0x10FFFF; cp++) {
            if (cp < 0xD800 || cp > 0xDFFF) {
                all.appendCodePoint(cp);
            }
        }
        assertEquals(all.toString(), CoreText.decodeUtf8(all.toString().getBytes(StandardCharsets.UTF_8)));

        // The forms a strict decoder refuses, each named
        int[][] refused = {
            {0xC0, 0xAF}, {0xC1, 0xBF}, {0xE0, 0x80, 0xAF}, {0xE0, 0x9F, 0xBF}, {0xF0, 0x80, 0x80, 0xAF},
            {0xF0, 0x8F, 0xBF, 0xBF}, {0xED, 0xA0, 0x80}, {0xED, 0xBF, 0xBF}, {0xF4, 0x90, 0x80, 0x80},
            {0xF5, 0x80, 0x80, 0x80}, {0xF8, 0x88, 0x80, 0x80, 0x80}, {0xFC, 0x84, 0x80, 0x80, 0x80, 0x80},
            {0xFE}, {0xFF}, {0x80}, {0xBF}, {0xC2}, {0xE2, 0x82}, {0xF0, 0x9F, 0x98}, {0xC2, 0x41},
            {0xE2, 0x28, 0xA1}, {0xE2, 0x82, 0x28}, {0xF0, 0x28, 0x8C, 0xBC}, {0x41, 0xC2}, {0xC0, 0x80}};
        for (int[] ints : refused) {
            byte[] bytes = new byte[ints.length];
            for (int i = 0; i < ints.length; i++) {
                bytes[i] = (byte) ints[i];
            }
            assertNull(jdkDecode(bytes));
            assertNull(CoreText.decodeUtf8(bytes));
        }
        // Noncharacters are text; so is an empty input
        assertEquals("￾￿", CoreText.decodeUtf8(new byte[] {(byte) 0xEF, (byte) 0xBF, (byte) 0xBE, (byte) 0xEF,
            (byte) 0xBF, (byte) 0xBF}));
        assertEquals("", CoreText.decodeUtf8(new byte[0]));

        // And anything at all: random bytes, biased towards the bytes that start and continue sequences
        Random random = new Random(17);
        int[] interesting = {0x00, 0x41, 0x7F, 0x80, 0xBF, 0xC0, 0xC1, 0xC2, 0xDF, 0xE0, 0xED, 0xEF, 0xF0, 0xF4, 0xF5,
            0xFF, 0x9F, 0xA0, 0x8F, 0x90};
        for (int round = 0; round < 400_000; round++) {
            byte[] bytes = new byte[1 + random.nextInt(6)];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) (random.nextInt(4) == 0 ? random.nextInt(256)
                        : interesting[random.nextInt(interesting.length)]);
            }
            assertEquals(jdkDecode(bytes), CoreText.decodeUtf8(bytes));
        }
    }

    @Test
    void blankIsWhatTheJdkCallsBlank() {
        for (int cp = 0; cp <= 0x10FFFF; cp++) {
            if (cp >= 0xD800 && cp <= 0xDFFF) {
                continue;
            }
            String one = new String(Character.toChars(cp));
            assertEquals(one.isBlank(), CoreText.isBlank(one), Integer.toHexString(cp));
        }
        for (String text : new String[] {"", " ", " \t\n\r ", " a ", " ", "  　", "a"}) {
            assertEquals(text.isBlank(), CoreText.isBlank(text), text);
        }
    }

    @Test
    void sineAndCosineAreStrictMathsToTheBit() {
        Random random = new Random(29);
        // Every angle a model can turn by, in the steps a file would write: degrees to radians as the check does
        for (int thousandths = -3_600_000; thousandths <= 3_600_000; thousandths += 7) {
            same(thousandths / 1000.0 * 0.017453292519943295);
        }
        for (int i = 0; i < 2_000_000; i++) {
            same((random.nextDouble() * 2 - 1) * 823_549);
            same((random.nextDouble() * 2 - 1) * 8);
            same(random.nextGaussian() * 1e-6);
        }
        for (double x : new double[] {0.0, -0.0, Double.MIN_VALUE, -Double.MIN_VALUE, 1e-300, 1e-9, 0.5, 1.0,
            Math.PI / 4, Math.PI / 2, Math.PI, 3 * Math.PI / 2, 2 * Math.PI, 0.7853981633974483, 0.7853981633974484,
            1.5707963267948966, 1.5707963267948968, 3.141592653589793, 100 * Math.PI, 823_549.0, -823_549.0}) {
            same(x);
            same(-x);
            same(Math.nextUp(x));
            same(Math.nextDown(x));
        }
        // Around every multiple of pi/2 the reduction handles, where it is most delicate
        for (int k = 1; k <= 524_287; k += 13) {
            double near = k * (Math.PI / 2);
            same(near);
            same(Math.nextUp(near));
            same(Math.nextDown(near));
        }
        assertTrue(Double.isNaN(CoreMath.sin(Double.NaN)));
        assertTrue(Double.isNaN(CoreMath.cos(Double.POSITIVE_INFINITY)));
        assertThrows(IllegalArgumentException.class, () -> CoreMath.sin(1e9));
        assertThrows(IllegalArgumentException.class, () -> CoreMath.cos(-1e9));
        assertEquals(0.017453292519943295, Math.toRadians(1.0));
        for (double degrees : new double[] {0.5, 22.5, 45, 90, 137.25, -359.999, 3600}) {
            assertEquals(Double.doubleToRawLongBits(Math.toRadians(degrees)),
                    Double.doubleToRawLongBits(degrees * 0.017453292519943295));
        }
    }

    private static void same(double x) {
        assertEquals(Double.doubleToRawLongBits(StrictMath.sin(x)), Double.doubleToRawLongBits(CoreMath.sin(x)),
                () -> "sin " + x);
        assertEquals(Double.doubleToRawLongBits(StrictMath.cos(x)), Double.doubleToRawLongBits(CoreMath.cos(x)),
                () -> "cos " + x);
    }

    @Test
    void aNumbersDoubleIsTheOneItsExactValueGives() {
        Random random = new Random(31);
        for (int i = 0; i < 1_500_000; i++) {
            StringBuilder token = new StringBuilder();
            if (random.nextInt(4) == 0) {
                token.append('-');
            }
            int whole = random.nextInt(4) == 0 ? 0 : 1 + random.nextInt(19);
            if (whole == 0) {
                token.append('0');
            } else {
                token.append((char) ('1' + random.nextInt(9)));
                for (int d = 1; d < whole; d++) {
                    token.append((char) ('0' + random.nextInt(10)));
                }
            }
            if (random.nextBoolean()) {
                token.append('.');
                int fraction = 1 + random.nextInt(20);
                for (int d = 0; d < fraction; d++) {
                    token.append((char) ('0' + (random.nextInt(3) == 0 ? 0 : random.nextInt(10))));
                }
            }
            if (random.nextInt(3) == 0) {
                token.append(random.nextBoolean() ? 'e' : 'E');
                int sign = random.nextInt(3);
                token.append(sign == 0 ? "" : sign == 1 ? "+" : "-");
                token.append(random.nextInt(random.nextInt(8) == 0 ? 400 : 30));
            }
            same(token.toString());
        }
        for (String token : new String[] {"0", "-0", "0.0", "-0.0", "0e5", "1", "-1", "0.1", "0.3", "1e22", "1e23",
            "1e-22", "1e-23", "9007199254740993", "9007199254740992", "123456789012345", "1234567890123456",
            "0.000001", "4.9e-324", "2.4703282292062327e-324", "2.4703282292062328e-324", "1.7976931348623157e308",
            "1.7976931348623159e308", "1e400", "-1e400", "1e-400", "2.2250738585072011e-308", "0.1e1", "100e-2",
            "000.5".substring(2), "5e000", "1.0000000000000002", "1.00000000000000011102230246251565404236316680908203125"}) {
            same(token);
        }
    }

    private static void same(String token) {
        double exact = new BigDecimal(token).doubleValue();
        JsonNumber number = new JsonNumber(token, false);
        assertEquals(Double.doubleToRawLongBits(exact), Double.doubleToRawLongBits(number.doubleValue()), token);
        assertEquals(token, number.toString());
        assertEquals(new BigDecimal(token), number.decimal());
    }

    @Test
    void aParsedDocumentKeepsItsNumbersAsWrittenAndReadsThemAsBefore() throws RemoteException, WorldContentException {
        String document = "{\"a\":[1,-0,2.50,1e3,1E-7,123456789012345678901234567890,0.1,1.0],\"b\":0.75}";
        JsonObject parsed = StrictJson.parseObject(document.getBytes(StandardCharsets.UTF_8), 1024);
        assertEquals(document, JsonOut.write(parsed));
        assertEquals(0.75, JsonFields.number(parsed.get("b"), "b", 0, 1));
        assertEquals(new BigDecimal("0.75"), JsonFields.decimal(parsed.get("b"), "b"));
        assertEquals(0.75f, parsed.get("b").getAsFloat());
        assertEquals(2, parsed.getAsJsonArray("a").get(2).getAsInt());
        assertEquals(1000L, parsed.getAsJsonArray("a").get(3).getAsLong());
        assertEquals(1000.0, JsonFields.number(parsed.getAsJsonArray("a").get(3), "a", 0, 1e6));
        assertEquals(1, JsonFields.integer(parsed.getAsJsonArray("a").get(7), "a", 0, 9));
        assertEquals(ContentError.JSON_TYPE, assertThrows(WorldContentException.class,
                () -> JsonFields.integer(parsed.get("b"), "b", 0, 9)).code());
        assertEquals(ContentError.JSON_RANGE, assertThrows(WorldContentException.class,
                () -> JsonFields.number(parsed.getAsJsonArray("a").get(5), "a", 0, 1e6)).code());
        assertEquals(parsed, StrictJson.parseObject(document.getBytes(StandardCharsets.UTF_8), 1024));
        // A number the exact reader refuses still fails the parse of an operator's or an official file
        assertThrows(RemoteException.class,
                () -> StrictJson.parseObject("{\"a\":1e99999999999}".getBytes(StandardCharsets.UTF_8), 1024));
        // The key reported missing is the first in order, whatever order a set happens to iterate in
        for (int i = 0; i < 20; i++) {
            WorldContentException missing = assertThrows(WorldContentException.class,
                    () -> JsonFields.keys(new JsonObject(), "thing", Set.of("zeta", "alpha", "mid", "beta"), Set.of()));
            assertEquals("thing is missing alpha", missing.getMessage());
        }
        assertFalse(StrictJson.isPlayerNumber("1e7"));
    }
}
