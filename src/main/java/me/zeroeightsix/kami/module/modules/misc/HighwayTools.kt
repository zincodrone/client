package me.zeroeightsix.kami.module.modules.misc

import baritone.api.BaritoneAPI
import me.zeroeightsix.kami.KamiMod
import me.zeroeightsix.kami.event.events.RenderEvent
import me.zeroeightsix.kami.module.Module
import me.zeroeightsix.kami.module.ModuleManager
import me.zeroeightsix.kami.module.modules.player.LagNotifier
import me.zeroeightsix.kami.module.modules.player.NoBreakAnimation
import me.zeroeightsix.kami.setting.Setting
import me.zeroeightsix.kami.setting.Settings
import me.zeroeightsix.kami.util.*
import me.zeroeightsix.kami.util.color.ColorHolder
import me.zeroeightsix.kami.util.graphics.ESPRenderer
import me.zeroeightsix.kami.util.graphics.GeometryMasks
import me.zeroeightsix.kami.util.math.RotationUtils
import me.zeroeightsix.kami.util.text.MessageSendHelper
import net.minecraft.block.*
import net.minecraft.block.Block.*
import net.minecraft.client.audio.PositionedSoundRecord
import net.minecraft.entity.item.EntityItem
import net.minecraft.entity.item.EntityXPOrb
import net.minecraft.init.SoundEvents
import net.minecraft.network.play.client.CPacketEntityAction
import net.minecraft.network.play.client.CPacketPlayerDigging
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.ResourceLocation
import net.minecraft.util.math.AxisAlignedBB
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraftforge.fml.common.registry.ForgeRegistries
import java.util.*
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * @author Avanatiker
 * @since 20/08/2020
 */

@Module.Info(
        name = "HighwayTools",
        description = "Even Better High-ways for the greater good.",
        category = Module.Category.MISC
)
class HighwayTools : Module() {
    private val mode = register(Settings.e<Mode>("Mode", Mode.HIGHWAY))
    val baritoneMode: Setting<Boolean> = register(Settings.b("Baritone", true))
    private val blocksPerTick = register(Settings.integerBuilder("Blocks Per Tick").withMinimum(1).withValue(1).withMaximum(9).build())
    private val tickDelay = register(Settings.integerBuilder("Tick-Delay Place").withMinimum(0).withValue(0).withMaximum(10).build())
    private val tickDelayBreak = register(Settings.integerBuilder("Tick-Delay Break").withMinimum(0).withValue(0).withMaximum(10).build())
    private val rotate = register(Settings.b("Rotate", true))
    private val filled = register(Settings.b("Filled", true))
    private val outline = register(Settings.b("Outline", true))
    private val aFilled = register(Settings.integerBuilder("Filled Alpha").withMinimum(0).withValue(31).withMaximum(255).withVisibility { filled.value }.build())
    private val aOutline = register(Settings.integerBuilder("Outline Alpha").withMinimum(0).withValue(127).withMaximum(255).withVisibility { outline.value }.build())

    private var playerHotbarSlot = -1
    private var lastHotbarSlot = -1
    private var isSneaking = false
    var pathing = true
    private var buildDirectionSaved = 0
    private var buildDirectionCoordinate = 0
    private val directions = listOf("North", "North-East", "East", "South-East", "South", "South-West", "West", "North-West")

    //Stats
    private var totalBlocksPlaced = 0
    var totalBlocksDestroyed = 0
    private var totalBlocksDistance = 0
    
    //Custom settings
    @JvmField
    var material: Block = ForgeRegistries.BLOCKS.getValue(ResourceLocation("minecraft", "obsidian"))!!
    var ignoreBlocks = mutableListOf(ForgeRegistries.BLOCKS.getValue(ResourceLocation("minecraft", "standing_sign")),
            ForgeRegistries.BLOCKS.getValue(ResourceLocation("minecraft", "wall_sign")),
            ForgeRegistries.BLOCKS.getValue(ResourceLocation("minecraft", "standing_banner")),
            ForgeRegistries.BLOCKS.getValue(ResourceLocation("minecraft", "wall_banner")),
            ForgeRegistries.BLOCKS.getValue(ResourceLocation("minecraft", "portal")))
    var buildHeight: Setting<Int> = register(Settings.integerBuilder("BuildHeight").withMinimum(1).withValue(4).withMaximum(6).build())
    var buildWidth: Setting<Int> = register(Settings.integerBuilder("BuildWidth").withMinimum(3).withValue(7).withMaximum(9).build())
    private val cornerBlock: Setting<Boolean> = register(Settings.b("CornerBlock", true))
    val clearSpace: Setting<Boolean> = register(Settings.b("ClearSpace", true))
    private val noRims: Setting<Boolean> = register(Settings.b("NoRims", false))

    val blockQueue: Queue<BlockTask> = LinkedList<BlockTask>()
    private val doneQueue: Queue<BlockTask> = LinkedList<BlockTask>()
    private var blockOffsets = mutableListOf<Pair<BlockPos, Boolean>>()
    private var waitTicks = 0
    private var blocksPlaced = 0
    private lateinit var currentBlockPos: BlockPos
    private lateinit var startingBlockPos: BlockPos

    fun isDone(): Boolean { return blockQueue.size == 0 }

    override fun onEnable() {
        if (mc.player == null) {
            disable()
            return
        }
        buildDirectionSaved = getPlayerDirection()
        startingBlockPos = BlockPos(floor(mc.player.posX).toInt(), floor(mc.player.posY).toInt(), floor(mc.player.posZ).toInt())
        currentBlockPos = startingBlockPos
        playerHotbarSlot = mc.player.inventory.currentItem
        lastHotbarSlot = -1

        playerHotbarSlot = mc.player.inventory.currentItem
        buildDirectionCoordinate = if (buildDirectionSaved == 0 || buildDirectionSaved == 4) { startingBlockPos.getX() }
        else if (buildDirectionSaved == 2 || buildDirectionSaved == 6) { startingBlockPos.getZ() }
        else { 0 }

        blockQueue.clear()
        doneQueue.clear()
        updateTasks()
        printEnable()
    }

