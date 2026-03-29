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
    @Inject(
            method = "loadLevel(Lnet/minecraft/client/gui/screens/Screen;Ljava/lang/String;)V",
            at = @At("HEAD"),
            remap = false,
            require = 0
    )
    private void minefading$preEntryRestoreOfficial(Screen lastScreen, String levelName, CallbackInfo ci)
    {
        minefading$preEntryRestore(levelName);
    }

    @Inject(
            method = "m_233133_(Lnet/minecraft/client/gui/screens/Screen;Ljava/lang/String;)V",
            at = @At("HEAD"),
            remap = false,
            require = 0
    )
    private void minefading$preEntryRestoreSrg(Screen lastScreen, String levelName, CallbackInfo ci)
    {
        minefading$preEntryRestore(levelName);
    }

    private void minefading$preEntryRestore(String levelName)
    {
        WorldRollbackManager.preEntryRestoreIfNeeded(levelName);
    }
}
