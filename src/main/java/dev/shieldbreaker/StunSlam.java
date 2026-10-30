package dev.shieldbreaker;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

public class StunSlam implements ClientModInitializer {
    private enum Phase { IDLE, AXE_DONE }
    private Phase phase = Phase.IDLE;
    private int timeout = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.interactionManager == null || mc.currentScreen != null) return;

        int axe = findSlot(p, true), mace = findSlot(p, false);
        if (axe < 0 || mace < 0) { phase = Phase.IDLE; return; }

        PlayerEntity target = null;
        if (mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.ENTITY
                && ((EntityHitResult) mc.crosshairTarget).getEntity() instanceof PlayerEntity t) target = t;

        if (phase == Phase.IDLE) {
            if (target != null && p.fallDistance > 1.5 && target.isBlocking()
                    && p.getAttackCooldownProgress(0f) >= 0.95f) {
                p.getInventory().setSelectedSlot(axe);
                mc.interactionManager.attackEntity(p, target);
                p.swingHand(Hand.MAIN_HAND);
                phase = Phase.AXE_DONE;
                timeout = 6;
            }
        } else {
            if (target != null && !target.isBlocking()) {
                p.getInventory().setSelectedSlot(mace);
                mc.interactionManager.attackEntity(p, target);
                p.swingHand(Hand.MAIN_HAND);
                phase = Phase.IDLE;
            } else if (--timeout <= 0) {
                phase = Phase.IDLE;
            }
        }
    }

    private int findSlot(ClientPlayerEntity p, boolean axe) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (axe ? s.isIn(ItemTags.AXES) : s.isOf(Items.MACE)) return i;
        }
        return -1;
    }
}
