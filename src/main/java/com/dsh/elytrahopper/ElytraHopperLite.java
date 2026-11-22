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
 * Elytra Hopper Lite - re-open gliding only once after landing, with a cooldown, no high-frequency switching.
 */
public class ElytraHopperLite implements ClientModInitializer {

    public static final String MOD_ID = "elytra_hopper_lite";

    private static boolean enabled = true;
    /** Ticks remaining until gliding can be re-opened again */
    private static int cooldown = 0;
    /** Ticks to wait after jumping before opening gliding (must be airborne) */
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

        // Close wings in water (easier swimming)
        if (player.isInWater() && player.isFallFlying()) {
            player.stopFallFlying();
            return;
        }

        // Takeoff: jump pressed while on the ground and cooldown is ready
        boolean jumpDown = mc.options.keyJump.isDown();
        if (pending == 0 && cooldown == 0 && jumpDown
                && player.onGround() && hasElytra(player) && !player.isFallFlying()) {
            pending = 2; // open gliding 2 ticks after jumping, once airborne
        }

        if (pending > 0) {
            pending--;
            if (pending == 0 && cooldown == 0 && !player.onGround()
                    && !player.isFallFlying() && hasElytra(player)) {
                player.startFallFlying();
                cooldown = 20; // never re-open within 1 second
            }
        }
    }

    private static boolean hasElytra(LocalPlayer player) {
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        return !chest.isEmpty() && chest.is(Items.ELYTRA);
    }
}
