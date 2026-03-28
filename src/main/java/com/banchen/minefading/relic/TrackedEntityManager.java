package com.banchen.minefading.relic;

import com.banchen.minefading.Minefading;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 细沙追踪实体的NBT存储管理器。
 * 负责将追踪实体的完整NBT数据保存到文件，以及在回档时从文件恢复实体。
 */
public class TrackedEntityManager
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE_NAME = "minefading_tracked_entities.dat";

    // 内存缓存：UUID -> (Dimension, CompoundTag)
    private final Map<UUID, TrackedEntityData> entityCache = new HashMap<>();

    /**
     * 保存追踪实体的NBT数据到缓存（尚未写入磁盘）。
     * 实际写入由 flushToFile() 触发。
     */
    public void cacheEntity(UUID entityId, Entity entity)
    {
        CompoundTag tag = entity.serializeNBT();
        ResourceKey<Level> dimension = entity.level().dimension();
        entityCache.put(entityId, new TrackedEntityData(dimension, tag));
    }

    /**
     * 将所有缓存的实体NBT数据持久化到磁盘文件。
     * 在每次存档点确认时调用。
     */
    public void flushToFile(MinecraftServer server) throws IOException
    {
        File dataDirectory = getDataDirectory(server);
        if (!dataDirectory.exists() && !dataDirectory.mkdirs())
        {
            LOGGER.warn("[Minefading] 无法创建追踪实体数据目录");
            return;
        }

        File file = new File(dataDirectory, FILE_NAME);

        CompoundTag root = new CompoundTag();
        ListTag entitiesTag = new ListTag();

        for (Map.Entry<UUID, TrackedEntityData> entry : entityCache.entrySet())
        {
            CompoundTag entityTag = new CompoundTag();
            entityTag.putUUID("UUID", entry.getKey());
            entityTag.putString("Dimension", entry.getValue().dimension.location().toString());
            entityTag.put("EntityData", entry.getValue().nbtData);

            entitiesTag.add(entityTag);
        }

        root.put("TrackedEntities", entitiesTag);

        try
        {
            NbtIo.writeCompressed(root, file);
            LOGGER.info("[Minefading] 追踪实体NBT已保存至 {}", file.getAbsolutePath());
        }
        catch (IOException e)
        {
            LOGGER.error("[Minefading] 保存追踪实体NBT失败", e);
            throw e;
        }
    }

    /**
     * 从磁盘文件加载所有追踪实体的NBT数据到缓存。
     * 在世界加载时调用。
     */
    public void loadFromFile(MinecraftServer server) throws IOException
    {
        File dataDirectory = getDataDirectory(server);
        File file = new File(dataDirectory, FILE_NAME);

        if (!file.exists())
        {
            LOGGER.info("[Minefading] 未找到追踪实体数据文件");
            return;
        }

        try
        {
            CompoundTag root = NbtIo.readCompressed(file);
            ListTag entitiesTag = root.getList("TrackedEntities", Tag.TAG_COMPOUND);

            entityCache.clear();
            for (int i = 0; i < entitiesTag.size(); i++)
            {
                CompoundTag entityTag = entitiesTag.getCompound(i);
                UUID uuid = entityTag.getUUID("UUID");
                String dimensionStr = entityTag.getString("Dimension");
                ResourceKey<Level> dimension = ResourceKey.create(
                    Registries.DIMENSION,
                    ResourceLocation.parse(dimensionStr)
                );
                CompoundTag entityData = entityTag.getCompound("EntityData");

                entityCache.put(uuid, new TrackedEntityData(dimension, entityData));
            }

            LOGGER.info("[Minefading] 从文件加载了 {} 个追踪实体NBT", entityCache.size());
        }
        catch (IOException e)
        {
            LOGGER.error("[Minefading] 加载追踪实体NBT失败", e);
            throw e;
        }
    }

    /**
     * 在回档时恢复一个追踪实体。
     * 从缓存获取NBT，在目标维度重建实体。
     *
     * @return 恢复的实体，如果NBT不存在或恢复失败则返回 null
     */
    public Entity restoreEntity(MinecraftServer server, UUID entityId)
    {
        TrackedEntityData data = entityCache.get(entityId);
        if (data == null)
        {
            LOGGER.warn("[Minefading] 未找到实体 {} 的NBT数据", entityId);
            return null;
        }

        ServerLevel targetLevel = server.getLevel(data.dimension);
        if (targetLevel == null)
        {
            LOGGER.warn("[Minefading] 目标维度 {} 不存在", data.dimension);
            return null;
        }

        try
        {
            // 从NBT中提取实体类型
            String typeStr = data.nbtData.getString("id");
            EntityType<?> entityType = EntityType.byString(typeStr).orElse(null);
            if (entityType == null)
            {
                LOGGER.warn("[Minefading] 未知的实体类型: {}", typeStr);
                return null;
            }

            // 创建新实体实例
            Entity newEntity = entityType.create(targetLevel);
            if (newEntity == null)
            {
                LOGGER.warn("[Minefading] 无法创建实体: {}", typeStr);
                return null;
            }

            // 加载NBT数据到新实体
            newEntity.deserializeNBT(data.nbtData);

            // 添加到世界
            targetLevel.addFreshEntity(newEntity);
            LOGGER.info("[Minefading] 实体 {} 已从NBT恢复", entityId);

            return newEntity;
        }
        catch (Exception e)
        {
            LOGGER.error("[Minefading] 恢复实体 {} 失败", entityId, e);
            return null;
        }
    }

    /**
     * 清空内存缓存（当追踪列表被重置时调用）。
     */
    public void clearCache()
    {
        entityCache.clear();
    }

    // 删除单条追踪实体记录（用于回溯后消费细沙记录）
    public void removeEntity(UUID entityId)
    {
        entityCache.remove(entityId);
    }

    /**
     * 获取追踪数据存储目录（<世界根>/data/minefading/）。
     */
    private File getDataDirectory(MinecraftServer server)
    {
        try
        {
            File worldDataDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile();
            File modDataDir = new File(worldDataDir, "data" + File.separator + Minefading.MODID);
            
            // 确保目录存在
            if (!modDataDir.exists() && !modDataDir.mkdirs())
            {
                LOGGER.warn("[Minefading] 无法创建数据目录: {}", modDataDir.getAbsolutePath());
            }
            return modDataDir;
        }
        catch (Exception e)
        {
            LOGGER.error("[Minefading] 获取数据目录失败", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 内部类：追踪实体的维度和NBT数据。
     */
    private static class TrackedEntityData
    {
        private final ResourceKey<Level> dimension;
        private final CompoundTag nbtData;

        private TrackedEntityData(ResourceKey<Level> dimension, CompoundTag nbtData)
        {
            this.dimension = dimension;
            this.nbtData = nbtData;
        }
    }
}
