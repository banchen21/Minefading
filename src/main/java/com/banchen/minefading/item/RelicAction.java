package com.banchen.minefading.item;

// 药芯物品的行为枚举，每种药芯对应一个唯一的行为类型
public enum RelicAction
{
    INHALER,   // 吸入器：装填提示
    SHEDDING,  // 蜕皮：回到上一存档点
    DISCONNECT, // 断线：手动创建存档点
    TOWER,     // 高塔：主动死亡
    CAUSALITY, // 因果：激活替身保护
    CHRONOS    // 柯罗诺斯：按键放缓时间
}
