package me.zeroeightsix.kami.module.modules.chat

import me.zeroeightsix.kami.KamiMod
import me.zeroeightsix.kami.event.events.SafeTickEvent
import me.zeroeightsix.kami.module.Module
import me.zeroeightsix.kami.setting.Settings
import me.zeroeightsix.kami.util.TimerUtils
import me.zeroeightsix.kami.util.text.MessageDetection
import me.zeroeightsix.kami.util.text.MessageSendHelper
import me.zeroeightsix.kami.util.text.MessageSendHelper.sendServerMessage
import net.minecraftforge.fml.common.gameevent.TickEvent
import org.kamiblue.event.listener.listener
import java.io.File
import kotlin.random.Random

@Module.Info(
        name = "Spammer",
        description = "Spams text from a file on a set delay into the chat",
        category = Module.Category.CHAT,
        modulePriority = 100
)
object Spammer : Module() {
    private val modeSetting = register(Settings.e<Mode>("Order", Mode.RANDOM_ORDER))
    private val delay = register(Settings.integerBuilder("Delay(s)").withRange(1, 240).withValue(10).withStep(5))

    private val file = File(KamiMod.DIRECTORY + "spammer.txt")
    private val spammer = ArrayList<String>()
    private val timer = TimerUtils.TickTimer(TimerUtils.TimeUnit.SECONDS)
    private var currentLine = 0

    private enum class Mode {
        IN_ORDER, RANDOM_ORDER
    }

    override fun onEnable() {
        spammer.clear()
        if (file.exists()) {
            try {
                file.forEachLine { if (it.isNotBlank()) spammer.add(it.trim()) }
                MessageSendHelper.sendChatMessage("$chatName Loaded spammer messages!")
            } catch (e: Exception) {
                MessageSendHelper.sendErrorMessage("$chatName Failed loading spammer, $e")
            }
        } else {
            file.createNewFile()
            MessageSendHelper.sendErrorMessage("$chatName Spammer file is empty!" +
                        ", please add them in the &7spammer.txt&f under the &7.minecraft/kamiblue&f directory.")
        }
    }

    init {
        listener<SafeTickEvent> {
            if (it.phase != TickEvent.Phase.START || spammer.isEmpty() || !timer.tick(delay.value.toLong())) return@listener
            val message = if (modeSetting.value == Mode.IN_ORDER) getOrdered() else getRandom()
            if (MessageDetection.Command.KAMI_BLUE detect message) {
                MessageSendHelper.sendKamiCommand(message)
            } else {
                sendServerMessage(message)
            }
        }
    }

    private fun getOrdered(): String {
        currentLine %= spammer.size
        return spammer[currentLine++]
    }

    private fun getRandom(): String {
        val prevLine = currentLine
        // Avoids sending the same message
        while (spammer.size != 1 && currentLine == prevLine) {
            currentLine = Random.nextInt(spammer.size)
        }
        return spammer[currentLine]
    }
}