    override fun onDisable() {
        if (mc.player == null) return

        // load initial player hand
        if (lastHotbarSlot != playerHotbarSlot && playerHotbarSlot != -1) {
            mc.player.inventory.currentItem = playerHotbarSlot
        }
        if (isSneaking) {
            mc.player.connection.sendPacket(CPacketEntityAction(mc.player, CPacketEntityAction.Action.STOP_SNEAKING))
            isSneaking = false
        }
        playerHotbarSlot = -1
        lastHotbarSlot = -1

        BaritoneAPI.getProvider().primaryBaritone.pathingBehavior.cancelEverything()
        printDisable()
        totalBlocksPlaced = 0
        totalBlocksDestroyed = 0
        totalBlocksDistance = 0
    }

    override fun onUpdate() {
        if (mc.playerController == null) return

        if (!isDone()) {
            if (!BaritoneAPI.getProvider().primaryBaritone.pathingBehavior.isPathing) {
                pathing = false
            }
            if (!doTask()) {
                doneQueue.clear()
                blockQueue.clear()
                updateTasks()
            }
        } else {
            if (checkTasks()) {
                currentBlockPos = getNextBlock()
                totalBlocksDistance++
                doneQueue.clear()
                updateTasks()
                pathing = true
                lookInWalkDirection()
            } else {
                doneQueue.clear()
                updateTasks()
            }
        }
        //printDebug()
    }

    private fun addTask(blockPos: BlockPos, taskState: TaskState, material: Block) {
        blockQueue.add(BlockTask(blockPos, taskState, material))
    }

    private fun checkTasks(): Boolean {
        for (bt in doneQueue) {
            val block = mc.world.getBlockState(bt.getBlockPos()).block
            var cont = false
            for (b in ignoreBlocks) {
                if (b!!::class == block::class) { cont = true }
            }
            if (cont) { continue }
            if (bt.getBlock()::class == material::class && block is BlockAir) {
                return false
            } else if (bt.getBlock()::class == BlockAir::class && block !is BlockAir) {
                return false
            }
        }
        return true
    }

    private fun doTask(): Boolean {
        BaritoneAPI.getProvider().primaryBaritone.pathingControlManager.registerProcess(KamiMod.highwayToolsProcess)
        if (!isDone() && !pathing && !ModuleManager.getModuleT(LagNotifier::class.java)!!.paused) {
            if (waitTicks == 0) {
                val blockAction = blockQueue.peek()
                if (blockAction.getTaskState() == TaskState.BREAK) {
                    val block = mc.world.getBlockState(blockAction.getBlockPos()).block
                    for (b in ignoreBlocks) {
                        if (block::class == b!!::class) {
                            blockAction.setTaskState(TaskState.DONE)
                            doTask()
                        }
                    }
                    for (side in EnumFacing.values()) {
                        val neighbour = blockAction.getBlockPos().offset(side)
                        var found = false
                        if (mc.world.getBlockState(neighbour).block is BlockLiquid) {
                            for (bt in blockQueue) {
                                if (bt.getBlockPos() == neighbour) {
                                    found = true
                                }
                            }
                            if (!found) {
                                var insideBuild = false
                                for ((pos, buildBlock) in blockOffsets) {
                                    if (neighbour == pos) {
                                        if (!buildBlock) { insideBuild = true }
                                    }
                                }
                                if (insideBuild) {
                                    addTask(neighbour, TaskState.PLACE, ForgeRegistries.BLOCKS.getValue(ResourceLocation("minecraft", "netherrack"))!!)
                                } else {
                                    addTask(neighbour, TaskState.PLACE, material)
                                }
                            }
                        }
                    }
                    when (block) {
                        is BlockAir -> {
                            blockAction.setTaskState(TaskState.BROKE)
                            doTask()
                        }
                        is BlockLiquid -> {
                            blockAction.setTaskState(TaskState.PLACE)
                            doTask()
                        }
                        else -> {
                            mineBlock(blockAction.getBlockPos(), true)
                            blockAction.setTaskState(TaskState.BREAKING)
                        }
                    }
                } else if (blockAction.getTaskState() == TaskState.BREAKING) {
                    mineBlock(blockAction.getBlockPos(), false)
                    blockAction.setTaskState(TaskState.BROKE)
                } else if (blockAction.getTaskState() == TaskState.BROKE) {
                    val block = mc.world.getBlockState(blockAction.getBlockPos()).block
                    if (block is BlockAir) {
                        totalBlocksDestroyed++
                        waitTicks = tickDelayBreak.value
                        if (blockAction.getBlock()::class == material::class) {
                            blockAction.setTaskState(TaskState.PLACE)
                        } else {
                            blockAction.setTaskState(TaskState.DONE)
                        }
                        doTask()
                    } else {
                        blockAction.setTaskState(TaskState.BREAK)
                    }
                } else if (blockAction.getTaskState() == TaskState.PLACE) {
                    val block = mc.world.getBlockState(blockAction.getBlockPos()).block
                    if (blockAction.getBlock() is BlockAir && block !is BlockLiquid) {
                        blockQueue.remove()
                        return true
                    }
                    if (block is BlockLiquid) {
                        blockAction.setBlock(ForgeRegistries.BLOCKS.getValue(ResourceLocation("minecraft", "netherrack"))!!)
                    }
                    if (placeBlock(blockAction.getBlockPos(), blockAction.getBlock())) {
                        blockAction.setTaskState(TaskState.PLACED)
                        if (blocksPerTick.value > blocksPlaced + 1) {
                            blocksPlaced++
                            doTask()
                        } else {
                            blocksPlaced = 0
                        }
                        waitTicks = tickDelay.value
                        totalBlocksPlaced++
                    } else {
                        return false
                    }
                } else if (blockAction.getTaskState() == TaskState.PLACED) {
                    if (blockAction.getBlock()::class == material::class) {
                        val block = mc.world.getBlockState(blockAction.getBlockPos()).block
                        if (block !is BlockAir) {
                            blockAction.setTaskState(TaskState.DONE)
                        } else {
                            blockAction.setTaskState(TaskState.PLACE)
                        }
                    } else {
                        blockAction.setTaskState(TaskState.BREAK)
                    }
                    doTask()
                } else if (blockAction.getTaskState() == TaskState.DONE) {
                    blockQueue.remove()
                    doneQueue.add(blockAction)
                    doTask()
                }
            } else {
                waitTicks--
            }
            return true
        } else {
            return false
        }
    }

