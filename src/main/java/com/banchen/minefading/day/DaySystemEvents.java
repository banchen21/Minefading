package com.banchen.minefading.day;

import com.banchen.minefading.Config;
import com.banchen.minefading.Minefading;
import com.banchen.minefading.WorldRollbackManager;
import com.banchen.minefading.client.ClientDayOverlay;
import com.banchen.minefading.relic.RelicRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// 客户端 tick 驱动的天数系统事件处理器
// 单人模式下通过 getSingleplayerServer() 访问集成客户端，处理天数变化、死亡回档和倒计时归零
@Mod.EventBusSubscriber(modid = Minefading.MODID, value = Dist.CLIENT)
public class DaySystemEvents
{
    // 防止同一次死亡触发多次回档
    private static boolean deathHandled;
    // 上次公告的显示天数，用于检测是否需要刷新覆盖层
    private static int lastAnnouncedDay = -1;
    // 进入世界后是否已执行过“自动快照检查”（有快照则回档，无快照则建档）
    private static boolean entrySnapshotChecked;
    // 本次已检查的世界 ID，用于跨世界切换时重新检查
    private static String checkedLevelId;

    // 将游戏时间转换为第几天（从第 1 天开始）
    private static int getWorldDay(ServerLevel level)
    {
        return (int) (level.getDayTime() / 24000L) + 1;
    }

    // 仅旁观模式不受天数系统影响（创造模式同样受影响）
    private static boolean canAffectPlayer(Player player)
    {
        return !player.isSpectator();
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

        // 优先推进回档状态机（即使 player/server 为 null 也需要继续处理）
        WorldRollbackManager.onClientTick(minecraft);

        // 回档进行中时跳过所有常规逻辑
        if (WorldRollbackManager.isRestoring())
            return;

        LocalPlayer player = minecraft.player;
        MinecraftServer server = minecraft.getSingleplayerServer();
        // 非单人游戏或尚未进入世界时跳过
        if (player == null || server == null)
        {
            deathHandled = false;
            lastAnnouncedDay = -1;
            entrySnapshotChecked = false;
            checkedLevelId = null;
            ClientDayOverlay.clearHud(); // 退出游戏时清除 HUD
            return;
        }

        if (!canAffectPlayer(player))
            return;

        // 仅在主世界维度内处理（避免在地狱/末路之地误触发）
        ServerLevel overworld = server.overworld();
        if (overworld.dimension() != Level.OVERWORLD)
            return;

        // 进入世界时先检查快照：有则回档，无则立即创建首个快照
        String levelId = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString();
        if (!levelId.equals(checkedLevelId))
        {
            checkedLevelId = levelId;
            entrySnapshotChecked = false;
        }

        // 进入世界后后台预热快照索引，减少回档触发时的首帧等待
        WorldRollbackManager.prewarmSnapshotIndex(server);

        if (!entrySnapshotChecked)
        {
            entrySnapshotChecked = true;
            if (WorldRollbackManager.consumePreEntryRestore())
            {
                // 世界加载前已完成快照文件还原，现在应用回档状态（天数偏移、追踪实体、蜕皮扣除）
                final MinecraftServer srv = server;
                srv.execute(() -> WorldRollbackManager.applyPreEntryRollbackState(srv));
                return;
            }
            else if (!WorldRollbackManager.consumeSkipAutoEntryCheck(server))
            {
                if (WorldRollbackManager.hasSnapshot(server))
                {
                    WorldRollbackManager.scheduleRestore(server);
                    deathHandled = true;
                    return;
                }
                else
                {
                    WorldRollbackManager.takeSnapshot(server);
                }
            }
        }

        DayStateData data = DayStateData.get(server);
        int worldDay = getWorldDay(overworld);

        // 新世界且当前没有可用快照时，避免复用同名旧世界的外部 day_state 导致开局不是第一天。
        if (!WorldRollbackManager.hasSnapshot(server))
            data.resetForLikelyFreshWorld(worldDay);

        boolean dayChanged = data.updateForWorldDay(worldDay);
        int displayedDay = data.getDisplayedDay(worldDay);
        int remainingDays = Math.max(0, Config.countdownDays - displayedDay + 1);

        // 新的一天到来时自动保存全世界快照
        if (dayChanged)
            WorldRollbackManager.takeSnapshot(server);

        // 天数有变化或刚进入游戏时触发覆盖层
        if (dayChanged || displayedDay != lastAnnouncedDay)
            announceCurrentDay(server);

        // 每 tick 持续更新血条上方的 HUD 文字
        ClientDayOverlay.updateHud(Config.mode, displayedDay, remainingDays);

        // 倒计时归零：强制杀死玩家（极限模式死亡）
        if (Config.mode == DayMode.COUNTDOWN && remainingDays <= 0 && !player.isDeadOrDying())
        {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.minefading.countdown_zero"), true);
            if (server.getPlayerList().getPlayer(player.getUUID()) != null)
                server.execute(() ->
                {
                    net.minecraft.server.level.ServerPlayer serverPlayer = server.getPlayerList().getPlayer(player.getUUID());
                    if (serverPlayer == null)
                        return;
                    WorldRollbackManager.deleteSnapshot(server);
                    com.banchen.minefading.relic.RelicRuntime.markForSpectatorLock(serverPlayer.getUUID());
                    serverPlayer.kill();
                });
            deathHandled = true;
            return;
        }

        // 玩家死亡时触发全世界回档
        if (player.isDeadOrDying() && !deathHandled)
        {
            ServerPlayer serverPlayer = server.getPlayerList().getPlayer(player.getUUID());
            if (RelicRuntime.isCausalityActive(player.getUUID()) || RelicRuntime.isCausalityRecoveryActive(player.getUUID()))
            {
                deathHandled = true;
                return;
            }
            if (serverPlayer != null && RelicRuntime.canCausalityPreventDeath(serverPlayer))
            {
                deathHandled = true;
                return;
            }

            WorldRollbackManager.scheduleRestore(server);
            deathHandled = true;
        }
        else if (!player.isDeadOrDying())
        {
            deathHandled = false; // 复活后重置标志
        }
    }
}
