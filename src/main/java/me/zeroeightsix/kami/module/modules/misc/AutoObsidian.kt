package me.zeroeightsix.kami.module.modules.misc

import me.zeroeightsix.kami.event.events.SafeTickEvent
import me.zeroeightsix.kami.module.Module
import me.zeroeightsix.kami.module.modules.player.NoBreakAnimation
import me.zeroeightsix.kami.process.AutoObsidianProcess
import me.zeroeightsix.kami.setting.Settings
import me.zeroeightsix.kami.util.BaritoneUtils
import me.zeroeightsix.kami.util.BlockUtils.getHitVecOffset
import me.zeroeightsix.kami.util.BlockUtils.isPlaceableForChest
import me.zeroeightsix.kami.util.EntityUtils
import me.zeroeightsix.kami.util.EntityUtils.getDroppedItem
import me.zeroeightsix.kami.util.InventoryUtils
import me.zeroeightsix.kami.util.combat.SurroundUtils
import me.zeroeightsix.kami.util.math.RotationUtils.getRotationTo
import me.zeroeightsix.kami.util.text.MessageSendHelper.sendChatMessage
import net.minecraft.block.BlockShulkerBox
import net.minecraft.client.audio.PositionedSoundRecord
import net.minecraft.client.gui.inventory.GuiShulkerBox
import net.minecraft.init.Blocks
import net.minecraft.init.SoundEvents
import net.minecraft.inventory.ClickType
import net.minecraft.item.Item.getIdFromItem
import net.minecraft.network.play.client.CPacketPlayer
import net.minecraft.network.play.client.CPacketPlayerDigging
import net.minecraft.network.play.client.CPacketPlayerTryUseItemOnBlock
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.RayTraceResult
import net.minecraft.util.math.Vec3d
import net.minecraftforge.fml.common.gameevent.TickEvent
import org.kamiblue.event.listener.listener
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min


@Module.Info(
        name = "AutoObsidian",
        category = Module.Category.MISC,
        description = "Breaks down Ender Chests to restock obsidian"
)
object AutoObsidian : Module() {
    private val mode = register(Settings.e<Mode>("Mode", Mode.TARGETSTACKS))
    private val modeExitStrings = mapOf(Mode.FILLINVENTORY to "Inventory filled", Mode.TARGETSTACKS to "Target Stacks Reached")

    private val searchShulker = register(Settings.b("SearchShulker", false))
    private val autoRefill = register(Settings.booleanBuilder("AutoRefill").withValue(false).withVisibility { mode.value != Mode.INFINITE })
    private val threshold = register(Settings.integerBuilder("RefillThreshold").withValue(8).withRange(1, 56).withVisibility { autoRefill.value && mode.value != Mode.INFINITE })
    private val targetStacks = register(Settings.integerBuilder("TargetStacks").withValue(1).withRange(1, 20).withVisibility { mode.value == Mode.TARGETSTACKS })
    private val delayTicks = register(Settings.integerBuilder("DelayTicks").withValue(5).withRange(0, 10))
    private val interacting = register(Settings.enumBuilder(InteractMode::class.java).withName("InteractMode").withValue(InteractMode.SPOOF))
    private val autoCenter = register(Settings.enumBuilder(AutoCenterMode::class.java).withName("AutoCenter").withValue(AutoCenterMode.MOTION))
    private val maxReach = register(Settings.floatBuilder("MaxReach").withValue(4.5F).withRange(1.0f, 6.0f).withStep(0.1f))

    private enum class Mode {
        TARGETSTACKS,
        INFINITE,
        FILLINVENTORY
    }

    enum class State {
        SEARCHING,
        PLACING,
        PRE_MINING,
        MINING,
        COLLECTING,
        DONE
    }

    private enum class SearchingState {
        PLACING,
        OPENING,
        PRE_MINING,
        MINING,
        COLLECTING,
        DONE
    }

    private enum class InteractMode {
        OFF,
        SPOOF,
        VIEWLOCK
    }

    private enum class AutoCenterMode {
        OFF,
        TP,
        MOTION
    }
    private enum class ItemID(val id: Int) {
        AIR(0),
        OBSIDIAN(49),
        ENDER_CHEST(130),
        DIAMOND_PICKAXE(278)
    }

    var pathing = false
    var goal: BlockPos? = null
    var state = State.SEARCHING

