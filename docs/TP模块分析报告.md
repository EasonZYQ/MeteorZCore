# TP 相关模块分析报告

> 目的：分析参考代码中的 TP（服务端瞬移）/远距离操作算法，评估其在 Minecraft 26.3 + Meteor Client 环境下移植到 MeteorZCore 的可行性。
> 结论先行：**GCore 的“同一 tick 连发多个位置包”核心手法在 26.3 会被服务端直接踢下线，不能原样移植；但选址、伤害最大化、动作编排、包挖掘等算法可以移植。** 详见第 6、7 节。

---

## 1. 参考资料说明

| 目录 | 内容 | 版本 / 说明 |
| --- | --- | --- |
| `GCore/` | LiquidBounce 插件 GCore 的反编译源码（CFR 0.150，Kotlin 反编译产物，120 个 .java） | Minecraft 1.20.4，包名 `dev.godden.core` |
| `GCoreClass/` | 同一插件的 `.class` 字节码（498 个类） | 与 GCore 对应（注：class 在 GCoreClass，源码在 GCore，与你的描述相反） |
| `GgBoy/` | Meteor 插件反编译源码（61 个 .java，含反编译器生成的 `*$ConstantPool.java` 残留） | 旧版 Meteor，非 26.3 |
| `lotus/` | Meteor 插件字节码（50 个 .class） | 旧版 Meteor；已用 Vineflower 反编译，结果保存在 `sources/lotus-decompiled/` |

以上四个目录均已加入 `.gitignore`，只作为本地参考资料，不会提交到仓库（避免把第三方反编译代码发布出去）。

---

## 2. GCore 的 TP 核心机制（`TpUtil`）

