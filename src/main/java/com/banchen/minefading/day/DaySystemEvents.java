package com.banchen.minefading.day;

import com.banchen.minefading.Config;
import com.banchen.minefading.Minefading;
import com.banchen.minefading.client.ClientDayOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// 客户端 tick 驱动的天数系统事件处理器
// 单人模式下通过 getSingleplayerServer() 访问集成服务端，处理天数变化、死亡回档和倒计时归零
@Mod.EventBusSubscriber(modid = Minefading.MODID, value = Dist.CLIENT)
public class DaySystemEvents
{
    // 防止同一次死亡触发多次回档
    private static boolean deathHandled;
    // 上次公告的显示天数，用于检测是否需要刷新覆盖层
    private static int lastAnnouncedDay = -1;

    // 将游戏时间转换为第几天（从第 1 天开始）
    private static int getWorldDay(ServerLevel level)
    {
        return (int) (level.getDayTime() / 24000L) + 1;
    }

    // 创意模式和旁观模式不受天数系统影响
    private static boolean canAffectPlayer(Player player)
    {
        return !player.isCreative() && !player.isSpectator();
    }

    // 触发黑屏覆盖层显示当前天数信息
    private static void announceCurrentDay(MinecraftServer server)
    {
        ServerLevel overworld = server.overworld();
        DayStateData data = DayStateData.get(server);
        int worldDay = getWorldDay(overworld);
        int displayedDay = data.getDisplayedDay(worldDay);
        int remainingDays = Math.max(0, Config.countdownDays - displayedDay + 1);
        ClientDayOverlay.showDayMessage(Config.mode, displayedDay, remainingDays, Config.overlayTicks);
        lastAnnouncedDay = displayedDay;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        MinecraftServer server = minecraft.getSingleplayerServer();
        // 非单人游戏或尚未进入世界时跳过
        if (player == null || server == null)
        {
            deathHandled = false;
            lastAnnouncedDay = -1;
            ClientDayOverlay.clearHud(); // 退出游戏时清除 HUD
            return;
        }

        if (!canAffectPlayer(player))
            return;

        // 仅在主世界维度内处理（避免在地狱/末路之地误触发）
        ServerLevel overworld = server.overworld();
        if (overworld.dimension() != Level.OVERWORLD)
            return;

        DayStateData data = DayStateData.get(server);
        int worldDay = getWorldDay(overworld);
        boolean dayChanged = data.updateForWorldDay(worldDay);
        int displayedDay = data.getDisplayedDay(worldDay);
        int remainingDays = Math.max(0, Config.countdownDays - displayedDay + 1);

        // 新的一天到来时自动保存存档点
        if (dayChanged)
            data.saveCheckpoint(server, worldDay);

        // 天数有变化或刚进入游戏时触发覆盖层
        if (dayChanged || displayedDay != lastAnnouncedDay)
            announceCurrentDay(server);

        // 每 tick 持续更新血条上方的 HUD 文字
        ClientDayOverlay.updateHud(Config.mode, displayedDay, remainingDays);

        // 倒计时归零：强制杀死玩家（极限模式死亡）
        if (Config.mode == DayMode.COUNTDOWN && remainingDays <= 0 && !player.isDeadOrDying())
        {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal("倒计时已归零。"), true);
            if (server.getPlayerList().getPlayer(player.getUUID()) != null)
                server.execute(() -> server.getPlayerList().getPlayer(player.getUUID()).kill());
            deathHandled = true;
            return;
        }

        // 玩家死亡时回档到上一个安全存档点
        if (player.isDeadOrDying() && !deathHandled)
        {
            data.rollbackToCheckpoint(server, worldDay);
            announceCurrentDay(server); // 回档后立即刷新天数显示
            deathHandled = true;
        }
        else if (!player.isDeadOrDying())
        {
            deathHandled = false; // 复活后重置标志
        }
    }
}
