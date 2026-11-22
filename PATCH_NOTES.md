# Elytra Hopper Lite — 补丁说明（v2 延迟重开版）

## 产物

`elytra-hopper-lite-PATCHED.jar` — 基于原版 `orig.jar` 打字节码补丁生成，**开箱即用**，直接放进 Minecraft 的 `mods` 文件夹即可。

## 为什么不用源码编译

`build.gradle` 使用 `loom.officialMojangMappings()`，但 Mojang **未发布 26.1.2 的官方映射**（版本元数据里 `client_mappings = None`），Fabric Yarn 也没有对应 26.1.2 的映射版本。没有映射表，`net.minecraft.*` 源码无法编译。因此改为直接对已编译的原版 jar 打字节码补丁——字节码里保留完整符号引用，不需要映射。

## 补丁内容（v2：延迟重开 + 持续循环）

整体重写了 `com.santr.elytrahopper.ElytraHopperClient.recastIfStoppedFallFlying`，新增 `private static int hopCooldown` 计数器，状态机如下：

1. **冷却递减**：只在落地状态（`!fallFlying`）且 `hopCooldown > 0` 时递减
2. **落地边沿**（`!fallFlying && previousFallFlying`）：设置 `awaitingRecast = true` 标记"待重开"，并把 `hopCooldown = 20`
3. **待重开状态**（`awaitingRecast == true`）：
   - 若已起飞（`fallFlying == true`）→ 清标志 `awaitingRecast = false`
   - 否则若冷却结束（`hopCooldown == 0`）→ 执行 `recastFlight(player)` 重新展翅，并设 `hopCooldown = 2`（短暂保护，避免起飞确认前重复调用）
4. **飞行中**（`fallFlying == true`）：不进入落地分支，**不会切换状态**

### 行为效果

- ✅ **持续循环弹跳**：长按跳跃 + 有鞘翅时，落地 → 约 1 秒后重开 → 飞行 → 落地 → 重开……可一直循环赶路
- ✅ **落地才触发**：只在 `!fallFlying && previousFallFlying` 的落地边沿标记重开，飞行途中绝不切换 `fallFlying` 状态
- ✅ **约 1 秒一次**：每次重开间隔 20 tick，降低高频 `START_FALL_FLYING` 包被反作弊判定的风险
- ✅ 保留原模组其它全部行为：落水收翅、动量守恒、4 个配置开关、配置界面、Mixin 钩子等

> 注意：v1 版本采用"落地立即重开 + 20 tick 冷却跳过"的简单做法，会因冷却期间丢失 `previousFallFlying` 边沿导致状态机卡死（只跳一次就停）。v2 用 `awaitingRecast` 标志保持待重开状态，彻底修复此问题。

## 验证

- 补丁后 class 用 javap 校验：major version 69，StackMapTable 完整，字段/方法引用合法
- javap 反汇编状态机字节码，逐段确认冷却递减、落地标记、延迟重开、飞行不切换逻辑正确

## 复现补丁

补丁器源码见 `Patcher2.java`（v2 整体重写版）。依赖 ASM 9.7.1，命令：

```
javac -cp asm-9.7.1.jar:asm-tree-9.7.1.jar Patcher2.java
java -cp .:asm-9.7.1.jar:asm-tree-9.7.1.jar:asm-commons-9.7.1.jar:asm-util-9.7.1.jar \
  Patcher2 <原始ElytraHopperClient.class> <输出.class>
```

然后用输出的 class 替换 jar 内的同名 class 即可。
