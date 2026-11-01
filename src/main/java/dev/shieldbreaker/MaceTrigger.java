package dev.shieldbreaker;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;

import java.util.Random;

/**
 * Triggerbot: Wenn du die Mace (oder das Schwert) in der Hand hast und den Gegner ansiehst,
 * schlägt die Mod automatisch zu, sobald der Angriff aufgeladen ist.
 *
 * Client-Command: /macetrigger         -> Mace-Trigger an/aus
 *                 /macetrigger sword   -> Schwert-Trigger an/aus
 *                 /macetrigger fall    -> Mace nur beim Fallen (Smash) an/aus
 *                 /macetrigger mobs    -> auch auf Mobs schlagen an/aus
 */
public class MaceTrigger implements ClientModInitializer {

    private static boolean maceOn = true;
    private static boolean swordOn = false;
    private static boolean requireFall = true;
    private static boolean mobs = false;

    private static final double MIN_FALL = 1.5;     // so viele Blöcke Fall für einen Smash-Schlag
    private static final float MIN_CHARGE = 0.95f;  // so weit muss der Angriffsbalken geladen sein

    private final Random rnd = new Random();
    private int lastTargetId = -1;
    private int wait = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("macetrigger")
                .executes(ctx -> {
                    maceOn = !maceOn;
                    ctx.getSource().sendFeedback(Text.literal("Mace-Trigger: " + (maceOn ? "AN" : "AUS")));
                    return 1;
                })
                .then(ClientCommandManager.literal("sword").executes(ctx -> {
                    swordOn = !swordOn;
                    ctx.getSource().sendFeedback(Text.literal("Schwert-Trigger: " + (swordOn ? "AN" : "AUS")));
                    return 1;
                }))
                .then(ClientCommandManager.literal("fall").executes(ctx -> {
                    requireFall = !requireFall;
                    ctx.getSource().sendFeedback(Text.literal("Mace nur beim Fallen: " + (requireFall ? "AN" : "AUS")));
                    return 1;
                }))
                .then(ClientCommandManager.literal("mobs").executes(ctx -> {
                    mobs = !mobs;
                    ctx.getSource().sendFeedback(Text.literal("Auch Mobs: " + (mobs ? "AN" : "AUS")));
                    return 1;
                }))
            )
        );
    }

    private void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null
                || mc.interactionManager == null || mc.currentScreen != null) return;

        if (StunWeb.busy) { lastTargetId = -1; return; }

        // Welche Waffe hast du in der Hand, und ist dafür der Trigger an?
        boolean holdingMace = p.getMainHandStack().isOf(Items.MACE);
        boolean holdingSword = p.getMainHandStack().isIn(ItemTags.SWORDS);
        boolean active = (holdingMace && maceOn) || (holdingSword && swordOn);
        if (!active) { lastTargetId = -1; return; }

        // Ziel am Fadenkreuz
        LivingEntity target = null;
        if (mc.targetedEntity instanceof PlayerEntity pl && pl != p) {
            target = pl;
        } else if (mobs && mc.targetedEntity instanceof LivingEntity le
                && !(le instanceof ArmorStandEntity)) {
            target = le;
        }
        if (target == null || !target.isAlive()) { lastTargetId = -1; return; }

        if (target.isBlocking()) return;                                    // Schild: macht ShieldBreakerMod
        if (holdingMace && requireFall && p.fallDistance <= MIN_FALL) return; // kein Smash-Schlag möglich

        // Kleine Reaktionszeit, wenn ein neues Ziel ins Fadenkreuz kommt
        if (target.getId() != lastTargetId) {
            lastTargetId = target.getId();
            wait = 1 + rnd.nextInt(2);
        }
        if (wait > 0) { wait--; return; }

        if (p.getAttackCooldownProgress(0.5f) < MIN_CHARGE) return;

        mc.interactionManager.attackEntity(p, target);
        p.swingHand(Hand.MAIN_HAND);
        wait = rnd.nextInt(2);   // 0-1 Tick Pause nach dem Schlag
    }
}
