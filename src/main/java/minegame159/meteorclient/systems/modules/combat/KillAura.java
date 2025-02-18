/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client/).
 * Copyright (c) 2021 Meteor Development.
 */

package minegame159.meteorclient.systems.modules.combat;

import baritone.api.BaritoneAPI;
import it.unimi.dsi.fastutil.objects.Object2BooleanMap;
import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap;
import meteordevelopment.orbit.EventHandler;
import minegame159.meteorclient.events.world.TickEvent;
import minegame159.meteorclient.settings.*;
import minegame159.meteorclient.systems.friends.Friends;
import minegame159.meteorclient.systems.modules.Categories;
import minegame159.meteorclient.systems.modules.Module;
import minegame159.meteorclient.utils.entity.EntityUtils;
import minegame159.meteorclient.utils.entity.SortPriority;
import minegame159.meteorclient.utils.entity.Target;
import minegame159.meteorclient.utils.entity.TargetUtils;
import minegame159.meteorclient.utils.player.InvUtils;
import minegame159.meteorclient.utils.player.PlayerUtils;
import minegame159.meteorclient.utils.player.Rotations;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.SwordItem;
import net.minecraft.util.Hand;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.List;

public class KillAura extends Module {
    public enum Weapon {
        Sword,
        Axe,
        Both,
        Any
    }

    public enum RotationMode {
        Always,
        OnHit,
        None
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTargeting = settings.createGroup("Targeting");
    private final SettingGroup sgDelay = settings.createGroup("Delay");

    // General

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
            .name("auto-switch")
            .description("Switches to your selected weapon when attacking the target.")
            .defaultValue(false)
            .build()
    );

    private final Setting<Weapon> weapon = sgGeneral.add(new EnumSetting.Builder<Weapon>()
            .name("weapon")
            .description("Only attacks an entity when a specified item is in your hand.")
            .defaultValue(Weapon.Both)
            .build()
    );

    private final Setting<Boolean> onlyOnClick = sgGeneral.add(new BoolSetting.Builder()
            .name("only-on-click")
            .description("Only attacks when hold left click.")
            .defaultValue(false)
            .build()
    );

    private final Setting<Boolean> onlyWhenLook = sgGeneral.add(new BoolSetting.Builder()
            .name("only-when-look")
            .description("Only attacks when you are looking at the entity.")
            .defaultValue(false)
            .build()
    );

    private final Setting<Boolean> randomTeleport = sgGeneral.add(new BoolSetting.Builder()
            .name("random-teleport")
            .description("Randomly teleport around the target")
            .defaultValue(false)
            .visible(() -> !onlyWhenLook.get())
            .build()
    );

    private final Setting<RotationMode> rotation = sgGeneral.add(new EnumSetting.Builder<RotationMode>()
            .name("rotate")
            .description("Determines when you should rotate towards the target.")
            .defaultValue(RotationMode.Always)
            .build()
    );

    private final Setting<Double> hitChance = sgGeneral.add(new DoubleSetting.Builder()
            .name("hit-chance")
            .description("The probability of your hits landing.")
            .defaultValue(100)
            .min(0)
            .max(100)
            .sliderMax(100)
            .build()
    );

    private final Setting<Boolean> pauseOnCombat = sgGeneral.add(new BoolSetting.Builder()
            .name("pause-on-combat")
            .description("Freezes Baritone temporarily until you are finished attacking the entity.")
            .defaultValue(true)
            .build()
    );

    // Targeting

    private final Setting<Object2BooleanMap<EntityType<?>>> entities = sgTargeting.add(new EntityTypeListSetting.Builder()
            .name("entities")
            .description("Entities to attack.")
            .defaultValue(new Object2BooleanOpenHashMap<>(0))
            .onlyAttackable()
            .build()
    );

    private final Setting<Double> range = sgTargeting.add(new DoubleSetting.Builder()
            .name("range")
            .description("The maximum range the entity can be to attack it.")
            .defaultValue(4)
            .min(0).max(6)
            .sliderMax(6)
            .build()
    );
    
    private final Setting<Double> wallsRange = sgTargeting.add(new DoubleSetting.Builder()
            .name("walls-range")
            .description("The maximum range the entity can be attacked through walls.")
            .defaultValue(3)
            .min(0).max(6)
            .sliderMax(6)
            .build()
    );

    private final Setting<SortPriority> priority = sgTargeting.add(new EnumSetting.Builder<SortPriority>()
            .name("priority")
            .description("How to filter targets within range.")
            .defaultValue(SortPriority.LowestHealth)
            .build()
    );

    private final Setting<Integer> maxTargets = sgTargeting.add(new IntSetting.Builder()
            .name("max-targets")
            .description("How many entities to target at once.")
            .defaultValue(1)
            .min(1).max(10)
            .sliderMin(1).sliderMax(5)
            .build()
    );

