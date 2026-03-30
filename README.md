# ⏳ Minefading

<div align="center">

***Relics of the Fading City Special: Minefading***  
*《亡都遗骨》特别篇：我的余晖*

[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-5C9C3E?logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAAAZdEVYdFNvZnR3YXJlAHBhaW50Lm5ldCA0LjAuMTM0A1t6AAAAJ0lEQVQ4T2P8//8/Ay0wYGBg+M8ABVgK2DABUqj7GZmYmJgYsAAAGY8E+3YZVH0AAAAASUVORK5CYII=)](https://minecraft.net)
[![Forge](https://img.shields.io/badge/Forge-47.4.10-CB5A5A?logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAAAZdEVYdFNvZnR3YXJlAHBhaW50Lm5ldCA0LjAuMTM0A1t6AAAAQ0lEQVQ4T2P8//8/Ay0YkAYYGRkZ/v//D2IxMjIyMDAwMDCiA2QKCTAwMKBKwCQnKyuLz+00GQq1iAYYGRkZUQMAoDUPDmGH2bUAAAAASUVORK5CYII=)](https://files.minecraftforge.net)
[![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)](https://adoptium.net)

</div>

---

## 📖 关于项目

> *当玩家安全且健康时被存档，死亡后回到上一个存档。*

**Minefading** 是《亡都遗骨》系列的特别篇模组，围绕「**存档与回溯**」这一核心机制展开。  
玩家在安全状态下会被自动存档，死亡后将回到上一次存档的时刻——每一次呼吸都可能是永恒，每一次选择都值得被铭记。

本项目基于 Forge 模组开发框架构建，提供了一个干净的项目骨架，便于在此基础上扩展功能内容。

---

## 🛠️ 开发环境

| 环境要求 | 版本 |
|---------|------|
| Minecraft | 1.20.1 |
| Forge | 47.4.10 |
| Java | 17 |

---

## 🚀 常用命令

```bash
# 生成 IntelliJ IDEA 运行配置
gradlew genIntellijRuns

# 生成 Eclipse 运行配置
gradlew genEclipseRuns

# 启动客户端
gradlew runClient

# 构建模组 JAR
gradlew build
```

---

## 🎮 核心机制

### 正式模式
- 每日开始时屏幕变黑并显示「第 X 天」
- 死亡后天数退回至上一个存档点

### 倒计时模式
- 界面显示「还剩 X 天」
- 倒计时归零后以极限模式死亡
- 死亡后剩余天数拨回上一个存档点

---

## ⚙️ 物品一览

### 🔧 吸入器
**本模组核心物品**，通过更换药芯来切换效果。  
*主手持有，副手放置药芯以激活效果。*

---

### 💊 药芯

| 药芯 | 效果 | 描述 |
|:----:|------|------|
| **断线** | 立即存档 | *舍弃过去* |
| **柯罗诺斯** | 立即存档 → 可放缓时间（按住 Z 键）→ 效果结束后再次存档 | *我听见的每一个字都在变得更长，蚊子的嗡嗡声变得越来越响。* |
| **蜕皮** | 回溯至上一个存档点 | *蝉回到了那个夏天* |
| **高塔** | 立即死亡（龙蛋 + 下界之星合成），并将玩家游戏模式永久锁定为旁观模式 | *迎接自己的毁灭* |
| **细沙** | 对生物使用 → 赋予发光 + 持久标记 → 回溯时将生物带回过去 | *没人会在意一粒凭空出现的沙子。* |
| **因果** | 对生命值 > 20 的非玩家生物使用 → 赋予发光并命名为玩家名字 → 生效期间该生物代替玩家死亡<br><br>• 若目标不会死亡：扣除 20 生命，为玩家恢复 20 生命<br>• 若目标会死亡：玩家与目标位置互换 | *只是脖子上顶着一样的脑袋。* |

---

## 🔄 优先级规则

```
因果生效 > 正常死亡
```

当因果效果处于激活状态时，将优先触发因果机制，而非普通死亡流程。

---

## 📦 项目结构

```
Minefading/
├── src/
│   └── main/
│       ├── java/          # 模组源代码
│       └── resources/     # 资源配置文件
├── build.gradle           # Gradle 构建配置
└── README.md              # 项目说明
```

---

## 📝 更新日志

### v1.0.0
- ✨ 核心机制：存档与回溯系统
- ✨ 吸入器与药芯系统
- ✨ 细沙：生物标记与回溯
- ✨ 因果：替死机制
- ✨ 柯罗诺斯：时间放缓效果
- ✨ 高塔：极限模式死亡与旁观者锁定

---

## 📄 许可

本项目基于 [MIT License](LICENSE) 开源。

---

<div align="center">

**Made with ⏳ by Banchen**

*在时间的裂缝中，寻找属于自己的余晖。*

</div>