    private fun updateTasks() {
        updateBlockArray()
        for ((a, b) in blockOffsets) {
            val block = mc.world.getBlockState(a).block
            if (!b && block in ignoreBlocks) { addTask(a, TaskState.DONE, getBlockById(0)) }
            else if (b && block is BlockAir) { addTask(a, TaskState.PLACE, material) }
            else if (b && block !is BlockAir && block::class != material::class) { addTask(a, TaskState.BREAK, material) }
            else if (!b && block !is BlockAir) { addTask(a, TaskState.BREAK, getBlockById(0)) }
            else if (b && block::class == material::class) { addTask(a, TaskState.DONE, material) }
            else if (!b && block is BlockAir) { addTask(a, TaskState.DONE, getBlockById(0)) }
        }
    }

    private fun mineBlock(pos: BlockPos, pre: Boolean) {
        if (InventoryUtils.getSlotsHotbar(278) == null && InventoryUtils.getSlotsNoHotbar(278) != null) {
            InventoryUtils.moveToHotbar(278, 130, (tickDelay.value * 16).toLong())
            return
        } else if (InventoryUtils.getSlots(0, 35, 278) == null) {
            MessageSendHelper.sendChatMessage("$chatName No Pickaxe was found in inventory")
            mc.getSoundHandler().playSound(PositionedSoundRecord.getRecord(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f))
            disable()
            return
        }
        InventoryUtils.swapSlotToItem(278)
        lookAtBlock(pos)

        if (pre) {
            mc.connection!!.sendPacket(CPacketPlayerDigging(CPacketPlayerDigging.Action.START_DESTROY_BLOCK, pos, mc.objectMouseOver.sideHit))
        } else {
            mc.connection!!.sendPacket(CPacketPlayerDigging(CPacketPlayerDigging.Action.STOP_DESTROY_BLOCK, pos, mc.objectMouseOver.sideHit))
        }
        mc.player.swingArm(EnumHand.MAIN_HAND)
    }

    private fun placeBlock(pos: BlockPos, mat: Block): Boolean
    {
        // check if block is already placed
        val block = mc.world.getBlockState(pos).block
        if (block !is BlockAir && block !is BlockLiquid) {
            return false
        }

        // check if entity blocks placing
        for (entity in mc.world.getEntitiesWithinAABBExcludingEntity(null, AxisAlignedBB(pos))) {
            if (entity !is EntityItem && entity !is EntityXPOrb) {
                return false
            }
        }
        val side = getPlaceableSide(pos) ?: return false

        // check if we have a block adjacent to blockpos to click at
        val neighbour = pos.offset(side)
        val opposite = side.opposite

        // check if neighbor can be right clicked
        if (!BlockUtils.canBeClicked(neighbour)) {
            return false
        }

        //Swap to Obsidian in Hotbar or get from inventory
        if (InventoryUtils.getSlotsHotbar(getIdFromBlock(mat)) == null && InventoryUtils.getSlotsNoHotbar(getIdFromBlock(mat)) != null) {
            InventoryUtils.moveToHotbar(getIdFromBlock(mat), 130, (tickDelay.value * 16).toLong())
            InventoryUtils.quickMoveSlot(1, (tickDelay.value * 16).toLong())
            return false
        } else if (InventoryUtils.getSlots(0, 35, getIdFromBlock(mat)) == null) {
            MessageSendHelper.sendChatMessage("$chatName No $mat was found in inventory")
            mc.getSoundHandler().playSound(PositionedSoundRecord.getRecord(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f))
            disable()
            return false
        }
        InventoryUtils.swapSlotToItem(getIdFromBlock(mat))

        val hitVec = Vec3d(neighbour).add(0.5, 0.5, 0.5).add(Vec3d(opposite.directionVec).scale(0.5))
        val neighbourBlock = mc.world.getBlockState(neighbour).block

        if (!isSneaking && BlockUtils.blackList.contains(neighbourBlock) || BlockUtils.shulkerList.contains(neighbourBlock)) {
            mc.player.connection.sendPacket(CPacketEntityAction(mc.player, CPacketEntityAction.Action.START_SNEAKING))
            isSneaking = true
        }
        if (rotate.value) {
            BlockUtils.faceVectorPacketInstant(hitVec)
        }

        mc.playerController.processRightClickBlock(mc.player, mc.world, neighbour, opposite, hitVec, EnumHand.MAIN_HAND)
        mc.player.swingArm(EnumHand.MAIN_HAND)
        mc.rightClickDelayTimer = 4

        val noBreakAnimation = ModuleManager.getModuleT(NoBreakAnimation::class.java)!!
        if (noBreakAnimation.isEnabled) {
            noBreakAnimation.resetMining()
        }
        return true
    }

    private fun getPlaceableSide(pos: BlockPos): EnumFacing? {
        for (side in EnumFacing.values()) {
            val neighbour = pos.offset(side)
            if (!mc.world.getBlockState(neighbour).block.canCollideCheck(mc.world.getBlockState(neighbour), false)) {
                continue
            }
            val blockState = mc.world.getBlockState(neighbour)
            if (!blockState.material.isReplaceable) {
                return side
            }
        }
        return null
    }

