# Elytra Hopper Lite — 补丁说明（v3 长按监测版）

## 产物

`elytra-hopper-lite-PATCHED.jar` — 基于原版 `orig.jar` 打字节码补丁生成，**开箱即用**，直接放进 Minecraft 的 `mods` 文件夹即可。

## 为什么不用源码编译

`build.gradle` 使用 `loom.officialMojangMappings()`，但 Mojang **未发布 26.1.2 的官方映射**（版本元数据里 `client_mappings = None`），Fabric Yarn 也没有对应 26.1.2 的映射版本。因此改为直接对已编译的原版 jar 打字节码补丁——字节码里保留完整符号引用，不需要映射。

## 补丁内容（v3：长按跳跃监测落地）

整体重写了 `com.santr.elytrahopper.ElytraHopperClient.recastIfStoppedFallFlying`，新增 `private static int hopCooldown` 计数器。逻辑按"长按监测"语义设计：

1. **总开关**：调用 `canRecastFlight(player)`（内部检测 `jump()` 按键 + 鞘翅 + 不在水/梯子/浮空等）。按住跳跃且可重开才继续；**松开跳跃键立即停止，不做任何动作**
2. **冷却递减**：长按期间每 tick 递减 `hopCooldown`
3. **侦测落地**：当 `!fallFlying`（不在飞行/已落地）且 `hopCooldown == 0` 时，执行 `recastFlight(player)` 翘翅跳一次，并把 `hopCooldown = 20`
4. **飞行中**（`fallFlying == true`）或**冷却中**：不重开
5. 保留动量守恒、落水收翅等原有行为

### 行为效果

- ✅ **长按跳跃 = 开启自动 hop**：贴地/落地时自动展翅，约 1 秒（20 tick）一次，稳定赶路
- ✅ **松开跳跃键立刻停**：不再做任何重开动作
- ✅ **不再疯狂切换**：冷却从 v2 的 2 tick 改为 20 tick，杜绝每 0.1 秒重发 `START_FALL_FLYING` 包
- ✅ **飞行中不切换状态**：`fallFlying == true` 时不触发，满足防反作弊的原始诉求

> 迭代记录：v1（落地边沿 + 冷却跳过）会因边沿丢失"只跳一次就停"；v2（延迟重开状态机）重开后只设 2 tick 保护，导致贴地时每 2 tick 疯狂重试；v3 改为"长按监测 + 20 tick 节流"，彻底解决以上两个问题。

## 验证

- 补丁后 class 用 javap 校验：major version 69，StackMapTable 完整，字段/方法引用合法
- javap 反汇编逐段确认：总开关、冷却递减、落地侦测重开、飞行不动作逻辑正确

## 复现补丁

补丁器源码见 `Patcher3.java`（v3 长按监测版）。依赖 ASM 9.7.1，命令：

```
javac -cp asm-9.7.1.jar:asm-tree-9.7.1.jar Patcher3.java
java -cp .:asm-9.7.1.jar:asm-tree-9.7.1.jar:asm-commons-9.7.1.jar:asm-util-9.7.1.jar \
  Patcher3 <原始ElytraHopperClient.class> <输出.class>
```

然后用输出的 class 替换 jar 内的同名 class 即可。
