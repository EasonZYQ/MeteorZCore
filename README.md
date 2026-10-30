# MeteorZCore

[Meteor Client](https://meteorclient.com) 的 Minecraft 插件（addon）。

## 环境要求

- JDK 25
- Gradle 由 wrapper 提供（9.8.1）

## 构建

```powershell
$env:JAVA_HOME="C:\Program Files\Java\zuluJDK-25"
.\gradlew.bat build
```

产物位于 `build/libs/MeteorZCore-<version>.jar`，与 Meteor Client 一起放入 `.minecraft/mods` 即可。

开发调试（启动带 Meteor Client 与本插件的客户端）：

```powershell
.\gradlew.bat runClient
```

## 项目结构

```text
src/main/java/com/eason/zcore
│── MeteorZCore.java        # 插件入口（fabric.mod.json 的 meteor entrypoint）
│── mixin/                  # Mixin，配置见 zcore.mixins.json
│── modules/                # 模块（Z Reach、Z TP Aura、Z TP Crystal Aura、Z TP Anchor Aura、Z Piston Aura、Z Click Tp、Z Freecam、Z Packet Mine）
╰── utils/                  # 工具类（位置伪造、动作伪造、多 tick 传送引擎）
```

## 版本

| 组件 | 版本 |
| --- | --- |
| Minecraft | 26.3 |
| Meteor Client | 26.3-SNAPSHOT |
| Fabric Loader | 0.19.5 |
| Loom | 1.18-SNAPSHOT |

版本号统一在 `gradle/libs.versions.toml` 中维护。
