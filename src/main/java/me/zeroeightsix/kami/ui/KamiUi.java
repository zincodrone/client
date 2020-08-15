package me.zeroeightsix.kami.ui;

import me.zeroeightsix.kami.ui.element.IKamiUiElement;
import me.zeroeightsix.kami.ui.element.KamiUiElementBackground;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import java.util.LinkedList;
import java.util.List;

/**
 * @author Sasha
 * <p>
 * This is where the code for the new KAMI ClickUI will live.
 * <p>
 * Key points:
 * - New user interface. Ordered my most often toggled modules by default.
 * - Searchbar, sort by relevancy
 * - Icons for modules(?)
 * - Simplisitic and lightweight, don't overdo it.
 * - Use Fiber for storing settings (depending on what settings there are).
 */
public class KamiUi extends GuiScreen {

    /**
     * The List of regstered IKamiUiElements. They are rendered from head to tail,
     * and user interactions are processed from head to tail.
     */
    private List<IKamiUiElement> uiElements = new LinkedList<>();

    /**
     * Initialise the UI
     * TODO: Create a seperate ctor for recovering a saved ui states (like x,y positions of elements).
     */
    public KamiUi() {
        this.mc = Minecraft.getMinecraft();
        uiElements.add(new KamiUiElementBackground(this));
    }

    public void showScreen() {
        mc.displayGuiScreen(this);
    }

    public void drawScreen() {
        uiElements.forEach(e -> e.drawElement(e.getX(), e.getY()));
    }

}
