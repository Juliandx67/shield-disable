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

public class StunWeb implements ClientModInitializer {
    private static final double MAX_DIST = 4.0;
    private static final double MIN_AIM = 0.80;
    private static final double REACH = 4.5;
    private static final int CLICK_DELAY = 1;   // Ticks zwischen Klick 1 und dem nächsten Schritt
    private static final int WEB_TIMEOUT = 12;  // so lange wird versucht, den Gegner einzuwebben
    private static final int MAX_WEBS = 2;      // so viele Webs werden maximal gesetzt

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

                delay = CLICK_DELAY;
                phase = Phase.HIT2;
            }
            case HIT2 -> {
                if (!valid(p)) { abort(p); return; }
                if (--delay > 0) return;

                if (stun) {
                    int axe = findSlot(p, true);
                    if (axe < 0) { abort(p); return; }
                    p.getInventory().setSelectedSlot(axe);   // Attribute Swap
                    attack(mc, p, locked);                   // Klick 2: Axt, zerstört den Schild
                }
                aimDown(mc, p);                              // Blick nach unten

                phase = Phase.WEB;
                timeout = WEB_TIMEOUT;
            }
            case WEB -> {
                if (!valid(p) || --timeout <= 0) { abort(p); return; }
                if (isWebbed(mc, locked)) { abort(p); return; }   // Gegner steckt im Web
                if (placed >= MAX_WEBS) { abort(p); return; }

                BlockHitResult h = findPlacement(mc, p, targetBlock());
                if (h == null) return; // keine sichere Fläche, nächster Tick

                int web = findSlot(p, false);
                if (web < 0) { abort(p); return; }
                lookAt(p, h.getPos());
                p.getInventory().setSelectedSlot(web);
                mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, h);
                p.swingHand(Hand.MAIN_HAND);
                placed++;
            }
        }
    }

    private void attack(MinecraftClient mc, ClientPlayerEntity p, PlayerEntity t) {
        mc.interactionManager.attackEntity(p, t);
        p.swingHand(Hand.MAIN_HAND);
    }

    // Der Block, in dem der Gegner mit seiner Mitte auf Fußhöhe steht
    private BlockPos targetBlock() {
        Vec3d pos = locked.getPos();
        return BlockPos.ofFloored(pos.x, pos.y + 0.05, pos.z);
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
        BlockPos block = targetBlock();
        BlockHitResult h = findPlacement(mc, p, block);
        lookAt(p, h != null ? h.getPos() : Vec3d.ofCenter(block.down()).add(0, 0.5, 0));
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
