package com.banchen.minefading.day;

import com.banchen.minefading.Minefading;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// 天数状态的持久化数据，存储于世界 data 目录中
// 管理显示天数偏移、存档点、被追踪实体列表及其位置快照
public class DayStateData extends SavedData
{
    private static final String DATA_NAME = Minefading.MODID + "_day_state";

    // 显示天数 = 世界天数 + offsetDays，用于回档后保持天数显示不跳变
    private int offsetDays;
    // 上一个安全点的显示天数（死亡时回退到此值）
    private int rollbackDisplayDay = 1;
    // 当前存档点的显示天数
    private int checkpointDisplayDay = 1;
    // 上次记录的世界真实天数，用于检测天数变化
    private int lastWorldDay = -1;
    // 被细沙追踪的实体 UUID 集合
    private final Set<UUID> trackedEntityIds = new HashSet<>();
    // 存档点时各追踪实体的位置快照
    private final Map<UUID, Snapshot> trackedSnapshots = new HashMap<>();

    // 从世界数据存储中获取或创建 DayStateData 实例（单例模式）
    public static DayStateData get(MinecraftServer server)
    {
        return server.overworld().getDataStorage().computeIfAbsent(DayStateData::load, DayStateData::new, DATA_NAME);
    }

    public static DayStateData load(CompoundTag tag)
    {
        DayStateData data = new DayStateData();
        data.offsetDays = tag.getInt("OffsetDays");
        data.rollbackDisplayDay = Math.max(1, tag.getInt("RollbackDisplayDay"));
        data.checkpointDisplayDay = Math.max(1, tag.getInt("CheckpointDisplayDay"));
        data.lastWorldDay = tag.getInt("LastWorldDay");

        ListTag tracked = tag.getList("TrackedEntityIds", Tag.TAG_INT_ARRAY);
        for (Tag entry : tracked)
            data.trackedEntityIds.add(NbtUtils.loadUUID(entry));

        ListTag snapshots = tag.getList("TrackedSnapshots", Tag.TAG_COMPOUND);
        for (Tag entry : snapshots)
        {
            CompoundTag snapshotTag = (CompoundTag) entry;
            UUID id = snapshotTag.getUUID("Id");
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(snapshotTag.getString("Dim")));
            data.trackedSnapshots.put(id, new Snapshot(dimension, snapshotTag.getDouble("X"), snapshotTag.getDouble("Y"), snapshotTag.getDouble("Z")));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        tag.putInt("OffsetDays", offsetDays);
        tag.putInt("RollbackDisplayDay", rollbackDisplayDay);
        tag.putInt("CheckpointDisplayDay", checkpointDisplayDay);
        tag.putInt("LastWorldDay", lastWorldDay);

        ListTag tracked = new ListTag();
        for (UUID id : trackedEntityIds)
            tracked.add(net.minecraft.nbt.NbtUtils.createUUID(id));
        tag.put("TrackedEntityIds", tracked);

        ListTag snapshots = new ListTag();
        for (Map.Entry<UUID, Snapshot> entry : trackedSnapshots.entrySet())
        {
            CompoundTag snapshotTag = new CompoundTag();
            snapshotTag.putUUID("Id", entry.getKey());
            snapshotTag.putString("Dim", entry.getValue().dimension.location().toString());
            snapshotTag.putDouble("X", entry.getValue().x);
            snapshotTag.putDouble("Y", entry.getValue().y);
            snapshotTag.putDouble("Z", entry.getValue().z);
            snapshots.add(snapshotTag);
        }
        tag.put("TrackedSnapshots", snapshots);
        return tag;
    }

    // 首次进入世界时初始化，避免天数从 1 开始强行跳变
    public void initializeIfNeeded(int worldDay)
    {
        if (lastWorldDay != -1)
            return;

        lastWorldDay = worldDay;
        checkpointDisplayDay = Math.max(1, getDisplayedDay(worldDay));
        rollbackDisplayDay = Math.max(1, checkpointDisplayDay - 1);
        setDirty();
    }

    // 世界天数变化时调用：更新记录并返回"是否刚过了一天"
    public boolean updateForWorldDay(int worldDay)
    {
        initializeIfNeeded(worldDay);
        if (worldDay == lastWorldDay)
            return false;

        lastWorldDay = worldDay;
        rollbackDisplayDay = Math.max(1, checkpointDisplayDay); // 前一天的检查点成为回滚目标
        setDirty();
        return true;
    }

    // 根据世界真实天数计算当前显示天数（含偏移）
    public int getDisplayedDay(int worldDay)
    {
        return Math.max(1, worldDay + offsetDays);
    }

    public int getRollbackDisplayDay()
    {
        return rollbackDisplayDay;
    }

    // 仅回退天数，不涉及实体
    public void rollbackToCheckpoint(int worldDay)
    {
        setDisplayedDay(worldDay, rollbackDisplayDay);
    }

    // 完整回档：回退天数 + 将追踪实体传送回快照位置
    public void rollbackToCheckpoint(MinecraftServer server, int worldDay)
    {
        setDisplayedDay(worldDay, rollbackDisplayDay);
        restoreTrackedEntities(server);
    }

    // 保存存档点：更新天数快照 + 记录追踪实体当前位置
    public void saveCheckpoint(MinecraftServer server, int worldDay)
    {
        checkpointDisplayDay = Math.max(1, getDisplayedDay(worldDay));
        rollbackDisplayDay = Math.max(1, checkpointDisplayDay);
        snapshotTrackedEntities(server);
        setDirty();
    }

    // 将实体 UUID 加入追踪列表
    public void trackEntity(UUID entityId)
    {
        trackedEntityIds.add(entityId);
        setDirty();
    }

    // 记录所有追踪实体的当前坐标和维度
    private void snapshotTrackedEntities(MinecraftServer server)
    {
        trackedSnapshots.clear();
        for (UUID id : trackedEntityIds)
        {
            Entity entity = findEntity(server, id);
            if (entity == null)
                continue;

            trackedSnapshots.put(id, new Snapshot(entity.level().dimension(), entity.getX(), entity.getY(), entity.getZ()));
        }
    }

    // 将所有追踪实体传送回快照记录的位置（跨维度也处理）
    private void restoreTrackedEntities(MinecraftServer server)
    {
        for (Map.Entry<UUID, Snapshot> entry : trackedSnapshots.entrySet())
        {
            Entity entity = findEntity(server, entry.getKey());
            if (entity == null)
                continue;

            Snapshot snapshot = entry.getValue();
            ServerLevel targetLevel = server.getLevel(snapshot.dimension);
            if (targetLevel == null)
                continue;

            if (entity.level().dimension() != snapshot.dimension)
                entity.changeDimension(targetLevel);

            entity.teleportTo(snapshot.x, snapshot.y, snapshot.z);
        }
    }

    private Entity findEntity(MinecraftServer server, UUID id)
    {
        for (ServerLevel level : server.getAllLevels())
        {
            Entity entity = level.getEntity(id);
            if (entity != null)
                return entity;
        }
        return null;
    }

    // 设置显示天数，通过调整 offsetDays 实现（不直接修改世界时间）
    public void setDisplayedDay(int worldDay, int displayedDay)
    {
        offsetDays = Math.max(1, displayedDay) - worldDay;
        setDirty();
    }

    // 追踪实体的位置快照（维度 + 坐标）
    private static class Snapshot
    {
        private final ResourceKey<Level> dimension;
        private final double x;
        private final double y;
        private final double z;

        private Snapshot(ResourceKey<Level> dimension, double x, double y, double z)
        {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