    private var active = false
    private var searchingState = SearchingState.PLACING
    private var playerPos = BlockPos(0, -1, 0)
    private var placingPos = BlockPos(0, -1, 0)
    private var shulkerBoxId = 0
    private var enderChestCount = -1
    private var maxEnderChests = -1 /* The number of ender chests required to completely fill an inventory */
    private var obsidianCount = -1
    private var tickCount = 0
    private var openTime = 0L

    override fun isActive(): Boolean {
        return isEnabled && active
    }

    override fun onEnable() {
        if (mc.player == null) return
        state = State.SEARCHING
    }

    init {
        listener<SafeTickEvent> {
            if (it.phase != TickEvent.Phase.END) {
                if (mc.playerController == null) return@listener

                if (tickCount < delayTicks.value) {
                    tickCount++
                    return@listener
                } else tickCount = 0

                updateState()
                when (state) {

                    /* Searching states */
                    State.SEARCHING -> {
                        if (searchShulker.value) {
                            when (searchingState) {
                                SearchingState.PLACING -> placeShulker(placingPos)
                                SearchingState.OPENING -> openShulker(placingPos)
                                SearchingState.PRE_MINING -> mineBlock(placingPos, true)
                                SearchingState.MINING -> mineBlock(placingPos, false)
                                SearchingState.COLLECTING -> collectDroppedItem(shulkerBoxId)
                                SearchingState.DONE -> {
                                    /* Positions need to be updated after moving while collecting dropped shulker box */
                                    val currentPos = BlockPos(floor(mc.player.posX).toInt(), floor(mc.player.posY).toInt(), floor(mc.player.posZ).toInt())
                                    playerPos = currentPos
                                    centerPlayer()
                                    setPlacingPos()
                                }
                            }
                        } else searchingState = SearchingState.DONE
                    }

                    /* Main states */
                    State.PLACING -> placeEnderChest(placingPos)
                    State.PRE_MINING -> mineBlock(placingPos, true)
                    State.MINING -> mineBlock(placingPos, false)
                    State.COLLECTING -> collectDroppedItem(ItemID.OBSIDIAN.id)
                    State.DONE -> {
                        if (!autoRefill.value) {
                            sendChatMessage("$chatName ".plus(modeExitStrings[mode.value]).plus(", disabling."))
                            this.disable()
                        } else {
                            if (active) sendChatMessage("$chatName ".plus(modeExitStrings[mode.value]).plus(", stopping."))
                            reset()
                        }
                    }
                }
            } else {
                return@listener
            }
        }
    }

    override fun onDisable() {
        BaritoneUtils.primary?.pathingControlManager?.mostRecentInControl()?.let {
            if (it.isPresent && it.get() == AutoObsidianProcess) {
                it.get().onLostControl()
            }
        }
        reset()
    }

