package com.banchen.minefading.mixin;

import com.banchen.minefading.WorldRollbackManager;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拦截世界加载入口：在 IntegratedServer 启动前检查快照，
 * 若存在则先将快照文件还原到存档目录，使世界直接以快照状态加载，无需二次重载。
 */
@Mixin(WorldOpenFlows.class)
public class WorldOpenFlowsMixin
{
    // remap = false：Forge 运行时已将方法名映射为 official，Mixin 在映射后应用
    @Inject(method = "loadLevel", at = @At("HEAD"), remap = false)
    private void minefading$preEntryRestore(Screen lastScreen, String levelName, CallbackInfo ci)
    {
        WorldRollbackManager.preEntryRestoreIfNeeded(levelName);
    }
}
