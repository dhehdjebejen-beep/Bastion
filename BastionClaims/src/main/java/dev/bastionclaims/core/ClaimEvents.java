package dev.bastionclaims.core;

import dev.bastionclaims.BastionClaims;
import dev.bastionclaims.config.ClaimConfig;
import dev.bastionclaims.model.Claim;
import dev.bastionclaims.util.Fmt;
import dev.bastionclaims.wand.ClaimWand;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.AbstractPressurePlateBlock;
import net.minecraft.block.Block;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.consume.UseAction;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/** Registers all interaction/break/attack event handlers (wand + protection). */
public final class ClaimEvents {

    public static void register() {
        AttackBlockCallback.EVENT.register(ClaimEvents::onAttackBlock);
        UseBlockCallback.EVENT.register(ClaimEvents::onUseBlock);
        PlayerBlockBreakEvents.BEFORE.register(ClaimEvents::onBreakBefore);
        UseEntityCallback.EVENT.register(ClaimEvents::onUseEntity);
        AttackEntityCallback.EVENT.register(ClaimEvents::onAttackEntity);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayerEntity p)) {
                // Animals, armour stands, villagers standing in someone's claim.
                // The attack event only ever sees a melee swing, so without this
                // an arrow, a splash potion or a TNT charge from outside the
                // border killed them anyway. Non-living valuables (item frames,
                // paintings, minecarts) go through EntityDamageMixin.
                return !ProtectionService.entityDamageBlocked(entity, source);
            }
            // Hostile-mob damage inside a mob-damage-off claim.
            if (ProtectionService.shouldCancelMobDamage(p, source)) return false;
            // Player damage of any kind — arrows, tridents, potions, fireworks —
            // inside a claim with PvP off.
            ServerPlayerEntity attacker = ProtectionService.playerDamageBlockedBy(p, source);
            if (attacker != null) {
                ProtectionService.notifyThrottled(attacker, Fmt.text(cfg().message("protect.pvp")));
                return false;
            }
            return true;
        });
    }

    // -------------------------------------------------------------- wand: corner 1

    private static ActionResult onAttackBlock(net.minecraft.entity.player.PlayerEntity player,
                                              net.minecraft.world.World world,
                                              net.minecraft.util.Hand hand, BlockPos pos, Direction direction) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity p)) return ActionResult.PASS;
        if (ClaimWand.isWand(p.getStackInHand(hand))) {
            String dim = world.getRegistryKey().getValue().toString();
            Selection sel = Selection.of(p.getUuid());
            sel.setPos1(dim, pos.getX(), pos.getY(), pos.getZ());
            p.sendMessage(Fmt.text(cfg().message("wand.pos1", pos.getX(), pos.getY(), pos.getZ())), false);
            sendSelInfo(p, sel);
            return ActionResult.FAIL; // don't start breaking the block
        }
        return ActionResult.PASS;
    }

    // -------------------------------------------------------------- wand: corner 2 + interact/build protect

    private static ActionResult onUseBlock(net.minecraft.entity.player.PlayerEntity player,
                                           net.minecraft.world.World world,
                                           net.minecraft.util.Hand hand, BlockHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity p)) return ActionResult.PASS;
        String dim = world.getRegistryKey().getValue().toString();
        BlockPos pos = hit.getBlockPos();

        if (ClaimWand.isWand(p.getStackInHand(hand))) {
            Selection sel = Selection.of(p.getUuid());
            sel.setPos2(dim, pos.getX(), pos.getY(), pos.getZ());
            p.sendMessage(Fmt.text(cfg().message("wand.pos2", pos.getX(), pos.getY(), pos.getZ())), false);
            sendSelInfo(p, sel);
            return ActionResult.FAIL;
        }

        ProtectionService.Interaction cat = classify(world, pos);

        // Eating is not an interaction with the claim, but it used to be denied
        // as one: the client gives up on using the held item the moment a block
        // interaction comes back FAIL, so standing on someone else's land made
        // food and potions impossible to consume. Plain blocks let food through;
        // containers and doors still don't, exactly as vanilla would (a chest
        // opens instead of feeding you either way).
        UseAction useAction = p.getStackInHand(hand).getUseAction();
        if (cat == ProtectionService.Interaction.OTHER
                && (useAction == UseAction.EAT || useAction == UseAction.DRINK)) {
            return ActionResult.PASS;
        }

        // Deny interacting with the clicked block (chests, doors, buttons, item use),
        // unless the matching guest flag opens that category to non-members.
        Claim interact = ProtectionService.blockingInteract(p, dim, pos.getX(), pos.getY(), pos.getZ(), cat);
        if (interact != null) {
            ProtectionService.notifyThrottled(p, Fmt.text(cfg().message("protect.deniedInteract", interact.owner)));
            return traced(p, "взаимодействие запрещено приватом «" + interact.name + "»", ActionResult.FAIL);
        }
        // Deny placing a block into a claim from just outside its border.
        // Where the block actually lands depends on what was clicked: a
        // replaceable target (tall grass, snow layer, water) is overwritten in
        // place, everything else pushes the new block to the neighbouring cell.
        // Assuming the neighbour unconditionally checked the wrong cell in both
        // directions — it let a placement through when only the interact check
        // happened to cover it, and refused legitimate placements outside a
        // claim whose neighbouring cell was inside one.
        //
        // Run it ONLY when the click would actually place something. Checking on
        // every right-click is what made the guest flags useless: opening a chest
        // also targets the cell next to it, so this fired and cancelled the very
        // interaction the flag had just permitted — silently, because the refusal
        // message is throttled to once per 1.5s.
        boolean interactive = cat == ProtectionService.Interaction.CONTAINER
                || cat == ProtectionService.Interaction.DOOR;
        ItemStack held = p.getStackInHand(hand);
        // Vanilla only places onto an interactive block while sneaking.
        boolean wouldPlace = held.getItem() instanceof BlockItem && (p.isSneaking() || !interactive);
        if (wouldPlace) {
            BlockState clicked = world.getBlockState(pos);
            BlockPos place = clicked.isReplaceable() ? pos : pos.offset(hit.getSide());
            Claim build = ProtectionService.blockingBuild(p, dim, place.getX(), place.getY(), place.getZ());
            if (build != null) {
                ProtectionService.notifyThrottled(p, Fmt.text(cfg().message("protect.denied", build.owner)));
                return traced(p, "установка запрещена приватом «" + build.name + "»", ActionResult.FAIL);
            }
        }
        return traced(p, "приваты пропускают клик по " + cat, ActionResult.PASS);
    }

    // -------------------------------------------------------------- break protect

    private static boolean onBreakBefore(net.minecraft.world.World world,
                                         net.minecraft.entity.player.PlayerEntity player,
                                         BlockPos pos, net.minecraft.block.BlockState state,
                                         net.minecraft.block.entity.BlockEntity be) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity p)) return true;
        String dim = world.getRegistryKey().getValue().toString();
        Claim c = ProtectionService.blockingBuild(p, dim, pos.getX(), pos.getY(), pos.getZ());
        if (c != null) {
            ProtectionService.notifyThrottled(p, Fmt.text(cfg().message("protect.denied", c.owner)));
            traced(p, "разрушение " + pos.toShortString() + " запрещено приватом «" + c.name + "»", ActionResult.FAIL);
            return false; // cancel the break
        }
        traced(p, "приваты пропускают разрушение " + pos.toShortString(), ActionResult.PASS);
        return true;
    }

    // -------------------------------------------------------------- entity interact protect

    private static ActionResult onUseEntity(net.minecraft.entity.player.PlayerEntity player,
                                            net.minecraft.world.World world, net.minecraft.util.Hand hand,
                                            net.minecraft.entity.Entity entity,
                                            net.minecraft.util.hit.EntityHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity p)) return ActionResult.PASS;
        if (ClaimWand.isWand(p.getStackInHand(hand))) return ActionResult.PASS;
        // Server NPCs (the passport clerk, Товарищ Майор) are services, not
        // property — they must answer to everyone regardless of whose land they
        // happen to stand on.
        if (isServiceEntity(entity)) return ActionResult.PASS;

        String dim = world.getRegistryKey().getValue().toString();
        BlockPos pos = entity.getBlockPos();
        Claim c = ProtectionService.blockingInteract(p, dim, pos.getX(), pos.getY(), pos.getZ(),
                ProtectionService.Interaction.ENTITY);
        if (c != null) {
            ProtectionService.notifyThrottled(p, Fmt.text(cfg().message("protect.deniedEntity", c.owner)));
            return ActionResult.FAIL;
        }
        return ActionResult.PASS;
    }

    // -------------------------------------------------------------- trace

    /**
     * Players who asked to see what this mod decides on every right-click.
     * Simulating the decision from a command is not the same as watching the
     * real event: if the trace says PASS and the click still does nothing, the
     * culprit is provably somewhere else.
     */
    private static final java.util.Set<java.util.UUID> TRACE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static boolean toggleTrace(ServerPlayerEntity p) {
        if (TRACE.remove(p.getUuid())) return false;
        TRACE.add(p.getUuid());
        return true;
    }

    public static void forgetTrace(java.util.UUID id) {
        TRACE.remove(id);
    }

    private static ActionResult traced(ServerPlayerEntity p, String what, ActionResult result) {
        if (TRACE.contains(p.getUuid())) {
            // ActionResult prints as an obfuscated record name on a server
            // ("class_9859[]"), which told the reader nothing.
            String verdict = result == ActionResult.PASS ? "&aPASS &8(приват не вмешался — дальше решает ваниль)"
                    : result == ActionResult.FAIL ? "&cFAIL &8(приват запретил)" : "&f" + result;
            p.sendMessage(Fmt.text("&8[trace] &7" + what + " &8→ " + verdict), false);
        }
        return result;
    }

    /**
     * Named NPCs listed in config are never treated as claim property. The
     * comparison deliberately requires the complete custom name: matching an
     * arbitrary substring allowed a renamed player entity to inherit a service
     * exception inside someone else's claim.
     */
    private static boolean isServiceEntity(net.minecraft.entity.Entity entity) {
        net.minecraft.text.Text name = entity.getCustomName();
        if (name == null) return false;
        String plain = name.getString().trim();
        for (String marker : cfg().serviceEntityNames) {
            if (marker != null && !marker.isBlank() && plain.equals(marker.trim())) return true;
        }
        return false;
    }

    // -------------------------------------------------------------- attack protect (PvP + entities)

    private static ActionResult onAttackEntity(net.minecraft.entity.player.PlayerEntity player,
                                               net.minecraft.world.World world, net.minecraft.util.Hand hand,
                                               net.minecraft.entity.Entity entity,
                                               net.minecraft.util.hit.EntityHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity p)) return ActionResult.PASS;
        String dim = world.getRegistryKey().getValue().toString();
        BlockPos pos = entity.getBlockPos();

        if (entity instanceof ServerPlayerEntity) {
            if (!ProtectionService.canPvp(p, dim, pos.getX(), pos.getY(), pos.getZ())) {
                ProtectionService.notifyThrottled(p, Fmt.text(cfg().message("protect.pvp")));
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        }
        // Let players defend themselves against hostiles even inside a claim;
        // protect only "valuable" entities (animals, item frames, armour stands…).
        if (entity instanceof HostileEntity) return ActionResult.PASS;
        Claim c = ProtectionService.blockingInteract(p, dim, pos.getX(), pos.getY(), pos.getZ(),
                ProtectionService.Interaction.ENTITY);
        if (c != null) {
            ProtectionService.notifyThrottled(p, Fmt.text(cfg().message("protect.deniedEntity", c.owner)));
            return ActionResult.FAIL;
        }
        return ActionResult.PASS;
    }

    /** Buckets a block into a guest-flag category (container / door-like / other). */
    public static ProtectionService.Interaction classify(World world, BlockPos pos) {
        if (world.getBlockEntity(pos) instanceof Inventory) {
            return ProtectionService.Interaction.CONTAINER;
        }
        net.minecraft.block.BlockState state = world.getBlockState(pos);
        Block b = state.getBlock();
        if (b instanceof DoorBlock || b instanceof TrapdoorBlock || b instanceof FenceGateBlock
                || b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof AbstractPressurePlateBlock) {
            return ProtectionService.Interaction.DOOR;
        }
        // A crafting table, anvil or loom opens a screen but has no block entity,
        // so it used to land in OTHER — which no guest flag covers, leaving it
        // locked for guests no matter what the owner switched on. They belong
        // with the containers.
        if (isWorkstation(state)) {
            return ProtectionService.Interaction.CONTAINER;
        }
        return ProtectionService.Interaction.OTHER;
    }

    private static boolean isWorkstation(net.minecraft.block.BlockState state) {
        net.minecraft.util.Identifier id = net.minecraft.registry.Registries.BLOCK.getId(state.getBlock());
        if (id == null) return false;
        for (String entry : cfg().workstationBlocks) {
            if (entry == null || entry.isBlank()) continue;
            String want = entry.trim().toLowerCase(java.util.Locale.ROOT);
            if (want.equals(id.toString()) || want.equals(id.getPath())) return true;
        }
        return false;
    }

    private static ClaimConfig cfg() {
        return BastionClaims.config();
    }

    /** After both corners are set, echo the box dimensions and total block count. */
    private static void sendSelInfo(ServerPlayerEntity p, Selection sel) {
        if (!sel.complete()) return;
        p.sendMessage(Fmt.text(cfg().message("wand.selInfo",
                sel.sizeX(), sel.sizeY(), sel.sizeZ(), sel.volume())), false);
        p.sendMessage(Fmt.text(ClaimService.selectionHint()), false);
    }

    private ClaimEvents() {}
}