    private fun updateState() {
        val currentPos = BlockPos(floor(mc.player.posX).toInt(), floor(mc.player.posY).toInt(), floor(mc.player.posZ).toInt())
        if (state != State.DONE && placingPos.y == -1) {
            playerPos = currentPos
            setPlacingPos()
        }

        if (!active && state != State.DONE) {
            active = true
            BaritoneUtils.primary?.pathingControlManager?.registerProcess(AutoObsidianProcess)
        }

        /* Tell baritone to get you back to position */
        if (state != State.DONE && state != State.COLLECTING && searchingState != SearchingState.COLLECTING) {
            if (currentPos.x != playerPos.x || currentPos.z != playerPos.z) {
                pathing = true
                goal = playerPos
                return
            } else {
                pathing = false
            }
        }

        /* Updates ender chest and obsidian counts before placing and mining ender chest */
        if (state == State.SEARCHING) {
            enderChestCount = InventoryUtils.countItemAll(ItemID.ENDER_CHEST.id)
            maxEnderChests = maxPossibleEnderChests()
            obsidianCount = countObsidian()
        }

        /* Updates main state */
        var placedEnderChest = enderChestCount - InventoryUtils.countItemAll(ItemID.ENDER_CHEST.id)
        var targetEnderChest = -1
        when(mode.value) {
            Mode.TARGETSTACKS -> {
                targetEnderChest = min((targetStacks.value * 64 - obsidianCount) / 8, maxEnderChests)
            }
            Mode.FILLINVENTORY -> {
                targetEnderChest = maxEnderChests
            }
        }
        state = when {
            (!canPickUpObsidian() && mode.value != Mode.INFINITE) -> State.DONE /* Never transition to done when in INFINITE mode */
            state == State.DONE && autoRefill.value && InventoryUtils.countItemAll(ItemID.OBSIDIAN.id) <= threshold.value -> State.SEARCHING
            state == State.COLLECTING && getDroppedItem(ItemID.OBSIDIAN.id, 16.0f) == null -> State.DONE
            state != State.DONE && mc.world.isAirBlock(placingPos) && placedEnderChest >= targetEnderChest && mode.value != Mode.INFINITE -> State.COLLECTING
            state == State.MINING && mc.world.isAirBlock(placingPos) -> State.PLACING
            state == State.PLACING && !mc.world.isAirBlock(placingPos) -> State.PRE_MINING
            state == State.SEARCHING && searchingState == SearchingState.DONE && (placedEnderChest < targetEnderChest || mode.value == Mode.INFINITE) -> State.PLACING
            else -> state
        }

        /* Updates searching state */
        if (state == State.SEARCHING && searchingState != SearchingState.DONE) {
            searchingState = when {
                searchingState == SearchingState.PLACING && InventoryUtils.countItemAll(ItemID.ENDER_CHEST.id) > 0 -> SearchingState.DONE
                searchingState == SearchingState.COLLECTING && getDroppedItem(shulkerBoxId, 16.0f) == null -> SearchingState.DONE
                searchingState == SearchingState.MINING && mc.world.isAirBlock(placingPos) -> {
                    if (InventoryUtils.countItemAll(ItemID.ENDER_CHEST.id) > 0) {
                        SearchingState.COLLECTING
                    } else { /* In case if the shulker wasn't placed due to server lag */
                        SearchingState.PLACING
                    }
                }
                searchingState == SearchingState.OPENING && (InventoryUtils.countItemAll(ItemID.ENDER_CHEST.id) >= 64 || InventoryUtils.getSlots(0, 35, 0) == null) -> SearchingState.PRE_MINING
                searchingState == SearchingState.PLACING && !mc.world.isAirBlock(placingPos) -> {
                    if (mc.world.getBlockState(placingPos).block is BlockShulkerBox) {
                        SearchingState.OPENING
                    } else { /* In case if the shulker wasn't placed due to server lag */
                        SearchingState.PRE_MINING
                    }
                }
                else -> searchingState
            }
        } else if (state != State.SEARCHING) searchingState = SearchingState.PLACING

    }

    /*
        Calculate the maximum possible ender chests we can break given the current space in our inventory
    */
    private fun maxPossibleEnderChests(): Int {
        var maxEnderChests = 0
        mc.player?.inventory?.mainInventory?.let {
            val clonedList = ArrayList(it)
            for (itemStack in clonedList) {
                if(getIdFromItem(itemStack.item) == ItemID.AIR.id) {
                    maxEnderChests += 8
                }
                else if(getIdFromItem(itemStack.item) == ItemID.OBSIDIAN.id) {
                    /* Pick floor: It is better to have an unfilled stack then overfill and get stuck trying to pick
                       up extra obsidian
                     */
                    maxEnderChests += floor((64.0 - itemStack.count) / 8.0).toInt()
                }
            }
        }
        /* Here we assume ender chests are stacked the most efficiently possible. For every 64 ender chests
           mined, another item slot is freed, which allows us to mine an additional 8 ender chests to fill
           that slot with obsidian.
         */
        maxEnderChests += floor(maxEnderChests / 64.0).toInt() * 8

        return maxEnderChests
    }

    /*
       Check if we can pick up more obsidian:
       There must be at least one slot which is either empty, or contains a stack of obsidian less than 64
    */
    private fun canPickUpObsidian(): Boolean {
        mc.player?.inventory?.mainInventory?.let {
            val clonedList = ArrayList(it)
            for (itemStack in clonedList) {
                /* If there is an air block slot, we have an open inventory slot */
                if(getIdFromItem(itemStack.item) == ItemID.AIR.id) {
                    return true
                }
                /* If there is a non-full stack of obsidian, we have an open inventory slot */
                if((getIdFromItem(itemStack.item) == ItemID.OBSIDIAN.id) && itemStack.count < 64) {
                    return true
                }
            }
        }

        /* No matches to eligible slots, we can not pick up any more items */
        return false
    }

