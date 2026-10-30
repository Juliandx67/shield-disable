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
import net.minecraft.util.math.Vec3d;

public class StunSlam implements ClientModInitializer {
    private static final double MAX_DIST = 3.8;   // Reichweite zum Gegner
    private static final double MIN_AIM = 0.85;   // 1.0 = exakt zielen, kleiner = großzügiger

    private boolean axeDone = false;
    private PlayerEntity locked = null;
    private int timeout = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null || mc.currentScreen != null) return;

        int axe = findSlot(p, true), mace = findSlot(p, false);
        if (axe < 0 || mace < 0) { reset(); return; }

        if (!axeDone) {
            if (p.fallDistance > 1.5 && p.getAttackCooldownProgress(0f) >= 0.95f) {
                PlayerEntity t = findTarget(mc, p);
                if (t != null) {
                    p.getInventory().setSelectedSlot(axe);
                    mc.interactionManager.attackEntity(p, t);
                    p.swingHand(Hand.MAIN_HAND);
                    axeDone = true;
                    locked = t;
                    timeout = 5;
                }
            }
        } else {
            if (locked == null || !locked.isAlive() || --timeout <= 0) { reset(); return; }
            if (!locked.isBlocking()) {
                p.getInventory().setSelectedSlot(mace);
                mc.interactionManager.attackEntity(p, locked);
                p.swingHand(Hand.MAIN_HAND);
                reset();
            }
        }
    }

    private PlayerEntity findTarget(MinecraftClient mc, ClientPlayerEntity p) {
        Vec3d eye = p.getEyePos();
        Vec3d look = p.getRotationVec(1f);
        PlayerEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (PlayerEntity o : mc.world.getPlayers()) {
            if (o == p || !o.isBlocking()) continue;
            Vec3d to = o.getBoundingBox().getCenter().subtract(eye);
            double d = to.length();
            if (d > MAX_DIST || d < 0.001) continue;
            if (to.normalize().dotProduct(look) < MIN_AIM) continue;
            if (d < bestDist) { bestDist = d; best = o; }
        }
        return best;
    }

    private void reset() { axeDone = false; locked = null; timeout = 0; }

    private int findSlot(ClientPlayerEntity p, boolean axe) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (axe ? s.isIn(ItemTags.AXES) : s.isOf(Items.MACE)) return i;
        }
        return -1;
    }
}
