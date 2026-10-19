package ru.hrp.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** Decorative panel widget, intentionally non-interactive. */
final class PanelWidget extends AbstractWidget {
    PanelWidget(int x, int y, int width, int height) {
        super(x, y, width, height, Component.empty());
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int right = x + width;
        int bottom = y + height;
        graphics.fill(x - 1, y - 1, right + 1, bottom + 1, 0xAA5F6EEA);
        graphics.fill(x, y, right, bottom, 0xF0161722);
        graphics.fill(x, y, right, y + 36, 0xFF202235);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        // Decorative panel has no narration.
    }
}
