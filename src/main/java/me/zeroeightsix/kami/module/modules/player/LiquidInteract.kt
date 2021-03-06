package me.zeroeightsix.kami.module.modules.player

import me.zeroeightsix.kami.mixin.client.world.MixinBlockLiquid
import me.zeroeightsix.kami.module.Module

/**
 * @see MixinBlockLiquid
 */
@Module.Info(
        name = "LiquidInteract",
        category = Module.Category.PLAYER,
        description = "Place blocks on liquid!"
)
object LiquidInteract : Module()