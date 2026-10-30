# GCore 模块分析报告

> 说明：GCore 中**没有** `models` 目录；`dev/godden/core/modules` 下共有 **36 个模块**（另有 `InfoData` 数据类）。本报告按分类逐一说明这些模块的功能与关键设置，并给出与 ZCore 的对照。
>
> 来源：`GCore/`（LiquidBounce 1.20.4 插件，CFR 反编译 Kotlin；对应字节码在 `GCoreClass/`）。

## 一、总体分布

| 分类 | 数量 | 模块 |
| --- | --- | --- |
| COMBAT | 14 | GKillaura、GTpaura、GCrystalAura、GTpCrystalAura、GAnchorAura、GTpAnchorAura、GLightTpAnchorAura、GTpPistonAura、GInfiniteAura、GTpBowAura、GBowGod、GTrap、GWebAura、GTPBot |
| WORLD | 2 | GNuker、GPacketMine |
| MOVEMENT | 3 | GSpeed、GSuperVClip、GRandomMove |
| RENDER | 2 | GPlayerRender、GRenderCheck |
| MISC | 15 | GAutoExperience、GAutoSpammer、GAntiLag、GAntiBlockPush、GAntiExplosion、GHornAura、GSurround、GEncryptMessage、GMessageProcessor、GChatCommand、GExtraMouseButton、GAutoMine、GPathTest、GTest、GTpCev |

## 二、战斗类模块

### 2.1 近战

| 模块 | 功能 | 关键设置 / 机制 |
| --- | --- | --- |
| **GKillaura** | 基础近战光环 | Delay、UseCooldown、MaxTargets、Critical（暴击）、SwingHand、TryKillTotem（针对图腾）、MoveDistance、Vclip1/Vclip2（伪高度/位移用于暴击） |
| **GTpaura** | TP 近战光环：把服务端位置送到目标旁再攻击 | Prev（目标历史位置）、AttackDelay、Critical、FindVecToAttack、BVR、AttackTimes、Render |
| **GInfiniteAura** | “无限距离”近战：用 `PathFindUtil.getInfiniteAuraPath` 生成多段小位移路径，**同一 tick 连续发送全部路径点**，攻击后再逆序发回 | MoveDistance（默认 10）、CalcAmount（默认 2000，路径节点上限）、UseCooldown、Critical、Render |
| **GTpBowAura** | TP 弓箭光环：传送到射击位置后射箭 | WaitForHurtTime、BVR、ShootDelay |
| **GBowGod** | 弓箭自动瞄准/蓄力（非 TP） | PowerDistance、MoveDistance、UseCooldown、Delay |

### 2.2 爆炸类

| 模块 | 功能 | 关键设置 |
| --- | --- | --- |
| **GCrystalAura** | 普通水晶光环：自动放黑曜石、放水晶、引爆 | Version、PlaceDelay、ScanBorder、PlaceRange、PlaceAmount、PlaceObsidian、PlaceCrystalSame、HitRange、ThroughWallRange、AttackDelay、MinDamageToTarget、MaxDamageToSelf、Prev、AttackTimes |
| **GTpCrystalAura** | TP 水晶光环（远距离放置/引爆，见前一份报告） | AttackDelay、HitRange、ThroughWallRange、PlaceDelay、ScanBorder、PlaceRange、PlaceAmount、PlaceObsidian、Min/Max 伤害、Prev |
| **GAnchorAura** | 普通重生锚光环 | Version、PlaceDelay、ScanBorder、PlaceRange、PlaceAmount、AllowClickAfterPlace、AllowPlaceProtect、Replace、ProtectBlock、Min/Max 伤害、Prev |
| **GTpAnchorAura** | TP 重生锚光环 | 同上 + BVR、Render |
| **GLightTpAnchorAura** | “轻量” TP 锚光环（步骤更少、逻辑更简单） | 同上 + OnlyPlaceProtect、BVR、Render |
| **GTpPistonAura** | TP 活塞推水晶光环：活塞 + 红石块 + 黑曜石 + 水晶 | Version、PlaceDelay、WaitForPistonRotationDelay、ScanBorder、PlaceRange、AllowPlaceObs、AllowPlaceUpPush（允许向上推）、OnlyPlayerFacing、AttackDelay、HitRange、Min/Max 伤害 |
| **GTpCev** | 只引爆不建造：TP 到可安全引爆的位置攻击水晶/锚后回传（源码分类为 MISC） | BreakDelay、ScanBorder、BreakRange、AllowPlaceAnchor（允许先补放锚）、PlaceRange、BVR、SwingHand、Render |

