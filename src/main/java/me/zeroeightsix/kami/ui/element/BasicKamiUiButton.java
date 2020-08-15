package me.zeroeightsix.kami.ui.element;

import me.zeroeightsix.kami.util.ColourHolder;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;

import java.util.function.Consumer;

public class BasicKamiUiButton extends AbstractKamiUiElement {

    private String text;
    private Consumer<BasicKamiUiButton> onClickAction;

    private int hexColor;

    public BasicKamiUiButton(GuiScreen screen, String text, int x, int y, int height, int width, int hexColor, Consumer<BasicKamiUiButton> onClickAction) {
        super(screen);
        this.text = text;
        this.hexColor = hexColor;
        setX(x);
        setY(y);
        setHeight(height);
        setWidth(width);
        this.onClickAction = onClickAction;
    }

    @Override
    public void drawElement(int x, int y) {
        drawNormal();
    }

    private void drawNormal() {
        Gui.drawRect(this.getX(), this.getY(), this.getWidth(), this.getHeight(), ColourHolder.fromHex(hexColor, true).toJavaColour().getRGB());
    }

    @Override
    public boolean onMouseEngage(int x, int y, int button) {
        boolean isCursorOverButton = x >= this.getX() && x <= (this.getX() + this.getWidth()) && y >= this.getY() && y <= (this.getY() + this.getHeight());
        return isCursorOverButton && button == 0;
    }

    @Override
    public boolean onMouseRelease(int x, int y, int button) {
        boolean isCursorOverButton = x >= this.getX() && x <= (this.getX() + this.getWidth()) && y >= this.getY() && y <= (this.getY() + this.getHeight());
        if (isCursorOverButton && button == 0) {
            onClickAction.accept(this);
            return true;
        }
        return false;
    }
}
