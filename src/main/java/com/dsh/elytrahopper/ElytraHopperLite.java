package com.dsh.elytrahopper;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Elytra Hopper Lite —— 落地后只重开一次滑翔，带冷却，不会高频切换。
 */
public class ElytraHopperLite implements ClientModInitializer {

    public static final String MOD_ID = "elytra_hopper_lite";

    private static boolean enabled = true;
    /** 距离上次重开滑翔还剩多少 tick */
    private static int cooldown = 0;
    /** 跳跃后等几 tick 再开滑翔（必须离地） */
    private static int pending = 0;

    private static KeyMapping toggleKey;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.elytra_hopper_lite.toggle",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_B,
                "key.categories.elytra_hopper_lite"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(ElytraHopperLite::tick);
    }

    private static void tick(Minecraft mc) {
        if (cooldown > 0) cooldown--;

        if (toggleKey != null && toggleKey.consumeClick()) {
            enabled = !enabled;
            LocalPlayer p = mc.player;
            if (p != null) {
                p.displayClientMessage(Component.literal("Elytra Hopper Lite: " + (enabled ? "ON" : "OFF")), true);
            }
        }

        LocalPlayer player = mc.player;
        if (player == null || !enabled) {
            pending = 0;
            return;
        }

        // 落水收翅（方便游泳）
        if (player.isInWater() && player.isFallFlying()) {
            player.stopFallFlying();
            return;
        }

        // 起跳：在落地状态按下跳跃，且冷却已好
        boolean jumpDown = mc.options.keyJump.isDown();
        if (pending == 0 && cooldown == 0 && jumpDown
                && player.onGround() && hasElytra(player) && !player.isFallFlying()) {
            pending = 2; // 跳起后 2 tick 再开滑翔，此时已离地
        }

        if (pending > 0) {
            pending--;
            if (pending == 0 && cooldown == 0 && !player.onGround()
                    && !player.isFallFlying() && hasElytra(player)) {
                player.startFallFlying();
                cooldown = 20; // 1 秒内绝不再重开
            }
        }
    }

    private static boolean hasElytra(LocalPlayer player) {
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        return !chest.isEmpty() && chest.is(Items.ELYTRA);
    }
}
