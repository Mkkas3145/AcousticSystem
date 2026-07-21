package org.macaroon.acousticsystem.client.screen;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.OptionsList;

/** Widget layout shared by Minecraft 26.1.2 and 26.2. */
public final class OptionsListCompat {
    private static final int BIG_OPTION_WIDTH = 310;

    private OptionsListCompat() {
    }

    public static void addBig(OptionsList list, AbstractWidget widget) {
        widget.setWidth(BIG_OPTION_WIDTH);
        list.addSmall(widget, null);
    }
}
