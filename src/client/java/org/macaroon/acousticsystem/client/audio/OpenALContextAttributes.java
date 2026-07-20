package org.macaroon.acousticsystem.client.audio;

import org.lwjgl.openal.EXTEfx;

import java.nio.IntBuffer;

/** Builds the EFX context request before OpenAL creates its device context. */
public final class OpenALContextAttributes {
    private OpenALContextAttributes() {
    }

    public static boolean requestAuxiliarySends(IntBuffer attributes, int sends) {
        int start = attributes.position();
        int limit = attributes.limit();
        int terminator = -1;
        for (int index = start; index < limit; index += 2) {
            int attribute = attributes.get(index);
            if (attribute == 0) {
                terminator = index;
                break;
            }
            if (index + 1 < limit
                    && attribute == EXTEfx.ALC_MAX_AUXILIARY_SENDS) {
                attributes.put(index + 1, sends);
                return true;
            }
        }
        if (terminator < 0 || terminator + 2 >= attributes.capacity()) {
            return false;
        }

        int originalPosition = attributes.position();
        int expandedLimit = Math.max(limit, terminator + 3);
        attributes.limit(attributes.capacity());
        attributes.put(terminator, EXTEfx.ALC_MAX_AUXILIARY_SENDS);
        attributes.put(terminator + 1, sends);
        attributes.put(terminator + 2, 0);
        attributes.limit(expandedLimit);
        attributes.position(Math.min(originalPosition, expandedLimit));
        return true;
    }
}