    /* Return the obsidian count, rounded up to the nearest 8th */
    private fun countObsidian(): Int {
        return ceil(InventoryUtils.countItemAll(ItemID.OBSIDIAN.id).toDouble() / 8.0).toInt() * 8
    }

    private fun setPlacingPos() {
        if (getPlacingPos().y != -1) {
            placingPos = getPlacingPos()
        } else {
            sendChatMessage("$chatName No valid position for placing shulker box / ender chest nearby, disabling.")
            mc.soundHandler.playSound(PositionedSoundRecord.getRecord(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f))
            this.disable()
            return
        }
    }

    private fun getPlacingPos(): BlockPos {
        val pos = playerPos
        var facing = EnumFacing.NORTH
        for (i in 1..4) {
            val posOffset = pos.offset(facing)
            val posOffsetDiagonal = posOffset.offset(facing.rotateY())
            when {
                isPlaceableForChest(posOffset) -> return posOffset
                isPlaceableForChest(posOffset.up()) -> return posOffset.up()
                isPlaceableForChest(posOffsetDiagonal) -> return posOffsetDiagonal
                isPlaceableForChest(posOffsetDiagonal.up()) -> return posOffsetDiagonal.up()
                else -> facing = facing.rotateY()
            }
        }
        return BlockPos(0, -1, 0)
    }

    /* Tasks */
    private fun placeShulker(pos: BlockPos) {
        for (i in 219..234) {
            if (InventoryUtils.getSlotsHotbar(i) == null) {
                if (i != 234) continue else {
                    sendChatMessage("$chatName No shulker box was found in hotbar, disabling.")
                    mc.soundHandler.playSound(PositionedSoundRecord.getRecord(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f))
                    this.disable()
                    return
                }
            }
            shulkerBoxId = i
            InventoryUtils.swapSlotToItem(i)
            break
        }

        if (mc.world.getBlockState(pos).block !is BlockShulkerBox) {
            placeBlock(pos)
        }
    }

    private fun placeEnderChest(pos: BlockPos) {
        /* Case where we need to move ender chests into the hotbar */
        if (InventoryUtils.getSlotsHotbar(ItemID.ENDER_CHEST.id) == null && InventoryUtils.getSlotsNoHotbar(ItemID.ENDER_CHEST.id) != null) {
            InventoryUtils.moveToHotbar(ItemID.ENDER_CHEST.id, ItemID.DIAMOND_PICKAXE.id)
            return
        } else if (InventoryUtils.getSlots(0, 35, ItemID.ENDER_CHEST.id) == null) {
            /* Case where we are out of ender chests */
            if (searchShulker.value) {
                state = State.SEARCHING
            } else {
                sendChatMessage("$chatName No ender chest was found in inventory, disabling.")
                mc.soundHandler.playSound(PositionedSoundRecord.getRecord(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f))
                this.disable()
                return
            }
        }
        /* Else, we already have enderchests in the hostbar */
        InventoryUtils.swapSlotToItem(ItemID.ENDER_CHEST.id)

        placeBlock(pos)
    }


