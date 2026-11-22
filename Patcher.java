import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.util.List;

/**
 * 给 ElytraHopperClient 打字节码补丁：最小侵入式插入。
 * 1) 新增 static int hopCooldown 字段
 * 2) recastIfStoppedFallFlying 开头递减冷却
 * 3) 只在"刚落地"分支(!fallFlying && previousFallFlying)处，
 *    若冷却>0则跳过 recastFlight；成功 recast 后设 hopCooldown=20。
 * 语义严格满足：落地才重开一次，飞行中不切换，1秒冷却防反作弊。
 */
public class Patcher {
    static final String CLS = "com/santr/elytrahopper/ElytraHopperClient";

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
            if (mn.name.equals("recastIfStoppedFallFlying")) {
                patch(mn);
                patched = true;
            }
        }
        if (!patched) throw new RuntimeException("目标方法未找到");

        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
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
        System.out.println("补丁完成 major=" + (realMajor & 0xFF));
    }

    static void patch(MethodNode mn) {
        InsnList il = mn.instructions;

        // === 1) 方法开头插入冷却递减: if(hopCooldown>0) hopCooldown-- ===
        AbstractInsnNode first = il.getFirst();
        InsnList dec = new InsnList();
        LabelNode skipDec = new LabelNode();
        dec.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "hopCooldown", "I"));
        dec.add(new JumpInsnNode(Opcodes.IFLE, skipDec));
        dec.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "hopCooldown", "I"));
        dec.add(new InsnNode(Opcodes.ICONST_1));
        dec.add(new InsnNode(Opcodes.ISUB));
        dec.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "hopCooldown", "I"));
        dec.add(skipDec);
        il.insert(first, dec);

        // === 2) 找到 recastFlight 调用，在其前面插入冷却守卫 ===
        AbstractInsnNode recastCall = null;
        for (AbstractInsnNode insn = il.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode m = (MethodInsnNode) insn;
                if (m.name.equals("recastFlight") && m.owner.equals(CLS)) {
                    recastCall = insn;
                    break;
                }
            }
        }
        if (recastCall == null) throw new RuntimeException("recastFlight 调用未找到");

        // recastFlight 前面是 aload_0 (offset 86)。我们要同时跳过 aload_0 和调用。
        AbstractInsnNode aloadBefore = recastCall.getPrevious();
        if (aloadBefore == null || aloadBefore.getOpcode() != Opcodes.ALOAD) {
            throw new RuntimeException("recastFlight 前不是 ALOAD");
        }

        // 跳到 this 分支之后的下一条指令（原 offset 90: iload_1）
        AbstractInsnNode afterBranch = recastCall.getNext();

        InsnList guard = new InsnList();
        LabelNode doRecast = new LabelNode();
        // if (hopCooldown > 0) 跳转到跳过区；==0 则继续原 aload/recast
        guard.add(new FieldInsnNode(Opcodes.GETSTATIC, CLS, "hopCooldown", "I"));
        guard.add(new JumpInsnNode(Opcodes.IFEQ, doRecast));
        // 冷却中：跳到分支之后（跳过 recastFlight）；此时栈为空，安全
        // 用一个 GOTO 标签占位，稍后重定位
        LabelNode skipToAfter = new LabelNode();
        guard.add(new JumpInsnNode(Opcodes.GOTO, skipToAfter));
        guard.add(doRecast);
        il.insertBefore(aloadBefore, guard);

        // recastFlight 调用后插入 hopCooldown=20
        InsnList setCd = new InsnList();
        setCd.add(new IntInsnNode(Opcodes.BIPUSH, 20));
        setCd.add(new FieldInsnNode(Opcodes.PUTSTATIC, CLS, "hopCooldown", "I"));
        il.insert(recastCall, setCd);

        // 在 afterBranch 前插入 skipToAfter 标签（冷却跳过落点）
        il.insertBefore(afterBranch, skipToAfter);

        // 清理可能的语法问题：验证指令序列
        System.out.println("插入完成: 字段+hopCooldown递减+recast守卫+20tick设置");
    }
}
