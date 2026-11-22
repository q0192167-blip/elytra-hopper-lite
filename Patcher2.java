import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;

/**
 * 整体重写 recastIfStoppedFallFlying，实现"延迟重开 + 持续循环"：
 *  - 落地边沿：awaitingRecast=true, hopCooldown=20
 *  - 落地期间每 tick 递减 hopCooldown，到 0 时重开
 *  - 飞行中 fallFlying=true 不进入落地分支，绝不切换状态
 *  - 重开后短暂保护 hopCooldown=2，避免起飞确认前重复调用
 */
public class Patcher2 {
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

        // 确保 hopCooldown 字段存在
        boolean hasField = cn.fields.stream().anyMatch(f -> f.name.equals("hopCooldown"));
        if (!hasField) {
            cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                    "hopCooldown", "I", null, 0));
        }

        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("recastIfStoppedFallFlying") && mn.desc.equals("(L" + LP + ";)V")) {
                mn.instructions = buildBody();
                mn.maxLocals = 2;
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
        System.out.println("补丁完成 major=" + realMajor + " (延迟重开版)");
    }

    static InsnList buildBody() {
        InsnList il = new InsnList();
        LabelNode L_reset = new LabelNode();
        LabelNode L_guardPass = new LabelNode();

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

        // reset
        il.add(L_reset);
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "previousFallFlying", "Z"));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "awaitingRecast", "Z"));
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, VEC, "ZERO", "L" + VEC + ";"));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "lastFlyingMovement", "L" + VEC + ";"));
        il.add(new InsnNode(Opcodes.RETURN));

        il.add(L_guardPass);
        // boolean fallFlying = player.isFallFlying();  -> local 1
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LP, "isFallFlying", "()Z", false));
        il.add(new VarInsnNode(Opcodes.ISTORE, 1));

        // if (!fallFlying && hopCooldown > 0) hopCooldown--;
        LabelNode L_skipDec = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new JumpInsnNode(Opcodes.IFNE, L_skipDec));
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "hopCooldown", "I"));
        il.add(new JumpInsnNode(Opcodes.IFLE, L_skipDec));
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "hopCooldown", "I"));
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new InsnNode(Opcodes.ISUB));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "hopCooldown", "I"));
        il.add(L_skipDec);

        // ===== 状态机 =====
        LabelNode L_elseIf = new LabelNode();
        LabelNode L_afterState = new LabelNode();
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "awaitingRecast", "Z"));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_elseIf));

        // --- if (awaitingRecast) ---
        LabelNode L_waitSkip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_waitSkip)); // !fallFlying -> 继续判断冷却
        // fallFlying 为真: awaitingRecast = false
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "awaitingRecast", "Z"));
        il.add(new JumpInsnNode(Opcodes.GOTO, L_afterState));
        il.add(L_waitSkip);
        // else if (hopCooldown == 0) { recastFlight(player); hopCooldown = 2; }
        LabelNode L_waitEnd = new LabelNode();
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "hopCooldown", "I"));
        il.add(new JumpInsnNode(Opcodes.IFNE, L_waitEnd));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CLS, "recastFlight", "(L" + LP + ";)V", false));
        il.add(new InsnNode(Opcodes.ICONST_2));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "hopCooldown", "I"));
        il.add(L_waitEnd);
        il.add(new JumpInsnNode(Opcodes.GOTO, L_afterState));

        // --- else if (!fallFlying && previousFallFlying) ---
        il.add(L_elseIf);
        LabelNode L_landSkip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new JumpInsnNode(Opcodes.IFNE, L_landSkip));
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "previousFallFlying", "Z"));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_landSkip));
        // 落地边沿: awaitingRecast = true; hopCooldown = 20
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "awaitingRecast", "Z"));
        il.add(new IntInsnNode(Opcodes.BIPUSH, 20));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "hopCooldown", "I"));
        il.add(L_landSkip);

        il.add(L_afterState);

        // ===== if (fallFlying) lastFlyingMovement = player.getDeltaMovement(); =====
        LabelNode L_noMove = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_noMove));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LP, "getDeltaMovement", "()L" + VEC + ";", false));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "lastFlyingMovement", "L" + VEC + ";"));
        il.add(L_noMove);

        // ===== previousFallFlying = fallFlying; return; =====
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "previousFallFlying", "Z"));
        il.add(new InsnNode(Opcodes.RETURN));

        return il;
    }
}