    private fun lookAtBlock(pos: BlockPos) {
        val vec3d = Vec3d(pos).add(0.5, 0.0, 0.5)
        val lookAt = RotationUtils.getRotationTo(vec3d, true)
        mc.player.rotationYaw = lookAt.x.toFloat()
        mc.player.rotationPitch = lookAt.y.toFloat()
    }

    fun getPlayerDirection(): Int {
        val yaw = (mc.player.rotationYaw % 360 + 360) % 360
        return if (yaw >= 158 && yaw < 203) { 0 } //NORTH
        else if (yaw >= 203 && yaw < 258) { 1 } //NORTH-EAST
        else if (yaw >= 258 && yaw < 293) { 2 } //EAST
        else if (yaw >= 293 && yaw < 338) { 3 } //SOUTH-EAST
        else if (yaw >= 338 || yaw < 23) { 4 } //SOUTH
        else if (yaw >= 23 && yaw < 68) { 5 } //SOUTH-WEST
        else if (yaw >= 68 && yaw < 113) { 6 } //WEST
        else { 7 } //NORTH-WEST
    }

    private fun updateRenderer(renderer: ESPRenderer): ESPRenderer {
        val side = GeometryMasks.Quad.ALL
        for (bt in blockQueue) {
            if (bt.getTaskState() != TaskState.DONE) { renderer.add(bt.getBlockPos(), bt.getTaskState().color, side) }
        }
        for (bt in doneQueue) {
            if (bt.getBlock()::class != BlockAir::class) { renderer.add(bt.getBlockPos(), bt.getTaskState().color, side) }
        }
        return renderer
    }

    override fun onWorldRender(event: RenderEvent) {
        if (mc.player == null) return
        val renderer = ESPRenderer()
        renderer.aFilled = if (filled.value) aFilled.value else 0
        renderer.aOutline = if (outline.value) aOutline.value else 0
        updateRenderer(renderer)
        renderer.render(true)
    }

    private fun printDebug() {
        MessageSendHelper.sendChatMessage("#### LOG ####")
        for (bt in blockQueue) {
            MessageSendHelper.sendChatMessage(bt.getBlockPos().toString() + " " + bt.getTaskState().toString() + " " + bt.getBlock().toString())
        }
        MessageSendHelper.sendChatMessage("#### DONE ####")
        for (bt in doneQueue) {
            MessageSendHelper.sendChatMessage(bt.getBlockPos().toString() + " " + bt.getTaskState().toString() + " " + bt.getBlock().toString())
        }
    }

    fun printSettings() {
        var message = "$chatName Settings" +
                "\n    §9> §rMaterial: §7$material" +
                "\n    §9> §rBaritone: §7${baritoneMode.value}" +
                "\n    §9> §rIgnored Blocks:"
        for (b in ignoreBlocks) {
            message += "\n        §9> §7$b"
        }
        MessageSendHelper.sendChatMessage(message)
    }

    private fun printEnable() {
        if (buildDirectionSaved == 1 || buildDirectionSaved == 3 || buildDirectionSaved == 5 || buildDirectionSaved == 5 || buildDirectionSaved == 7) {
            MessageSendHelper.sendChatMessage("$chatName Module started." +
                    "\n    §9> §7Direction: §a" + directions[buildDirectionSaved] + "§r" +
                    "\n    §9> §7Coordinates: §a" + mc.player.positionVector.x.roundToInt() + ", " + mc.player.positionVector.z.roundToInt() + "§r" +
                    "\n    §9> §7Baritone mode: §a" + baritoneMode.value + "§r")
        } else {
            MessageSendHelper.sendChatMessage("$chatName Module started." +
                    "\n    §9> §7Direction: §a" + directions[buildDirectionSaved] + "§r" +
                    "\n    §9> §7Coordinate: §a" + buildDirectionCoordinate + "§r" +
                    "\n    §9> §7Baritone mode: §a" + baritoneMode.value + "§r")
        }
    }

    private fun printDisable() {
        MessageSendHelper.sendChatMessage("$chatName Module stopped." +
                "\n    §9> §7Placed blocks: §a" + totalBlocksPlaced + "§r" +
                "\n    §9> §7Destroyed blocks: §a" + totalBlocksDestroyed + "§r" +
                "\n    §9> §7Distance: §a" + totalBlocksDistance + "§r")
    }

    fun getNextBlock(): BlockPos {
        return when (buildDirectionSaved) {
            0 -> { currentBlockPos.north() }
            1 -> { currentBlockPos.north().east() }
            2 -> { currentBlockPos.east() }
            3 -> { currentBlockPos.south().east() }
            4 -> { currentBlockPos.south() }
            5 -> { currentBlockPos.south().west() }
            6 -> { currentBlockPos.west() }
            else -> { currentBlockPos.north().west() }
        }
    }

    private fun lookInWalkDirection() {
        // set head rotation to get max walking speed
        when (buildDirectionSaved) {
            0 -> { mc.player.rotationYaw = -180F }
            1 -> { mc.player.rotationYaw = -135F }
            2 -> { mc.player.rotationYaw = -90F }
            3 -> { mc.player.rotationYaw = -45F }
            4 -> { mc.player.rotationYaw = 0F }
            5 -> { mc.player.rotationYaw = 45F }
            6 -> { mc.player.rotationYaw = 90F }
            else -> { mc.player.rotationYaw = 135F }
        }
        mc.player.rotationPitch = 0F
    }