    private fun openShulker(pos: BlockPos) {
        if(mc.currentScreen is GuiShulkerBox) {
            Thread {
                /* Extra delay here to wait for the item list to be loaded */
                Thread.sleep(delayTicks.value * 50L)
                val currentContainer = mc.player.openContainer
                var enderChestSlot = -1
                for (i in 0..26) {
                    if (getIdFromItem(currentContainer.inventory[i].item) == ItemID.ENDER_CHEST.id) {
                        enderChestSlot = i
                        break
                    }
                }
                if (enderChestSlot != -1) {
                    mc.playerController.windowClick(currentContainer.windowId, enderChestSlot, 0, ClickType.QUICK_MOVE, mc.player)
                    mc.player.closeScreen()
                } else {
                    sendChatMessage("$chatName No ender chest was found in shulker, disabling.")
                    mc.soundHandler.playSound(PositionedSoundRecord.getRecord(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f))
                    this.disable()
                }
            }.start()
        } else {
            var rayTrace = mc.world.rayTraceBlocks(mc.player.getPositionEyes(1f), Vec3d(pos).add(0.5, 0.5, 0.5)) ?: return
            if (rayTrace.blockPos != pos) {
                var found = false
                for (side in EnumFacing.values()) {
                    if (mc.world.getBlockState(pos.offset(side)).block == Blocks.AIR) {
                        rayTrace = mc.world.rayTraceBlocks(mc.player.getPositionEyes(1f), Vec3d(pos).add(0.5, 0.5, 0.5).add(Vec3d(side.directionVec).scale(0.499)))?: continue
                        if (rayTrace.blockPos == pos) {
                            found = true
                            break
                        }
                    }
                }
                if (!found) {
                    return
                }
            }
            val hitVecOffset = rayTrace.hitVec
            val rotation = getRotationTo(hitVecOffset, true)
            when (interacting.value) {
                InteractMode.SPOOF -> {
                    val rotationPacket = CPacketPlayer.PositionRotation(mc.player.posX, mc.player.posY, mc.player.posZ, rotation.x.toFloat(), rotation.y.toFloat(), mc.player.onGround)
                    mc.connection!!.sendPacket(rotationPacket)
                }
                InteractMode.VIEWLOCK -> {
                    mc.player.rotationYaw = rotation.x.toFloat()
                    mc.player.rotationPitch = rotation.y.toFloat()
                }
            }

            /* Added a delay here so it doesn't spam right click and get you kicked */
            if (System.currentTimeMillis() >= openTime + 2000L) {
                openTime = System.currentTimeMillis()
                Thread{
                    Thread.sleep(delayTicks.value * 25L)
                    val placePacket = CPacketPlayerTryUseItemOnBlock(rayTrace.blockPos, rayTrace.sideHit, EnumHand.MAIN_HAND, hitVecOffset.x.toFloat(), hitVecOffset.y.toFloat(), hitVecOffset.z.toFloat())
                    mc.connection!!.sendPacket(placePacket)
                    mc.player.swingArm(EnumHand.MAIN_HAND)
                    if (NoBreakAnimation.isEnabled) NoBreakAnimation.resetMining()
                }.start()
            }
        }
    }

    private fun placeBlock(pos: BlockPos) {
        val rayTraces = mutableListOf<RayTraceResult>()
        for (side in EnumFacing.values()) {
            val offPos = pos.offset(side)
            if (mc.world.getBlockState(offPos).material.isReplaceable) continue
            if (mc.player.getPositionEyes(1f).distanceTo(Vec3d(offPos).add(getHitVecOffset(side))) > maxReach.value) continue
            val rotationVector = Vec3d(offPos).add(0.5, 0.5, 0.5).add(Vec3d(side.opposite.directionVec).scale(0.499))
            val rt = mc.world.rayTraceBlocks(mc.player.getPositionEyes(1f), rotationVector)?: continue
            if (rt.typeOfHit != RayTraceResult.Type.BLOCK) continue
            if (rt.blockPos == offPos && offPos.offset(rt.sideHit) == pos) {
                rayTraces.add(rt)
            }
        }
        if (rayTraces.size == 0) {
            sendChatMessage("Position: $pos not available")
            // placeBlockWall(pos, mat)
            return
        }

        var rayTrace: RayTraceResult? = null
        var shortestRT = 99.0
        for (rt in rayTraces) {
            if (mc.player.getPositionEyes(1f).distanceTo(Vec3d(rt.blockPos).add(getHitVecOffset(rt.sideHit))) < shortestRT) {
                shortestRT = mc.player.getPositionEyes(1f).distanceTo(Vec3d(rt.blockPos).add(getHitVecOffset(rt.sideHit)))
                rayTrace = rt
            }
        }
        if (rayTrace == null) {
            sendChatMessage("Can't find any vector?")
            return
        }

        val hitVecOffset = rayTrace.hitVec
        val rotation = getRotationTo(hitVecOffset, true)
        when (interacting.value) {
            InteractMode.SPOOF -> {
                val rotationPacket = CPacketPlayer.PositionRotation(mc.player.posX, mc.player.posY, mc.player.posZ, rotation.x.toFloat(), rotation.y.toFloat(), mc.player.onGround)
                mc.connection!!.sendPacket(rotationPacket)
            }
            InteractMode.VIEWLOCK -> {
                mc.player.rotationYaw = rotation.x.toFloat()
                mc.player.rotationPitch = rotation.y.toFloat()
            }
        }

        Thread{
            Thread.sleep(delayTicks.value * 25L)
            val placePacket = CPacketPlayerTryUseItemOnBlock(rayTrace.blockPos, rayTrace.sideHit, EnumHand.MAIN_HAND, hitVecOffset.x.toFloat(), hitVecOffset.y.toFloat(), hitVecOffset.z.toFloat())
            mc.connection!!.sendPacket(placePacket)
            mc.player.swingArm(EnumHand.MAIN_HAND)
            if (NoBreakAnimation.isEnabled) NoBreakAnimation.resetMining()
        }.start()
    }

