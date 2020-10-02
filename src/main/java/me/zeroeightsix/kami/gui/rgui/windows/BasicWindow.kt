package me.zeroeightsix.kami.gui.rgui.windows

import me.zeroeightsix.kami.module.modules.client.GuiColors
import me.zeroeightsix.kami.util.graphics.RenderUtils2D
import me.zeroeightsix.kami.util.graphics.VertexHelper
import me.zeroeightsix.kami.util.math.Vec2d

/**
 * Window with rectangle rendering
 */
open class BasicWindow(
        name: String,
        posX: Double,
        posY: Double,
        width: Double,
        height: Double
) : CleanWindow(name, posX, posY, width, height) {
    override fun onRender(vertexHelper: VertexHelper) {
        super.onRender(vertexHelper)
        RenderUtils2D.drawRectFilled(vertexHelper, Vec2d(0.0, 0.0), Vec2d(renderWidth, renderHeight), GuiColors.backGround)
        RenderUtils2D.drawRectOutline(vertexHelper, Vec2d(0.0, 0.0), Vec2d(renderWidth, renderHeight), 2.5f, GuiColors.primary)
    }
}