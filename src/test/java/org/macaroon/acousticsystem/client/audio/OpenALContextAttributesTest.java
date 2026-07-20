package org.macaroon.acousticsystem.client.audio;

import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.EXTEfx;

import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenALContextAttributesTest {
    @Test
    void appendsSendRequestToVanillaAttributeBuffer() {
        IntBuffer attributes = BufferUtils.createIntBuffer(11);
        attributes.put(0x1992).put(1);
        attributes.put(0x199A).put(1);
        attributes.put(0).flip();

        assertTrue(OpenALContextAttributes.requestAuxiliarySends(attributes, 3));
        assertEquals(7, attributes.limit());
        assertEquals(EXTEfx.ALC_MAX_AUXILIARY_SENDS, attributes.get(4));
        assertEquals(3, attributes.get(5));
        assertEquals(0, attributes.get(6));
    }

    @Test
    void updatesExistingSendRequestWithoutChangingBufferShape() {
        IntBuffer attributes = BufferUtils.createIntBuffer(7);
        attributes.put(EXTEfx.ALC_MAX_AUXILIARY_SENDS).put(2).put(0).flip();

        assertTrue(OpenALContextAttributes.requestAuxiliarySends(attributes, 3));
        assertEquals(3, attributes.limit());
        assertEquals(3, attributes.get(1));
    }
}
