package dev.shieldbreaker;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;

/**
 * Wenn der Gegner am Fadenkreuz seinen Schild hochhaelt:
 *  1. auf die Axt in der Hotbar wechseln
 *  2. (optional) automatisch zuschlagen, sobald der Angriff aufgeladen ist -> Schild wird disabled
 *  3. danach zurueck auf den vorherigen Slot
 *
 * Client-Command: /shieldbreaker          -> Mod an/aus
 *                 /shieldbreaker attack   -> Auto-Angriff an/aus
 *                 /shieldbreaker delay <ticks> -> Wartezeit zwischen Wechsel und Schlag (0-20, 20 Ticks = 1 s)
 */
public class ShieldBreakerMod implements ClientModInitializer {

    private static boolean enabled = true;
    private static boolean autoAttack = true;
    /** Ticks, die nach dem Slot-Wechsel gewartet wird, bevor zugeschlagen wird. */
    private static int attackDelayTicks = 2;
    private int waitTicks = 0;

    /** Slot, auf dem der Spieler vor dem Wechsel war (-1 = kein Wechsel aktiv). */
    private int previousSlot = -1;
    /** Axt-Slot, auf den wir gewechselt haben. */
    private int axeSlot = -1;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("shieldbreaker")
                .executes(ctx -> {
                    enabled = !enabled;
                    ctx.getSource().sendFeedback(Text.literal("ShieldBreaker: " + (enabled ? "AN" : "AUS")));
                    return 1;
                })
                .then(ClientCommandManager.literal("attack").executes(ctx -> {
                    autoAttack = !autoAttack;
                    ctx.getSource().sendFeedback(Text.literal("Auto-Angriff: " + (autoAttack ? "AN" : "AUS")));
                    return 1;
                }))
                .then(ClientCommandManager.literal("delay")
                    .then(ClientCommandManager.argument("ticks", IntegerArgumentType.integer(0, 20))
                        .executes(ctx -> {
                            attackDelayTicks = IntegerArgumentType.getInteger(ctx, "ticks");
                            ctx.getSource().sendFeedback(Text.literal("Delay: " + attackDelayTicks + " Ticks"));
                            return 1;
                        })))
            )
        );
    }

    private void onTick(MinecraftClient client) {
        // Andere Macros haben Vorrang: dann hält der Shieldbreak still
        if (StunWeb.busy || StunWeb.armed || StunSlam.busy || PearlCatch.busy || slamSituation(client)) {
            previousSlot = -1;
            axeSlot = -1;
            waitTicks = 0;
            return;
        }

        ClientPlayerEntity player = client.player;
        if (!enabled || player == null || client.world == null
                || client.interactionManager == null || client.currentScreen != null) {
            return;
        }

        PlayerInventory inv = player.getInventory();
        Entity target = client.targetedEntity;
        boolean shieldUp = target instanceof LivingEntity living
                && living != player
                && living.isBlocking();

        if (shieldUp) {
            // Noch keine Axt in der Hand -> wechseln, Angriff erfolgt im naechsten Tick
            if (!player.getMainHandStack().isIn(ItemTags.AXES)) {
                int slot = findAxeSlot(inv);
                if (slot == -1) return; // keine Axt in der Hotbar
                if (previousSlot == -1) previousSlot = inv.getSelectedSlot();
                axeSlot = slot;
                inv.setSelectedSlot(slot);
                waitTicks = attackDelayTicks;
                return;
            }

            if (waitTicks > 0) {
                waitTicks--;
                return;
            }

            if (autoAttack && player.getAttackCooldownProgress(0.5f) >= 1.0f) {
                client.interactionManager.attackEntity(player, target);
                player.swingHand(Hand.MAIN_HAND);
            }
        } else if (previousSlot != -1) {
            // Schild ist unten (bzw. disabled) oder Ziel weg -> zurueckwechseln,
            // aber nur, wenn der Spieler nicht zwischenzeitlich selbst gescrollt hat
            if (inv.getSelectedSlot() == axeSlot) {
                inv.setSelectedSlot(previousSlot);
            }
            previousSlot = -1;
            axeSlot = -1;
        }
    }

    private static int findAxeSlot(PlayerInventory inv) {
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isIn(ItemTags.AXES)) return i;
        }
        return -1;
    }

    // Du fällst mit Axt und Mace in der Hotbar: dann übernimmt der Stun Slam
    private static boolean slamSituation(MinecraftClient c) {
        ClientPlayerEntity p = c.player;
        if (p == null || p.fallDistance <= 1.5) return false;
        boolean mace = false, axe = false;
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getStack(i).isOf(net.minecraft.item.Items.MACE)) mace = true;
            if (p.getInventory().getStack(i).isIn(ItemTags.AXES)) axe = true;
        }
        return mace && axe;
    }
}
