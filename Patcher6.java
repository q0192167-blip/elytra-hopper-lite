import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;

/**
 * v6：修复 v5 空中抽搐 bug
 *
 * v5 致命错误：grounded = onGround() || !脚下块.isAir()
 *    -> 空中滑翔时脚下就是地面, 脚下方块非空恒真 -> grounded 恒为 true
 *    -> 服务端每次把 fallFlying 切回 false, 立刻被误判为"落地" -> 开伞 -> 又切回 -> 抽搐
 *
 * v6 修复：落地判据只用 onGround()（真正接触地面），并恢复原版的
 *    边沿检测 !fallFlying && previousFallFlying + awaitingRecast 闩锁。
 *
 * 逻辑（每 tick）：
 *   前置失败/松开跳跃 -> reset
 *   冷却递减
 *   fallFlying = isFallFlying()          -> local 1
 *   onGround   = onGround()             -> local 2   (唯一落地判据)
 *
 *   if (awaitingRecast) {                // 闩锁态
 *       if (fallFlying) awaitingRecast = false;   // 确认起飞 -> 解锁
 *       // 否则保持闩锁，绝不重试（防被切回后疯狂开伞）
 *   } else if (!fallFlying && previousFallFlying && onGround && hopCooldown==0) {
 *       recastFlight; awaitingRecast=true; hopCooldown=20;   // 真落地边沿 -> 重开一次
 *   }
 *   if (fallFlying) lastFlyingMovement = getDeltaMovement()
 *   previousFallFlying = fallFlying
 *
 * API 签名已在 26.1.2 client.jar 逐一确认, 直接硬编码。
 */
public class Patcher6 {
    static final String CLS = "com/santr/elytrahopper/ElytraHopperClient";
    static final String CFG = "com/santr/elytrahopper/ElytraHopperConfig";
    static final String LP  = "net/minecraft/client/player/LocalPlayer";
    static final String ENT = "net/minecraft/world/entity/Entity";
    static final String VEC = "net/minecraft/world/phys/Vec3";
    static final String LP_DESC  = "L" + LP + ";";
    static final String VEC_DESC = "L" + VEC + ";";
    static final String CFG_DESC = "L" + CFG + ";";

