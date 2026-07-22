package org.macaroon.acousticsystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AcousticSystem {
    public static final String MOD_ID = "acousticsystem";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private AcousticSystem() {
    }

    public static void initialize() {
        LOGGER.info("AcousticSystem initialized");
    }
}
