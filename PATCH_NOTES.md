# Elytra Hopper Lite — 字节码补丁说明

## 补丁目标

给原模组 `com.santr.elytrahopper` 的"落地自动重开滑翔"加冷却与状态保护，避免飞行状态被反复切换触发服务器反作弊。

## 修改的方法

`ElytraHopperClient.recastIfStoppedFallFlying(LocalPlayer)`

## v4 逻辑（当前版本）

在保留**原版状态机（边沿检测 + 闩锁）**的基础上，叠加"长按跳跃开关"和"20 tick 冷却"：

```
前置检查失败 / 松开跳跃键 -> 重置全部状态并停止
冷却递减
fallFlying = player.isFallFlying()

if (awaitingRecast) {                  // 闩锁态
    if (fallFlying) awaitingRecast = false;   // 确认真的飞起来，才解锁
    // 仍 !fallFlying（状态被切回移动）-> 保持闩锁，什么都不做，绝不重试
} else if (!fallFlying && previousFallFlying && hopCooldown == 0) {
    // 真实的"从飞行落地"边沿
    awaitingRecast = true;             // 上闩锁
    recastFlight(player);              // 重开滑翔
    hopCooldown = 20;                  // 20 tick ≈ 1 秒节流
}

if (fallFlying) lastFlyingMovement = getDeltaMovement()
previousFallFlying = fallFlying
```

### 关键点

- **边沿检测** `!fallFlying && previousFallFlying`：只在"从飞行真正落地那一帧"触发，不是只要"不在飞行"就触发
- **闩锁 `awaitingRecast`**：触发后锁住，只有确认飞起来才解锁；状态被服务器切回移动时保持锁住，杜绝"切到飞行→被切回→再重开"的疯狂横跳
- **长按跳跃开关** `canRecastFlight`：松开跳跃键立即重置并停止
- **20 tick 冷却**：限制重开频率，降低反作弊检测风险

## 版本演进（踩坑记录）

| 版本 | 触发方式 | 问题 |
|---|---|---|
| v1 | 落地边沿 + 冷却期间跳过重开 | 边沿丢失，只跳一次就停 |
| v2 | 延迟重开状态机 | 重开后只设 2 tick 保护，贴地时每 0.1 秒疯狂重试 |
| v3 | 长按监测 + `!fallFlying` 电平检测 + 20 tick | 状态被切回移动时 `!fallFlying` 又成立，导致切到飞行又被切回、反复横跳 |
| **v4** | **原版边沿检测 + 闩锁 + 长按开关 + 20 tick** | 状态被切回时闩锁保持，不重试，杜绝横跳 |

## 验证

- javap 校验：class major 69、StackMapTable 完整、字段引用合法
- 字节码逐段核对：前置检查、闩锁分支、落地边沿分支、冷却节流均与预期一致

## 补丁器源码

`Patcher4.java` — 可复现，输入原版 class，输出补丁后 class。
