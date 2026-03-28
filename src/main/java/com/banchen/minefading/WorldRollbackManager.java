package com.banchen.minefading;

import com.banchen.minefading.client.BlackTransitionScreen;
import com.banchen.minefading.item.RelicItems;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * 全世界回档管理器（仅单人模式）。
 *
 * 存档时：server.saveEverything() → 拷贝世界文件夹到备份目录
 * 回档时：黑屏 → disconnect()（内部 halt 等待客户端完全停止）→ 还原文件 → 重新加载世界
 */
public class WorldRollbackManager
{
    private static final Logger LOGGER = LogUtils.getLogger();

    // 回档状态机（volatile 保证客户端线程与客户端线程的可见性）
    // FILE_RESTORING：文件还原正在后台线程执行中（客户端继续渲染黑屏）
    private enum State { IDLE, PENDING_DISCONNECT, FILE_RESTORING, PENDING_RELOAD }
    private static volatile State state = State.IDLE;

    // 待重载的世界文件夹名（saves/<levelId>）
    private static volatile String pendingLevelId = null;
    // 当前世界根目录（saves/<levelId>/）
    private static volatile Path worldRootPath = null;
    // 备份目录（<gameDir>/minefading_snapshots/<levelId>/）
    private static volatile Path backupRootPath = null;
    // 是否已经发起 loadLevel（避免在 PENDING_RELOAD 中重复调用）
    private static volatile boolean reloadStarted = false;
    // 玩家就绪后 screen 连续为 null 的 tick 数（满足阈值才真正退出 PENDING_RELOAD）
    private static volatile int playerReadyTicks = 0;
    // 蜕皮标记文件名（放在 minefading_snapshots/ 目录下，不在快照子目录内，不会被还原覆盖）
    private static final String SHEDDING_PENDING_FILE = "shedding_pending";
    // 是否已在 PENDING_RELOAD 期间提交了蜕皮扣除（避免重复提交）
    private static volatile boolean sheddingDeductScheduled = false;

    // 是否已有可用快照
    public static boolean hasSnapshot()
    {
        return backupRootPath != null && Files.exists(backupRootPath);
    }

    // 是否存在“当前世界”的可用快照（即使本次启动尚未写入 backupRootPath）
    public static boolean hasSnapshot(MinecraftServer server)
    {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path backupRoot = getBackupRoot(worldRoot);
        return Files.exists(backupRoot);
    }

    // 删除当前世界快照（用于高塔触发前清空回溯点）
    public static void deleteSnapshot(MinecraftServer server)
    {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path backupRoot = getBackupRoot(worldRoot);
        if (!Files.exists(backupRoot))
            return;

        try
        {
            deleteDirectory(backupRoot);
            if (backupRootPath != null && backupRootPath.equals(backupRoot))
                backupRootPath = null;
            LOGGER.info("[Minefading] 已删除世界快照：{}", backupRoot);
        }
        catch (IOException e)
        {
            LOGGER.error("[Minefading] 删除世界快照失败", e);
        }
    }

    // 回档后首次重进世界时跳过一次“自动回档检查”，避免进入循环
    private static volatile String skipNextEntryCheckLevelId = null;

    // 是否正在回档（用于阻止 DaySystemEvents 重复触发）
    public static boolean isRestoring()
    {
        return state != State.IDLE;
    }

    /**
     * 创建世界快照：saveEverything 在客户端线程执行，文件拷贝在后台线程执行。
     */
    public static void takeSnapshot(MinecraftServer server)
    {
        server.execute(() ->
        {
            try
            {
                server.saveEverything(true, false, true);

                // normalize() 将 saves/<世界名>/. 解析为 saves/<世界名>，避免 getFileName() 返回 "."
                final Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
                final Path backupRoot = getBackupRoot(worldRoot);

                // 文件拷贝耗时较长，移至后台线程，避免阻塞客户端线程导致接下来的 clearLevel() 卡顿
                Thread copyThread = new Thread(() ->
                {
                    try
                    {
                        if (Files.exists(backupRoot))
                            deleteDirectory(backupRoot);
                        copyDirectory(worldRoot, backupRoot);

                        worldRootPath = worldRoot;
                        backupRootPath = backupRoot;
                        LOGGER.info("[Minefading] 世界快照已保存至 {}", backupRoot);
                    }
                    catch (IOException e)
                    {
                        LOGGER.error("[Minefading] 世界快照保存失败", e);
                    }
                }, "MF-Snapshot");
                copyThread.setDaemon(true);
                copyThread.start();
            }
            catch (Exception e)
            {
                LOGGER.error("[Minefading] 世界快照保存失败", e);
            }
        });
    }

    // 将快照放到 saves 目录外：<gameDir>/minefading_snapshots/<levelId>/
    private static Path getBackupRoot(Path worldRoot)
    {
        Path savesDir = worldRoot.getParent();
        Path gameDir = savesDir != null ? savesDir.getParent() : null;
        if (gameDir == null)
            gameDir = worldRoot.toAbsolutePath().getParent();
        return gameDir.resolve("minefading_snapshots").resolve(worldRoot.getFileName().toString());
    }