### 2.3 控制类

| 模块 | 功能 | 关键设置 |
| --- | --- | --- |
| **GTrap** | 陷阱光环：在目标周围自动放置黑曜石/重生锚把目标困住 | Version、UseAnchorPlace、ScanBorder、PlaceDelay、PlaceRange、PlaceAmount、UseTop、UseTp（可 TP 到远处目标旁） |
| **GWebAura** | 蜘蛛网光环：在目标身上放蜘蛛网与黑曜石，可联动陷阱/锚 | Version、PlaceDelay、ScanBorder、PlaceRange、WebAmount、ObsidianAmount、Prev、UseTp、UseAnchor、UseGTrap |
| **GTPBot** | 随机 TP 假人：周期性地小距离 TP + 随机左右键，规避 AFK/检测 | Delay、Prev、KeepY、OnlyXZ、Random、LeftClick、RightClick、Always |

## 三、世界类模块

| 模块 | 功能 | 关键设置 |
| --- | --- | --- |
| **GNuker** | 范围挖掘：扫描周围方块逐个破坏 | Scanborder、BreakDelay、BreakRange、OnlyBreakBy1Tick（单 tick 破坏模式） |
| **GPacketMine** | 发包挖掘：只发动作包模拟挖掘，远距离时 TP 到方块旁 | BreakDelay、BreakRange、Damage、SwingHand、Render、tpBreakRange、tpPlaceRange、PlaceDelay |

## 四、移动类模块

| 模块 | 功能 | 关键设置 |
| --- | --- | --- |
| **GSpeed** | 简易加速 | Speed、AutoJump |
| **GSuperVClip** | 垂直穿方块（向上/向下） | Clip、MoveDistance、ProtectY、SneakDownClip、VoidProtect |
| **GRandomMove** | 随机移动 / 防挂机 | MoveDistance、ScanBorder、AllowVertical、AllowHorizon |

## 五、渲染类模块

| 模块 | 功能 | 关键设置 |
| --- | --- | --- |
| **GPlayerRender** | 按目标筛选条件排序实体并交给内部 `PlayerRender` 渲染（玩家信息渲染） | 复用 `TpSelectTargetsConfigurable`（默认包含好友） |
| **GRenderCheck** | 伤害来源分析/显示：标注受伤类型（近战、水晶爆炸、射击、雪球、锚爆炸、TNT、摔落、岩浆、烧伤、敌对攻击）与距离，并统计 Totem Pop / Death / Eat | 无常规设置，主要用于观察对方攻击距离（检测作弊） |

## 六、杂项模块

