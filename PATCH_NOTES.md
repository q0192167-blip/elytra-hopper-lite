# Elytra Hopper Lite — 补丁版说明

## 当前版本：v8（落地电平 + onGround + 冷却节流，修复"只跳一次就卡死"）

文件名：`8_elytra-hopper-lite-PATCHED.jar`（数字前缀 = 版本号）

> 请下载**数字前缀最大**的那个 jar，即 `8_elytra-hopper-lite-PATCHED.jar`。旧版本（1~7）均有已知缺陷，不再建议使用。

## v8 修复了什么（重要）

v7 虽然修好了崩溃，但又出现"只跳一次就不动了 / 变回手动"——根因是 **awaitingRecast 闩锁死锁**：

- 落地那一刻触发重开 → 上闩锁 `awaitingRecast=true`，同时 `previousFallFlying` 被置 `false`
- 如果这次起飞没被认可（服务器把 `fallFlying` 切回 `false`），闩锁态"只有 `fallFlying=true` 才解锁"永远不满足 → **永久锁死**
- 只能松开跳跃走 reset 重新武装 → 表现就是"手动、只跳一次"

v8 彻底移除边沿 + 闩锁，改成最朴素的**落地电平检测**：

```
如果  没在飞(!fallFlying) 且 在地面(onGround) 且 冷却结束(cooldown==0)
    -> recastFlight() 重开, 上 20 tick 冷却
松开跳跃 / 前置不满足 -> 立即 reset 停止
```

- **不再死锁**：只要还按着跳跃、人在地面，冷却一结束就重试，不依赖任何边沿/闩锁
- **不抽搐**：`onGround` 保证空中被切回时不重开；20 tick 冷却保证最多每秒一次
- **松开即停**：松开跳跃立刻 reset

状态机模拟器 4 个场景全部通过（正常循环 / 起飞被拒稳定重试 / 空中切回 0 次重开 / 松开即停），字节码 owner 全部为 `LocalPlayer`（无 v6 的 NoSuchMethodError 隐患）。

## 这个模组做什么

原版 Elytra Hopper 的核心功能：你在空中滑翔、落地那一刻按着跳跃键，模组自动帮你重新展翅起飞，实现连续 hopping 赶路。本补丁在此基础上加了节流与判定，避免切换状态过快被反作弊判定。

## v6 修复了什么（重要）

v5 有一个致命 bug：落地判定用了 `onGround() || 脚下方块非空`。

问题在于——**空中滑翔时脚下就是地面，脚下方块非空永远成立**，导致 `grounded` 在空中恒为 true。于是服务器每次把飞行状态切回移动，模组都误判成"落地"，立刻重新开伞 → 又被切回 → 又开伞，表现就是长按跳跃时空中疯狂"开伞/收伞"抽搐。

v6 的修复：
- **落地判据只用 `onGround()`**（真正接触地面），彻底移除脚下方块检测（那个在空中恒真，不能用）
- 恢复原版的**落地边沿检测** `!fallFlying && previousFallFlying`：只在"上一帧在飞、这一帧没飞"的那一次瞬间才考虑重开
- 恢复 **awaitingRecast 闩锁**：重开一次后锁住，只有确认真的飞起来（`fallFlying=true`）才解锁；若被服务器切回移动则保持锁定，绝不重试 —— 这是杜绝反复开伞的关键
- 保留 **20 tick（约 1 秒）冷却** 和 **长按跳跃总开关**（松开跳跃立即重置停止）

## 每 tick 逻辑

```
前置失败 / 松开跳跃        -> 重置所有状态并停止
冷却 > 0                   -> 递减
fallFlying = isFallFlying()
onGround   = onGround()          // 唯一落地判据

if (闩锁中):
    确认起飞(fallFlying=true)    -> 解锁
    被切回移动                   -> 保持闩锁，绝不重试
else if (!fallFlying 且 上一帧在飞 且 onGround 且 冷却结束):
    重开一次 + 上闩锁 + 20 tick 冷却

飞行中记录动量（保持原有惯性守恒）
```

## 静态验证

用状态机模拟器跑了 5 个边界场景，全部通过：

| 场景 | 结果 |
|---|---|
| 正常 hopping 循环 | ✅ 落地才重开，每次间隔冷却 |
| **空中被反复切回（v5 的抽搐场景）** | ✅ **0 次重开，不再开伞收伞** |
| 真落地后重开被拒 | ✅ 上闩锁，不疯狂重试 |
| 短飞行 + 冷却节流 | ✅ 冷却期内不重复 |
| 松开重按恢复 | ✅ 松开即停，重按重新武装 |

字节码验证：class major 69、StackMapTable 完整、致抽搐的 `getBlockState/isAir` 等 API 已完全移除、方法引用签名与 MC 26.1.2 官方映射逐一匹配（排除运行时 NoSuchMethodError）。

## 版本演进

| 版本 | 逻辑 | 问题 |
|---|---|---|
| v1 | 落地边沿 + 冷却跳过 | 边沿丢失，只跳一次就停 |
| v2 | 延迟重开状态机 | 2 tick 疯狂重试 |
| v3 | 长按电平检测 | 状态切回时横跳 |
| v4 | 边沿 + 闩锁（无 onGround） | 空中切回误判成落地 |
| v5 | 状态 + onGround + 脚下方块 | 脚下方块空中恒真 → 空中抽搐 |
| v6 | 边沿 + onGround + 闩锁 | 空中抽搐修好，但 owner 写错崩溃 |
| v7 | v6 + owner 改 LocalPlayer | 崩溃修好，但闩锁死锁 → 只跳一次 |
| **v8** | **落地电平 + onGround + 20t 冷却，无闩锁** | **当前版本** |

## 使用

把 jar 放进 mods 文件夹。空中滑翔、落地瞬间按住跳跃键即可自动重开；松开跳跃停止。

注意：这是客户端模组，纯客户端运行，服务器无需安装。但服务器反作弊对"落地重开鞘翅"的判定是服务端权威——若服务器主动拒绝起飞，闩锁会让它停住而不再抽搐，此时松开重按可重新武装。

## v7 热修复（崩溃修复）

v6 进游戏直接崩溃，日志堆栈：

```
java.lang.NoSuchMethodError: 'boolean net.minecraft.world.entity.Entity.isFallFlying()'
    at com.santr.elytrahopper.ElytraHopperClient.recastIfStoppedFallFlying
```

**根因**：v5/v6 的 ASM 补丁把 `isFallFlying()`、`onGround()`、`getDeltaMovement()` 的调用 owner 硬编码成了 `Entity`（`net/minecraft/world/entity/Entity`）。但这些方法的真实声明类不在 Entity 上（`isFallFlying` 在 `LivingEntity`），且原版字节码里所有调用的 owner 都是 `LocalPlayer`。Sinytra Connector 按"原版 jar 出现过的引用组合"建立映射表，`Entity.isFallFlying` 这个组合从未在原版出现，未被重映射 → 运行时方法不存在 → 崩溃。

**修复**：v7 把三处 owner 全部改回 `LocalPlayer`，与原版字节码逐字节一致。交叉验证：补丁 class 相对原版只新增两个引用——自有的 `hopCooldown` 字段（安全）和 `LocalPlayer.onGround`（LocalPlayer 继承链含 Entity.onGround，合法）。

**教训**：给已编译 jar 打 ASM 字节码补丁时，所有对 MC 类的引用必须与原始字节码的 owner/name/desc 完全一致，不能凭官方映射自行改 owner——Connector/NeoForge 的映射表只覆盖原版出现过的引用组合。
