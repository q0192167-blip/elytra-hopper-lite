# Elytra Hopper Lite — 补丁说明

## 产物

`elytra-hopper-lite-PATCHED.jar` — 基于原版 `orig.jar` 打字节码补丁生成，**开箱即用**，直接放进 Minecraft 的 `mods` 文件夹即可。

## 为什么不用源码编译

`build.gradle` 使用 `loom.officialMojangMappings()`，但 Mojang **未发布 26.1.2 的官方映射**（版本元数据里 `client_mappings = None`），Fabric Yarn 也没有对应 26.1.2 的映射版本。没有映射表，`net.minecraft.*` 源码无法编译。因此改为直接对已编译的原版 jar 打字节码补丁——字节码里保留完整符号引用，不需要映射。

## 补丁内容

修改了 `com.santr.elytrahopper.ElytraHopperClient.recastIfStoppedFallFlying`：

1. 新增 `private static int hopCooldown` 计数器
2. 每次方法调用先递减冷却：`if (hopCooldown > 0) hopCooldown--`
3. 重开逻辑只在 **`!fallFlying && previousFallFlying`（刚落地那一帧）且 `hopCooldown == 0`** 时执行
4. 成功重开后设 `hopCooldown = 20`（20 tick ≈ 1 秒冷却）

### 行为效果

- ✅ **只在落地那一刻重开一次滑翔** —— 飞行中 `fallFlying == true`，永远不会进入重开分支，不会反复切换 `fallFlying` 状态
- ✅ **1 秒冷却** —— 避免短时间内高频 `START_FALL_FLYING` 包被反作弊判定
- ✅ 保留原模组其它全部行为：落水收翅、动量守恒、4 个配置开关、配置界面、Mixin 钩子等

## 验证

- 补丁后 class 用 javap 校验：major version 69，StackMapTable 完整，字段/方法引用合法
- 用 CFR 反编译补丁后 class，确认逻辑与上述描述一致

## 复现补丁

补丁器源码见 `Patcher.java`（在会话工作区）。依赖 ASM 9.7.1，命令：

```
javac -cp asm-9.7.1.jar:asm-tree-9.7.1.jar Patcher.java
java -cp .:asm-9.7.1.jar:asm-tree-9.7.1.jar:asm-commons-9.7.1.jar:asm-util-9.7.1.jar \
  Patcher <原始ElytraHopperClient.class> <输出.class>
```

然后用输出的 class 替换 jar 内的同名 class 即可。
