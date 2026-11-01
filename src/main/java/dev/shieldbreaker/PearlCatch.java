package dev.shieldbreaker;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Random;

/**
 * Pearl Catch: Taste X wirft eine Pearl und fängt sie nach ein paar Ticks mit einer Windcharge ab.
 * Die Windcharge wird auf die vorausberechnete Position der Pearl gezielt, egal in welche Richtung
 * du die Pearl geworfen hast (auch diagonal). Danach ist die Windcharge in der Hand.
 *
 * Client-Command: /pearlcatch               -> Pearl Catch an/aus
 *                 /pearlcatch delay <1-10>  -> Ticks zwischen Pearl und Windcharge (Standard 3)
 */
public class PearlCatch implements ClientModInitializer {
    private static final double WIND_SPEED = 1.5;   // Fluggeschwindigkeit der Windcharge (Blöcke pro Tick)
    private static final int SIM_TICKS = 14;        // so weit wird die Flugbahn der Pearl vorausberechnet

    private static boolean enabled = true;
    private static int baseDelay = 3;

    public static boolean busy = false;

    private enum Phase { IDLE, WAIT, RESTORE }

    private static KeyBinding key;
    private final Random rnd = new Random();
    private Phase phase = Phase.IDLE;
    private int wait = 0;
    private int giveUp = 0;
    private int prevSlot = -1;
    private float origYaw = 0, origPitch = 0;

    @Override
    public void onInitializeClient() {
        key = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.shieldbreaker.pearlcatch",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_X,
                KeyBinding.Category.create(Identifier.of("shieldbreaker", "pearl"))));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("pearlcatch")
                .executes(ctx -> {
                    enabled = !enabled;
                    ctx.getSource().sendFeedback(Text.literal("Pearl Catch: " + (enabled ? "AN" : "AUS")));
                    return 1;
                })
                .then(ClientCommandManager.literal("delay")
                    .then(ClientCommandManager.argument("ticks", IntegerArgumentType.integer(1, 10))
                        .executes(ctx -> {
                            baseDelay = IntegerArgumentType.getInteger(ctx, "ticks");
                            ctx.getSource().sendFeedback(Text.literal("Pearl Catch Delay: " + baseDelay + " Ticks"));
                            return 1;
                        })))
            )
        );
    }

    private void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null) return;

        boolean pressed = false;
        while (key.wasPressed()) pressed = true;

        switch (phase) {
            case IDLE -> {
                if (!enabled || !pressed || mc.currentScreen != null) return;

                int pearl = findSlot(p, Items.ENDER_PEARL);
                int wind = findSlot(p, Items.WIND_CHARGE);
                if (pearl < 0 || wind < 0) {
                    p.sendMessage(Text.literal("Pearl und Windcharge müssen in der Hotbar liegen"), true);
                    return;
                }

                busy = true;
                prevSlot = p.getInventory().getSelectedSlot();
                origYaw = p.getYaw();
                origPitch = p.getPitch();

                // Pearl in deine Blickrichtung werfen
                p.getInventory().setSelectedSlot(pearl);
                mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
                p.swingHand(Hand.MAIN_HAND);

                int r = rnd.nextInt(4);                        // meist genau der Delay, manchmal +-1 Tick
                wait = baseDelay + (r == 0 ? -1 : (r == 1 ? 1 : 0));
                if (wait < 1) wait = 1;
                giveUp = 10;
                phase = Phase.WAIT;
            }
            case WAIT -> {
                if (--wait > 0) return;

                EnderPearlEntity pearl = findPearl(mc, p);
                if (pearl == null) {                           // Pearl noch nicht zu sehen
                    if (--giveUp <= 0) finish(p);
                    return;
                }
                int wind = findSlot(p, Items.WIND_CHARGE);
                if (wind < 0) { finish(p); return; }

                lookAt(p, interceptPoint(p, pearl));           // Blick auf die Pearl-Flugbahn
                p.getInventory().setSelectedSlot(wind);
                mc.interactionManager.interactItem(p, Hand.MAIN_HAND);   // Windcharge werfen
                p.swingHand(Hand.MAIN_HAND);
                phase = Phase.RESTORE;
            }
            case RESTORE -> finish(p);
        }
    }

    // Die eigene, zuletzt geworfene Pearl in der Nähe finden
    private EnderPearlEntity findPearl(MinecraftClient mc, ClientPlayerEntity p) {
        List<EnderPearlEntity> list = mc.world.getEntitiesByClass(
                EnderPearlEntity.class, p.getBoundingBox().expand(48),
                e -> e.getOwner() == p || e.getOwner() == null);
        EnderPearlEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (EnderPearlEntity e : list) {
            double d = e.squaredDistanceTo(p);
            if (d < bestD) { bestD = d; best = e; }
        }
        return best;
    }

    // Wo die Windcharge hinfliegen soll: dorthin, wo die Pearl ist, wenn die Windcharge ankommt
    private Vec3d interceptPoint(ClientPlayerEntity p, EnderPearlEntity pearl) {
        Vec3d eye = p.getEyePos();
        Vec3d pos = new Vec3d(pearl.getX(), pearl.getY(), pearl.getZ());
        Vec3d v = pearl.getVelocity();

        Vec3d best = pos;
        double bestDiff = Double.MAX_VALUE;
        for (int k = 1; k <= SIM_TICKS; k++) {
            pos = pos.add(v);                                              // Pearl fliegt weiter
            v = new Vec3d(v.x * 0.99, v.y * 0.99 - 0.03, v.z * 0.99);     // Luftwiderstand und Schwerkraft
            double reach = WIND_SPEED * Math.max(k - 1, 0);                // so weit kommt die Windcharge in k Ticks
            double dist = eye.distanceTo(pos);
            if (dist <= reach + 0.3) return pos;                           // Treffer möglich
            double diff = Math.abs(dist - reach);
            if (diff < bestDiff) { bestDiff = diff; best = pos; }
        }
        return best;                                                       // sonst die knappste Stelle
    }

    private void lookAt(ClientPlayerEntity p, Vec3d pos) {
        Vec3d eye = p.getEyePos();
        double dx = pos.x - eye.x, dy = pos.y - eye.y, dz = pos.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        p.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        p.setPitch((float) -Math.toDegrees(Math.atan2(dy, flat)));
    }

    private void finish(ClientPlayerEntity p) {
        int wind = findSlot(p, Items.WIND_CHARGE);
        if (wind >= 0) p.getInventory().setSelectedSlot(wind);   // Windcharge bleibt in der Hand
        else if (prevSlot >= 0) p.getInventory().setSelectedSlot(prevSlot);
        p.setYaw(origYaw);
        p.setPitch(origPitch);
        busy = false;
        phase = Phase.IDLE;
        prevSlot = -1;
        wait = 0;
        giveUp = 0;
    }

    private static int findSlot(ClientPlayerEntity p, Item item) {
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getStack(i).isOf(item)) return i;
        }
        return -1;
    }
}
