/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.blaze3d.platform.InputConstants
 *  com.mojang.blaze3d.platform.InputConstants$Type
 *  net.fabricmc.api.ClientModInitializer
 *  net.fabricmc.api.EnvType
 *  net.fabricmc.api.Environment
 *  net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
 *  net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
 *  net.minecraft.ChatFormatting
 *  net.minecraft.client.KeyMapping
 *  net.minecraft.client.KeyMapping$Category
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.player.LocalPlayer
 *  net.minecraft.network.chat.Component
 *  net.minecraft.network.protocol.Packet
 *  net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
 *  net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket$Action
 *  net.minecraft.resources.Identifier
 *  net.minecraft.world.effect.MobEffects
 *  net.minecraft.world.entity.Entity
 *  net.minecraft.world.entity.EquipmentSlot
 *  net.minecraft.world.entity.LivingEntity
 *  net.minecraft.world.item.ItemStack
 *  net.minecraft.world.phys.Vec3
 */
package com.santr.elytrahopper;

import com.mojang.blaze3d.platform.InputConstants;
import com.santr.elytrahopper.ElytraHopperConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

@Environment(value=EnvType.CLIENT)
public final class ElytraHopperClient
implements ClientModInitializer {
    public static final String MOD_ID = "elytra-hopper";
    public static ElytraHopperConfig config = ElytraHopperConfig.load();
    private static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register((Identifier)Identifier.fromNamespaceAndPath((String)"elytra-hopper", (String)"controls"));
    private static KeyMapping toggleEnabledKey;
    private static KeyMapping toggleRecastKey;
    private static KeyMapping toggleCloseInWaterKey;
    private static KeyMapping toggleJumpCooldownKey;
    private static final double MAX_CONSERVED_HORIZONTAL_SPEED = 2.0;
    private static boolean previousFallFlying;
    private static boolean awaitingRecast;
    private static Vec3 lastFlyingMovement;

    public void onInitializeClient() {
        toggleEnabledKey = ElytraHopperClient.registerUnboundKey("key.elytra-hopper.toggle_enabled");
        toggleRecastKey = ElytraHopperClient.registerUnboundKey("key.elytra-hopper.toggle_recast");
        toggleCloseInWaterKey = ElytraHopperClient.registerUnboundKey("key.elytra-hopper.toggle_close_in_water");
        toggleJumpCooldownKey = ElytraHopperClient.registerUnboundKey("key.elytra-hopper.toggle_jump_cooldown");
        ClientTickEvents.END_CLIENT_TICK.register(ElytraHopperClient::tick);
    }

    private static void tick(Minecraft client) {
        ElytraHopperClient.handleKeyToggles(client.player);
        LocalPlayer player = client.player;
        if (player == null || !ElytraHopperClient.config.enabled) {
            previousFallFlying = false;
            awaitingRecast = false;
            lastFlyingMovement = Vec3.ZERO;
            return;
        }
        if (ElytraHopperClient.config.closeWingsInWater && player.isFallFlying() && player.isInWater()) {
            player.stopFallFlying();
        }
    }

    public static void recastIfStoppedFallFlying(LocalPlayer player) {
        if (player == null || player != Minecraft.getInstance().player || !ElytraHopperClient.config.enabled || !ElytraHopperClient.config.recastFlight) {
            previousFallFlying = false;
            awaitingRecast = false;
            lastFlyingMovement = Vec3.ZERO;
            return;
        }
        boolean fallFlying = player.isFallFlying();
        if (awaitingRecast) {
            if (fallFlying) {
                awaitingRecast = false;
            }
        } else if (!fallFlying && previousFallFlying) {
            awaitingRecast = ElytraHopperClient.canRecastFlight(player);
            ElytraHopperClient.recastFlight(player);
        }
        if (fallFlying) {
            lastFlyingMovement = player.getDeltaMovement();
        }
        previousFallFlying = fallFlying;
    }

    public static boolean shouldShortenJumpDelay(LocalPlayer player) {
        return ElytraHopperClient.config.enabled && ElytraHopperClient.config.jumpCooldown && player != null && player == Minecraft.getInstance().player;
    }

    public static boolean hasUsableElytra(LocalPlayer player) {
        ItemStack chestStack = player.getItemBySlot(EquipmentSlot.CHEST);
        return LivingEntity.canGlideUsing((ItemStack)chestStack, (EquipmentSlot)EquipmentSlot.CHEST);
    }

    private static boolean canRecastFlight(LocalPlayer player) {
        return player != null && player == Minecraft.getInstance().player && player.input.keyPresses.jump() && !player.getAbilities().flying && !player.isPassenger() && !player.isInWater() && !player.onClimbable() && !player.hasEffect(MobEffects.LEVITATION) && ElytraHopperClient.hasUsableElytra(player);
    }

    private static void recastFlight(LocalPlayer player) {
        if (!ElytraHopperClient.canRecastFlight(player)) {
            return;
        }
        player.startFallFlying();
        ElytraHopperClient.conserveHorizontalMomentum(player);
        player.connection.send((Packet)new ServerboundPlayerCommandPacket((Entity)player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
    }

    private static void conserveHorizontalMomentum(LocalPlayer player) {
        Vec3 currentMovement = player.getDeltaMovement();
        double currentHorizontal = currentMovement.horizontalDistanceSqr();
        Vec3 conservedHorizontal = new Vec3(ElytraHopperClient.lastFlyingMovement.x, 0.0, ElytraHopperClient.lastFlyingMovement.z);
        double conservedSpeed = conservedHorizontal.length();
        if (conservedSpeed > 2.0) {
            conservedHorizontal = conservedHorizontal.normalize().scale(2.0);
        }
        if (conservedHorizontal.lengthSqr() > currentHorizontal) {
            player.setDeltaMovement(conservedHorizontal.x, currentMovement.y, conservedHorizontal.z);
        }
    }

    private static KeyMapping registerUnboundKey(String translationKey) {
        return KeyMappingHelper.registerKeyMapping((KeyMapping)new KeyMapping(translationKey, InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), KEY_CATEGORY));
    }

    private static void handleKeyToggles(LocalPlayer player) {
        boolean changed = false;
        while (toggleEnabledKey.consumeClick()) {
            ElytraHopperClient.config.enabled = !ElytraHopperClient.config.enabled;
            ElytraHopperClient.showToggleMessage(player, "Elytra Hopper", ElytraHopperClient.config.enabled);
            changed = true;
        }
        while (toggleRecastKey.consumeClick()) {
            ElytraHopperClient.config.recastFlight = !ElytraHopperClient.config.recastFlight;
            ElytraHopperClient.showToggleMessage(player, "Hopping", ElytraHopperClient.config.recastFlight);
            changed = true;
        }
        while (toggleCloseInWaterKey.consumeClick()) {
            ElytraHopperClient.config.closeWingsInWater = !ElytraHopperClient.config.closeWingsInWater;
            ElytraHopperClient.showToggleMessage(player, "Close in Water", ElytraHopperClient.config.closeWingsInWater);
            changed = true;
        }
        while (toggleJumpCooldownKey.consumeClick()) {
            ElytraHopperClient.config.jumpCooldown = !ElytraHopperClient.config.jumpCooldown;
            ElytraHopperClient.showToggleMessage(player, "Jump Cooldown", ElytraHopperClient.config.jumpCooldown);
            changed = true;
        }
        if (changed) {
            config.save();
        }
    }

    private static void showToggleMessage(LocalPlayer player, String feature, boolean enabled) {
        if (player == null) {
            return;
        }
        player.sendOverlayMessage((Component)Component.literal((String)(feature + " ")).append((Component)Component.literal((String)(enabled ? "ON" : "OFF")).withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED)));
    }

    static {
        lastFlyingMovement = Vec3.ZERO;
    }
}