    public static void main(String[] args) throws Exception {
        byte[] orig = Files.readAllBytes(Paths.get(args[0]));
        int realMajor = ((orig[6] & 0xFF) << 8) | (orig[7] & 0xFF);
        byte[] tmp = orig.clone();
        tmp[6] = 0; tmp[7] = 61; // 临时降到 Java17 让 ASM 能读

        ClassReader cr = new ClassReader(tmp);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        if (!cn.fields.stream().anyMatch(f -> f.name.equals("hopCooldown"))) {
            cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                    "hopCooldown", "I", null, 0));
        }

        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("recastIfStoppedFallFlying") && mn.desc.equals("(" + LP_DESC + ")V")) {
                mn.instructions = buildBody();
                mn.maxLocals = 3;
                mn.maxStack = 4;
                mn.tryCatchBlocks.clear();
                patched = true;
            }
        }
        if (!patched) throw new RuntimeException("目标方法未找到");

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                if (a.equals(b)) return a;
                return "java/lang/Object";
            }
        };
        cn.accept(cw);
        byte[] out = cw.toByteArray();
        out[6] = (byte)((realMajor >> 8) & 0xFF);
        out[7] = (byte)(realMajor & 0xFF);
        Files.write(Paths.get(args[1]), out);
        System.out.println("补丁完成 major=" + realMajor + " (v6 边沿+onGround+闩锁版)");
    }

    static InsnList buildBody() {
        InsnList il = new InsnList();
        LabelNode L_reset = new LabelNode();
        LabelNode L_guardPass = new LabelNode();
        LabelNode L_skipDec = new LabelNode();
        LabelNode L_lockedCheck = new LabelNode();
        LabelNode L_lockedStay = new LabelNode();
        LabelNode L_notLocked = new LabelNode();
        LabelNode L_noRecast = new LabelNode();
        LabelNode L_skipMove = new LabelNode();

        // ===== 前置检查 =====
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new JumpInsnNode(Opcodes.IFNULL, L_reset));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraft/client/Minecraft", "getInstance", "()Lnet/minecraft/client/Minecraft;", false));
        il.add(new FieldInsnNode(Opcodes.GETFIELD, "net/minecraft/client/Minecraft", "player", LP_DESC));
        il.add(new JumpInsnNode(Opcodes.IF_ACMPNE, L_reset));
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "config", CFG_DESC));
        il.add(new FieldInsnNode(Opcodes.GETFIELD, CFG, "enabled", "Z"));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_reset));
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "config", CFG_DESC));
        il.add(new FieldInsnNode(Opcodes.GETFIELD, CFG, "recastFlight", "Z"));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_reset));
        il.add(new JumpInsnNode(Opcodes.GOTO, L_guardPass));

        // ===== RESET =====
        il.add(L_reset);
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "previousFallFlying", "Z"));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "awaitingRecast", "Z"));
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, VEC, "ZERO", VEC_DESC));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "lastFlyingMovement", VEC_DESC));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "hopCooldown", "I"));
        il.add(new InsnNode(Opcodes.RETURN));

        il.add(L_guardPass);

        // ===== 长按跳跃总开关 =====
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CLS, "canRecastFlight", "(" + LP_DESC + ")Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_reset));

        // ===== 冷却递减 =====
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "hopCooldown", "I"));
        il.add(new JumpInsnNode(Opcodes.IFLE, L_skipDec));
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "hopCooldown", "I"));
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new InsnNode(Opcodes.ISUB));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "hopCooldown", "I"));
        il.add(L_skipDec);

        // ===== fallFlying -> local 1 =====
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENT, "isFallFlying", "()Z", false));
        il.add(new VarInsnNode(Opcodes.ISTORE, 1));

        // ===== onGround -> local 2（唯一落地判据，去掉脚下方块）=====
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENT, "onGround", "()Z", false));
        il.add(new VarInsnNode(Opcodes.ISTORE, 2));

        // ===== 闩锁态判断 =====
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "awaitingRecast", "Z"));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_notLocked));

        // 闩锁中：只有 fallFlying=true 才解锁，否则保持闩锁不重试
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_lockedStay));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "awaitingRecast", "Z"));
        il.add(L_lockedStay);
        il.add(new JumpInsnNode(Opcodes.GOTO, L_noRecast));

        // 非闩锁：落地边沿 + onGround + 冷却结束 -> 重开一次
        il.add(L_notLocked);
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new JumpInsnNode(Opcodes.IFNE, L_noRecast));               // 飞行中 -> 不重开
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "previousFallFlying", "Z"));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_noRecast));               // 非落地边沿 -> 不重开
        il.add(new VarInsnNode(Opcodes.ILOAD, 2));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_noRecast));               // 未真正落地 -> 不重开
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "hopCooldown", "I"));
        il.add(new JumpInsnNode(Opcodes.IFNE, L_noRecast));               // 冷却中 -> 不重开
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CLS, "recastFlight", "(" + LP_DESC + ")V", false));
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "awaitingRecast", "Z"));
        il.add(new IntInsnNode(Opcodes.BIPUSH, 20));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "hopCooldown", "I"));

        il.add(L_noRecast);

        // ===== if (fallFlying) lastFlyingMovement = getDeltaMovement() =====
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_skipMove));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENT, "getDeltaMovement", "()" + VEC_DESC, false));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "lastFlyingMovement", VEC_DESC));
        il.add(L_skipMove);

        // ===== 状态更新 =====
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "previousFallFlying", "Z"));
        il.add(new InsnNode(Opcodes.RETURN));

        return il;
    }
}
