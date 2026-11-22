import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;

/**
 * v3：按用户明确的语义重写 recastIfStoppedFallFlying
 *
 * 规则：
 *  - 长按跳跃（canRecastFlight 内含 jump() 检测）时持续监测
 *  - 侦测到"不在飞行/落地"状态 且 冷却结束 -> 翘翅跳一次，重置 20 tick 冷却
 *  - 飞行中不重开；冷却中不重开
 *  - 松开跳跃键（canRecastFlight=false）-> 立即停止，不动作
 *
 * 冷却 20 tick ≈ 1 秒，杜绝 v2 的 2 tick 疯狂切换。
 */
public class Patcher3 {
    static final String CLS = "com/santr/elytrahopper/ElytraHopperClient";
    static final String CFG = "com/santr/elytrahopper/ElytraHopperConfig";
    static final String LP  = "net/minecraft/client/player/LocalPlayer";
    static final String VEC = "net/minecraft/world/phys/Vec3";

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
            if (mn.name.equals("recastIfStoppedFallFlying") && mn.desc.equals("(L" + LP + ";)V")) {
                mn.instructions = buildBody();
                mn.maxLocals = 3;
                mn.maxStack = 3;
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
        System.out.println("补丁完成 major=" + realMajor + " (v3 长按监测版)");
    }

    static InsnList buildBody() {
        InsnList il = new InsnList();
        LabelNode L_reset = new LabelNode();       // 松开跳/不满足条件 -> 停止
        LabelNode L_guardPass = new LabelNode();   // 前置检查通过

        // ===== 前置检查 =====
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new JumpInsnNode(Opcodes.IFNULL, L_reset));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraft/client/Minecraft", "getInstance", "()Lnet/minecraft/client/Minecraft;", false));
        il.add(new FieldInsnNode(Opcodes.GETFIELD, "net/minecraft/client/Minecraft", "player", "L" + LP + ";"));
        il.add(new JumpInsnNode(Opcodes.IF_ACMPNE, L_reset));
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "config", "L" + CFG + ";"));
        il.add(new FieldInsnNode(Opcodes.GETFIELD, CFG, "enabled", "Z"));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_reset));
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "config", "L" + CFG + ";"));
        il.add(new FieldInsnNode(Opcodes.GETFIELD, CFG, "recastFlight", "Z"));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_reset));
        il.add(new JumpInsnNode(Opcodes.GOTO, L_guardPass));

        // ===== RESET/停止分支：松开跳跃或条件不满足 =====
        il.add(L_reset);
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "previousFallFlying", "Z"));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "awaitingRecast", "Z"));
        il.add(new InsnNode(Opcodes.RETURN));

        il.add(L_guardPass);

        // ===== canRecastFlight(player) -> local 2 (总开关：按住跳跃+鞘翅+可重开) =====
        LabelNode L_cannot = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CLS, "canRecastFlight", "(L" + LP + ";)Z", false));
        il.add(new VarInsnNode(Opcodes.ISTORE, 2));
        il.add(new VarInsnNode(Opcodes.ILOAD, 2));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_reset)); // 松开跳跃/不满足 -> 停止

        // ===== 冷却递减：if (hopCooldown > 0) hopCooldown-- =====
        LabelNode L_skipDec = new LabelNode();
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "hopCooldown", "I"));
        il.add(new JumpInsnNode(Opcodes.IFLE, L_skipDec));
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "hopCooldown", "I"));
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new InsnNode(Opcodes.ISUB));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "hopCooldown", "I"));
        il.add(L_skipDec);

        // ===== fallFlying = player.isFallFlying() -> local 1 =====
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LP, "isFallFlying", "()Z", false));
        il.add(new VarInsnNode(Opcodes.ISTORE, 1));

        // ===== 侦测落地：if (!fallFlying && hopCooldown == 0) { recastFlight; hopCooldown=20 } =====
        LabelNode L_noRecast = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new JumpInsnNode(Opcodes.IFNE, L_noRecast));       // 飞行中 -> 不重开
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "hopCooldown", "I"));
        il.add(new JumpInsnNode(Opcodes.IFNE, L_noRecast));       // 冷却中 -> 不重开
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CLS, "recastFlight", "(L" + LP + ";)V", false));
        il.add(new IntInsnNode(Opcodes.BIPUSH, 20));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "hopCooldown", "I"));
        il.add(L_noRecast);

        // ===== if (fallFlying) lastFlyingMovement = getDeltaMovement() =====
        LabelNode L_noMove = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_noMove));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LP, "getDeltaMovement", "()L" + VEC + ";", false));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "lastFlyingMovement", "L" + VEC + ";"));
        il.add(L_noMove);

        // ===== previousFallFlying = fallFlying; return =====
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "previousFallFlying", "Z"));
        il.add(new InsnNode(Opcodes.RETURN));

        return il;
    }
}