文件：[TpUtil.java](file:///E:/setup/Java/Mods/MeteorZCore/GCore/dev/godden/core/Utils/TpUtil.java)

### 2.1 参数（`TpOption` 默认值）

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `SearchVclipMode` | `OnlyUp` | 中转点搜索方向：All / OnlyUp / OnlyDown |
| `SearchFindStep` | 1.8 | 垂直搜索步长（格） |
| `MoveDistance` | 50.0 | 计算包数时假设的“单包位移” |
| `AllowIntoVoid` | false | 是否允许进入虚空（Y ≤ -64 或 0，按维度判断） |
| `LimitPacket` | 20 | 单次 TP 最多允许的位置包数量 |
| `PrintWhenTooManyPacket` | true | 超过包数上限时提示 “TP packet limit !!!” |
| `Back` | true | 动作完成后是否回传原位 |

### 2.2 `doTpMove` 的执行流程

1. `fromVec` 取玩家**上一 tick**的位置（`getPrevPos`），`toVec` 为目标点。
2. `findVClipVecToMove(fromVec → toVec)`：从 `fromVec` 沿 Y 轴按 `SearchFindStep` 上下步进（最多 200 格），返回第一个满足以下条件的中转点 `goVClipVec`：
   - `BlockUtil.checkNoPosCollie`：玩家碰撞箱在该点不卡方块；
   - `BlockUtil.checkCanMove`：该点到目标点之间存在可行路径/视线。
3. 反向再求一次 `backVClipVec`（`toVec → fromVec`），用于回程。
4. 计算包数：
   `maxPacket = max(ceil(dist(from,go)), ceil(dist(from,back)), ceil(dist(from,to)) / MoveDistance) - 1 + 4`
   超过 `LimitPacket` 则放弃。
5. **先重复发送 `maxPacket - 1` 个“原地位置包”，再依次发送中转点、目标点**（`PlayerMoveC2SPacket.PositionAndOnGround`）。
   - 这些重复包的作用是抬高服务端本 tick 允许的位移阈值（服务端阈值随本 tick收到位置包数量放大），从而让一次 tick 内的大距离位移通过检查。
6. 在目标点执行回调 `action`（放置方块 / 攻击实体 / 引爆水晶等）。
7. `Back = true`：发送回程中转点 + 原点，客户端位置不变；
   `Back = false`：直接 `player.setPosition(toVec)` 真实传送（客户端与服务端位置一致，但极易被拉回）。

其他工具方法：

- `doTp(from, to, moveDistance)`：简化版，先发 `n-1` 个原点包，再发目标点。
- `findVecToAttack(targetVec, targetEyeY)`：在目标周围 7×7×7 范围内寻找“无碰撞且可直视目标眼睛”的落脚点，用于近战 TP。
- `MovePacketCounter`：统计本次 TP 发送的包数。

### 2.3 其他 TP 工具

- `TpCrystalUtil`：水晶/黑曜石选址（见 3.2）。
- `TpAnchorUtil`：锚选址，思路与水晶相同。
- `TpBreakUtil`：寻找“能打到目标方块”的 TP 点，支持按版本（1.14 / 1.20）区分校验；`getAnchorBreakPos` 负责找可拆的锚。
- `TpPistonUtil`（1095 行）：活塞推水晶的选址，计算活塞、红石块、黑曜石、水晶四类放置点与 TP 点。
- `TaskQueueManager`：按优先级 + 延迟时间执行的全局任务队列，用于把“传送→放置→引爆”拆成多个 tick 步骤。

---

## 3. GCore 的 TP 模块

### 3.1 `GTpaura`（TP 近战）

- 目标：`TargetUtil.getTarget`，支持 `BVR`（目标离开范围后仍保留）与 `Prev`（使用目标的**历史位置**，默认 0，范围 0–5 tick）。
- 攻击判定：自己到目标距离 ≤ `selectTargets.range`；`AttackDelay` 默认 800ms 或 `UseCooldown`（血条蓄力 ≥ 0.75）。
- 传送点：
  - 默认直接用目标的历史位置；
  - `FindVecToAttack` 开启且该点卡方块时，调用 `TpUtil.findVecToAttack` 找可直视目标的落脚点。
- 暴击技巧：若目标脚下无碰撞 → 发送 `y - 0.001` 的位置包（伪造下落）；否则发送 `y + 0.001` 再发 `y`（伪造跳起）。**两次位置包**配合 `Critical` 设置触发暴击。
- 动作：`TpUtil.doTpMove(...)` 到达后按 `AttackTimes`（默认 1）连续 `doAttackEntity`，可选手动挥手；结束按 `Back` 回传或真实 setPosition。
- 额外：可自动切换到 1.21 Mace（重锤）攻击。

### 3.2 `GTpCrystalAura`（TP 水晶）

文件：[ModuleGTpCrystalAura.java](file:///E:/setup/Java/Mods/MeteorZCore/GCore/dev/godden/core/modules/ModuleGTpCrystalAura.java)，选址在 `TpCrystalUtil`。

流程：

1. 以目标（含 `Prev`/`BVR` 预测位置）为中心，在 `ScanBorder` 范围内扫描候选位置。
2. 对每个候选点：
   - 底座必须是黑曜石 / 基岩，或允许补放黑曜石（`PlaceObsidian`）；
   - 水晶位置必须可替换；
   - 用爆炸伤害模型计算对目标伤害与自身伤害，过滤 `MinDamageToTarget` / `MaxDamageToSelf`；
   - 可叠加多个水晶（`PlaceAmount`），要求爆炸包围盒不重叠（避免互相引爆/浪费）。
3. `findAndSetTpVec`：以第一个候选底座为中心 ±6 格扫描站立点，满足：
   - 玩家碰撞箱（0.3×1.8）不卡方块；
   - 着眼睛位置到**方块中心**距离 ≤ 6（保证 TP 后服务端接受放置）；
   - 玩家到方块中心距离 < `PlaceRange`。
4. `TpUtil.doTpMove` 传送到该点 → 依次放黑曜石 / 水晶（`PlaceDelay`、`HitRange`、`ThroughWallRange` 控制）→ 回传或留在原地。
5. 水晶引爆：对已有水晶按伤害阈值筛选后攻击引爆，`AttackDelay` 控制节奏。

### 3.3 `GTpAnchorAura` / `GLightTpAnchorAura`（TP 重生锚）

- 与水晶同构：先扫描目标周围候选位置（锚位置 + 是否能放），计算 `anchorDamage` 与自身伤害，再找 TP 点、传送、放置锚、充能、引爆。
- 额外设置：`AllowClickAfterPlace`（放置后允许点击引爆）、`AllowPlaceProtect` / `OnlyPlaceProtect` / `ProtectBlock`（在目标周围预先放保护方块）、`Replace`（替换已有方块）、`BVR`、`Prev`、`SwingHand`。
- 锚需要先放萤石充能再引爆，因此它比水晶多一个“充能”步骤。

### 3.4 `GTpPistonAura`（活塞推水晶）

- 需要物品：`PISTON`、`REDSTONE_BLOCK`、`OBSIDIAN`、`END_CRYSTAL`。
- `TpPistonUtil` 计算五类坐标：活塞放置点 + 朝向、红石块位置、黑曜石位置、水晶位置、TP 点。
- 流程：TP 到目标附近 → 放活塞 → 放黑曜石 → 放水晶 → 放红石块（给活塞供能）→ 活塞把水晶向前推 → 引爆。
- 目的：把水晶推到更贴近目标的位置，提高伤害/越过阻挡。

### 3.5 其他

- `GTpCev`：传送到 `TpBreakUtil` 给出的引爆点后攻击/引爆目标（水晶/锚），再回传，适合“只炸不建”的远距离引爆。
- `GTpbot`：按 `delay/prev/keepY/onlyXZ/random` 周期性地小距离 TP 并随机左右键，属于反 AFK/反检测的“假人 TP”。
- `GSuperVClip`：直接 `player.setPos` 垂直穿方块（“make you VClip”），不涉及发包 TP，26.3 下会被服务端拉回。
- `ModuleGPacketMine`：普通/TP 两种挖掘模式，TP 分支用 `TpBreakUtil.getTpBreakData` 找能打到方块的位置，再 `TpUtil` 传送过去破坏。

---

## 4. GgBoy（Meteor 插件，旧版本）

### 4.1 `CrystalHelper`（水晶辅助）

文件：[CrystalHelper.java](file:///E:/setup/Java/Mods/MeteorZCore/GgBoy/CrystalHelper.java)

- `PlaceMode.Breaked`：每个 tick 检查 `breakList` 中的位置，若位置为空且距离 ≤ range → `doBreak` + `doPlace` + `doBreak` 循环（`placeTimes` 控制次数）。
- `PlaceMode.StartBreak`：监听**玩家发出的破坏包**（`PlayerActionC2SPacket`），若被挖方块的下方是可放水晶的底座（黑曜石/基岩），立即对该位置执行 “攻击水晶 → 放水晶 → 攻击水晶” 的连招，实现“挖即炸”。
- `doPlace(pos)`：取底座相邻面 `BlockUtils.getClickSide_alien`，用 `BlockUtilGrim.clickBlock` 在底座面上点击放置水晶。
- `playerCheck`：限制自身/队友误伤（检查周围玩家包围盒与 `burrowCheck`）。

### 4.2 `FeetPlace`（环绕）

- 位置模板：`Normal`（4 个相邻格）、`Super`（12 个：8 邻 + 2 格外 4 向）、`SuperPlus`（24 个：再加上一层 8 向 + 2 格外）、最后补脚下 `(0,-1,0)`。
- 每 tick 按 `delay` 放置，`BlockUtilGrim.placeBlock` 执行。

### 4.3 `GrimPactetMine`（包挖掘）

- 维护 `breakBlocks` 队列（`BlockDate{pos, direction, isMining, isBreaked}`）。
- 首次挖掘发送序列：`START → ABORT → STOP → START → STOP`；
- 后续每 tick：`STOP → START → ABORT`（`rebreak` 模式下额外补 `ABORT + STOP`）。
- 目的：利用 Grim 对连续动作包的处理缺陷实现“瞬间破坏”；并过滤 `godBlocks`、超距离目标。
- 不属于 TP，但属于“发包类功能”，可移植性较高。

### 4.4 其他

- `AotuPlaceStep`：带位置列表的分步自动放置（状态机 + tick 延迟）。
- `Printer` / `PalletBuilder` / `AutoBuy` / `AutoLogin` / `AutoMineSand` / `VelocityPlus` 等与 TP 无关，略。

---

## 5. lotus（Meteor 插件，已反编译）

反编译结果：`sources/lotus-decompiled/`

### 5.1 `PacketMinePlus`（发包挖掘+）

文件：[PacketMinePlus.java](file:///E:/setup/Java/Mods/MeteorZCore/sources/lotus-decompiled/modules/PacketMinePlus.java)

- 核心：`SilentMineBlock` 在客户端模拟挖掘进度，只发包、不产生客户端破坏反馈；`onStartBreakingBlock` 直接取消原版破坏事件。
- 双目标：`delayedDestroyBlock`（延迟破坏）与 `rebreakBlock`（重破），带优先级、失败 tick 计数、自动切换最快工具（`SwapManager`）。
- `inBreakRange` 限制作用距离；`onPacket` 拦截服务端 `START/STOP` 破坏包并可改写为 `ABORT`；在 `rebreakSetBlockBroken` 开启时直接本地 `setBlockState` 为空气。
- 没有 TP/位置伪造。

### 5.2 `LitematicaPrinter` + `printer/PlaceBlockHelper`

- 按投影批量放置：`放置范围(格)` 默认 4.5、`放置高度` 1、`放置延迟` 1 tick、`每tick放置数量` 1、`优先放置模式`（DownTop 等）、`第二放置模式`（最远等）。
- `PlaceBlockHelper.tryPlace`：检查目标位置可替换、朝向可见（`canViewDirection`）后 `place()`；`rotate` 开启时 `Rotations.rotate(yaw, pitch, 50, true, ...)` 再构造 `BlockHitResult` 发送交互/挥手包。
- 无距离伪造，超出 5.5 格会被服务端拒绝。

### 5.3 其他

- `MixinClientConnection` / `MixinClientPlayerEntity`：只做发包/收包日志与记录，没有 TP 相关逻辑。
- `InfinityMinerPlus`、`HunterSupply`、`ElytraFlyPlus`、`trouser` 系列：自动化/ESP，与 TP 无关。

---

## 6. 26.3 兼容性结论（关键）

**26.3 服务端新增了 `ServerboundClientTickEndPacket` 与“每客户端 tick 只允许一个位置包”的限制。** 服务端 `handleMovePlayer` 中：

```java
if (packet.hasPosition()) {
    if (this.receivedPositionThisTick) {
        this.disconnect("invalid_player_movement");
        return;
    }
    this.receivedPositionThisTick = true;
}
```

该标记只会在收到 `ServerboundClientTickEndPacket` 时重置。因此：

1. **GCore 的 `doTpMove`（同 tick 连发 N 个位置包）在 26.3 原版服务端会立即断线**，其整个 TP 体系（GTpaura/GTpCrystalAura/GTpAnchorAura/GTpPistonAura/GTpCev/GTpbot）不能原样移植。
2. `Back = false` 的 `setPosition` 真实传送在 26.3 下会被服务端拉回，风险更高。
3. 26.3 可行的远距离方案只有两类：
   - 维持 ZCore 现有方案：**每个 tick 组只发 1 个伪造位置包**，动作完成后在下一个 tick 组还原；
   - 新增“多 tick 推进”方案：每 tick 把服务端位置向目标推 ≤10 格，连续若个 tick 后到达目标（取代 GCore 的多包 TP）。
4. 选址/伤害/动作编排算法与版本无关，可直接移植。
5. `ServerboundPlayerActionPacket` 在 26.3 仍带 `sequence` 字段，包挖掘（GgBoy/lotus）可以移植，但需按 26.3 映射重写。
6. GgBoy/lotus 使用旧版 Meteor API（`PlayerActionC2SPacket`、`BlockUtilGrim`、Yarn 中间名），移植时要全部改写为 26.3 官方映射与 Meteor 现有工具类（`BlockUtils`、`InvUtils`、`Rotations`、`DamageUtils`）。

---

## 7. 建议加入 ZCore 的方案（供审阅）

按优先级排列：

| 优先级 | 功能 | 来源 | 实现要点 |
| --- | --- | --- | --- |
| P0 | **TP 推进基础能力（内部工具）** | GCore `TpUtil` 思路改造 | 每 tick 只发 1 个位置包，向目标推进 ≤10 格；期间抑制客户端自身位置包；到达后执行动作，再逐 tick 返回。作为所有远距离功能的地基 |
| P0 | **升级 Z TP Crystal Aura** | GCore `TpCrystalUtil` + GgBoy `CrystalHelper` | 落点搜索（底座 ±6 内无碰撞 + 着眼 ≤6 格 + 到目标 < placeRange）、多点水晶（爆炸箱不重叠）、`Prev` 预测位置、可选“挖即放水晶” |
| P0 | **升级 Z TP Anchor Aura** | GCore `TpAnchorUtil` | 充能/引爆节奏、`Protect`/`Replace`、`BVR`/`Prev`、放置后允许点击 |
| P1 | **Z Packet Mine（发包挖掘）** | GgBoy `GrimPactetMine` + lotus `PacketMinePlus` | `START/ABORT/STOP` 序列、`delayedDestroy`/`rebreak` 状态机、自动换最快工具；可作为 Aura 的“自动挖开”加速器 |
| P1 | **Z TP Aura 增强** | GCore `GTpaura` | `Prev` 目标预测、`findVecToAttack` 落点、`AttackTimes`；暴击伪高度需跨 tick 实现（26.3 下风险高，建议默认关闭） |
| P2 | **Z Piston Aura** | GCore `GTpPistonAura` | 活塞 + 红石块 + 黑曜石 + 水晶，把水晶推到更优点位；物品需求高、步骤多，建议最后做 |
| P2 | **Z VClip** | GCore `GSuperVClip` | 垂直穿方块；`setPos` 方式在 26.3 容易被拉回，建议只做“发包式 VClip”（受单包限制） |
| 不建议 | `GTpbot` 随机 TP | GCore | 反检测用途，风险高；且多包 TP 在 26.3 不可用 |

风险提示：

- 服务端/反作弊（Grim、Matrix 等）会检测异常位移；公开服务器使用可能导致封禁。
- 26.3 的单位置包限制决定了远距离操作必然是“低频”的（约 2 tick 一次），不要指望 GCore 那样的高频 TP。
- 水晶/锚自伤逻辑建议完全保留（`min-damage`、`max-self-damage`、`anti-suicide`），并补充“周围玩家/队友”检测（GgBoy `playerCheck`）。

---

## 8. 备注

- 本报告只做分析，**未修改任何 ZCore 功能代码**；待你审阅确认优先级后，我再按上表逐项实现。
- 参考目录（GCore、GCoreClass、GgBoy、lotus）与反编译结果（`sources/lotus-decompiled`）均被 `.gitignore` 排除。
