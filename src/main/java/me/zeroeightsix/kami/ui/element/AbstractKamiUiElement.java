package me.zeroeightsix.kami.ui.element;

import net.minecraft.client.gui.GuiScreen;

/**
 * @author Sasha
 * This class outlines the basic functionality of a barebones IKamiUiElement
 */
public abstract class AbstractKamiUiElement implements IKamiUiElement {

    private int x;
    private int y;
    private int height;
    private int width;
    private GuiScreen screen;

    public AbstractKamiUiElement(GuiScreen screen) {
        this.screen = screen;
    }

    @Override
    public abstract void drawElement(int x, int y);

    @Override
    public abstract boolean onMouseEngage(int x, int y, int button);

    @Override
    public abstract boolean onMouseRelease(int x, int y, int button);

    @Override
    public int getX() {
        return x;
    }

    @Override
    public void setX(int x) {
        this.x = x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public void setY(int y) {
        this.y = y;
    }

    @Override
    public int getHeight() {
        return this.height;
    }

    @Override
    public int getWidth() {
        return this.width;
    }

    @Override
    public void setHeight(int height) {
        this.height = height;
    }

    @Override
    public void setWidth(int width) {
        this.width = width;
    }

    /**
     * @return the GuiScreen that this element is bound to
     */
    public GuiScreen getScreen() {
        return this.screen;
    }
}
