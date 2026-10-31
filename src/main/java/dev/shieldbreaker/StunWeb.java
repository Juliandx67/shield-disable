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
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

public class StunWeb implements ClientModInitializer {
    private static final double MAX_DIST = 4.0;
    private static final double MIN_AIM = 0.80;
    private static final double REACH = 4.5;
    private static final int CLICK_DELAY = 1;      // Ticks zwischen Schwert- und Axt-Klick (nur bei Schild)
    private static final int WEB_TIMEOUT = 12;     // so lange wird versucht, den Gegner einzuwebben
    private static final int MAX_WEBS = 2;         // so viele Webs werden maximal gesetzt
    private static final boolean INSTANT = true;   // erstes Web im selben Tick wie der letzte Schlag
    private static final int PATH_TICKS = 6;       // so weit wird die Flugbahn vorausberechnet
    private static final int[] ORDER = {2, 1, 3, 0, 4, 5, 6}; // in dieser Reihenfolge werden Bahn-Blöcke probiert

    private enum Phase { IDLE, HIT2, WEB }

    public static boolean busy = false; // true, solange das Macro läuft

    private static KeyBinding key;
    private Phase phase = Phase.IDLE;
    private PlayerEntity locked = null;
    private boolean stun = false;   // true = Schild war oben -> Axt-Swap nötig
    private int returnSlot = -1;
    private int delay = 0;
    private int timeout = 0;
    private int placed = 0;
    private float origYaw = 0, origPitch = 0;
    private boolean viewSaved = false;

    private final Map<Integer, Vec3d> lastPos = new HashMap<>();
    private final Map<Integer, Vec3d> vel = new HashMap<>();

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

        trackPlayers(mc, p);

        boolean pressed = false;
        while (key.wasPressed()) pressed = true;

