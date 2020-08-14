package me.zeroeightsix.kami.ui.element;

import net.minecraft.client.gui.GuiScreen;

public class KamiUiElementBackground extends AbstractKamiUiElement {

    public KamiUiElementBackground(GuiScreen screen) {
        super(screen);
    }

    @Override
    public void drawElement(int x, int y) {
        getScreen().drawWorldBackground(0); // draw a default gray transparent background over the screen.
    }

    @Override
    public boolean onMouseEngage(int x, int y, int button) {
        return false;
    }

    @Override
    public boolean onMouseRelease(int x, int y, int button) {
        return false;
    }
}