    /**
     * 触发回档（从客户端 tick 调用，必须在有可用快照的情况下调用）。
     */
    public static void scheduleRestore(MinecraftServer server)
    {
        if (state != State.IDLE)
            return;

        // normalize() 将 saves/<世界名>/. 解析为 saves/<世界名>，getFileName() 才能返回正确的世界名
        worldRootPath = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        backupRootPath = getBackupRoot(worldRootPath);
        if (!Files.exists(backupRootPath))
        {
            LOGGER.warn("[Minefading] 没有可用的世界快照，无法回档：{}", backupRootPath);
            return;
        }

        pendingLevelId = worldRootPath.getFileName().toString();
        reloadStarted = false;
        playerReadyTicks = 0;
        state = State.PENDING_DISCONNECT;
        LOGGER.info("[Minefading] 回档已触发，levelId={}", pendingLevelId);
    }

    // 若返回 true，表示本次进入该世界应跳过自动快照检查一次
    public static boolean consumeSkipAutoEntryCheck(MinecraftServer server)
    {
        String levelId = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString();
        if (levelId.equals(skipNextEntryCheckLevelId))
        {
            skipNextEntryCheckLevelId = null;
            return true;
        }
        return false;
    }

    /**
     * 每客户端 tick 推进状态机（由 DaySystemEvents.onClientTick 在最开始调用）。
     */
    public static void onClientTick(Minecraft minecraft)
    {
        switch (state)
        {
            case PENDING_DISCONNECT ->
            {
                net.minecraft.client.server.IntegratedServer srv = minecraft.getSingleplayerServer();
                if (srv != null)
                {
                    // halt(false) 让客户端停止时跳过关服自动保存（快照时已保存），
                    // 可将 clearLevel() 的等待时间从分钟级大幅缩短至秒级。
                    srv.halt(false);
                    minecraft.clearLevel(new BlackTransitionScreen());

                    // 客户端已停止，将耗时的文件 I/O 移至后台线程，
                    // 避免阻塞客户端主线程导致黑屏卡送。
                    state = State.FILE_RESTORING;
                    final Path worldRoot = worldRootPath;
                    final Path backupRoot = backupRootPath;
                    final String levelId = pendingLevelId;
                    Thread restoreThread = new Thread(() ->
                    {
                        try
                        {
                            LOGGER.info("[Minefading] 正在从快照还原世界文件...");
                            if (Files.exists(worldRoot))
                                deleteDirectory(worldRoot);
                            copyDirectory(backupRoot, worldRoot);
                            LOGGER.info("[Minefading] 文件还原完成，准备重新加载世界...");
                            // 通知主线程切换到 PENDING_RELOAD
                            pendingLevelId = levelId;
                            state = State.PENDING_RELOAD;
                        }
                        catch (IOException e)
                        {
                            LOGGER.error("[Minefading] 世界还原失败", e);
                            state = State.IDLE;
                        }
                    }, "MF-WorldRestore");
                    restoreThread.setDaemon(true);
                    restoreThread.start();
                }
            }
            case FILE_RESTORING ->
            {
                // 后台线程正在还原文件，保持黑幕 Screen 即可（单层黑幕）
                if (!(minecraft.screen instanceof BlackTransitionScreen))
                    minecraft.setScreen(new BlackTransitionScreen());
            }
            case PENDING_RELOAD ->
            {
                // 文件已还原：仅首次调用一次 loadLevel
                // 黑幕由 ClientDayOverlay.onScreenRenderPost（ScreenEvent.Render.Post）负责，
                // 该事件在每帧 Screen 渲染后、Overlay 渲染前触发，在同一帧内可同时：
                // 1. 在 Screen 层绘制纯黑覆盖任何 Screen 内容
                // 2. 将原版 LevelLoadingScreen overlay 包装到 BlackTransitionOverlay 内
                if (!reloadStarted)
                {
                    LOGGER.info("[Minefading] 正在重载世界：{}", pendingLevelId);
                    skipNextEntryCheckLevelId = pendingLevelId;
                    playerReadyTicks = 0;
                    sheddingDeductScheduled = false;
                    minecraft.createWorldOpenFlows().loadLevel(new BlackTransitionScreen(), pendingLevelId);
                    reloadStarted = true;
                }

                // 退出条件：玩家就绪 + screen 连续 5 tick 为空
                boolean playerReady = minecraft.player != null && minecraft.getSingleplayerServer() != null;
                if (playerReady && minecraft.screen == null)
                {
                    playerReadyTicks++;
                }
                else
                {
                    playerReadyTicks = 0;
                }

                // 玩家刚就绪（黑屏仍在显示），立即在服务端线程扣除蜕皮
                if (playerReadyTicks == 1 && !sheddingDeductScheduled)
                {
                    sheddingDeductScheduled = true;
                    MinecraftServer srv = minecraft.getSingleplayerServer();
                    if (srv != null)
                    {
                        srv.execute(() -> deductSheddingFromMarker(srv));
                    }
                }

                if (playerReadyTicks >= 5)
                {
                    // 清除黑幕 Overlay 并结束回档
                    minecraft.setOverlay(null);
                    if (minecraft.screen instanceof BlackTransitionScreen)
                        minecraft.setScreen(null);
                    state = State.IDLE;
                    pendingLevelId = null;
                    reloadStarted = false;
                    playerReadyTicks = 0;
                    sheddingDeductScheduled = false;
                    // entrySnapshotChecked 在回档期间保持 true（isRestoring 阻止了重置），
                    // 已足以防止本次重载时再次触发入口检查，无需保留 skipNextEntryCheckLevelId。
                    // 若不清除，该标记会残留到下次退出重进时才被 consumeSkipAutoEntryCheck 消耗，
                    // 导致该次重进跳过快照检查、不执行回档。
                    skipNextEntryCheckLevelId = null;
                }
            }
            default -> { /* IDLE：无需处理 */ }
        }
    }

