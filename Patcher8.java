import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;

/**
 * v8：修复 v7 的"只跳一次就死锁 / 变手动"问题
 *
 * v7 根因（闩锁死锁）：
 *   落地边沿触发 -> recastFlight -> 设 awaitingRecast=true, previousFallFlying=false
 *   若服务端拒绝这次起飞(反作弊判定 / 落地瞬间状态未认可), fallFlying 保持 false
 *   -> 闩锁态 "只有 fallFlying=true 才解锁" 永远不满足 -> 永久卡死
 *   -> 只能松开跳跃走 reset 再武装 -> 表现就是"手动、只跳一次就不动"
 *
 * v8 修复：彻底移除 "边沿 + awaitingRecast 闩锁"，改为落地电平检测：
 *   if (!fallFlying && onGround && hopCooldown==0) { recastFlight(); hopCooldown=20; }
 *
 * 为什么不再抽搐(v5/v2 的老问题)：
 *   1) onGround 判据：服务端在空中把 fallFlying 切回 false 时 onGround=false -> 不重开
 *   2) hopCooldown=20：两次重开至少间隔 1 秒，不可能每 tick 开伞收伞
 * 为什么不再死锁(v7 的老问题)：
 *   电平检测不依赖 previousFallFlying 边沿；落地后只要条件满足就会在冷却结束后重试
 * 为什么松开即停：
 *   canRecastFlight() 前置包含 jump 按键，松开 -> 走 reset 清空状态
 *
 * 所有对 MC 的方法引用 owner 一律保持 LocalPlayer（v6 崩溃教训）。
 */
public class Patcher8 {
    static final String CLS = "com/santr/elytrahopper/ElytraHopperClient";
    static final String CFG = "com/santr/elytrahopper/ElytraHopperConfig";
    static final String LP  = "net/minecraft/client/player/LocalPlayer";
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
        System.out.println("补丁完成 major=" + realMajor + " (v8 落地电平+onGround+冷却节流版)");
    }

    static InsnList buildBody() {
        InsnList il = new InsnList();
        LabelNode L_reset = new LabelNode();
        LabelNode L_guardPass = new LabelNode();
        LabelNode L_skipDec = new LabelNode();
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

        // ===== RESET（松开跳跃 / 前置失败）=====
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

        // ===== 长按跳跃总开关（松开即走 reset）=====
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

        // ===== onGround -> local 2 =====
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LP, "onGround", "()Z", false));
        il.add(new VarInsnNode(Opcodes.ISTORE, 2));

        // ===== 落地电平检测：!fallFlying && onGround && cooldown==0 -> 重开 =====
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new JumpInsnNode(Opcodes.IFNE, L_noRecast));               // 飞行中 -> 不重开
        il.add(new VarInsnNode(Opcodes.ILOAD, 2));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_noRecast));               // 未在地面 -> 不重开(防空中切回抽搐)
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "hopCooldown", "I"));
        il.add(new JumpInsnNode(Opcodes.IFNE, L_noRecast));               // 冷却中 -> 不重开(节流)
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CLS, "recastFlight", "(" + LP_DESC + ")V", false));
        il.add(new IntInsnNode(Opcodes.BIPUSH, 20));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "hopCooldown", "I"));

        il.add(L_noRecast);

        // ===== 动量守恒：if (fallFlying) lastFlyingMovement = getDeltaMovement() =====
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_skipMove));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LP, "getDeltaMovement", "()" + VEC_DESC, false));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "lastFlyingMovement", VEC_DESC));
        il.add(L_skipMove);

        // ===== 状态更新（保留字段，不再用于触发）=====
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "previousFallFlying", "Z"));
        il.add(new InsnNode(Opcodes.RETURN));

        return il;
    }
}