        switch (phase) {
            case IDLE -> {
                if (!pressed || mc.currentScreen != null) return;
                if (findSlot(p, false) < 0) return;          // Cobweb ist Pflicht

                PlayerEntity t = findTarget(mc, p);
                if (t == null) return;

                busy = true;
                locked = t;
                placed = 0;
                stun = t.isBlocking() && findSlot(p, true) >= 0;

                // Klick 1: immer, mit dem Schwert (oder dem Item in der Hand, falls kein Schwert da ist)
                int sword = findSwordSlot(p);
                if (sword >= 0) {
                    returnSlot = sword;
                    p.getInventory().setSelectedSlot(sword);
                } else {
                    returnSlot = p.getInventory().getSelectedSlot();
                }
                attack(mc, p, t);

                if (stun) {
                    delay = CLICK_DELAY;
                    phase = Phase.HIT2;
                } else {
                    doSecond(mc, p);   // ohne Schild sofort weiter zum Web
                }
            }
            case HIT2 -> {
                if (!valid(p)) { abort(p); return; }
                if (--delay > 0) return;
                doSecond(mc, p);
            }
            case WEB -> {
                if (!valid(p) || --timeout <= 0) { abort(p); return; }
                if (isWebbed(mc, locked)) { abort(p); return; }   // Gegner steckt im Web
                if (placed >= MAX_WEBS) { abort(p); return; }
                tryPlace(mc, p);
            }
        }
    }

    // Zweiter Schritt: bei Schild Axt-Swap + Schlag, dann Blick nach unten und erstes Web
    private void doSecond(MinecraftClient mc, ClientPlayerEntity p) {
        if (stun) {
            int axe = findSlot(p, true);
            if (axe < 0) { abort(p); return; }
            p.getInventory().setSelectedSlot(axe);   // Attribute Swap
            attack(mc, p, locked);                   // Klick 2: Axt, zerstört den Schild
        }
        aimDown(mc, p);
        phase = Phase.WEB;
        timeout = WEB_TIMEOUT;
        if (INSTANT) tryPlace(mc, p);
    }

    private void attack(MinecraftClient mc, ClientPlayerEntity p, PlayerEntity t) {
        mc.interactionManager.attackEntity(p, t);
        p.swingHand(Hand.MAIN_HAND);
    }

    // Setzt ein Web auf die vorhergesagte Position des Gegners
    private void tryPlace(MinecraftClient mc, ClientPlayerEntity p) {
        if (placed >= MAX_WEBS) return;
        BlockHitResult h = findBestPlacement(mc, p);
        if (h == null) return;
        int web = findSlot(p, false);
        if (web < 0) return;
        lookAt(p, h.getPos());
        p.getInventory().setSelectedSlot(web);
        mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, h);
        p.swingHand(Hand.MAIN_HAND);
        placed++;
    }

    // ---------- Bewegung verfolgen und Flugbahn vorhersagen ----------

    private void trackPlayers(MinecraftClient mc, ClientPlayerEntity p) {
        if (lastPos.size() > 200) { lastPos.clear(); vel.clear(); }
        for (PlayerEntity o : mc.world.getPlayers()) {
            if (o == p) continue;
            Vec3d now = new Vec3d(o.getX(), o.getY(), o.getZ());
            Vec3d prev = lastPos.get(o.getId());
            if (prev != null) {
                Vec3d v = now.subtract(prev);
                if (v.length() > 1.5) v = Vec3d.ZERO; // Teleport ignorieren
                vel.put(o.getId(), v);
            }
            lastPos.put(o.getId(), now);
        }
    }

    private boolean blocked(MinecraftClient mc, double x, double y, double z) {
        BlockPos bp = BlockPos.ofFloored(x, y, z);
        return !mc.world.getBlockState(bp).getCollisionShape(mc.world, bp).isEmpty();
    }

    // Simuliert Tick für Tick, wo der Gegner (Fußblock) sein wird
    private BlockPos[] predictPath(MinecraftClient mc, PlayerEntity e) {
        Vec3d v = vel.getOrDefault(e.getId(), Vec3d.ZERO);
        double x = e.getX(), y = e.getY(), z = e.getZ();
        double vx = v.x, vy = v.y, vz = v.z;
        boolean ground = e.isOnGround();
        if (ground && vy < 0) vy = 0;

        BlockPos[] path = new BlockPos[PATH_TICKS + 1];
        for (int i = 0; i <= PATH_TICKS; i++) {
            path[i] = BlockPos.ofFloored(x, y + 0.05, z);

            // waagerecht bewegen, an Wänden stoppen
            double nx = x + vx, nz = z + vz;
            if (blocked(mc, nx, y + 0.1, nz) || blocked(mc, nx, y + 1.1, nz)) {
                nx = x; nz = z; vx = 0; vz = 0;
            }
            x = nx; z = nz;

            // senkrecht bewegen, auf Blöcken landen
            double ny = y + vy;
            if (vy <= 0 && blocked(mc, x, ny, z)) {
                y = Math.floor(ny) + 1.0;
                vy = 0;
                ground = true;
            } else {
                y = ny;
                if (ground && !blocked(mc, x, y - 0.1, z)) ground = false; // Kante verlassen
            }

            // Physik des nächsten Ticks
            vy = ground ? 0 : (vy - 0.08) * 0.98;
            double f = ground ? 0.546 : 0.91;
            vx *= f;
            vz *= f;
        }
        return path;
    }

    // Probiert Blöcke der Flugbahn durch und nimmt den ersten mit einer Fläche zum Anklicken
    private BlockHitResult findBestPlacement(MinecraftClient mc, ClientPlayerEntity p) {
        BlockPos[] path = predictPath(mc, locked);
        for (int idx : ORDER) {
            BlockHitResult h = findPlacement(mc, p, path[idx]);
            if (h != null) return h;
        }
        return null;
    }

    // Sucht eine feste Fläche neben dem Ziel-Block, auf die das Web gesetzt werden kann
    private BlockHitResult findPlacement(MinecraftClient mc, ClientPlayerEntity p, BlockPos target) {
        if (!mc.world.getBlockState(target).isReplaceable()) return null;
        if (new Box(target).intersects(p.getBoundingBox())) return null; // nie sich selbst einwebben

        for (Direction d : Direction.values()) { // zuerst unten, dann oben, dann Seiten
            BlockPos support = target.offset(d);
            if (mc.world.getBlockState(support).isReplaceable()) continue;
            if (p.getEyePos().distanceTo(Vec3d.ofCenter(support)) > REACH) continue;

            Direction face = d.getOpposite();
            Vec3d hitPos = Vec3d.ofCenter(support).add(
                    face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
            return new BlockHitResult(hitPos, face, support, false);
        }
        return null;
    }

    // Berührt die Hitbox des Gegners irgendein Cobweb?
    private boolean isWebbed(MinecraftClient mc, PlayerEntity e) {
        Box b = e.getBoundingBox();
        int x0 = (int) Math.floor(b.minX + 0.01), x1 = (int) Math.floor(b.maxX - 0.01);
        int y0 = (int) Math.floor(b.minY + 0.01), y1 = (int) Math.floor(b.maxY - 0.01);
        int z0 = (int) Math.floor(b.minZ + 0.01), z1 = (int) Math.floor(b.maxZ - 0.01);
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++)
                    if (mc.world.getBlockState(new BlockPos(x, y, z)).isOf(Blocks.COBWEB)) return true;
        return false;
    }

    // Blick merken und nach unten auf die Web-Stelle drehen
    private void aimDown(MinecraftClient mc, ClientPlayerEntity p) {
        if (!viewSaved) { origYaw = p.getYaw(); origPitch = p.getPitch(); viewSaved = true; }
        BlockHitResult h = findBestPlacement(mc, p);
        Vec3d target = h != null ? h.getPos()
                : new Vec3d(locked.getX(), locked.getY(), locked.getZ());
        lookAt(p, target);
    }

    // Blick direkt auf einen Punkt drehen
    private void lookAt(ClientPlayerEntity p, Vec3d pos) {
        Vec3d eye = p.getEyePos();
        double dx = pos.x - eye.x, dy = pos.y - eye.y, dz = pos.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        p.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        p.setPitch((float) -Math.toDegrees(Math.atan2(dy, flat)));
    }

    private boolean valid(ClientPlayerEntity p) {
        return locked != null && locked.isAlive() && p.distanceTo(locked) <= MAX_DIST + 1.0;
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
        if (viewSaved) { p.setYaw(origYaw); p.setPitch(origPitch); viewSaved = false; } // Blick zurück
        if (returnSlot >= 0) p.getInventory().setSelectedSlot(returnSlot);
        phase = Phase.IDLE;
        busy = false;
        locked = null;
        stun = false;
        returnSlot = -1;
        delay = 0;
        timeout = 0;
        placed = 0;
    }

    private static int findSwordSlot(ClientPlayerEntity p) {
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getStack(i).isIn(ItemTags.SWORDS)) return i;
        }
        return -1;
    }

    private static int findSlot(ClientPlayerEntity p, boolean axe) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (axe ? s.isIn(ItemTags.AXES) : s.isOf(Items.COBWEB)) return i;
        }
        return -1;
    }
}
