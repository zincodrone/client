package me.zeroeightsix.kami.module.modules;

import me.zeroeightsix.kami.KamiMod;
import me.zeroeightsix.kami.module.Module;
import org.lwjgl.input.Keyboard;

/**
 * Created by 086 on 23/08/2017.
 */
@Module.Info(
        name = "clickGUI",
        description = "Opens the Click GUI",
        category = Module.Category.HIDDEN
)
public class ClickGUI extends Module {

    public ClickGUI() {
        getBind().setKey(Keyboard.KEY_Y);
    }

    @Override
    protected void onEnable() {
        KamiMod.KAMI_UI.showScreen();
        this.onDisable();
    }

}
