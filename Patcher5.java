import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;

/**
 * v5：三重落地检测（状态 + onGround + 脚下方块）+ 20 tick 冷却
 *
 * 修复 v4 的坑：
 *  - v4 用边沿检测+闩锁，状态被切回时闩锁永久卡死、短飞行边沿丢失卡地面。
 *  - v5 引入 grounded 维度区分"真落地"和"空中被切回"：
 *      grounded = player.onGround() || !level.getBlockState(blockPos.below()).isAir()
 *    只有当 !fallFlying && grounded && 冷却结束 时才 recast。
 *    "空中被服务器切回"时 fallFlying=false 但 grounded=false -> 不触发，杜绝横跳。
 *
 * 26.1.2 client.jar 已确认是 Mojang 官方映射可读名，直接硬编码调用，无需反射。
 *
 * 逻辑：
 *   前置失败/松开跳跃 -> 重置停止
 *   冷却递减
 *   fallFlying = player.isFallFlying()          -> local 1
 *   grounded = player.onGround()
 *              || !player.level().getBlockState(player.blockPosition().below()).isAir()  -> local 2
 *   if (!fallFlying && grounded && hopCooldown==0) { recastFlight; hopCooldown=20 }
 *   if (fallFlying) lastFlyingMovement = getDeltaMovement()
 */
public class Patcher5 {
    static final String CLS = "com/santr/elytrahopper/ElytraHopperClient";
    static final String CFG = "com/santr/elytrahopper/ElytraHopperConfig";
    static final String LP  = "net/minecraft/client/player/LocalPlayer";
    static final String ENT = "net/minecraft/world/entity/Entity";
    static final String LVL = "net/minecraft/world/level/Level";
    static final String BP  = "net/minecraft/core/BlockPos";
    static final String BS  = "net/minecraft/world/level/block/state/BlockState";
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

        boolean hasField = cn.fields.stream().anyMatch(f -> f.name.equals("hopCooldown"));
        if (!hasField) {
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
        System.out.println("补丁完成 major=" + realMajor + " (v5 三重检测版)");
    }

    static InsnList buildBody() {
        InsnList il = new InsnList();
        LabelNode L_reset = new LabelNode();
        LabelNode L_guardPass = new LabelNode();
        LabelNode L_skipDec = new LabelNode();
        LabelNode L_groundComputed = new LabelNode();
        LabelNode L_groundTrue = new LabelNode();
        LabelNode L_groundEnd = new LabelNode();
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

        // ===== grounded 计算 -> local 2 =====
        // grounded = onGround() || !level().getBlockState(blockPosition().below()).isAir()
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENT, "onGround", "()Z", false));
        il.add(new JumpInsnNode(Opcodes.IFNE, L_groundTrue)); // onGround=true -> grounded=true

        // onGround=false, 再查脚下方块：!isAir() -> grounded=true
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENT, "level", "()" + "L" + LVL + ";", false));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENT, "blockPosition", "()L" + BP + ";", false));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BP, "below", "()L" + BP + ";", false));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LVL, "getBlockState", "(L" + BP + ";)L" + BS + ";", false));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BS, "isAir", "()Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_groundTrue)); // isAir=false(有方块) -> grounded=true

        // 两处都为 false -> grounded=false
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new VarInsnNode(Opcodes.ISTORE, 2));
        il.add(new JumpInsnNode(Opcodes.GOTO, L_groundEnd));

        il.add(L_groundTrue);
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new VarInsnNode(Opcodes.ISTORE, 2));

        il.add(L_groundEnd);

        // ===== if (!fallFlying && grounded && hopCooldown==0) recast =====
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new JumpInsnNode(Opcodes.IFNE, L_noRecast));  // 飞行中 -> 不重开
        il.add(new VarInsnNode(Opcodes.ILOAD, 2));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_noRecast));  // 未落地 -> 不重开
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "hopCooldown", "I"));
        il.add(new JumpInsnNode(Opcodes.IFNE, L_noRecast));  // 冷却中 -> 不重开
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CLS, "recastFlight", "(" + LP_DESC + ")V", false));
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
