import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;

/**
 * v6: fix the v5 airborne flapping bug
 *
 * v5 fatal error: grounded = onGround() || !blockBelow.isAir()
 *    -> while gliding in the air the block below is the ground, so "block below non-air" is always true
 *    -> grounded is always true
 *    -> every time the server flips fallFlying back to false it is misjudged as "landed" -> open wings -> flipped back -> flap
 *
 * v6 fix: use only onGround() as the landing criterion (real ground contact), and restore the original
 *    edge detection !fallFlying && previousFallFlying + awaitingRecast latch.
 *
 * Logic (per tick):
 *   precondition failed / jump released -> reset
 *   decrement cooldown
 *   fallFlying = isFallFlying()          -> local 1
 *   onGround   = onGround()             -> local 2   (only landing criterion)
 *
 *   if (awaitingRecast) {                // latch state
 *       if (fallFlying) awaitingRecast = false;   // takeoff confirmed -> unlock
 *       // otherwise keep the latch, never retry (prevent frantic re-opening after being flipped back)
 *   } else if (!fallFlying && previousFallFlying && onGround && hopCooldown==0) {
 *       recastFlight; awaitingRecast=true; hopCooldown=20;   // real landing edge -> recast once
 *   }
 *   if (fallFlying) lastFlyingMovement = getDeltaMovement()
 *   previousFallFlying = fallFlying
 *
 * API signatures verified one by one in the 26.1.2 client.jar, hard-coded directly.
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
        tmp[6] = 0; tmp[7] = 61; // temporarily lower to Java17 so ASM can read it

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
        if (!patched) throw new RuntimeException("target method not found");

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
        System.out.println("patch complete major=" + realMajor + " (v6 edge+onGround+latch)");
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

        // ===== preconditions =====
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

        // ===== hold-jump master switch =====
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CLS, "canRecastFlight", "(" + LP_DESC + ")Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_reset));

        // ===== decrement cooldown =====
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

        // ===== onGround -> local 2 (only landing criterion, block-below removed) =====
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LP, "onGround", "()Z", false));
        il.add(new VarInsnNode(Opcodes.ISTORE, 2));

        // ===== latch state check =====
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "awaitingRecast", "Z"));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_notLocked));

        // latched: only fallFlying=true unlocks, otherwise keep the latch and never retry
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_lockedStay));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "awaitingRecast", "Z"));
        il.add(L_lockedStay);
        il.add(new JumpInsnNode(Opcodes.GOTO, L_noRecast));

        // not latched: landing edge + onGround + cooldown over -> recast once
        il.add(L_notLocked);
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new JumpInsnNode(Opcodes.IFNE, L_noRecast));               // airborne -> no recast
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "previousFallFlying", "Z"));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_noRecast));               // no landing edge -> no recast
        il.add(new VarInsnNode(Opcodes.ILOAD, 2));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_noRecast));               // not really landed -> no recast
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "hopCooldown", "I"));
        il.add(new JumpInsnNode(Opcodes.IFNE, L_noRecast));               // cooling down -> no recast
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CLS, "recastFlight", "(" + LP_DESC + ")V", false));
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "awaitingRecast", "Z"));
        il.add(new IntInsnNode(Opcodes.BIPUSH, 20));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "hopCooldown", "I"));

        il.add(L_noRecast);

        // ===== momentum conservation: if (fallFlying) lastFlyingMovement = getDeltaMovement() =====
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new JumpInsnNode(Opcodes.IFEQ, L_skipMove));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LP, "getDeltaMovement", "()" + VEC_DESC, false));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "lastFlyingMovement", VEC_DESC));
        il.add(L_skipMove);

        // ===== state update =====
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "previousFallFlying", "Z"));
        il.add(new InsnNode(Opcodes.RETURN));

        return il;
    }
}
