package me.zeroeightsix.kami.ui.element;

public interface IKamiUiElement {

    /**
     * Draw the element to the screen
     *
     * @param x X coordinate of where to draw (2D space)
     * @param y Y coordinate of where to draw (2D space)
     */
    void drawElement(int x, int y);

    /**
     * Invoked when the pointing device is engaged on the KamiUi GuiScreen instance
     *
     * @param x      The X position of the cursor when the pointing device is engaged
     * @param y      The Y position of the cursor when the pointing device is engaged
     * @param button The button on the pointing device that was engaged
     * @return Whether to stop the iteration of active IKamiUiElements after executing this code.
     */
    boolean onMouseEngage(int x, int y, int button);

    /**
     * Invoked when the pointing device is released on the KamiUi GuiScreen instance
     *
     * @param x      The X position of the cursor when the pointing device is released
     * @param y      The Y position of the cursor when the pointing device is released
     * @param button The button on the pointing device that was released
     * @return Whether to stop the iteration of registered IKamiUiElements after executing this code.
     */
    boolean onMouseRelease(int x, int y, int button);

    int getX();

    void setX(int x);

    int getY();

    void setY(int y);

    int getHeight();

    int getWidth();

    void setHeight(int height);

    void setWidth(int height);

}