    private fun mineBlock(pos: BlockPos, pre: Boolean): Boolean {
        if (pre) {
            if (InventoryUtils.getSlotsHotbar(ItemID.DIAMOND_PICKAXE.id) == null && InventoryUtils.getSlotsNoHotbar(ItemID.DIAMOND_PICKAXE.id) != null) {
                InventoryUtils.moveToHotbar(ItemID.DIAMOND_PICKAXE.id, ItemID.ENDER_CHEST.id)
                return false
            } else if (InventoryUtils.getSlots(0, 35, ItemID.DIAMOND_PICKAXE.id) == null) {
                sendChatMessage("No pickaxe was found in inventory.")
                mc.soundHandler.playSound(PositionedSoundRecord.getRecord(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f))
                return false
            }
            InventoryUtils.swapSlotToItem(ItemID.DIAMOND_PICKAXE.id)
        }

        var rayTrace = mc.world.rayTraceBlocks(mc.player.getPositionEyes(1f), Vec3d(pos).add(0.5, 0.5, 0.5)) ?: return false
        if (rayTrace.blockPos != pos) {
            var found = false
            for (side in EnumFacing.values()) {
                if (mc.world.getBlockState(pos.offset(side)).block == Blocks.AIR) {
                    rayTrace = mc.world.rayTraceBlocks(mc.player.getPositionEyes(1f), Vec3d(pos).add(0.5, 0.5, 0.5).add(Vec3d(side.directionVec).scale(0.499)))?: continue
                    if (rayTrace.blockPos == pos) {
                        found = true
                        break
                    }
                }
            }
            if (!found) {
                return false
            }
        }
        val facing = rayTrace.sideHit ?: return false
        val rotation = getRotationTo(rayTrace.hitVec, true)
        when (interacting.value) {
            InteractMode.SPOOF -> {
                val rotationPacket = CPacketPlayer.PositionRotation(mc.player.posX, mc.player.posY, mc.player.posZ, rotation.x.toFloat(), rotation.y.toFloat(), mc.player.onGround)
                mc.connection!!.sendPacket(rotationPacket)
            }
            InteractMode.VIEWLOCK -> {
                mc.player.rotationYaw = rotation.x.toFloat()
                mc.player.rotationPitch = rotation.y.toFloat()
            }
        }

        Thread {
            Thread.sleep(delayTicks.value * 25L)
            if (pre) {
                mc.connection!!.sendPacket(CPacketPlayerDigging(CPacketPlayerDigging.Action.START_DESTROY_BLOCK, pos, facing))
                if (state != State.SEARCHING) state = State.MINING else searchingState = SearchingState.MINING
            } else {
                mc.connection!!.sendPacket(CPacketPlayerDigging(CPacketPlayerDigging.Action.STOP_DESTROY_BLOCK, pos, facing))
            }
            mc.player.swingArm(EnumHand.MAIN_HAND)
        }.start()
        return true
    }

    private fun collectDroppedItem(itemId: Int) {
        pathing = if (getDroppedItem(itemId, 16.0f) != null) {
            goal = getDroppedItem(itemId, 16.0f)
            true
        } else false
    }

    private fun centerPlayer(): Boolean {
        return if (autoCenter.value == AutoCenterMode.OFF) {
            true
        } else {
            SurroundUtils.centerPlayer(autoCenter.value == AutoCenterMode.TP)
        }
    }

    private fun reset() {
        active = false
        pathing = false
        searchingState = SearchingState.PLACING
        playerPos = BlockPos(0, -1, 0)
        placingPos = BlockPos(0, -1, 0)
        tickCount = 0
    }
    /* End of tasks */
}
