package dev.shieldbreaker;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

public class StunWeb implements ClientModInitializer {
    private static final double MAX_DIST = 4.0;
    private static final double MIN_AIM = 0.80;
    private static final double REACH = 4.5;
    private static final int CLICK_DELAY = 1; // Ticks zwischen Klick 1 und Klick 2

    private enum Phase { IDLE, HIT2, WEB }

    public static boolean busy = false; // true, solange das Macro läuft

    private static KeyBinding key;
    private Phase phase = Phase.IDLE;
    private PlayerEntity locked = null;
    private int swordSlot = -1;
    private int delay = 0;
    private int timeout = 0;

    @Override
    public void onInitializeClient() {
        key = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.shieldbreaker.stunweb",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_3,
                KeyBinding.Category.create(Identifier.of("shieldbreaker", "main"))));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null) return;

        boolean pressed = false;
        while (key.wasPressed()) pressed = true;

        switch (phase) {
            case IDLE -> {
                if (!pressed || mc.currentScreen != null) return;
                if (findSlot(p, true) < 0 || findSlot(p, false) < 0) return;
                int sword = findSwordSlot(p);
                if (sword < 0) return;
                PlayerEntity t = findTarget(mc, p);
                if (t == null) return;

                swordSlot = sword;
                p.getInventory().setSelectedSlot(sword);
                busy = true;
                attack(mc, p, t);            // Klick 1: Schwert
                locked = t;
                delay = CLICK_DELAY;
                phase = Phase.HIT2;
            }
            case HIT2 -> {
                if (!valid(p)) { abort(p); return; }
                if (--delay > 0) return;
                int axe = findSlot(p, true);
                if (axe < 0) { abort(p); return; }
                p.getInventory().setSelectedSlot(axe);   // Attribute Swap
                attack(mc, p, locked);                   // Klick 2: Axt
                phase = Phase.WEB;
                timeout = 6;
            }
            case WEB -> {
                if (!valid(p) || --timeout <= 0) { abort(p); return; }
                BlockPos feet = locked.getBlockPos();
                if (mc.world.getBlockState(feet).isOf(Blocks.COBWEB)) { abort(p); return; }

                int web = findSlot(p, false);
                if (web < 0) { abort(p); return; }
                p.getInventory().setSelectedSlot(web);
                placeWeb(mc, p, feet);
            }
        }
    }

    private void attack(MinecraftClient mc, ClientPlayerEntity p, PlayerEntity t) {
        mc.interactionManager.attackEntity(p, t);
        p.swingHand(Hand.MAIN_HAND);
    }

    private void placeWeb(MinecraftClient mc, ClientPlayerEntity p, BlockPos feet) {
        if (!mc.world.getBlockState(feet).isReplaceable()) return;

        for (Direction d : Direction.values()) {
            BlockPos support = feet.offset(d);
            if (mc.world.getBlockState(support).isReplaceable()) continue;
            if (p.getEyePos().distanceTo(Vec3d.ofCenter(support)) > REACH) continue;

            Direction face = d.getOpposite();
            Vec3d hitPos = Vec3d.ofCenter(support).add(
                    face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
            BlockHitResult hit = new BlockHitResult(hitPos, face, support, false);
            mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, hit);
            p.swingHand(Hand.MAIN_HAND);
            return;
        }
    }

    private boolean valid(ClientPlayerEntity p) {
        return locked != null && locked.isAlive() && p.distanceTo(locked) <= MAX_DIST + 0.5;
    }

    private PlayerEntity findTarget(MinecraftClient mc, ClientPlayerEntity p) {
        Vec3d eye = p.getEyePos();
        Vec3d look = p.getRotationVec(1f);
        PlayerEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (PlayerEntity o : mc.world.getPlayers()) {
            if (o == p) continue;
            Vec3d to = o.getBoundingBox().getCenter().subtract(eye);
            double dist = to.length();
            if (dist > MAX_DIST || dist < 0.001) continue;
            if (to.normalize().dotProduct(look) < MIN_AIM) continue;
            if (dist < bestDist) { bestDist = dist; best = o; }
        }
        return best;
    }

    private void abort(ClientPlayerEntity p) {
        if (swordSlot >= 0) p.getInventory().setSelectedSlot(swordSlot);
        phase = Phase.IDLE;
        busy = false;
        locked = null;
        swordSlot = -1;
        delay = 0;
        timeout = 0;
    }

    private int findSwordSlot(ClientPlayerEntity p) {
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getStack(i).isIn(ItemTags.SWORDS)) return i;
        }
        return -1;
    }

    private int findSlot(ClientPlayerEntity p, boolean axe) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (axe ? s.isIn(ItemTags.AXES) : s.isOf(Items.COBWEB)) return i;
        }
        return -1;
    }
}