    private final Setting<Boolean> ignoreWalls = sgTargeting.add(new BoolSetting.Builder()
            .name("ignore-walls")
            .description("Whether or not to attack the entity through a wall.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> babies = sgTargeting.add(new BoolSetting.Builder()
            .name("babies")
            .description("Whether or not to attack baby variants of the entity.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> nametagged = sgTargeting.add(new BoolSetting.Builder()
            .name("nametagged")
            .description("Whether or not to attack mobs with a name tag.")
            .defaultValue(false)
            .build()
    );

    // Delay

    private final Setting<Boolean> smartDelay = sgDelay.add(new BoolSetting.Builder()
            .name("smart-delay")
            .description("Uses the vanilla cooldown to attack entities.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Integer> hitDelay = sgDelay.add(new IntSetting.Builder()
            .name("hit-delay")
            .description("How fast you hit the entity in ticks.")
            .defaultValue(0)
            .min(0)
            .sliderMax(60)
            .visible(() -> !smartDelay.get())
            .build()
    );

    private final Setting<Boolean> randomDelayEnabled = sgDelay.add(new BoolSetting.Builder()
            .name("random-delay-enabled")
            .description("Adds a random delay between hits to attempt to bypass anti-cheats.")
            .defaultValue(false)
            .visible(() -> !smartDelay.get())
            .build()
    );

    private final Setting<Integer> randomDelayMax = sgDelay.add(new IntSetting.Builder()
            .name("random-delay-max")
            .description("The maximum value for random delay.")
            .defaultValue(4)
            .min(0)
            .sliderMax(20)
            .visible(() -> randomDelayEnabled.get() && !smartDelay.get())
            .build()
    );

    private int hitDelayTimer;
    private int randomDelayTimer;
    private boolean wasPathing;

    private final List<Entity> entityList = new ArrayList<>();

    public KillAura() {
        super(Categories.Combat, "kill-aura", "Attacks specified entities around you.");
    }

    @Override
    public void onDeactivate() {
        hitDelayTimer = 0;
        randomDelayTimer = 0;
        entityList.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!mc.player.isAlive() || PlayerUtils.getGameMode() == GameMode.SPECTATOR) return;

        TargetUtils.getList(entity -> {
            if (entity.equals(mc.player) || entity.equals(mc.cameraEntity)) return false;
            if ((entity instanceof LivingEntity && ((LivingEntity) entity).isDead()) || !entity.isAlive()) return false;
            if (entity.distanceTo(mc.player) > range.get()) return false;
            if (!entities.get().getBoolean(entity.getType())) return false;
            if (!nametagged.get() && entity.hasCustomName()) return false;
            if (ignoreWalls.get()) {
                if (EntityUtils.distanceToCamera(entity) > wallsRange.get()) return false;
            } else {
                if (!PlayerUtils.canSeeEntity(entity)) return false;
            }
            if (entity instanceof PlayerEntity) {
                if (((PlayerEntity) entity).isCreative()) return false;
                if (!Friends.get().shouldAttack((PlayerEntity) entity)) return false;
            }
            return !(entity instanceof AnimalEntity) || babies.get() || !((AnimalEntity) entity).isBaby();
        }, priority.get(), entityList, maxTargets.get());

        if (entityList.isEmpty()) {
            if (wasPathing) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("resume");
                wasPathing = false;
            }
            return;
        }

        if (pauseOnCombat.get() && BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing() && !wasPathing) {
            BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("pause");
            wasPathing = true;
        }

        if (delayCheck()) {
            entityList.forEach(this::attack);
        }

        if (randomTeleport.get() && !onlyWhenLook.get()) {
            Entity target = entityList.get(0);
            mc.player.updatePosition(target.getX() + randomOffset(), target.getY(), target.getZ() + randomOffset());
        }

        if (rotation.get() == RotationMode.Always && !entityList.isEmpty() && itemInHand()) {
            rotate(entityList.get(0), null);
        }
    }

    private double randomOffset() {
        return Math.random() * 4 - 2;
    }

    private boolean delayCheck() {
        if (smartDelay.get()) return mc.player.getAttackCooldownProgress(0.5f) >= 1;

        if (hitDelayTimer >= 0) {
            hitDelayTimer--;
            return false;
        }
        else {
            hitDelayTimer = hitDelay.get();
        }

        if (randomDelayEnabled.get()) {
            if (randomDelayTimer > 0) {
                randomDelayTimer--;
                return false;
            } else {
                randomDelayTimer = (int) Math.round(Math.random() * randomDelayMax.get());
            }
        }

        return true;
    }

    private boolean attack(Entity target) {
        if (Math.random() > hitChance.get() / 100) return false;
        if (onlyOnClick.get() && !mc.options.keyAttack.isPressed()) return false;
        if (onlyWhenLook.get() && (!target.equals(mc.targetedEntity))) return false;

        if (rotation.get() == RotationMode.OnHit) {
            rotate(target, () -> hitEntity(target));
        } else {
            hitEntity(target);
        }

        return true;
    }

    private void hitEntity(Entity target) {
        if (autoSwitch.get()) {
            int slot = InvUtils.findItemInHotbar(itemStack -> {
                Item item = itemStack.getItem();

                switch(weapon.get()) {
                    case Axe:        return item instanceof AxeItem;
                    case Sword:      return item instanceof SwordItem;
                    case Both:       return item instanceof AxeItem || item instanceof SwordItem;
                    default:         return true;
                }
            });

            if (slot == -1) return;
            mc.player.inventory.selectedSlot = slot;
        }

        if (!itemInHand()) return;

        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private void rotate(Entity target, Runnable callback) {
        Rotations.rotate(Rotations.getYaw(target), Rotations.getPitch(target, Target.Body), callback);
    }

    private boolean itemInHand() {
        switch(weapon.get()) {
            case Axe:        return mc.player.getMainHandStack().getItem() instanceof AxeItem;
            case Sword:      return mc.player.getMainHandStack().getItem() instanceof SwordItem;
            case Both:       return mc.player.getMainHandStack().getItem() instanceof AxeItem || mc.player.getMainHandStack().getItem() instanceof SwordItem;
            default:         return true;
        }
    }

    @Override
    public String getInfoString() {
        if (!entityList.isEmpty()) {
            Entity targetFirst = entityList.get(0);
            if (targetFirst instanceof PlayerEntity) return targetFirst.getEntityName();
            return targetFirst.getType().getName().getString();
        }
        return null;
    }

    public Entity getTarget() {
        if (!entityList.isEmpty()) return entityList.get(0);
        return null;
    }
}
