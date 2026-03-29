package com.banchen.minefading;

import com.banchen.minefading.client.BlackTransitionScreen;
import com.banchen.minefading.day.DayStateData;
import com.banchen.minefading.item.RelicItems;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/**
 * 全世界回档管理器（仅单人模式）。
 *
 * 存档时：server.saveEverything() → 拷贝世界文件夹到备份目录
 * 回档时：黑屏 → disconnect()（内部 halt 等待客户端完全停止）→ 还原文件 → 重新加载世界
 */
public class WorldRollbackManager
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SNAPSHOT_INDEX_FILE = ".mf_snapshot_index.tsv";
    private static final Set<String> TRACKED_DIR_NAMES = Set.of("region", "poi", "entities", "playerdata", "data");
    private static final int PARALLEL_COPY_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
    private static final ExecutorService SNAPSHOT_EXECUTOR = Executors.newSingleThreadExecutor(r ->
    {
        Thread t = new Thread(r, "MF-Snapshot");
        t.setDaemon(true);
        return t;
    });
    private static final Object SNAPSHOT_TASK_MONITOR = new Object();

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
    // 快照索引预热状态
    private static volatile boolean snapshotIndexWarmupRunning = false;
    private static volatile Path warmedSnapshotRoot = null;
    private static volatile SnapshotIndex warmedSnapshotIndex = SnapshotIndex.empty();
    private static volatile int snapshotTasksInFlight = 0;
    private static volatile boolean rollbackStateApplied = false;
    // 世界加载前预还原是否完成（由 Mixin 在 loadLevel 前设置，由 DaySystemEvents 进入世界后消费）
    private static volatile boolean preEntryRestorePending = false;

    private record FileMeta(long size, long modifiedMillis) {}

    private record SnapshotIndex(Map<String, FileMeta> files)
    {
        private static SnapshotIndex empty()
        {
            return new SnapshotIndex(Collections.emptyMap());
        }
    }

    private record SyncStats(int copied, int deleted) {}

    // 是否已有可用快照
    public static boolean hasSnapshot()
    {
        return backupRootPath != null && (Files.exists(backupRootPath) || hasPendingSnapshotTasks());
    }

    // 是否存在“当前世界”的可用快照（即使本次启动尚未写入 backupRootPath）
    public static boolean hasSnapshot(MinecraftServer server)
    {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path backupRoot = getBackupRoot(worldRoot);
        return Files.exists(backupRoot) || hasPendingSnapshotTasks();
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
            if (backupRoot.equals(warmedSnapshotRoot))
            {
                warmedSnapshotRoot = null;
                warmedSnapshotIndex = SnapshotIndex.empty();
            }
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
                ServerLevel overworld = server.overworld();
                DayStateData.get(server).saveCheckpoint(server, getWorldDay(overworld));
                server.saveEverything(true, false, true);

                // normalize() 将 saves/<世界名>/. 解析为 saves/<世界名>，避免 getFileName() 返回 "."
                final Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
                final Path backupRoot = getBackupRoot(worldRoot);

                beginSnapshotTask();

                // 文件拷贝耗时较长，移至后台线程，避免阻塞客户端线程导致接下来的 clearLevel() 卡顿
                SNAPSHOT_EXECUTOR.execute(() ->
                {
                    try
                    {
                        Files.createDirectories(backupRoot);

                        SnapshotIndex sourceIndex = buildIndex(worldRoot, true);
                        SnapshotIndex backupIndex = readSnapshotIndexPreferWarm(backupRoot);
                        SyncStats stats = syncTreesIncremental(worldRoot, backupRoot, sourceIndex, backupIndex);
                        writeSnapshotIndex(backupRoot, sourceIndex);

                        worldRootPath = worldRoot;
                        backupRootPath = backupRoot;
                        warmedSnapshotRoot = backupRoot;
                        warmedSnapshotIndex = sourceIndex;
                        LOGGER.info("[Minefading] 世界快照增量保存完成：{}（复制 {}，删除 {}）", backupRoot, stats.copied(), stats.deleted());
                    }
                    catch (IOException e)
                    {
                        LOGGER.error("[Minefading] 世界快照保存失败", e);
                    }
                    finally
                    {
                        finishSnapshotTask();
                    }
                });
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

        // 细沙要求“回溯时才把生物带到过去”，因此这里在真正断开并还原前
        // 抓取一次当前追踪实体的最新状态，而不是把整张世界快照往前推进。
        server.executeBlocking(() -> DayStateData.get(server).captureTrackedEntitiesForRollback(server));

        pendingLevelId = worldRootPath.getFileName().toString();
        reloadStarted = false;
        playerReadyTicks = 0;
        rollbackStateApplied = false;
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
                if (hasPendingSnapshotTasks())
                    return;

                if (backupRootPath == null || !Files.exists(backupRootPath))
                {
                    LOGGER.warn("[Minefading] 回档时未找到可用快照，已取消本次回档：{}", backupRootPath);
                    state = State.IDLE;
                    pendingLevelId = null;
                    return;
                }

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
                            Files.createDirectories(worldRoot);

                            SnapshotIndex snapshotIndex = readSnapshotIndexPreferWarm(backupRoot);
                            SnapshotIndex worldIndex = buildIndex(worldRoot, true);
                            SyncStats stats = syncTreesIncremental(backupRoot, worldRoot, snapshotIndex, worldIndex);

                            // 回档后确保快照索引继续可用
                            warmedSnapshotRoot = backupRoot;
                            warmedSnapshotIndex = snapshotIndex;

                            LOGGER.info("[Minefading] 世界差异还原完成（复制 {}，删除 {}），准备重新加载世界...", stats.copied(), stats.deleted());
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
                if (playerReadyTicks == 1 && !rollbackStateApplied)
                {
                    rollbackStateApplied = true;
                    MinecraftServer srv = minecraft.getSingleplayerServer();
                    if (srv != null)
                    {
                        srv.execute(() ->
                        {
                            applyRollbackCheckpointState(srv);
                            if (!sheddingDeductScheduled && hasPendingSheddingCost(srv))
                            {
                                sheddingDeductScheduled = true;
                                deductSheddingFromMarker(srv);
                            }
                        });
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
                    rollbackStateApplied = false;
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

    private static void applyRollbackCheckpointState(MinecraftServer server)
    {
        ServerLevel overworld = server.overworld();
        if (overworld == null)
            return;

        DayStateData.get(server).rollbackToCheckpoint(server, getWorldDay(overworld));
    }

    /**
     * 启动后台索引预热：读取（或重建）当前世界快照索引，减少回档首帧等待。
     */
    public static void prewarmSnapshotIndex(MinecraftServer server)
    {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path backupRoot = getBackupRoot(worldRoot);
        prewarmSnapshotIndex(backupRoot);
    }

    private static void prewarmSnapshotIndex(Path backupRoot)
    {
        if (!Files.isDirectory(backupRoot))
            return;
        if (backupRoot.equals(warmedSnapshotRoot) && !warmedSnapshotIndex.files().isEmpty())
            return;
        if (snapshotIndexWarmupRunning)
            return;

        snapshotIndexWarmupRunning = true;
        Thread warmupThread = new Thread(() ->
        {
            try
            {
                SnapshotIndex index = readSnapshotIndexOrBuild(backupRoot);
                warmedSnapshotRoot = backupRoot;
                warmedSnapshotIndex = index;
                LOGGER.info("[Minefading] 快照索引预热完成：{}（{} 个文件）", backupRoot, index.files().size());
            }
            catch (IOException e)
            {
                LOGGER.warn("[Minefading] 快照索引预热失败：{}", backupRoot, e);
            }
            finally
            {
                snapshotIndexWarmupRunning = false;
            }
        }, "MF-SnapshotIndexWarmup");
        warmupThread.setDaemon(true);
        warmupThread.start();
    }

    private static SnapshotIndex readSnapshotIndexPreferWarm(Path backupRoot) throws IOException
    {
        if (backupRoot.equals(warmedSnapshotRoot) && !warmedSnapshotIndex.files().isEmpty())
            return warmedSnapshotIndex;
        return readSnapshotIndexOrBuild(backupRoot);
    }

    private static SnapshotIndex readSnapshotIndexOrBuild(Path backupRoot) throws IOException
    {
        Path indexFile = backupRoot.resolve(SNAPSHOT_INDEX_FILE);
        if (Files.isRegularFile(indexFile))
            return readSnapshotIndex(indexFile);

        SnapshotIndex index = buildIndex(backupRoot, true);
        writeSnapshotIndex(backupRoot, index);
        return index;
    }

    private static SnapshotIndex buildIndex(Path root, boolean trackedOnly) throws IOException
    {
        if (!Files.isDirectory(root))
            return SnapshotIndex.empty();

        Map<String, FileMeta> map = new HashMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
            {
                if (!attrs.isRegularFile())
                    return FileVisitResult.CONTINUE;
                if (SNAPSHOT_INDEX_FILE.equals(file.getFileName().toString()))
                    return FileVisitResult.CONTINUE;
                if ("session.lock".equals(file.getFileName().toString()))
                    return FileVisitResult.CONTINUE;
                if (trackedOnly && !isTrackedFile(root, file))
                    return FileVisitResult.CONTINUE;

                String key = toUnixPath(root.relativize(file));
                map.put(key, new FileMeta(attrs.size(), attrs.lastModifiedTime().toMillis()));
                return FileVisitResult.CONTINUE;
            }
        });
        return new SnapshotIndex(map);
    }

    private static boolean isTrackedFile(Path root, Path file)
    {
        Path rel = root.relativize(file);
        if (rel.getNameCount() == 1)
        {
            return "level.dat".equals(rel.getFileName().toString());
        }

        for (int i = 0; i < rel.getNameCount() - 1; i++)
        {
            String segment = rel.getName(i).toString();
            if (TRACKED_DIR_NAMES.contains(segment))
                return true;
        }
        return false;
    }

    private static String toUnixPath(Path relPath)
    {
        return relPath.toString().replace('\\', '/');
    }

    private static SnapshotIndex readSnapshotIndex(Path indexFile) throws IOException
    {
        Map<String, FileMeta> map = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(indexFile))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                if (line.isBlank())
                    continue;
                String[] parts = line.split("\\t", 3);
                if (parts.length != 3)
                    continue;
                String path = parts[0];
                long size = Long.parseLong(parts[1]);
                long modified = Long.parseLong(parts[2]);
                map.put(path, new FileMeta(size, modified));
            }
        }
        return new SnapshotIndex(map);
    }

    private static void writeSnapshotIndex(Path backupRoot, SnapshotIndex index) throws IOException
    {
        Files.createDirectories(backupRoot);
        Path indexFile = backupRoot.resolve(SNAPSHOT_INDEX_FILE);
        List<String> paths = new ArrayList<>(index.files().keySet());
        paths.sort(String::compareTo);

        try (BufferedWriter writer = Files.newBufferedWriter(indexFile,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))
        {
            for (String path : paths)
            {
                FileMeta meta = index.files().get(path);
                writer.write(path);
                writer.write('\t');
                writer.write(Long.toString(meta.size()));
                writer.write('\t');
                writer.write(Long.toString(meta.modifiedMillis()));
                writer.newLine();
            }
        }
    }

    /**
     * 将 dstRoot 增量同步为 desired（srcRoot 提供源文件，existing 表示 dstRoot 当前索引）。
     */
    private static SyncStats syncTreesIncremental(Path srcRoot, Path dstRoot, SnapshotIndex desired, SnapshotIndex existing) throws IOException
    {
        Map<String, FileMeta> desiredMap = desired.files();
        Map<String, FileMeta> existingMap = existing.files();

        List<String> toCopy = new ArrayList<>();
        for (Map.Entry<String, FileMeta> entry : desiredMap.entrySet())
        {
            FileMeta old = existingMap.get(entry.getKey());
            if (old == null || old.size() != entry.getValue().size() || old.modifiedMillis() != entry.getValue().modifiedMillis())
            {
                toCopy.add(entry.getKey());
            }
        }

        List<String> toDelete = new ArrayList<>();
        for (String key : existingMap.keySet())
        {
            if (!desiredMap.containsKey(key))
                toDelete.add(key);
        }

        copyFilesParallel(srcRoot, dstRoot, toCopy);
        deleteFilesAndPruneEmptyDirs(dstRoot, toDelete);

        return new SyncStats(toCopy.size(), toDelete.size());
    }

    private static void copyFilesParallel(Path srcRoot, Path dstRoot, Collection<String> relPaths) throws IOException
    {
        if (relPaths.isEmpty())
            return;

        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_COPY_THREADS, r ->
        {
            Thread t = new Thread(r, "MF-ParallelCopy");
            t.setDaemon(true);
            return t;
        });

        List<Future<Void>> futures = new ArrayList<>();
        for (String rel : relPaths)
        {
            futures.add(pool.submit(() ->
            {
                Path src = srcRoot.resolve(rel.replace('/', FileSystems.getDefault().getSeparator().charAt(0)));
                Path dst = dstRoot.resolve(rel.replace('/', FileSystems.getDefault().getSeparator().charAt(0)));

                if (!Files.exists(src))
                    return null;
                Files.createDirectories(dst.getParent());
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return null;
            }));
        }

        pool.shutdown();

        IOException firstIoException = null;
        for (Future<Void> future : futures)
        {
            try
            {
                future.get();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                if (firstIoException == null)
                    firstIoException = new IOException("并行拷贝被中断", e);
            }
            catch (ExecutionException e)
            {
                Throwable cause = e.getCause();
                if (firstIoException == null)
                {
                    firstIoException = cause instanceof IOException
                            ? (IOException) cause
                            : new IOException("并行拷贝失败", cause);
                }
            }
        }

        if (firstIoException != null)
            throw firstIoException;
    }

    private static void deleteFilesAndPruneEmptyDirs(Path root, Collection<String> relPaths) throws IOException
    {
        if (relPaths.isEmpty())
            return;

        Set<Path> candidateDirs = new HashSet<>();
        for (String rel : relPaths)
        {
            Path file = root.resolve(rel.replace('/', FileSystems.getDefault().getSeparator().charAt(0)));
            Files.deleteIfExists(file);
            Path parent = file.getParent();
            while (parent != null && parent.startsWith(root))
            {
                candidateDirs.add(parent);
                if (parent.equals(root))
                    break;
                parent = parent.getParent();
            }
        }

        List<Path> dirs = new ArrayList<>(candidateDirs);
        dirs.sort(Comparator.comparingInt(Path::getNameCount).reversed());
        for (Path dir : dirs)
        {
            if (dir.equals(root))
                continue;
            if (isDirectoryEmpty(dir))
                Files.deleteIfExists(dir);
        }
    }

    private static boolean isDirectoryEmpty(Path dir) throws IOException
    {
        if (!Files.isDirectory(dir))
            return false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir))
        {
            return !stream.iterator().hasNext();
        }
    }

    private static void beginSnapshotTask()
    {
        synchronized (SNAPSHOT_TASK_MONITOR)
        {
            snapshotTasksInFlight++;
        }
    }

    private static void finishSnapshotTask()
    {
        synchronized (SNAPSHOT_TASK_MONITOR)
        {
            if (snapshotTasksInFlight > 0)
                snapshotTasksInFlight--;
            SNAPSHOT_TASK_MONITOR.notifyAll();
        }
    }

    private static boolean hasPendingSnapshotTasks()
    {
        synchronized (SNAPSHOT_TASK_MONITOR)
        {
            return snapshotTasksInFlight > 0;
        }
    }

    private static int getWorldDay(ServerLevel level)
    {
        return (int) (level.getDayTime() / 24000L) + 1;
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
     * 在黑屏期间结算蜕皮使用：读取标记文件，扣除 1 个蜕皮并扣除吸入器 1 点耐久，然后删除标记。
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

        // 扣除第一个玩家背包中的 1 个蜕皮，并补扣 1 点吸入器耐久（单人模式只有一个玩家）
        Item sheddingItem = RelicItems.SHEDDING.get();
        Item inhalerItem = RelicItems.INHALER.get();
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            boolean sheddingDeducted = deductOneShedding(player, sheddingItem);
            boolean inhalerDamaged = deductInhalerDurability(player, inhalerItem);

            player.inventoryMenu.broadcastChanges();

            if (!sheddingDeducted)
                LOGGER.warn("[Minefading] 回档完成但未在玩家 {} 背包中找到蜕皮物品", player.getName().getString());
            if (!inhalerDamaged)
                LOGGER.warn("[Minefading] 回档完成但未在玩家 {} 背包中找到吸入器", player.getName().getString());

            return;
        }
    }

    private static boolean deductOneShedding(ServerPlayer player, Item sheddingItem)
    {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
        {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.is(sheddingItem))
                continue;

            stack.shrink(1);
            if (stack.isEmpty())
                player.getInventory().setItem(i, ItemStack.EMPTY);

            LOGGER.info("[Minefading] 已从玩家 {} 背包扣除 1 个蜕皮", player.getName().getString());
            return true;
        }
        return false;
    }

    private static boolean deductInhalerDurability(ServerPlayer player, Item inhalerItem)
    {
        ItemStack mainHand = player.getMainHandItem();
        if (mainHand.is(inhalerItem) && damageInhaler(mainHand))
        {
            LOGGER.info("[Minefading] 已从玩家 {} 主手吸入器扣除 1 点耐久", player.getName().getString());
            return true;
        }

        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
        {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.is(inhalerItem) || stack == mainHand)
                continue;

            if (damageInhaler(stack))
            {
                if (stack.isEmpty())
                    player.getInventory().setItem(i, ItemStack.EMPTY);

                LOGGER.info("[Minefading] 已从玩家 {} 背包中的吸入器扣除 1 点耐久", player.getName().getString());
                return true;
            }
        }
        return false;
    }

    private static boolean damageInhaler(ItemStack stack)
    {
        if (!stack.isDamageableItem())
            return false;

        int nextDamage = stack.getDamageValue() + 1;
        if (nextDamage >= stack.getMaxDamage())
            stack.shrink(1);
        else
            stack.setDamageValue(nextDamage);

        return true;
    }

    private static Path getSheddingMarkerPath(MinecraftServer server)
    {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path snapshotsDir = getBackupRoot(worldRoot).getParent();
        return snapshotsDir.resolve(SHEDDING_PENDING_FILE);
    }

    private static boolean hasPendingSheddingCost(MinecraftServer server)
    {
        return Files.exists(getSheddingMarkerPath(server));
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

    // ── 世界加载前预还原（由 Mixin 在 WorldOpenFlows.loadLevel HEAD 调用） ──

    /**
     * 在世界实际加载前检查快照是否存在：若存在则同步还原文件到存档目录，
     * 使 IntegratedServer 直接以快照状态启动，避免"加载 → 断开 → 还原 → 重载"的二次加载。
     */
    public static void preEntryRestoreIfNeeded(String levelId)
    {
        // 回档状态机进行中（PENDING_RELOAD 调用的 loadLevel），不做预还原
        if (state != State.IDLE)
            return;

        // 回档后重进世界时跳过（避免二次还原）
        if (levelId.equals(skipNextEntryCheckLevelId))
            return;

        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        Path snapshotDir = gameDir.resolve("minefading_snapshots").resolve(levelId);

        if (!Files.isDirectory(snapshotDir))
            return;

        Path worldDir = gameDir.resolve("saves").resolve(levelId);

        try
        {
            LOGGER.info("[Minefading] 检测到快照，在世界加载前执行预还原：{}", snapshotDir);
            Files.createDirectories(worldDir);

            SnapshotIndex snapshotIndex = readSnapshotIndexPreferWarm(snapshotDir);
            SnapshotIndex worldIndex = buildIndex(worldDir, true);
            SyncStats stats = syncTreesIncremental(snapshotDir, worldDir, snapshotIndex, worldIndex);

            // 缓存快照索引供后续使用
            warmedSnapshotRoot = snapshotDir;
            warmedSnapshotIndex = snapshotIndex;
            // 记录路径供后续 hasSnapshot / takeSnapshot 使用
            worldRootPath = worldDir;
            backupRootPath = snapshotDir;

            preEntryRestorePending = true;
            LOGGER.info("[Minefading] 预还原完成（复制 {}，删除 {}），世界将以快照状态加载",
                    stats.copied(), stats.deleted());
        }
        catch (IOException e)
        {
            LOGGER.error("[Minefading] 预还原失败，世界将以当前状态加载", e);
        }
    }

    /**
     * 消费预还原标记：若为 true 则表示本次进入世界是预还原后的首次加载，
     * 需要在服务端执行回档状态恢复（天数偏移、追踪实体、蜕皮扣除等）。
     */
    public static boolean consumePreEntryRestore()
    {
        if (preEntryRestorePending)
        {
            preEntryRestorePending = false;
            return true;
        }
        return false;
    }

    /**
     * 预还原后应用回档状态：恢复天数偏移与追踪实体，然后处理蜕皮标记扣除。
     * 由 DaySystemEvents 在进入世界后通过 server.execute() 调度到服务端线程执行。
     */
    public static void applyPreEntryRollbackState(MinecraftServer server)
    {
        applyRollbackCheckpointState(server);
        if (hasPendingSheddingCost(server))
            deductSheddingFromMarker(server);
    }
}
