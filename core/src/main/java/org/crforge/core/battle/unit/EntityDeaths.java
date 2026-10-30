/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.RunActionOnTroopDestroyed;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.projectile.ProjectileLauncher;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.combat.AreaDamage;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.combat.LevelScaling;
import org.crforge.core.pathfinding.combat.ScalingGlobals;
import org.crforge.core.pathfinding.combat.ScalingMode;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.target.TargetView;
import org.crforge.core.pathfinding.target.ValidatorQueries;

/**
 * What an arena entity's death brings: the death handler, which schedules the death action and the
 * killed action and pays the killing side; the death slot inside the killing hit - the death damage
 * around the dying entity with its pushback, its death projectiles, its death spawn and its buffs'
 * death spawns, the elixir it pays its own king, and the notice to every run listening for a
 * destroyed object; and the deaths outside a hit: at a removal, at a lifetime's decay and as a
 * carrier leaves its riders. The pushing area a dash's landing shares with the death damage lives
 * here too.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the death handler scheduling the death action and, unless the entity killed"
            + " itself, the killed action on the dying entity with its killer as the cause; the"
            + " death slot inside the killing hit - the death damage around the dying entity with"
            + " its pushback, and the death spawn on its ring, whose children are"
            + " CharacterSpawns' - held by the reference battles golem_death_pushes_minipekka_pekka,"
            + " troops_golem_dies_mid_fight, card_Tombstone and hero_tombstone; a bomb's death slot"
            + " as its deploy ends, without the death hooks, held by"
            + " giant_skeleton_bomb_pushes_knight and card_GiantSkeleton; a lifetime's death"
            + " without the death handler, held by card_Tombstone. Refused: a death whose row sets"
            + " a column of the slot not modelled, and a death hook with no attacker or no pending"
            + " pass ahead of it. Left out: the elixir a death gives. Not modelled: the copy of the"
            + " attacker the game makes as a death hook's cause.")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class EntityDeaths {

  private final BattleWorld world;

  /** The most victims a death's damage takes. */
  private static final int DEATH_DAMAGE_LIMIT = 1000;

  /**
   * The runs listening for destroyed objects, in the order they started listening: each hears of
   * every object whose death slot starts until it is let go.
   */
  private final List<RunActionOnTroopDestroyed.Listener> destroyedListeners = new ArrayList<>();

  /**
   * A death: runs when a hit takes an arena entity's hit points to zero, in the pass that lands it,
   * once the entity has switched off what it no longer does.
   *
   * <p>The death slot comes first, inside the killing hit. Its death damage lands around the entity
   * - see {@link #deathDamage} - and then its death spawn stands on the ring around it or on its
   * point - see {@link CharacterSpawns#deathSpawn}. A death whose row sets a column of the slot the
   * battle does not model is refused: a second or third death spawn, a death projectile or area
   * effect, a starting buff taken back, and the parts of the death spawn's placement not
   * established. The elixir a death gives is left out, as the battle models no elixir.
   *
   * <p>Between the two, the killer's hook: an arena entity whose hit killed, and whose row has a
   * killed-done action, schedules it on itself - see {@link #killedDone}.
   *
   * <p>Then the death handler's hook: the death slot has scheduled the row's death action already,
   * the dying entity its own cause (see {@link #slotDeathAction}), so the handler schedules the
   * killed action alone (see {@link #killedHook}), built for the entity and scheduled on its own
   * holder with the row's own delay, what killed it as the cause: the unit for a direct hit or its
   * area, the projectile itself for an impact, the source of a typed hit, the killer of a kill. The
   * entity stays in the tick's snapshot until the closing cleanup, so a hook with no delay runs in
   * the next pending pass of the same tick: phase 2 after a hit in a component pass, phase 3 after
   * an impact or a typed hit, and at once after a kill inside a pending pass.
   *
   * <p>A buff's damage over time kills with the buff's source as the attacker, for the side the
   * buff was applied for (see {@link HitQueue#dealBuffDamageNow}). A death with a killing side and
   * no attacker - the damage of a buff whose source has left the battle or never had one, or a hit
   * of the evolved Dart Goblin's poison run, which kills for the run's side - is handed in the game
   * a cause that carries that side alone and no object, which the battle does not hold. The battle
   * schedules the killed action with no cause when no action of its chain reads more of a cause
   * than its object (see {@link BattleAction#readsSideAloneCause}): the evolved Wall Breaker's
   * spawn, whose source is its owner, and the Boss Bandit's check of its killer's row, which finds
   * no object and runs nothing.
   *
   * <p>Refused rather than guessed: a killed action with no attacker whose chain reads the side,
   * level or point of its cause, a killed action whose attacker is an object of another kind than a
   * unit, a projectile or an area effect, and a death hook scheduled after the tick's last pending
   * pass, which would leave with the entity. The cause is the attacker's own holder, where the game
   * hands the hook a copy of the attacker made at the kill; a hook reads no more of it than its
   * presence and its row, which the copy shares.
   *
   * <p>After the hooks, the death handler's reward: in a match, a player's unit that gives elixir
   * on its death pays it to the king of the killing side, ten times its column in ten-thousandths;
   * a death with no killing side pays nothing.
   *
   * @param dying the entity that died
   * @param attacker what killed it: an arena entity, a projectile, or null for nothing
   * @param killingSide the side of the killing hit, or -1 for none
   */
  void entityDied(WorldEntity dying, BattleEntity attacker, int killingSide) {
    UnitData data = dying.getData();
    deathSlot(dying, data);
    killedDone(dying, attacker);
    killedHook(dying, attacker, data, killingSide);
    deathReward(dying, data, killingSide);
  }

  /**
   * The killer's hook, which the hit-points chain calls on the attacker of every hit, after the
   * dying entity's death slot and before its death handler: on a kill, an arena entity whose row
   * has a killed-done action schedules it on its own holder with the row's own delay, the entity it
   * killed as the cause. Outside a pending pass it runs in the killer's next pending pass of the
   * tick: phase 2 after a melee hit or a dash landing. A unit that killed itself is no attacker
   * here.
   *
   * <p>A projectile's kill is its shooter's: the hit-points chain hands the hook to the
   * projectile's launcher when that is a character, which schedules its own killed-done action the
   * same way, the entity killed its cause; a projectile without one runs none.
   *
   * <p>Refused rather than guessed: a kill after the tick's last pending pass, as for the death
   * hooks. Of the hook's other blocks, the evolved Pekka's TempResurrect hands a character's kill
   * to the battle's presentation listener and changes nothing the battle reads, so it has no part
   * here; a buff or a conversion on a kill read columns the battle refuses as it creates the unit.
   * Its first block, the reference a row that passes over buffed targets drops on every hit, comes
   * first - see {@link HitQueue#referenceDrop}.
   *
   * @param dying the entity killed
   * @param attacker what killed it
   */
  private void killedDone(WorldEntity dying, BattleEntity attacker) {
    world.getHits().referenceDrop(attacker, dying, true);
    // A projectile's kill reaches its shooter: its launcher, when that is a character. With none
    // the projectile itself hears the kill, which runs no row.
    BattleEntity hook =
        attacker instanceof ProjectileEntity projectile ? projectile.getOwner() : attacker;
    if (!(hook instanceof WorldEntity killer) || killer == dying) {
      return;
    }
    String action = killer.getData().onKilledDoneAction();
    if (action == null) {
      return;
    }
    if (!world.getHolder().hasPendingPassAhead()) {
      throw new UnsupportedOperationException(
          killer.name()
              + " killed after the tick's last pending pass, where its killed-done action would"
              + " wait; such a kill is not established");
    }
    boolean inPendingPass = world.getHolder().isInPendingPass();
    world.getEvents().killedDoneScheduled(killer, dying, action, inPendingPass);
    killer
        .actionHolder()
        .schedule(
            world.getActions().build(action, world.binding(killer)),
            ActionHolder.OWN_DELAY,
            false,
            dying.actionHolder());
  }

  /**
   * The death handler's reward: ManaOnDeathForOpponent, stored by the loader as ten times the
   * column, paid in ten-thousandths to the king in the killing side's tower slot. Outside a match
   * no king holds elixir and nothing is paid.
   */
  private void deathReward(WorldEntity dying, UnitData data, int killingSide) {
    int amount = data.manaOnDeathForOpponent() * 10;
    if (amount < 1 || world.getKingElixir() == null || killingSide < 0 || killingSide > 1) {
      return;
    }
    world.getKingElixir().add(killingSide, amount);
    world.getEvents().deathElixirPaid(dying, killingSide, amount);
  }

  /**
   * The death handler's hook: the dying unit's killed action alone, with what killed it as the
   * cause; the death slot schedules the death action (see {@link #slotDeathAction}). None when the
   * row has no killed action, when the death has no killing side, or when the unit killed itself on
   * the killing side. With no attacker the game's cause is the killing side alone and no object:
   * the action is scheduled with no cause when no action of its chain reads more of a cause than
   * its object. Refused rather than guessed: any other killed action with no attacker, whose chain
   * reads what a side alone holds, one whose attacker is an object of another kind, and one
   * scheduled after the tick's last pending pass.
   *
   * @param dying the entity that died
   * @param attacker what killed it, or null for nothing
   * @param data its row
   * @param killingSide the side of the killing hit, or -1 for none
   */
  private void killedHook(
      WorldEntity dying, BattleEntity attacker, UnitData data, int killingSide) {
    if (data.onKilledAction() == null || killingSide == -1) {
      return;
    }
    // Built here for a death with no attacker, whose chain is asked whether it reads its cause.
    BattleAction killed =
        attacker == null
            ? world.getActions().build(data.onKilledAction(), world.binding(dying))
            : null;
    ActionHolder cause;
    int side;
    if (killed != null && !chainReads(killed, BattleAction::readsSideAloneCause)) {
      // No action of the chain reads anything of the cause but the object a side alone lacks.
      cause = null;
      side = killingSide;
    } else if (attacker instanceof WorldEntity entity) {
      cause = entity.actionHolder();
      side = entity.side();
    } else if (attacker instanceof ProjectileEntity projectile) {
      cause = projectile.actionHolder();
      side = projectile.getSide();
    } else if (attacker instanceof AreaEffectEntity areaEffect) {
      cause = areaEffect.actionHolder();
      side = areaEffect.side();
    } else if (attacker == null) {
      throw new UnsupportedOperationException(
          dying.name()
              + " died with no attacker and its killed action reads its cause, which would be a"
              + " side alone, not modelled");
    } else {
      throw new UnsupportedOperationException(
          dying.name()
              + " was killed by "
              + attacker.getClass().getSimpleName()
              + ", whose cause its killed action would read, not modelled");
    }
    if (attacker == dying && side == killingSide) {
      return;
    }
    if (!world.getHolder().hasPendingPassAhead()) {
      throw new UnsupportedOperationException(
          dying.name()
              + " died after the tick's last pending pass, where its death hooks would leave with"
              + " it; such a death is not established");
    }
    List<String> hooks = List.of(data.onKilledAction());
    boolean inPendingPass = world.getHolder().isInPendingPass();
    world.getEvents().deathHooksScheduled(dying, attacker, side, hooks, inPendingPass);
    if (killed == null) {
      killed = world.getActions().build(data.onKilledAction(), world.binding(dying));
    }
    dying.actionHolder().schedule(killed, ActionHolder.OWN_DELAY, false, cause);
  }

  /**
   * Whether an action or any action chained after it, each scheduled with the same cause, answers
   * the given question about its cause. A chain that comes back to an action already asked has been
   * asked in full.
   */
  private static boolean chainReads(BattleAction action, Predicate<BattleAction> reads) {
    Set<BattleAction> asked = Collections.newSetFromMap(new IdentityHashMap<>());
    for (BattleAction step = action; step != null && asked.add(step); step = step.nextAction()) {
      if (reads.test(step)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The death slot's last act: the row's death action, built for the dying object and scheduled on
   * its own holder with the row's own delay, the dying object its own cause, rather than from the
   * death handler with what killed it. So an area effect it spawns is for the dying object's side,
   * and a death the slot runs without the death handler (a removal, a rider let go, a decay) has it
   * too. It runs in the next pending pass of the tick; after the tick's last pending pass the
   * object leaves at the closing cleanup with the entry unrun.
   *
   * @param dying the object dying
   * @param data its row
   */
  private void slotDeathAction(WorldEntity dying, UnitData data) {
    List<String> hooks = List.of(data.onDeathAction());
    boolean inPendingPass = world.getHolder().isInPendingPass();
    world.getEvents().deathHooksScheduled(dying, dying, dying.side(), hooks, inPendingPass);
    dying
        .actionHolder()
        .schedule(
            world.getActions().build(data.onDeathAction(), world.binding(dying)),
            ActionHolder.OWN_DELAY,
            false,
            dying.actionHolder());
  }

  /**
   * The death of an object without hit points that its state visit removes - a bomb as its deploy
   * ends: its death slot, with no death handler after it, so no hooks. It leaves at a later
   * cleanup, once the visit has asked for its removal.
   *
   * @param dying the object
   */
  void deathAtRemoval(WorldEntity dying) {
    world.getEvents().diedAtRemoval(dying);
    deathSlot(dying, dying.getData());
  }

  /**
   * The death of a character whose lifetime decay took its last hit point, in its hit-points visit:
   * its death slot, with no death handler after it. It leaves at the closing cleanup of the tick.
   * Then its death action, and not its killed action, is scheduled on it with itself as the cause,
   * and runs in the tick's next pending pass.
   *
   * @param dying the character
   * @param hitPointsBefore its hit points before the decay's last step
   */
  void decayDeath(WorldEntity dying, int hitPointsBefore) {
    UnitData data = dying.getData();
    world.getEvents().decayDied(dying, hitPointsBefore);
    // The death slot schedules the death action, on itself with itself as the cause.
    deathSlot(dying, data);
  }

  /**
   * A rider let go as its parent leaves the holder, in the parent's removal notice: its death slot
   * runs - its death spawn stands where it rode - without the death handler, and it leaves at the
   * same cleanup. A parent whose row sets the inherited ignore list is refused.
   *
   * @param rider the rider
   * @param parent the parent that left
   */
  void parentLeft(CharacterEntity rider, CharacterEntity parent) {
    if (parent.getData().deathInheritIgnoreList()) {
      // Every entity accepted then would list the rider's id, which no run holds.
      throw new UnsupportedOperationException(
          parent.name() + " lets its riders go under an inherited ignore list, which is not held");
    }
    world.getEvents().parentLeft(rider, parent);
    deathSlot(rider, rider.getData());
  }

  /**
   * The death slot: what a dying object's row does as it dies, in order - its starting buff taken
   * off; its ManaOnDeath paid to its own side's king; its area effect at its point (kept the
   * creation's inset inside the arena), for its side and at its level, a clone's for a dying clone;
   * what its buffs leave; what the object switches off; its death damage; its death spawn; its
   * death projectiles. The switches come after the buffs: a child a buff leaves is visited as it is
   * made, and finds the dying object still moving. A death whose row sets a column of the slot the
   * battle does not model is refused.
   */
  private void deathSlot(WorldEntity dying, UnitData data) {
    if (!data.unmodelledDeathColumns().isEmpty()) {
      throw new UnsupportedOperationException(
          dying.name()
              + " died, and what its row does as it dies is not modelled: "
              + data.unmodelledDeathColumns());
    }
    destroyedNotice(dying);
    // Its starting buff comes off every unit and tower that lists an instance of it with the dying
    // object as its parent; the level setter applies it with none.
    if (data.startingBuff() != null) {
      for (BattleEntity entity : world.getHolder().entities()) {
        if (entity instanceof WorldEntity carrier && carrier.getBuffs() != null) {
          carrier.getBuffs().removeParented(data.startingBuff(), dying);
        }
      }
    }
    deathMana(dying, data);
    if (data.deathAreaEffect() != null) {
      // It is created as every game object is, its point kept the creation's inset inside each
      // edge of the arena: a unit pushed onto the edge leaves its area effect the inset inside it.
      AreaEffectEntity deathArea =
          world.createAreaEffect(
              data.deathAreaEffect(),
              CharacterSpawns.inset(dying.getView().getX(), world.getTileMap().width()),
              CharacterSpawns.inset(dying.getView().getY(), world.getTileMap().height()),
              dying.side(),
              dying.getPackedLevel(),
              null,
              "death",
              dying.name());
      // The death slot gives its area effect the dying object's clone answer.
      if (CharacterSpawns.isClone(dying)) {
        deathArea.markClone();
      }
    }
    world.getSpawns().buffDeathSpawns(dying);
    dying.deathSwitches();
    deathDamage(dying, data);
    world.getSpawns().deathSpawn(dying, data);
    deathProjectiles(dying, data);
    deathNotice(dying);
    if (data.onDeathAction() != null) {
      slotDeathAction(dying, data);
    }
  }

  /**
   * The death slot's payout: the row's ManaOnDeath, in whole elixir, to the king of the dying
   * object's own side, whatever killed it and whether or not it is a clone, up to the cap with what
   * goes above it counted as wasted. Outside a match no king holds elixir and nothing is paid.
   *
   * @param dying the object dying
   * @param data its row
   */
  private void deathMana(WorldEntity dying, UnitData data) {
    int side = dying.side();
    if (data.manaOnDeath() < 1 || world.getKingElixir() == null || side < 0 || side > 1) {
      return;
    }
    int amount = data.manaOnDeath() * KingElixir.SCALE;
    world.getKingElixir().add(side, amount);
    world.getEvents().deathManaPaid(dying, side, amount);
  }

  /**
   * The death slot's first act: every run listening for destroyed objects hears of the dying
   * object, in the order the runs started listening, before anything else the slot does.
   *
   * @param dying the object dying
   */
  private void destroyedNotice(WorldEntity dying) {
    if (destroyedListeners.isEmpty()) {
      return;
    }
    world.getEvents().destroyedNoticed(dying, destroyedListeners.size());
    for (RunActionOnTroopDestroyed.Listener listener : new ArrayList<>(destroyedListeners)) {
      listener.destroyed(dying.filterSubject(), dying.getId(), dying.side(), dying.actionHolder());
    }
  }

  /**
   * A run starts listening for destroyed objects.
   *
   * @param owner the object the run is on
   * @param action the run's row name
   * @param listener the run
   */
  void listenForDestroyed(
      WorldEntity owner, String action, RunActionOnTroopDestroyed.Listener listener) {
    destroyedListeners.add(listener);
    world.getEvents().destroyedListening(owner, action, true);
  }

  /**
   * A run stops listening for destroyed objects, as it is let go.
   *
   * @param owner the object the run is on
   * @param action the run's row name
   * @param listener the run
   */
  void unlistenForDestroyed(
      WorldEntity owner, String action, RunActionOnTroopDestroyed.Listener listener) {
    destroyedListeners.remove(listener);
    world.getEvents().destroyedListening(owner, action, false);
  }

  /**
   * The death slot's notice, after its projectiles and with the dying object's hit points already
   * at 0: for a dying character that is not a clone, every character of the live list, in its order
   * and the dying one included, is told of the death and may count a soul for it. No position and
   * no clock is read: a death anywhere on the arena counts, as its death slot runs.
   *
   * @param dying the object dying
   */
  private void deathNotice(WorldEntity dying) {
    if (dying instanceof CharacterEntity unit && unit.isClone()) {
      return;
    }
    for (BattleEntity entity : new ArrayList<>(world.getHolder().entities())) {
      if (entity instanceof CharacterEntity receiver) {
        receiver.countSoul(dying);
      }
    }
  }

  /**
   * The death slot's projectiles, after its death spawn: as many as the death spawn's count, each
   * on the dying object's side, launched from its point and height with the dying object as
   * launcher and owner, at its level re-based on the row's rarity, with no target and no delay.
   * Each is aimed at the row's spawn radius from that point, at an angle that starts at the row's
   * angle shift and steps by an equal share of the circle: the sine of the angle times the radius
   * over 1024 across the width, the sine of the angle and a quarter turn along the length, each
   * toward zero, the bottom side's turned across the width and the top side's along the length.
   * With no spawn radius - the Phoenix - the projectile is aimed at its own start. Each has its id
   * at once, joins the live list at the tick's closing cleanup and runs its registration pass.
   *
   * <p>A clone launches them as any unit: the game marks each a clone's projectile, which only what
   * the projectile spawns reads, so a row that only deals its damage (see {@link
   * ProjectileData#dealsOnlyDamage()}), as the Goblin Demolisher's, lands as the original's would,
   * and a row that also spawns characters where it lands, as the Phoenix's, makes each of them a
   * clone (see {@link CharacterSpawns#impactSpawn}): the Phoenix's egg is a clone of 1 hit point,
   * and so is the Phoenix it hatches.
   *
   * <p>Refused rather than guessed: a least radius, which draws each radius from the battle's
   * random source; a row with a spawner limit, whose count is what the limit has left; and a
   * clone's row that spawns an area effect or a projectile, runs an action, applies a buff or hops
   * on (see {@link ProjectileData#dealsDamageOrSpawnsCharacters()}), which no recording shows for a
   * clone.
   */
  private void deathProjectiles(WorldEntity dying, UnitData data) {
    ProjectileData row = data.deathSpawnProjectile();
    if (row == null) {
      return;
    }
    String refused = null;
    if (data.deathSpawnMinRadius() != 0) {
      refused = "draws each one's radius";
    } else if (data.spawnLimit() > 0) {
      refused = "counts them by what its spawner's limit has left";
    } else if (dying instanceof CharacterEntity character
        && character.isClone()
        && !row.dealsDamageOrSpawnsCharacters()) {
      refused = "is a clone, whose projectiles carry it to what they spawn";
    }
    boolean clone = dying instanceof CharacterEntity unit && unit.isClone();
    if (refused != null) {
      throw new UnsupportedOperationException(
          dying.name()
              + " launches "
              + row.name()
              + " as it dies and "
              + refused
              + ", not modelled");
    }
    int count = data.deathSpawnCount();
    int step = 360 / count;
    int radius = data.spawnRadius();
    int angle = data.spawnAngleShift();
    int x = dying.getView().getX();
    int y = dying.getView().getY();
    boolean bottom = (dying.side() & 1) == 0;
    for (int i = 0; i < count; i++) {
      int dx = FixedMath.div(FixedMath.sine1024(angle) * radius, 1024);
      int dy = FixedMath.div(FixedMath.sine1024(angle + 90) * radius, 1024);
      ProjectileEntity projectile = new ProjectileEntity(world, row, dying.side());
      ProjectileLauncher.launchOnDeath(
          projectile, dying, bottom ? x - dx : x + dx, bottom ? y + dy : y - dy);
      if (clone) {
        projectile.markClone();
      }
      world.launch(projectile);
      world.getEvents().deathProjectileLaunched(dying, projectile);
      angle += step;
    }
  }

  /**
   * The death damage: the area damage around the dying entity, with the entity as its owner, as the
   * death slot deals it. Its damage is the row's at the entity's level, scaled as a card's damage
   * whatever the entity, and a crown tower takes it raised by the row's crown-tower percent. It has
   * no hit id, no limit short of a thousand victims and no split, hits air units when the row
   * attacks air and ground ones unless the row attacks air alone, and takes the entity's own
   * targeting as its validator's owner, but not its buildings-only column. Each victim still alive
   * after its damage, with a movement component and a row that does not ignore pushback, is pushed
   * the row's death pushback away from the entity. Nothing happens without a radius, or with
   * neither damage nor pushback.
   */
  private void deathDamage(WorldEntity dying, UnitData data) {
    int radius = data.deathDamageRadius();
    int push = data.deathPushBack();
    int damage =
        LevelScaling.scale(
            ScalingGlobals.standard(),
            data.deathDamage(),
            dying.getPackedLevel(),
            ScalingMode.CARD_DAMAGE,
            data.rarity());
    if (radius < 1 || (damage < 1 && push < 1)) {
      return;
    }
    int towerDamage = ((Math.max(data.crownTowerDamagePercent(), -100) + 100) * damage + 99) / 100;
    pushingArea(dying, radius, damage, towerDamage, 0, push, false);
  }

  /**
   * The landing hit of a dash with a radius: the area damage around the landing point, the dashing
   * unit as its owner, its damage the row's dash damage at the unit's level, taken whole by a crown
   * tower too, under the hit id the landing took, with no split and a thousand victims at most. It
   * hits air units when the row attacks air and ground ones unless the row attacks air alone, and
   * pushes each victim still alive, with a movement component and a row that does not ignore
   * pushback, the row's dash pushback away from the landing point. It collects its victims as a
   * projectile's area does: a unit the untouchable test refuses as it collects, the immunity left
   * after a dash counted - one dashing under a row with a dash immunity among them - is passed by,
   * neither damaged nor pushed.
   *
   * @param dasher the unit that landed
   * @param damage the dash damage at its level
   * @param hitId the id the landing took
   */
  void dashLandingArea(CharacterEntity dasher, int damage, int hitId) {
    UnitData data = dasher.getData();
    pushingArea(dasher, data.dashRadius(), damage, damage, hitId, data.dashPushBack(), true);
  }

  /**
   * An area damage around an entity, the entity as its owner and its targeting as the validator's,
   * that pushes each victim away from its centre: no split, a thousand victims at most, air units
   * when the entity's row attacks air and ground ones unless it attacks air alone. It passes by a
   * hidden victim, and with the collection gate also one the untouchable test refuses, the immunity
   * left after a dash counted.
   *
   * @param gated true for an area that collects through the untouchable test (a dash landing's),
   *     false for one that passes by a hidden victim only (the death damage's)
   */
  private void pushingArea(
      WorldEntity owner,
      int radius,
      int damage,
      int towerDamage,
      int hitId,
      int push,
      boolean gated) {
    UnitData data = owner.getData();
    int x = owner.getView().getX();
    int y = owner.getView().getY();
    boolean air = data.attacksAir();
    AreaDamage.Area area =
        new AreaDamage.Area(
            x,
            y,
            radius,
            damage,
            towerDamage,
            hitId,
            DEATH_DAMAGE_LIMIT,
            false,
            air,
            !air || data.attacksGround(),
            false,
            push,
            x,
            y);
    List<TargetView> entities = new ArrayList<>();
    for (WorldEntity entity : world.presentNow()) {
      entities.add(entity.getTargetView());
    }
    AreaDamage.Outcome outcome =
        AreaDamage.damage(
            owner.getTargeting(),
            entities,
            area,
            ValidatorQueries.standard1v1(),
            new AreaDamage.Queries() {
              @Override
              public boolean untouchable(TargetView victim) {
                WorldEntity entity = world.entityOf(victim.getEntity());
                return gated ? entity.passedByAreaCollection() : entity.passedBy(false);
              }

              @Override
              public DamageResult damage(TargetView victim, int dealt, int id) {
                return world
                    .getHits()
                    .dealAreaDamage(owner, world.entityOf(victim.getEntity()), dealt, id);
              }

              @Override
              public boolean push(TargetView victim, int fromX, int fromY, int distance) {
                return world.entityOf(victim.getEntity()) instanceof CharacterEntity character
                    && character.pushedByArea(fromX, fromY, distance);
              }
            });
    world.getEvents().areaDamaged(owner, area, outcome);
  }
}
