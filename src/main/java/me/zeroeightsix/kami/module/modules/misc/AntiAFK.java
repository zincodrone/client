package me.zeroeightsix.kami.module.modules.misc;

import me.zero.alpine.listener.EventHandler;
import me.zero.alpine.listener.Listener;
import me.zeroeightsix.kami.event.events.PacketEvent;
import me.zeroeightsix.kami.event.events.RenderEvent;
import me.zeroeightsix.kami.module.Module;
import me.zeroeightsix.kami.setting.Setting;
import me.zeroeightsix.kami.setting.Settings;
import me.zeroeightsix.kami.util.ColourUtils;
import me.zeroeightsix.kami.util.GeometryMasks;
import me.zeroeightsix.kami.util.KamiTessellator;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.network.play.client.CPacketAnimation;
import net.minecraft.network.play.server.SPacketChat;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.opengl.GL11;

import java.util.List;
import java.util.Objects;
import java.util.Random;

import static me.zeroeightsix.kami.util.ColourConverter.rgbToInt;
import static me.zeroeightsix.kami.util.MathsUtils.reverseNumber;
import static me.zeroeightsix.kami.util.MessageSendHelper.sendChatMessage;
import static me.zeroeightsix.kami.util.MessageSendHelper.sendServerMessage;
import static me.zeroeightsix.kami.util.VectorUtil.getBlockPositionsInArea;
import static me.zeroeightsix.kami.util.VectorUtil.getHighestTerrainPos;

/**
 * Created by 086 on 16/12/2017.
 * Updated by dominikaaaa on 21/04/20
 * TODO: Path finding to stay inside 1 chunk
 */
@Module.Info(
        name = "AntiAFK",
        category = Module.Category.MISC,
        description = "Prevents being kicked for AFK"
)
public class AntiAFK extends Module {

    private Setting<Integer> frequency = register(Settings.integerBuilder("Action Frequency").withMinimum(1).withMaximum(100).withValue(40).build());
    public Setting<Boolean> autoReply = register(Settings.b("AutoReply", true));
    private Setting<Mode> mode = register(Settings.enumBuilder(Mode.class).withName("Mode").withValue(Mode.TRADITIONAL).build());
    private Setting<Integer> size = register(Settings.integerBuilder("Chunk Size").withMinimum(1).withMaximum(10).withValue(1).build());
    private Setting<Boolean> swing = register(Settings.b("Swing", true));
    private Setting<Boolean> jump = register(Settings.b("Jump", true));
    private Setting<Boolean> turn = register(Settings.booleanBuilder("Turn").withValue(true).withVisibility(v -> mode.getValue().equals(Mode.TRADITIONAL)).build());

    private enum Mode { TRADITIONAL, CHUNK;}
    private Random random = new Random();

    private BlockPos pos1 = new BlockPos(0, 0, 0);
    private BlockPos pos2 = new BlockPos(0, 0, 0);
    private int[] cornerY = { 0, 0, 0, 0 }; // 22, 21, 12, 11
    private List<BlockPos> positions;

    public void onEnable() {
        if (mc.player == null) return;

        if (!mode.getValue().equals(Mode.CHUNK)) return;

        pos1.x = (int) mc.player.posX;
        pos1.y = (int) mc.player.posY;
        pos1.z = (int) mc.player.posZ;
        pos2.x = pos1.getX() + (16 * size.getValue());
        pos2.y = pos1.getY();
        pos2.z = pos1.getZ() + (16 * size.getValue());
        sendChatMessage(getChatName() + "Registered chunk: X: [" + pos1.getX() + "][" + pos2.getX() + "] Z: [" + pos1.getZ() + "][" + pos2.getZ() + "]");

        positions = getBlockPositionsInArea(pos1, pos2);
        sendChatMessage(positions.size() + "");
        for (int i = 0 ; i < positions.size() ; i++) { /* Flatten all positions to terrain level */
            positions.set(i, getHighestTerrainPos(positions.get(i)));
            sendChatMessage("Setting " + positions.get(i).getX() + " " + positions.get(i).getZ() + " to " + positions.get(i).getY());
        }
        cornerY[0] = getHighestTerrainPos(pos2).getY();
        cornerY[1] = getHighestTerrainPos(new BlockPos(pos2.getX(), pos1.getY(), pos1.getZ())).getY();
        cornerY[2] = getHighestTerrainPos(new BlockPos(pos1.getX(), pos1.getY(), pos2.getZ())).getY();
        cornerY[3] = getHighestTerrainPos(pos1).getY();
    }

    private boolean insideChunk() {
        return (mc.player.posX > pos1.getX() && pos2.getX() > mc.player.posX) && (mc.player.posZ > pos1.getZ() && pos2.getZ() > mc.player.posZ);
    }

    @Override
    public void onUpdate() {
        if (mc.playerController.getIsHittingBlock()) return;

        if (swing.getValue() && mc.player.ticksExisted % (0.5 * getFrequency()) == 0) {
            Objects.requireNonNull(mc.getConnection()).sendPacket(new CPacketAnimation(EnumHand.MAIN_HAND));
        }

        if (jump.getValue() && mc.player.ticksExisted % (2 * getFrequency()) == 0) {
            mc.player.jump();
        }

        if (mode.getValue().equals(Mode.TRADITIONAL) && turn.getValue() && mc.player.ticksExisted % (0.375 * getFrequency()) == 0) {
            mc.player.rotationYaw = random.nextInt(360) - makeNegRandom(180);
        }
    }

    @Override
    public void onWorldRender(RenderEvent event) {
        if (mc.player == null || positions == null) return;
        GlStateManager.pushMatrix();
        int side = GeometryMasks.Quad.ALL;
        int colourBlue = rgbToInt(155, 144, 255, 50);
        int colourPink = rgbToInt(255, 144, 255, 50);

        KamiTessellator.prepare(GL11.GL_QUADS);

        for (BlockPos renderPos : positions)
            KamiTessellator.drawBox(renderPos, colourBlue, GeometryMasks.Quad.UP);
        KamiTessellator.drawBox(new BlockPos(pos2.getX(), cornerY[0], pos2.getZ()), colourPink, side, cornerY[0] + 2, 1, 1, 0);
        KamiTessellator.drawBox(new BlockPos(pos2.getX(), cornerY[1], pos1.getZ()), colourPink, side, cornerY[1] + 2, 1, 1, 0);
        KamiTessellator.drawBox(new BlockPos(pos1.getX(), cornerY[2], pos2.getZ()), colourPink, side, cornerY[2] + 2, 1, 1, 0);
        KamiTessellator.drawBox(new BlockPos(pos1.getX(), cornerY[3], pos1.getZ()), colourPink, side, cornerY[3] + 2, 1, 1, 0);

        KamiTessellator.release();
        GlStateManager.popMatrix();
        GlStateManager.enableTexture2D();
    }

    @EventHandler
    public Listener<PacketEvent.Receive> receiveListener = new Listener<>(event -> {
        if (autoReply.getValue() && event.getPacket() instanceof SPacketChat && ((SPacketChat) event.getPacket()).getChatComponent().getUnformattedText().contains("whispers: ") && !((SPacketChat) event.getPacket()).getChatComponent().getUnformattedText().contains(mc.player.getName())) {
            sendServerMessage("/r I am currently AFK and using KAMI Blue!");
        }
    });

    private float getFrequency() {
        return reverseNumber(frequency.getValue(), 1, 100);
    }

    private int makeNegRandom(int input) {
        int rand = random.nextBoolean() ? 1 : 0;
        if (rand == 0) {
            return -input;
        } else {
            return input;
        }
    }
}
