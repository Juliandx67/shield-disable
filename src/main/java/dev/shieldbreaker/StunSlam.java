package dev.shieldbreaker;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

/**
 * Stun Slam. Du fällst und zielst auf einen Gegner mit Schild:
 *   - mit Windcharge in der Hand: Windcharge werfen, dann Axt, dann Mace
 *   - sonst: Axt, dann Mace
 */
public class StunSlam implements ClientModInitializer {
    private static final double MAX_DIST = 3.8;   // Reichweite zum Gegner
    private static final double MIN_AIM = 0.85;   // 1.0 = exakt zielen, kleiner = großzügiger

    // Wohin die Windcharge fliegt: 0 = unter dich (Boost), 1 = auf den Gegner, 2 = Boden unter dem Gegner
    private static final int WIND_MODE = 2;

    private enum Phase { IDLE, AXE, MACE }

    public static boolean busy = false;

    private Phase phase = Phase.IDLE;
    private PlayerEntity locked = null;
    private int timeout = 0;
    private float origYaw = 0, origPitch = 0;
    private boolean viewSaved = false;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null) return;
        if (StunWeb.busy || PearlCatch.busy) return;

        switch (phase) {
            case IDLE -> {
                if (mc.currentScreen != null || p.fallDistance <= 1.5) return;
                int axe = findAxe(p), mace = findMace(p);
                if (axe < 0 || mace < 0) return;

                PlayerEntity t = findTarget(mc, p);
                if (t == null) return;

                locked = t;
                busy = true;
                origYaw = p.getYaw();
                origPitch = p.getPitch();
                viewSaved = true;

                if (p.getMainHandStack().isOf(Items.WIND_CHARGE)) {
                    // Schritt 1: Windcharge werfen
                    aim(p, t);
                    mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
                    p.swingHand(Hand.MAIN_HAND);
                    phase = Phase.AXE;      // nächster Tick: Axt
                    timeout = 8;
                } else {
                    hitAxe(mc, p);
                }
            }
            case AXE -> {
                if (!stillValid(p) || --timeout <= 0) { finish(p); return; }
                hitAxe(mc, p);
            }
            case MACE -> {
                if (!stillValid(p) || --timeout <= 0) { finish(p); return; }
                if (!locked.isBlocking()) {          // Schild ist kaputt
                    int mace = findMace(p);
                    if (mace >= 0) {
                        p.getInventory().setSelectedSlot(mace);
                        mc.interactionManager.attackEntity(p, locked);
                        p.swingHand(Hand.MAIN_HAND);
                    }
                    finish(p);
                }
            }
        }
    }

    // Schritt 2: Axt auswählen und zuschlagen
    private void hitAxe(MinecraftClient mc, ClientPlayerEntity p) {
        int axe = findAxe(p);
        if (axe < 0) { finish(p); return; }
        p.getInventory().setSelectedSlot(axe);
        mc.interactionManager.attackEntity(p, locked);
        p.swingHand(Hand.MAIN_HAND);
        phase = Phase.MACE;
        timeout = 6;
    }

    // Blick für den Windcharge-Wurf setzen
    private void aim(ClientPlayerEntity p, PlayerEntity t) {
        if (WIND_MODE == 0) { p.setPitch(90f); return; }   // senkrecht nach unten
        Vec3d target = WIND_MODE == 1
                ? t.getBoundingBox().getCenter()
                : new Vec3d(t.getX(), t.getY() - 0.2, t.getZ());
        Vec3d eye = p.getEyePos();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        p.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        p.setPitch((float) -Math.toDegrees(Math.atan2(dy, flat)));
    }

    private boolean stillValid(ClientPlayerEntity p) {
        return locked != null && locked.isAlive() && p.distanceTo(locked) <= MAX_DIST + 2.0;
    }

    private void finish(ClientPlayerEntity p) {
        if (viewSaved) { p.setYaw(origYaw); p.setPitch(origPitch); viewSaved = false; }
        busy = false;
        phase = Phase.IDLE;
        locked = null;
        timeout = 0;
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

    private int findAxe(ClientPlayerEntity p) {
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getStack(i).isIn(ItemTags.AXES)) return i;
        }
        return -1;
    }

    private int findMace(ClientPlayerEntity p) {
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getStack(i).isOf(Items.MACE)) return i;
        }
        return -1;
    }
}
