package com.banchen.minefading;

import com.banchen.minefading.client.BlackTransitionScreen;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * 全世界回档管理器（仅单人模式）。
 *
 * 存档时：server.saveEverything() → 拷贝世界文件夹到备份目录
 * 回档时：黑屏 → disconnect()（内部 halt 等待服务端完全停止）→ 还原文件 → 重新加载世界
 */
public class WorldRollbackManager
{
    private static final Logger LOGGER = LogUtils.getLogger();

    // 回档状态机（volatile 保证客户端线程与服务端线程的可见性）
    // FILE_RESTORING：文件还原正在后台线程执行中（客户端继续渲染黑屏）
    private enum State { IDLE, PENDING_DISCONNECT, FILE_RESTORING, PENDING_RELOAD }
    private static volatile State state = State.IDLE;

    // 待重载的世界文件夹名（saves/<levelId>）
    private static volatile String pendingLevelId = null;
    // 当前世界根目录（saves/<levelId>/）
    private static volatile Path worldRootPath = null;
    // 备份目录（<gameDir>/minefading_snapshots/<levelId>/）
    private static volatile Path backupRootPath = null;

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

    // 回档后首次重进世界时跳过一次“自动回档检查”，避免进入循环
    private static volatile String skipNextEntryCheckLevelId = null;

    // 是否正在回档（用于阻止 DaySystemEvents 重复触发）
    public static boolean isRestoring()
    {
        return state != State.IDLE;
    }

    /**
     * 创建世界快照：saveEverything 在服务端线程执行，文件拷贝在后台线程执行。
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

                // 文件拷贝耗时较长，移至后台线程，避免阻塞服务端线程导致接下来的 clearLevel() 卡顿
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
                    // halt(false) 让服务端停止时跳过关服自动保存（快照时已保存），
                    // 可将 clearLevel() 的等待时间从分钟级大幅缩短至秒级。
                    srv.halt(false);
                    minecraft.clearLevel(new BlackTransitionScreen());

                    // 服务端已停止，将耗时的文件 I/O 移至后台线程，
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
                // 后台线程正在还原文件，主线程继续渲染黑屏，无需操作
            }
            case PENDING_RELOAD ->
            {
                // 文件已还原，在客户端主线程调用 loadLevel 重新打开世界
                LOGGER.info("[Minefading] 正在重载世界：{}", pendingLevelId);
                skipNextEntryCheckLevelId = pendingLevelId;
                minecraft.createWorldOpenFlows().loadLevel(new BlackTransitionScreen(), pendingLevelId);
                state = State.IDLE;
                pendingLevelId = null;
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
                // session.lock 由运行中的服务端独占，无法拷贝；还原时新服务端会重新创建它
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
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException
            {
                if (exc != null) throw exc;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
