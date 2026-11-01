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
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class StunWeb implements ClientModInitializer {
    private static final double MAX_DIST = 4.0;
    private static final double MIN_AIM = 0.80;
    private static final double REACH = 4.5;
    private static final int WEB_TIMEOUT = 14;     // so lange wird versucht, den Gegner einzuwebben
    private static final int MAX_WEBS = 2;         // so viele Webs werden maximal gesetzt
    private static final int MAX_AIM_TICKS = 4;    // nach so vielen Ticks wird das Web auf jeden Fall gesetzt
    private static final float AIM_TOLERANCE = 8f; // so nah (in Grad) muss die Kamera am Ziel sein
    private static final int PATH_TICKS = 6;       // so weit wird die Flugbahn vorausberechnet
    private static final int[] ORDER = {2, 1, 3, 0, 4, 5, 6};

    private enum Phase { IDLE, HIT2, WEB }

    public static boolean busy = false; // true, solange das Macro läuft

    private static KeyBinding keyStun;  // Taste 3: Stunweb
    private static KeyBinding keyHit;   // Mittlere Maustaste: Hit + Web

    private final Random rnd = new Random();
    private Phase phase = Phase.IDLE;
    private PlayerEntity locked = null;
    private boolean stun = false;
    private int returnSlot = -1;
    private int delay = 0;
    private int timeout = 0;
    private int placed = 0;
    private int hitAge = 99;
    private int webDelay = 0;
    private int aimTicks = 0;
    private double jitA = 0, jitB = 0;

    private float origYaw = 0, origPitch = 0;
    private boolean viewSaved = false;
    private boolean restoring = false;
    private float lastSetYaw = 0, lastSetPitch = 0;

    private final Map<Integer, Vec3d> lastPos = new HashMap<>();
    private final Map<Integer, Vec3d> vel = new HashMap<>();

    @Override
    public void onInitializeClient() {
        KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of("shieldbreaker", "main"));
        keyStun = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.shieldbreaker.stunweb", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_3, cat));
        keyHit = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.shieldbreaker.hitweb", InputUtil.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, cat));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null) return;

        trackPlayers(mc, p);

        boolean pStun = false, pHit = false;
        while (keyStun.wasPressed()) pStun = true;
        while (keyHit.wasPressed()) pHit = true;
        if (phase != Phase.IDLE) hitAge++;
        doRestore(p);

        switch (phase) {
            case IDLE -> {
                if ((!pStun && !pHit) || mc.currentScreen != null) return;
                if (findSlot(p, false) < 0) return;          // Cobweb ist Pflicht

                PlayerEntity t = findTarget(mc, p);
                if (t == null) return;

                busy = true;
                restoring = false;
                locked = t;
                placed = 0;
                aimTicks = 0;
                stun = pStun && findSlot(p, true) >= 0;
                jitA = (rnd.nextDouble() - 0.5) * 0.5;
                jitB = (rnd.nextDouble() - 0.5) * 0.5;
                saveView(p);

                // Klick 1: Schwert (oder das Item in der Hand, falls kein Schwert da ist)
                int sword = findSwordSlot(p);
                if (sword >= 0) {
                    returnSlot = sword;
                    p.getInventory().setSelectedSlot(sword);
                } else {
                    returnSlot = p.getInventory().getSelectedSlot();
                }
                attack(mc, p, t);

                if (stun) {
                    delay = rnd.nextInt(4) == 0 ? 2 : 1;   // meist 1 Tick, manchmal 2
                    phase = Phase.HIT2;
                } else {
                    startWebPhase();
                }
            }
            case HIT2 -> {
                if (!valid(p)) { abort(p); return; }
                if (--delay > 0) return;
                int axe = findSlot(p, true);
                if (axe < 0) { abort(p); return; }

                p.getInventory().setSelectedSlot(axe);   // Attribute Swap
                attack(mc, p, locked);                   // Klick 2: Axt, zerstört den Schild
                startWebPhase();
            }
            case WEB -> {
                if (!valid(p) || --timeout <= 0) { abort(p); return; }
                if (isWebbed(mc, locked)) { abort(p); return; }   // Gegner steckt im Web
                if (placed >= MAX_WEBS) { abort(p); return; }
                if (webDelay > 0) { webDelay--; return; }

                BlockHitResult h = findBestPlacement(mc, p);
                if (h == null) return;

                boolean aimed = stepAim(p, h.getPos());          // weich zielen
                aimTicks++;
                if (!aimed && aimTicks < (stun ? 2 : MAX_AIM_TICKS)) return;  // noch nicht nah genug

                int web = findSlot(p, false);
                if (web < 0) { abort(p); return; }
                p.getInventory().setSelectedSlot(web);
                mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, h);
                p.swingHand(Hand.MAIN_HAND);
                placed++;
                aimTicks = 0;
            }
        }
    }

    private void startWebPhase() {
        phase = Phase.WEB;
        timeout = WEB_TIMEOUT;
        webDelay = stun ? 0 : rnd.nextInt(2);   // Stunweb ohne Extra-Verzögerung
        aimTicks = 0;
    }

    private void attack(MinecraftClient mc, ClientPlayerEntity p, PlayerEntity t) {
        mc.interactionManager.attackEntity(p, t);
        p.swingHand(Hand.MAIN_HAND);
        hitAge = 0;
    }

    // ---------- Kamera ----------

    private void saveView(ClientPlayerEntity p) {
        if (!viewSaved) { origYaw = p.getYaw(); origPitch = p.getPitch(); viewSaved = true; }
    }

    // Dreht die Kamera pro Tick ein Stück zum Ziel. true = nah genug dran.
    private boolean stepAim(ClientPlayerEntity p, Vec3d pos) {
        Vec3d eye = p.getEyePos();
        double dx = pos.x - eye.x, dy = pos.y - eye.y, dz = pos.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        float ty = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float tp = (float) -Math.toDegrees(Math.atan2(dy, flat));

        float diffY = MathHelper.wrapDegrees(ty - p.getYaw());
        float diffP = tp - p.getPitch();
        float f = stun ? 0.80f + rnd.nextFloat() * 0.15f : 0.55f + rnd.nextFloat() * 0.20f;
        float sy = Math.abs(diffY) < 2f ? diffY : diffY * f;
        float sp = Math.abs(diffP) < 2f ? diffP : diffP * f;
        sy += (rnd.nextFloat() - 0.5f) * 0.6f;
        sp += (rnd.nextFloat() - 0.5f) * 0.6f;

        p.setYaw(p.getYaw() + sy);
        p.setPitch(p.getPitch() + sp);
        lastSetYaw = p.getYaw();
        lastSetPitch = p.getPitch();
        float tol = stun ? 16f : AIM_TOLERANCE;
        return Math.abs(diffY - sy) < tol && Math.abs(diffP - sp) < tol;
    }

    // Nach dem Macro weich zurück zur alten Blickrichtung (außer du drehst selbst)
    private void doRestore(ClientPlayerEntity p) {
        if (!restoring) return;
        if (Math.abs(MathHelper.wrapDegrees(p.getYaw() - lastSetYaw)) > 3f
                || Math.abs(p.getPitch() - lastSetPitch) > 3f) { restoring = false; return; }
        float dy = MathHelper.wrapDegrees(origYaw - p.getYaw());
        float dp = origPitch - p.getPitch();
        if (Math.abs(dy) < 1.5f && Math.abs(dp) < 1.5f) {
            p.setYaw(origYaw);
            p.setPitch(origPitch);
            restoring = false;
            return;
        }
        float f = 0.5f + rnd.nextFloat() * 0.2f;
        p.setYaw(p.getYaw() + dy * f);
        p.setPitch(p.getPitch() + dp * f);
        lastSetYaw = p.getYaw();
        lastSetPitch = p.getPitch();
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

    // Simuliert Tick für Tick, wo der Gegner (Fußblock) sein wird, inklusive Rückstoß von dir weg
    private BlockPos[] predictPath(MinecraftClient mc, ClientPlayerEntity p, PlayerEntity e) {
        Vec3d v = vel.getOrDefault(e.getId(), Vec3d.ZERO);
        double x = e.getX(), y = e.getY(), z = e.getZ();
        double vx = v.x, vy = v.y, vz = v.z;
        boolean ground = e.isOnGround();
        if (ground && vy < 0) vy = 0;

        // Rückstoß deines Schlags, solange er in der gemessenen Bewegung noch nicht sichtbar ist
        if (hitAge <= 2) {
            double dx = e.getX() - p.getX(), dz = e.getZ() - p.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0.001) {
                double kb = 0.4 + (p.isSprinting() ? 0.5 : 0.0);
                vx = vx * 0.5 + dx / len * kb;
                vz = vz * 0.5 + dz / len * kb;
                if (ground) vy = Math.min(0.4, vy * 0.5 + 0.4);
            }
        }

        BlockPos[] path = new BlockPos[PATH_TICKS + 1];
        for (int i = 0; i <= PATH_TICKS; i++) {
            path[i] = BlockPos.ofFloored(x, y + 0.05, z);

            double nx = x + vx, nz = z + vz;
            if (blocked(mc, nx, y + 0.1, nz) || blocked(mc, nx, y + 1.1, nz)) {
                nx = x; nz = z; vx = 0; vz = 0;
            }
            x = nx; z = nz;

            double ny = y + vy;
            if (vy <= 0 && blocked(mc, x, ny, z)) {
                y = Math.floor(ny) + 1.0;
                vy = 0;
                ground = true;
            } else {
                y = ny;
                if (ground && !blocked(mc, x, y - 0.1, z)) ground = false;
            }

            vy = ground ? 0 : (vy - 0.08) * 0.98;
            double f = ground ? 0.546 : 0.91;
            vx *= f;
            vz *= f;
        }
        return path;
    }

    // Probiert Blöcke der Flugbahn durch, nie vor dem Gegner (auf deiner Seite)
    private BlockHitResult findBestPlacement(MinecraftClient mc, ClientPlayerEntity p) {
        BlockPos[] path = predictPath(mc, p, locked);

        double ax = locked.getX() - p.getX(), az = locked.getZ() - p.getZ();
        double al = Math.sqrt(ax * ax + az * az);
        if (al > 0.001) { ax /= al; az /= al; }

        for (int idx : ORDER) {
            BlockPos c = path[idx];
            Vec3d cc = Vec3d.ofCenter(c);
            double along = (cc.x - locked.getX()) * ax + (cc.z - locked.getZ()) * az;
            if (along < -0.3) continue;                       // würde vor dem Gegner liegen
            BlockHitResult h = findPlacement(mc, p, c);
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
            Vec3d off = switch (face.getAxis()) {
                case X -> new Vec3d(0, jitA, jitB);
                case Y -> new Vec3d(jitA, 0, jitB);
                case Z -> new Vec3d(jitA, jitB, 0);
            };
            Vec3d hitPos = Vec3d.ofCenter(support).add(
                    face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5).add(off);
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
        if (returnSlot >= 0) p.getInventory().setSelectedSlot(returnSlot);
        restoring = viewSaved;          // Blick danach weich zurückdrehen
        viewSaved = false;
        lastSetYaw = p.getYaw();
        lastSetPitch = p.getPitch();
        phase = Phase.IDLE;
        busy = false;
        locked = null;
        stun = false;
        returnSlot = -1;
        delay = 0;
        timeout = 0;
        placed = 0;
        aimTicks = 0;
        webDelay = 0;
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