| 模块 | 功能 | 关键设置 |
| --- | --- | --- |
| **GAntiExplosion** | 反爆炸：监听水晶生成、锚更新包，若对自己的伤害超上限，则 TP 到安全位置抢先引爆（`doAntiCrystal` / `doAntiAnchor`） | ScanBorder、HitRange、ThroughWallRange、MaxDamageToSelf、ClickRange |
| **GAutoExperience** | 自动扔经验瓶 | Delay、Amount、LimitHeadDown、LimitRightClick、LimitSneak、SwingHand |
| **GAutoSpammer** | 自动聊天刷屏 | Mode（Normal/Random）、SendDelay、SendTimes、IntervalRange、Prefix、Suffix、Message；支持 `{randomPlayer}`、`{message}`、`{prefix}`、`{suffix}`，内容来自 `\GoddenScript\AutoSpammer.txt` |
| **GAntiLag** | 限制发包频率，压低网络负载 | Version（1.16/1.9）、Range、LimitPerSecond |
| **GAntiBlockPush** | 防止被方块推动/位移 | 无设置，仅在 tick 中处理 |
| **GHornAura** | 定时自动使用山羊角 | Delay（默认 8000ms） |
| **GSurround** | 黑曜石环绕自己 | Version、ScanBorder、PlaceDelay、PlaceRange、PlaceAmount、Double（双层）、Top（顶部）、AutoCenter |
| **GEncryptMessage** | 聊天加密：发出的消息加密为 `$...$`，收到的密文自动还原 | KeyCode、Key、Prefix、HideOriginalMessage |
| **GMessageProcessor** | 聊天消息模板处理：把 `{targetName}` + `{targetMessage}` 按格式转发 | SendMessage、CustomMessage、RandomBypass |
| **GChatCommand** | 监听聊天包，在收到指定聊天内容时执行操作 | 无设置 |
| **GExtraMouseButton** | 鼠标侧键（Button4/5）绑定指令 | DownCommands、UpCommands |
| **GAutoMine** | **空模块**：只有构造函数，没有任何功能代码 | 无 |
| **GPathTest** | 路径测试工具：调试 InfiniteAura 的路径生成 | CalcAmount、VclipFindStep、MoveDistance |
| **GTest** | 调试用测试模块：扫描/打印坐标 | ScanBorder、KeyPrint、limitDamage、times |

## 七、值得注意的机制

1. **TP 家族统一依赖 `TpUtil`**：`findVClipVecToMove` 找中转点 + 同 tick 连发多个 `C2S` 位置包，再执行动作、必要时回传。26.3 的“每 tick 只能一个位置包”限制使其**不能原样移植**（ZCore 已改为单包伪造/多 tick 推进）。
2. **GInfiniteAura** 是这套机制的极端形态：一条路径上几十上百个包一次性发出、攻击后逆序发回，属于典型的多包绕过。
3. **GAntiExplosion** 展示了“威胁评估 + 抢先引爆”的反制思路：用 `ExplosionUtils` 计算爆炸伤害，超阈值才行动。
4. **GWebAura / GTrap** 展示了模块间联动（`UseGTrap`、`UseAnchor`）和按目标历史位置预判（`Prev`）。
5. **GKillaura** 的 `Vclip1/Vclip2` 与 `TryKillTotem` 是“伪高度暴击 + 破图腾”组合。

## 八、与 ZCore 的对照

| GCore 模块 | ZCore 现状 |
| --- | --- |
| GTpaura | 已实现 `Z TP Aura` |
| GTpCrystalAura | 已实现 `Z TP Crystal Aura` |
| GTpAnchorAura | 已实现 `Z TP Anchor Aura` |
| GTpPistonAura | 已实现 `Z Piston Aura`（仅水平推） |
| GTpCev | 部分覆盖（Z TP Crystal/Anchor Aura 的“只引爆”场景） |
| GPacketMine | 已实现 `Z Packet Mine` |
| GTpbow / GBowGod / GTrap / GWebAura / GSurround / GAntiExplosion / GHornAura / GAutoExperience / GAutoSpammer | 尚未实现，可作为候选功能 |
| GInfiniteAura / GTPBot / GSuperVClip | 依赖同 tick 多包，26.3 原版服务端不可用 |
| GNuker / GSpeed / GRandomMove / GPlayerRender / GRenderCheck / 其余杂项 | 与 TP 无关，暂未纳入 |

## 九、结论

- GCore 的 36 个模块大致可分为：**战斗（近战/爆炸/控制/弓箭）14、杂项 15、世界 2、移动 3、渲染 2**。
- 真正的核心价值集中在 `TpUtil` + 爆炸选址工具 + 各 TP Aura 的动作编排；其余模块多为辅助、调试或环境特化功能（聊天加密、刷屏、反检测、渲染检查等）。
- 若后续继续扩充 ZCore，优先级较高的候选是：**GHornAura（自动山羊角）、GSurround（环绕）、GAutoExperience（自动经验瓶）、GAntiExplosion（反水晶/锚）、GTrap/GWebAura（控制类）**；这些在 26.3 都可以用现有包/放置机制实现，不依赖多包 TP。
