# Elytra Hopper Lite — 字节码补丁说明

## 补丁目标

给原模组 `com.santr.elytrahopper` 的"落地自动重开滑翔"加**三重落地检测**与冷却，区分"真落地"和"空中被服务器切回 fallFlying 状态"，避免状态反复横跳触发反作弊。

## 修改的方法

`ElytraHopperClient.recastIfStoppedFallFlying(LocalPlayer)`

## v5 逻辑（当前版本）— 三重检测

**核心问题**：客户端分不清"真落地"和"服务器把 fallFlying 打回 false"。v4 之前只看 fallFlying 状态，所以状态一切回就误判成落地。

**v5 解法**：引入 `grounded` 维度，用地面/方块信息交叉验证。

```
前置检查失败 / 松开跳跃 -> 重置全部状态并停止
冷却递减
fallFlying = player.isFallFlying()                          // 状态检测
grounded =
    player.onGround()                                        // 地面检测
    || !player.level().getBlockState(                        // 范围（脚下）方块检测
           player.blockPosition().below()
       ).isAir()

if (!fallFlying && grounded && hopCooldown == 0) {          // 三重投票通过
    recastFlight(player);                                    // 翘翅跳一次
    hopCooldown = 20;                                        // 20 tick ≈ 1 秒节流
}
if (fallFlying) lastFlyingMovement = getDeltaMovement()
```

- **① 状态检测**：`!fallFlying` —— 不在飞行状态
- **② 地面检测**：`onGround()` —— 实体接触地面
- **③ 范围方块检测**：脚下方块非空气（`getBlockState(blockPosition().below()).isAir() == false`）
- 三者投票：状态满足 + (地面 或 脚下方块) 确认落地 + 冷却结束 → 才 hop
- "空中被服务器切回"时 `fallFlying=false` 但 `grounded=false`（人在空中、脚下空气）→ **不触发**，杜绝横跳

## 为什么不用反射

26.1.2 的 `client.jar` 实测为 **Mojang 官方映射可读名**（`onGround()`、`getBlockState()` 等），与原 jar 的命名体系一致。而反射的字符串方法名在 Fabric/NeoForge 环境不会被重映射，运行时易失效。因此直接硬编码官方 API 调用，并用 26.1.2 类库逐一校验了全部 8 个方法描述符。

## 版本演进（踩坑记录）

| 版本 | 触发方式 | 问题 |
|---|---|---|
| v1 | 落地边沿 + 冷却跳过重开 | 边沿丢失，只跳一次就停 |
| v2 | 延迟重开状态机 | 重开后只设 2 tick，贴地时每 0.1 秒疯狂重试 |
| v3 | 长按监测 + `!fallFlying` 电平检测 | 状态被切回时又成立，切到飞行又被切回、反复横跳 |
| v4 | 边沿检测 + 闩锁 | 状态被切回时闩锁永久卡死、短飞行边沿丢失卡地面 |
| **v5** | **三重检测（状态+onGround+脚下方块）+ 20 tick** | 地面/方块维度区分真落地与假切回，五种边界场景模拟全通过 |

## 验证

- 字节码逐段核对：三重检测分支、冷却节流、状态更新均正确
- **API 签名校验**：全部 8 个方法描述符与 26.1.2 `client.jar` 实测逐一匹配（排除 NoSuchMethodError）
- **状态机模拟**：正常循环 / 短飞行 / 空中被切回 / 延迟切回 / 松开重按 五种场景全部通过
- class 结构：major 69、StackMapTable 完整

## 仍有的局限

- 客户端补丁**治不了服务端权威切回**：若服务器因反作弊/规则主动拒绝起飞（START_FALL_FLYING 被服务端驳回），客户端再怎么检测也无法起飞。v5 只能保证"不因为假落地而疯狂切换"。
- 沙箱无法运行真实 Minecraft，以上为静态验证 + 逻辑模拟，未做实机加载测试。

## 补丁器源码

`Patcher5.java` — 可复现，输入原版 class，输出补丁后 class。