    private fun updateBlockArray() {
        blockOffsets.clear()
        val b = currentBlockPos

        when(mode.value) {
            Mode.HIGHWAY -> {
                when (buildDirectionSaved) {
                    0 -> { //NORTH
                        blockOffsets.add(Pair(b.down().north(), true))
                        blockOffsets.add(Pair(b.down().north().north(), true))
                        blockOffsets.add(Pair(b.down().north().north().east(), true))
                        blockOffsets.add(Pair(b.down().north().north().west(), true))
                        blockOffsets.add(Pair(b.down().north().north().east().east(), true))
                        blockOffsets.add(Pair(b.down().north().north().west().west(), true))
                        if (cornerBlock.value) {
                            blockOffsets.add(Pair(b.down().north().north().east().east().east(), true))
                            blockOffsets.add(Pair(b.down().north().north().west().west().west(), true))
                        }
                        if (buildHeight.value > 1) {
                            if (!noRims.value) {
                                blockOffsets.add(Pair(b.north().north().east().east().east(), true))
                                blockOffsets.add(Pair(b.north().north().west().west().west(), true))
                            }
                        }
                        if (clearSpace.value) {
                            if (noRims.value) {
                                blockOffsets.add(Pair(b.north().north().east().east().east(), false))
                                blockOffsets.add(Pair(b.north().north().west().west().west(), false))
                            }
                            if (buildHeight.value > 1) {
                                blockOffsets.add(Pair(b.north().north(), false))
                                blockOffsets.add(Pair(b.north().north().east(), false))
                                blockOffsets.add(Pair(b.north().north().west(), false))
                                blockOffsets.add(Pair(b.north().north().east().east(), false))
                                blockOffsets.add(Pair(b.north().north().west().west(), false))
                            }
                            if (buildHeight.value > 2) {
                                blockOffsets.add(Pair(b.up().north().north(), false))
                                blockOffsets.add(Pair(b.up().north().north().east(), false))
                                blockOffsets.add(Pair(b.up().north().north().west(), false))
                                blockOffsets.add(Pair(b.up().north().north().east().east(), false))
                                blockOffsets.add(Pair(b.up().north().north().west().west(), false))
                                blockOffsets.add(Pair(b.up().north().north().east().east().east(), false))
                                blockOffsets.add(Pair(b.up().north().north().west().west().west(), false))
                            }
                            if (buildHeight.value > 3) {
                                blockOffsets.add(Pair(b.up().up().north().north(), false))
                                blockOffsets.add(Pair(b.up().up().north().north().east(), false))
                                blockOffsets.add(Pair(b.up().up().north().north().west(), false))
                                blockOffsets.add(Pair(b.up().up().north().north().east().east(), false))
                                blockOffsets.add(Pair(b.up().up().north().north().west().west(), false))
                                blockOffsets.add(Pair(b.up().up().north().north().east().east().east(), false))
                                blockOffsets.add(Pair(b.up().up().north().north().west().west().west(), false))
                            }
                            if (buildHeight.value > 4) {
                                blockOffsets.add(Pair(b.up().up().up().north().north(), false))
                                blockOffsets.add(Pair(b.up().up().up().north().north().east(), false))
                                blockOffsets.add(Pair(b.up().up().up().north().north().west(), false))
                                blockOffsets.add(Pair(b.up().up().up().north().north().east().east(), false))
                                blockOffsets.add(Pair(b.up().up().up().north().north().west().west(), false))
                                blockOffsets.add(Pair(b.up().up().up().north().north().east().east().east(), false))
                                blockOffsets.add(Pair(b.up().up().up().north().north().west().west().west(), false))
                            }
                        }
                    }
                    1 -> { // NORTH-EAST
                        blockOffsets.add(Pair(b.north().east().down(), true))
                        blockOffsets.add(Pair(b.north().east().down().north(), true))
                        blockOffsets.add(Pair(b.north().east().down().east(), true))
                        blockOffsets.add(Pair(b.north().east().down().north().east(), true))
                        blockOffsets.add(Pair(b.north().east().down().north().north(), true))
                        blockOffsets.add(Pair(b.north().east().down().east().east(), true))
                        blockOffsets.add(Pair(b.north().east().down().east().east().south(), true))
                        blockOffsets.add(Pair(b.north().east().down().north().north().west(), true))
                        blockOffsets.add(Pair(b.north().east().down().east().east().south().east(), true))
                        blockOffsets.add(Pair(b.north().east().down().north().north().west().north(), true))
                        blockOffsets.add(Pair(b.north().east().east().east().south().east(), true))
                        blockOffsets.add(Pair(b.north().east().north().north().west().north(), true))
                        blockOffsets.add(Pair(b.north().east().north(), false))
                        blockOffsets.add(Pair(b.north().east().north().up(), false))
                        blockOffsets.add(Pair(b.north().east().north().up().up(), false))
                        blockOffsets.add(Pair(b.north().east().east(), false))
                        blockOffsets.add(Pair(b.north().east().east().up(), false))
                        blockOffsets.add(Pair(b.north().east().east().up().up(), false))
                        blockOffsets.add(Pair(b.north().east().north().east(), false))
                        blockOffsets.add(Pair(b.north().east().north().east().up(), false))
                        blockOffsets.add(Pair(b.north().east().north().east().up().up(), false))
                        blockOffsets.add(Pair(b.north().east().north().north(), false))
                        blockOffsets.add(Pair(b.north().east().north().north().up(), false))
                        blockOffsets.add(Pair(b.north().east().north().north().up().up(), false))
                        blockOffsets.add(Pair(b.north().east().east().east(), false))
                        blockOffsets.add(Pair(b.north().east().east().east().up(), false))
                        blockOffsets.add(Pair(b.north().east().east().east().up().up(), false))
                        blockOffsets.add(Pair(b.north().east().north().north().west(), false))
                        blockOffsets.add(Pair(b.north().east().north().north().west().up(), false))
                        blockOffsets.add(Pair(b.north().east().north().north().west().up().up(), false))
                        blockOffsets.add(Pair(b.north().east().east().east().south(), false))
                        blockOffsets.add(Pair(b.north().east().east().east().south().up(), false))
                        blockOffsets.add(Pair(b.north().east().east().east().south().up().up(), false))
                        blockOffsets.add(Pair(b.north().east().north().north().west().north().up(), false))
                        blockOffsets.add(Pair(b.north().east().north().north().west().north().up().up(), false))
                        blockOffsets.add(Pair(b.north().east().east().east().south().east().up(), false))
                        blockOffsets.add(Pair(b.north().east().east().east().south().east().up().up(), false))
                    }
                    2 -> { //EAST
                        blockOffsets.add(Pair(b.down().east(), true))
                        blockOffsets.add(Pair(b.down().east().east(), true))
                        blockOffsets.add(Pair(b.down().east().east().south(), true))
                        blockOffsets.add(Pair(b.down().east().east().north(), true))
                        blockOffsets.add(Pair(b.down().east().east().south().south(), true))
                        blockOffsets.add(Pair(b.down().east().east().north().north(), true))
                        blockOffsets.add(Pair(b.down().east().east().south().south().south(), true))
                        blockOffsets.add(Pair(b.down().east().east().north().north().north(), true))
                        blockOffsets.add(Pair(b.east().east().south().south().south(), true))
                        blockOffsets.add(Pair(b.east().east().north().north().north(), true))
                        blockOffsets.add(Pair(b.east().east(), false))
                        blockOffsets.add(Pair(b.east().east().south(), false))
                        blockOffsets.add(Pair(b.east().east().north(), false))
                        blockOffsets.add(Pair(b.east().east().south().south(), false))
                        blockOffsets.add(Pair(b.east().east().north().north(), false))
                        blockOffsets.add(Pair(b.up().east().east(), false))
                        blockOffsets.add(Pair(b.up().east().east().south(), false))
                        blockOffsets.add(Pair(b.up().east().east().north(), false))
                        blockOffsets.add(Pair(b.up().east().east().south().south(), false))
                        blockOffsets.add(Pair(b.up().east().east().north().north(), false))
                        blockOffsets.add(Pair(b.up().east().east().south().south().south(), false))
                        blockOffsets.add(Pair(b.up().east().east().north().north().north(), false))
                        blockOffsets.add(Pair(b.up().up().east().east(), false))
                        blockOffsets.add(Pair(b.up().up().east().east().south(), false))
                        blockOffsets.add(Pair(b.up().up().east().east().north(), false))
                        blockOffsets.add(Pair(b.up().up().east().east().south().south(), false))
                        blockOffsets.add(Pair(b.up().up().east().east().north().north(), false))
                        blockOffsets.add(Pair(b.up().up().east().east().south().south().south(), false))
                        blockOffsets.add(Pair(b.up().up().east().east().north().north().north(), false))
                    }
                    3 -> { //SOUTH-EAST
                        blockOffsets.add(Pair(b.east().south().down(), true))
                        blockOffsets.add(Pair(b.east().south().down().east(), true))
                        blockOffsets.add(Pair(b.east().south().down().south(), true))
                        blockOffsets.add(Pair(b.east().south().down().east().south(), true))
                        blockOffsets.add(Pair(b.east().south().down().east().east(), true))
                        blockOffsets.add(Pair(b.east().south().down().south().south(), true))
                        blockOffsets.add(Pair(b.east().south().down().south().south().west(), true))
                        blockOffsets.add(Pair(b.east().south().down().east().east().north(), true))
                        blockOffsets.add(Pair(b.east().south().down().south().south().west().south(), true))
                        blockOffsets.add(Pair(b.east().south().down().east().east().north().east(), true))
                        blockOffsets.add(Pair(b.east().south().south().south().west().south(), true))
                        blockOffsets.add(Pair(b.east().south().east().east().north().east(), true))
                        blockOffsets.add(Pair(b.east().south().east(), false))
                        blockOffsets.add(Pair(b.east().south().east().up(), false))
                        blockOffsets.add(Pair(b.east().south().east().up().up(), false))
                        blockOffsets.add(Pair(b.east().south().south(), false))
                        blockOffsets.add(Pair(b.east().south().south().up(), false))
                        blockOffsets.add(Pair(b.east().south().south().up().up(), false))
                        blockOffsets.add(Pair(b.east().south().east().south(), false))
                        blockOffsets.add(Pair(b.east().south().east().south().up(), false))
                        blockOffsets.add(Pair(b.east().south().east().south().up().up(), false))
                        blockOffsets.add(Pair(b.east().south().east().east(), false))
                        blockOffsets.add(Pair(b.east().south().east().east().up(), false))
                        blockOffsets.add(Pair(b.east().south().east().east().up().up(), false))
                        blockOffsets.add(Pair(b.east().south().south().south(), false))
                        blockOffsets.add(Pair(b.east().south().south().south().up(), false))
                        blockOffsets.add(Pair(b.east().south().south().south().up().up(), false))
                        blockOffsets.add(Pair(b.east().south().east().east().north(), false))
                        blockOffsets.add(Pair(b.east().south().east().east().north().up(), false))
                        blockOffsets.add(Pair(b.east().south().east().east().north().up().up(), false))
                        blockOffsets.add(Pair(b.east().south().south().south().west(), false))
                        blockOffsets.add(Pair(b.east().south().south().south().west().up(), false))
                        blockOffsets.add(Pair(b.east().south().south().south().west().up().up(), false))
                        blockOffsets.add(Pair(b.east().south().east().east().north().east().up(), false))
                        blockOffsets.add(Pair(b.east().south().east().east().north().east().up().up(), false))
                        blockOffsets.add(Pair(b.east().south().south().south().west().south().up(), false))
                        blockOffsets.add(Pair(b.east().south().south().south().west().south().up().up(), false))
                    }
                    4 -> { //SOUTH
                        blockOffsets.add(Pair(b.down().south(), true))
                        blockOffsets.add(Pair(b.down().south().south(), true))
                        blockOffsets.add(Pair(b.down().south().south().east(), true))
                        blockOffsets.add(Pair(b.down().south().south().west(), true))
                        blockOffsets.add(Pair(b.down().south().south().east().east(), true))
                        blockOffsets.add(Pair(b.down().south().south().west().west(), true))
                        blockOffsets.add(Pair(b.down().south().south().east().east().east(), true))
                        blockOffsets.add(Pair(b.down().south().south().west().west().west(), true))
                        blockOffsets.add(Pair(b.south().south().east().east().east(), true))
                        blockOffsets.add(Pair(b.south().south().west().west().west(), true))
                        blockOffsets.add(Pair(b.south().south(), false))
                        blockOffsets.add(Pair(b.south().south().east(), false))
                        blockOffsets.add(Pair(b.south().south().west(), false))
                        blockOffsets.add(Pair(b.south().south().east().east(), false))
                        blockOffsets.add(Pair(b.south().south().west().west(), false))
                        blockOffsets.add(Pair(b.up().south().south(), false))
                        blockOffsets.add(Pair(b.up().south().south().east(), false))
                        blockOffsets.add(Pair(b.up().south().south().west(), false))
                        blockOffsets.add(Pair(b.up().south().south().east().east(), false))
                        blockOffsets.add(Pair(b.up().south().south().west().west(), false))
                        blockOffsets.add(Pair(b.up().south().south().east().east().east(), false))
                        blockOffsets.add(Pair(b.up().south().south().west().west().west(), false))
                        blockOffsets.add(Pair(b.up().up().south().south(), false))
                        blockOffsets.add(Pair(b.up().up().south().south().east(), false))
                        blockOffsets.add(Pair(b.up().up().south().south().west(), false))
                        blockOffsets.add(Pair(b.up().up().south().south().east().east(), false))
                        blockOffsets.add(Pair(b.up().up().south().south().west().west(), false))
                        blockOffsets.add(Pair(b.up().up().south().south().east().east().east(), false))
                        blockOffsets.add(Pair(b.up().up().south().south().west().west().west(), false))
                    }
                    5 -> { // SOUTH-WEST
                        blockOffsets.add(Pair(b.south().west().down(), true))
                        blockOffsets.add(Pair(b.south().west().down().south(), true))
                        blockOffsets.add(Pair(b.south().west().down().west(), true))
                        blockOffsets.add(Pair(b.south().west().down().south().west(), true))
                        blockOffsets.add(Pair(b.south().west().down().south().south(), true))
                        blockOffsets.add(Pair(b.south().west().down().west().west(), true))
                        blockOffsets.add(Pair(b.south().west().down().west().west().north(), true))
                        blockOffsets.add(Pair(b.south().west().down().south().south().east(), true))
                        blockOffsets.add(Pair(b.south().west().down().west().west().north().west(), true))
                        blockOffsets.add(Pair(b.south().west().down().south().south().east().south(), true))
                        blockOffsets.add(Pair(b.south().west().west().west().north().west(), true))
                        blockOffsets.add(Pair(b.south().west().south().south().east().south(), true))
                        blockOffsets.add(Pair(b.south().west().south(), false))
                        blockOffsets.add(Pair(b.south().west().south().up(), false))
                        blockOffsets.add(Pair(b.south().west().south().up().up(), false))
                        blockOffsets.add(Pair(b.south().west().west(), false))
                        blockOffsets.add(Pair(b.south().west().west().up(), false))
                        blockOffsets.add(Pair(b.south().west().west().up().up(), false))
                        blockOffsets.add(Pair(b.south().west().south().west(), false))
                        blockOffsets.add(Pair(b.south().west().south().west().up(), false))
                        blockOffsets.add(Pair(b.south().west().south().west().up().up(), false))
                        blockOffsets.add(Pair(b.south().west().south().south(), false))
                        blockOffsets.add(Pair(b.south().west().south().south().up(), false))
                        blockOffsets.add(Pair(b.south().west().south().south().up().up(), false))
                        blockOffsets.add(Pair(b.south().west().west().west(), false))
                        blockOffsets.add(Pair(b.south().west().west().west().up(), false))
                        blockOffsets.add(Pair(b.south().west().west().west().up().up(), false))
                        blockOffsets.add(Pair(b.south().west().south().south().east(), false))
                        blockOffsets.add(Pair(b.south().west().south().south().east().up(), false))
                        blockOffsets.add(Pair(b.south().west().south().south().east().up().up(), false))
                        blockOffsets.add(Pair(b.south().west().west().west().north(), false))
                        blockOffsets.add(Pair(b.south().west().west().west().north().up(), false))
                        blockOffsets.add(Pair(b.south().west().west().west().north().up().up(), false))
                        blockOffsets.add(Pair(b.south().west().south().south().east().south().up(), false))
                        blockOffsets.add(Pair(b.south().west().south().south().east().south().up().up(), false))
                        blockOffsets.add(Pair(b.south().west().west().west().north().west().up(), false))
                        blockOffsets.add(Pair(b.south().west().west().west().north().west().up().up(), false))
                    }
                    6 -> { //WEST
                        blockOffsets.add(Pair(b.down().west(), true))
                        blockOffsets.add(Pair(b.down().west().west(), true))
                        blockOffsets.add(Pair(b.down().west().west().south(), true))
                        blockOffsets.add(Pair(b.down().west().west().north(), true))
                        blockOffsets.add(Pair(b.down().west().west().south().south(), true))
                        blockOffsets.add(Pair(b.down().west().west().north().north(), true))
                        blockOffsets.add(Pair(b.down().west().west().south().south().south(), true))
                        blockOffsets.add(Pair(b.down().west().west().north().north().north(), true))
                        blockOffsets.add(Pair(b.west().west().south().south().south(), true))
                        blockOffsets.add(Pair(b.west().west().north().north().north(), true))
                        blockOffsets.add(Pair(b.west().west(), false))
                        blockOffsets.add(Pair(b.west().west().south(), false))
                        blockOffsets.add(Pair(b.west().west().north(), false))
                        blockOffsets.add(Pair(b.west().west().south().south(), false))
                        blockOffsets.add(Pair(b.west().west().north().north(), false))
                        blockOffsets.add(Pair(b.up().west().west(), false))
                        blockOffsets.add(Pair(b.up().west().west().south(), false))
                        blockOffsets.add(Pair(b.up().west().west().north(), false))
                        blockOffsets.add(Pair(b.up().west().west().south().south(), false))
                        blockOffsets.add(Pair(b.up().west().west().north().north(), false))
                        blockOffsets.add(Pair(b.up().west().west().south().south().south(), false))
                        blockOffsets.add(Pair(b.up().west().west().north().north().north(), false))
                        blockOffsets.add(Pair(b.up().up().west().west(), false))
                        blockOffsets.add(Pair(b.up().up().west().west().south(), false))
                        blockOffsets.add(Pair(b.up().up().west().west().north(), false))
                        blockOffsets.add(Pair(b.up().up().west().west().south().south(), false))
                        blockOffsets.add(Pair(b.up().up().west().west().north().north(), false))
                        blockOffsets.add(Pair(b.up().up().west().west().south().south().south(), false))
                        blockOffsets.add(Pair(b.up().up().west().west().north().north().north(), false))
                    }
                    7 -> { //NORTH-WEST
                        blockOffsets.add(Pair(b.west().north().down(), true))
                        blockOffsets.add(Pair(b.west().north().down().west(), true))
                        blockOffsets.add(Pair(b.west().north().down().north(), true))
                        blockOffsets.add(Pair(b.west().north().down().west().north(), true))
                        blockOffsets.add(Pair(b.west().north().down().west().west(), true))
                        blockOffsets.add(Pair(b.west().north().down().north().north(), true))
                        blockOffsets.add(Pair(b.west().north().down().north().north().east(), true))
                        blockOffsets.add(Pair(b.west().north().down().west().west().south(), true))
                        blockOffsets.add(Pair(b.west().north().down().north().north().east().north(), true))
                        blockOffsets.add(Pair(b.west().north().down().west().west().south().west(), true))
                        blockOffsets.add(Pair(b.west().north().north().north().east().north(), true))
                        blockOffsets.add(Pair(b.west().north().west().west().south().west(), true))
                        blockOffsets.add(Pair(b.west().north().west(), false))
                        blockOffsets.add(Pair(b.west().north().west().up(), false))
                        blockOffsets.add(Pair(b.west().north().west().up().up(), false))
                        blockOffsets.add(Pair(b.west().north().north(), false))
                        blockOffsets.add(Pair(b.west().north().north().up(), false))
                        blockOffsets.add(Pair(b.west().north().north().up().up(), false))
                        blockOffsets.add(Pair(b.west().north().west().north(), false))
                        blockOffsets.add(Pair(b.west().north().west().north().up(), false))
                        blockOffsets.add(Pair(b.west().north().west().north().up().up(), false))
                        blockOffsets.add(Pair(b.west().north().west().west(), false))
                        blockOffsets.add(Pair(b.west().north().west().west().up(), false))
                        blockOffsets.add(Pair(b.west().north().west().west().up().up(), false))
                        blockOffsets.add(Pair(b.west().north().north().north(), false))
                        blockOffsets.add(Pair(b.west().north().north().north().up(), false))
                        blockOffsets.add(Pair(b.west().north().north().north().up().up(), false))
                        blockOffsets.add(Pair(b.west().north().west().west().south(), false))
                        blockOffsets.add(Pair(b.west().north().west().west().south().up(), false))
                        blockOffsets.add(Pair(b.west().north().west().west().south().up().up(), false))
                        blockOffsets.add(Pair(b.west().north().north().north().east(), false))
                        blockOffsets.add(Pair(b.west().north().north().north().east().up(), false))
                        blockOffsets.add(Pair(b.west().north().north().north().east().up().up(), false))
                        blockOffsets.add(Pair(b.west().north().west().west().south().west().up(), false))
                        blockOffsets.add(Pair(b.west().north().west().west().south().west().up().up(), false))
                        blockOffsets.add(Pair(b.west().north().north().north().east().north().up(), false))
                        blockOffsets.add(Pair(b.west().north().north().north().east().north().up().up(), false))
                    }
                }
            }
            Mode.FLAT -> {
                blockOffsets.add(Pair((b.down().north()), true))
                blockOffsets.add(Pair((b.down().east()), true))
                blockOffsets.add(Pair((b.down().south()), true))
                blockOffsets.add(Pair((b.down().west()), true))
                blockOffsets.add(Pair((b.down().north().east()), true))
                blockOffsets.add(Pair((b.down().north().west()), true))
                blockOffsets.add(Pair((b.down().south().east()), true))
                blockOffsets.add(Pair((b.down().south().west()), true))
            }
            null -> {
                disable()
            }
        }
    }
}

class BlockTask(private val bp: BlockPos, private var tt: TaskState, private var bb: Block) {
    fun getBlockPos(): BlockPos { return bp }
    fun getTaskState(): TaskState { return tt }
    fun setTaskState(tts: TaskState) { tt = tts }
    fun getBlock(): Block { return bb }
    fun setBlock(b: Block) { bb = b }
}

enum class TaskState(val color: ColorHolder) {
    BREAK(ColorHolder(222, 0, 0)),
    BREAKING(ColorHolder(240, 222, 60)),
    BROKE(ColorHolder(240, 77, 60)),
    PLACE(ColorHolder(35, 188, 254)),
    PLACED(ColorHolder(53, 222, 66)),
    DONE(ColorHolder(50, 50, 50))
}

enum class Mode {
    FLAT, HIGHWAY
}
