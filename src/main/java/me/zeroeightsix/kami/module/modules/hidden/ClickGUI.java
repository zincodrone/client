package me.zeroeightsix.kami.module.modules.hidden;

import me.zeroeightsix.kami.KamiMod;
import me.zeroeightsix.kami.module.Module;

@Module.Info(
        name = "ClickGUI",
        category = Module.Category.HIDDEN,
        showOnArray = Module.ShowOnArray.OFF,
        description = "Show the client ClickGUI"
)
public class ClickGUI extends Module {

    @Override
    protected void onEnable() {
        KamiMod.KAMI_UI.showScreen();
        this.onDisable();
    }
}
