import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;

/**
 * v4：恢复原版状态机（边沿检测 + 闩锁），叠加长按跳跃开关 + 20 tick 冷却
 *
 * 修复 v3 的坑：v3 用 !fallFlying 电平检测，状态被服务器切回移动时又满足条件，
 * 导致"切到飞行->被切回->再重开"疯狂横跳。
 *
 * v4 逻辑：
 *  - 前置检查失败 / 松开跳跃 -> 重置全部状态并停止
 *  - 冷却递减
 *  - fallFlying = isFallFlying()
 *  - if (awaitingRecast):            闩锁态
 *        if (fallFlying) awaitingRecast = false;   // 确认飞起来才解锁
 *        // 仍 !fallFlying -> 保持闩锁，绝不重试（杜绝横跳）
 *    else if (!fallFlying && previousFallFlying && hopCooldown==0):
 *        // 真正的"从飞行落地"边沿
 *        awaitingRecast = true;       // 上闩锁
 *        recastFlight(player);
 *        hopCooldown = 20;            // 节流，防反作弊
 *  - if (fallFlying) lastFlyingMovement = getDeltaMovement()
 *  - previousFallFlying = fallFlying
 */
public class Patcher4 {
    static final String CLS = "com/santr/elytrahopper/ElytraHopperClient";
    static final String CFG = "com/santr/elytrahopper/ElytraHopperConfig";
    static final String LP  = "net/minecraft/client/player/LocalPlayer";
    static final String VEC = "net/minecraft/world/phys/Vec3";
    static final String LP_DESC = "L" + LP + ";";
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

        boolean hasField = cn.fields.stream().anyMatch(f -> f.name.equals("hopCooldown"));
        if (!hasField) {
            cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                    "hopCooldown", "I", null, 0));
        }

        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("recastIfStoppedFallFlying") && mn.desc.equals("(" + LP_DESC + ")V")) {
                mn.instructions = buildBody();
                mn.maxLocals = 2;
                mn.maxStack = 2;
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
        System.out.println("补丁完成 major=" + realMajor + " (v4 边沿检测+闩锁版)");
    }

    static InsnList buildBody() {
        InsnList il = new InsnList();
        LabelNode L_reset = new LabelNode();
        LabelNode L_guardPass = new LabelNode();
        LabelNode L_skipDec = new LabelNode();
        LabelNode L_elseBranch = new LabelNode();
        LabelNode L_endState = new LabelNode();
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

        // ===== RESET：前置失败 或 松开跳跃 -> 重置全部、停止 =====
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

        // ===== 长按跳跃总开关：!canRecastFlight -> reset(停止) =====
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
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LP, "isFallFlying", "()Z", false));
        il.add(new VarInsnNode(Opcodes.ISTORE, 1));

        // ===== if (awaitingRecast) 闩锁态 =====
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "awaitingRecast", "Z"));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_elseBranch));
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_endState)); // 仍不在飞行 -> 保持闩锁，什么都不做
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "awaitingRecast", "Z")); // 确认起飞，解锁
        il.add(new JumpInsnNode(Opcodes.GOTO, L_endState));

        // ===== else if (!fallFlying && previousFallFlying && hopCooldown==0) 落地边沿 =====
        il.add(L_elseBranch);
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new JumpInsnNode(Opcodes.IFNE, L_endState));       // 飞行中 -> 跳过
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "previousFallFlying", "Z"));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_endState));       // 非落地边沿 -> 跳过
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "hopCooldown", "I"));
        il.add(new JumpInsnNode(Opcodes.IFNE, L_endState));       // 冷却中 -> 跳过
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "awaitingRecast", "Z")); // 上闩锁
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CLS, "recastFlight", "(" + LP_DESC + ")V", false));
        il.add(new IntInsnNode(Opcodes.BIPUSH, 20));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "hopCooldown", "I"));

        il.add(L_endState);

        // ===== if (fallFlying) lastFlyingMovement = getDeltaMovement() =====
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_skipMove));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LP, "getDeltaMovement", "()" + VEC_DESC, false));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "lastFlyingMovement", VEC_DESC));
        il.add(L_skipMove);

        // ===== previousFallFlying = fallFlying; return =====
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "previousFallFlying", "Z"));
        il.add(new InsnNode(Opcodes.RETURN));

        return il;
    }
}