    // 递归拷贝目录
    private static void copyDirectory(Path src, Path dst) throws IOException
    {
        Files.walkFileTree(src, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException
            {
                Files.createDirectories(dst.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
            {
                // session.lock 由运行中的客户端独占，无法拷贝；还原时新客户端会重新创建它
                if (file.getFileName().toString().equals("session.lock"))
                    return FileVisitResult.CONTINUE;
                Files.copy(file, dst.resolve(src.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // 递归删除目录
    private static void deleteDirectory(Path path) throws IOException
    {
        Files.walkFileTree(path, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
            {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException
            {
                // 文件已被其他线程删除，忽略并继续
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException
            {
                if (exc != null) throw exc;
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ── 蜕皮使用记录（标记文件放在 minefading_snapshots/ 目录下，不会被快照还原覆盖） ──

    /**
     * 蜕皮使用时调用：写入标记文件到 minefading_snapshots/shedding_pending
     */
    public static void writeShedding(MinecraftServer server)
    {
        Path marker = getSheddingMarkerPath(server);
        try
        {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "1");
            LOGGER.info("[Minefading] 已写入蜕皮使用标记：{}", marker);
        }
        catch (IOException e)
        {
            LOGGER.error("[Minefading] 写入蜕皮标记失败", e);
        }
    }

    /**
     * 在黑屏期间扣除蜕皮：读取标记文件，扣除 1 个蜕皮，然后删除标记。
     * 由 PENDING_RELOAD 在 playerReadyTicks==1 时通过 server.execute() 调度到服务端线程执行。
     */
    private static void deductSheddingFromMarker(MinecraftServer server)
    {
        // 读取并删除标记文件
        Path marker = getSheddingMarkerPath(server);
        if (!Files.exists(marker))
            return;

        try
        {
            Files.deleteIfExists(marker);
            LOGGER.info("[Minefading] 已清除蜕皮使用标记");
        }
        catch (IOException e)
        {
            LOGGER.error("[Minefading] 清除蜕皮标记失败", e);
        }

        // 扣除第一个玩家背包中的 1 个蜕皮（单人模式只有一个玩家）
        Item sheddingItem = RelicItems.SHEDDING.get();
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            for (int i = 0; i < player.getInventory().getContainerSize(); i++)
            {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack.is(sheddingItem))
                {
                    stack.shrink(1);
                    if (stack.isEmpty())
                    {
                        player.getInventory().setItem(i, ItemStack.EMPTY);
                    }
                    player.inventoryMenu.broadcastChanges();
                    LOGGER.info("[Minefading] 已从玩家 {} 背包扣除 1 个蜕皮", player.getName().getString());
                    return;
                }
            }
        }
        LOGGER.warn("[Minefading] 回档完成但未在任何玩家背包中找到蜕皮物品");
    }

    private static Path getSheddingMarkerPath(MinecraftServer server)
    {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path snapshotsDir = getBackupRoot(worldRoot).getParent();
        return snapshotsDir.resolve(SHEDDING_PENDING_FILE);
    }

    /**
     * 清理孤立快照：删除 minefading_snapshots/ 下所有在 saves/ 中已不存在的世界快照。
     * 在客户端打开世界选择列表时调用（纯客户端操作，不需要 MinecraftServer）。
     */
    public static void cleanOrphanedSnapshots()
    {
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        Path snapshotsDir = gameDir.resolve("minefading_snapshots");
        Path savesDir = gameDir.resolve("saves");

        if (!Files.isDirectory(snapshotsDir))
            return;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(snapshotsDir))
        {
            for (Path snapshotEntry : stream)
            {
                if (!Files.isDirectory(snapshotEntry))
                    continue;

                String levelId = snapshotEntry.getFileName().toString();
                Path worldDir = savesDir.resolve(levelId);

                if (!Files.isDirectory(worldDir))
                {
                    try
                    {
                        deleteDirectory(snapshotEntry);
                        LOGGER.info("[Minefading] 已清理孤立快照：{}", snapshotEntry);
                    }
                    catch (IOException e)
                    {
                        LOGGER.error("[Minefading] 清理孤立快照失败：{}", snapshotEntry, e);
                    }
                }
            }
        }
        catch (IOException e)
        {
            LOGGER.error("[Minefading] 扫描快照目录失败", e);
        }
    }
}
