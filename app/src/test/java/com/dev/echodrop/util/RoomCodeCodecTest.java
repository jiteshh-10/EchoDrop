package com.dev.echodrop.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for {@link RoomCodeCodec}.
 */
public class RoomCodeCodecTest {

    @Test
    public void toScopeIdFromRawCode_validCode_returnsRoomPrefixedLowercase() {
        assertEquals("room:abcd5678", RoomCodeCodec.toScopeIdFromRawCode("ABCD5678"));
    }

    @Test
    public void toScopeIdFromRawCode_invalidCode_returnsEmpty() {
        assertEquals("", RoomCodeCodec.toScopeIdFromRawCode("ABC"));
    }

    @Test
    public void extractRawCode_prefixedScopeId_returnsNormalizedRawCode() {
        assertEquals("ABCD5678", RoomCodeCodec.extractRawCode("room:abcd5678"));
    }

    @Test
    public void extractRawCode_legacyRawCode_returnsNormalizedRawCode() {
        assertEquals("ABCD5678", RoomCodeCodec.extractRawCode("abcd-5678"));
    }

    @Test
    public void extractRawCode_invalidValue_returnsEmpty() {
        assertEquals("", RoomCodeCodec.extractRawCode("room:bad"));
    }
}
