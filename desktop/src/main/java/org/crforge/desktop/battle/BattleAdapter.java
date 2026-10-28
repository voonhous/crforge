/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.battle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.BurstAttack;
import org.crforge.core.battle.action.ChefCooking;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.Hand;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.match.Timeline;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.battle.unit.AreaEffectEntity;
import org.crforge.core.battle.unit.BuffData;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.TowerEntity;
import org.crforge.core.battle.unit.UnitData;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.crforge.core.pathfinding.target.TargetView;

/**
 * Reads a battle core session into what the renderers draw: one {@link EntityView} per entity of
 * the holder's live list and the match's sides and clock. The battle core knows nothing of the
 * renderers; this is the only place the desktop module reads its entities.
 *
 * <p>Snapshots read the battle's objects between steps. Known buff rows receive presentation
 * labels; their timers and clone identity come from the core, and nothing is written back.
 */
public final class BattleAdapter {

  // Explicit row identities: ZapFreeze is electrical, and snares/clone setup also stop attacks.
  // Unknown rows stay visible as generic effects instead of guessing from "Freeze" in a name.
  private static final Set<String> FREEZES =
      Set.of(
          "Freeze",
          "ContinueFreeze",
          "Event_Freeze",
          "IceWizardHero_FreezeBuff",
          "IceWizardHero_FreezeBuff_dummy",
          "IceGolemiteHero_Freeze_Buff_Base",
          "IceGolemiteHero_Freeze_Buff_Small",
          "IceGolemiteHero_Freeze_Buff_Medium",
          "IceGolemiteHero_Freeze_Buff_Large",
          "IceGolemiteHero_Freeze_Buff_Tower");

  private static final Set<String> STUNS =
      Set.of(
          "ZapFreeze",
          "Stun",
          "ElectroGiantZapFreeze",
          "MysteryBuff_ZapFreeze",
          "Knight_crazy_2_stun",
          "buff_goblinstein_doctor",
          "electro_dragon_hit_buff",
          "GiantHero_Slap_Stun",
          "Zap_EV1_WithDamage",
          "Tesla_EV1_WithDamage");

  private BattleAdapter() {
    // Utility class
  }

  /** Reads one frame of a session, its messages naming side 0 blue and side 1 red. */
  public static BattleFrame frame(BattleSession session) {
    return frame(session, BattleSession::sideName);
  }

  /**
   * Reads one frame of a session.
   *
   * @param session the session
   * @param sideNames the name the frame's messages give each side, as the screen colours it
   */
  public static BattleFrame frame(BattleSession session, IntFunction<String> sideNames) {
    List<EntityView> entities = new ArrayList<>();
    BattleRecords records = session.getBattle().getWorld().getRecords();
    for (BattleEntity entity : session.getBattle().getBattle().getHolder().entities()) {
      EntityView view = entity(entity, records);
      if (view != null) {
        entities.add(view);
      }
    }
    LadderMatch match = session.match();
    List<BattleFrame.SideView> sides = new ArrayList<>();
    int timeMs = session.getBattle().getBattle().getClockMs();
    boolean overtime = false;
    int rate = 0;
    boolean ended = false;
    int winner = -1;
    if (match != null) {
      for (int side = 0; side < match.playerCount(); side++) {
        sides.add(side(session, match, side));
      }
      Timeline timeline = match.getTimeline();
      timeMs = timeline.getTimeMs();
      overtime = timeline.overtime();
      int openingBar = timeline.row().fullBarMs().get(0);
      rate = Math.max(1, Math.round((float) openingBar / timeline.getFullBarMs()));
      ended = match.isEnded();
      winner = match.getWinner();
    }
    return new BattleFrame(
        session.tick(),
        timeMs,
        overtime,
        rate,
        List.copyOf(entities),
        List.copyOf(sides),
        ended,
        winner,
        session.isOver(),
        session.getHalted(),
        session.messages(sideNames));
  }

  /**
   * One entity as the renderers draw it, or null for an object that stands nowhere on the arena,
   * such as an action owner.
   */
  public static EntityView entity(BattleEntity entity) {
    return entity(entity, null);
  }

  /**
   * One entity as the renderers draw it, with the view columns of its row read from the records'
   * tables (a character whose row hides its health bar), or null for an object that stands nowhere
   * on the arena.
   *
   * @param records the battle's records, or null to read no view column
   */
  public static EntityView entity(BattleEntity entity, BattleRecords records) {
    if (entity instanceof WorldEntity world) {
      return character(world, records);
    }
    if (entity instanceof ProjectileEntity projectile) {
      return projectile(projectile);
    }
    if (entity instanceof AreaEffectEntity area) {
      return areaEffect(area);
    }
    return null;
  }

  private static EntityView character(WorldEntity entity, BattleRecords records) {
    UnitData data = entity.getData();
    GridEntity view = entity.getView();
    EntityView.Kind kind =
        entity instanceof TowerEntity
            ? EntityView.Kind.TOWER
            : data.building() ? EntityView.Kind.BUILDING : EntityView.Kind.TROOP;
    HitPoints hp = entity.getHitPoints();
    TargetView reference = entity.getTargeting().getReference();
    int state = view.getState();
    boolean deploying =
        state == GridEntityState.DEPLOYING || state == GridEntityState.WAITING_TO_DEPLOY;
    CharacterEntity character = entity instanceof CharacterEntity c ? c : null;
    boolean hidden = character != null && (character.hidden() || character.invisible());
    EntityView.Bar bar = bar(entity, data, hp, records);
    return new EntityView(
        entity.getId(),
        kind,
        entity.side(),
        data.name(),
        entity.level(),
        view.getX(),
        view.getY(),
        view.getCollisionRadius(),
        hp == null ? 0 : hp.getHitPoints(),
        hp == null ? 0 : hp.getMaximum(),
        hp == null ? 0 : hp.getShield(),
        hp == null ? 0 : hp.getShieldMaximum(),
        view.isAir(),
        data.king(),
        state,
        deploying,
        hidden,
        data.range(),
        data.minimumRange(),
        data.sightRange(),
        reference != null,
        reference == null ? 0 : reference.x(),
        reference == null ? 0 : reference.y(),
        view.getDirX(),
        view.getDirY(),
        bar == EntityView.Bar.TIMER ? timeLeftMs(hp, data) : 0,
        bar == EntityView.Bar.TIMER ? data.lifeTimeMs() : 0,
        0,
        0,
        0,
        0,
        0,
        character == null ? 0 : character.getSpeedBudget(),
        character == null ? null : character.getUnit(),
        meter(entity),
        statuses(entity),
        kind == EntityView.Kind.TOWER ? UnitRole.NONE : UnitRole.of(data),
        bar);
  }

  /**
   * What a character's bar shows: nothing when its row draws no bar at all ({@link
   * #hidesHealthBar}); a timer when it cannot be targeted now (its live tags) and its row gives it
   * a lifetime, so its hit points only run down over that lifetime; else its health. Without the
   * records no view column is read and only the timer is told apart.
   */
  private static EntityView.Bar bar(
      WorldEntity entity, UnitData data, HitPoints hp, BattleRecords records) {
    if (records != null && hidesHealthBar(records.tables(), data.name())) {
      return EntityView.Bar.NONE;
    }
    GridEntity view = entity.getView();
    boolean untargetable = (view.getFlags() & view.getFlagBits().untargetable()) != 0;
    if (untargetable && data.lifeTimeMs() > 0 && hp != null && hp.getMaximum() > 0) {
      return EntityView.Bar.TIMER;
    }
    return EntityView.Bar.HEALTH;
  }

  /**
   * A timer unit's time left: its share of hit points left times its lifetime, as its hit points
   * run down evenly over that lifetime.
   */
  private static int timeLeftMs(HitPoints hp, UnitData data) {
    return (int) ((long) data.lifeTimeMs() * hp.getHitPoints() / hp.getMaximum());
  }

  /**
   * Whether a unit is drawn with no health bar at all: its row hides the default bar (its
   * HideHealthbar column) and starts no client action of its own (its OnStartingClientActions
   * column) either, view columns the battle does not read. A row that hides the default bar but
   * starts client actions, as every hero, champion and many evolutions do with their own bar
   * (Healthbar_Medium and the like), keeps its bar; the ones left are decorations standing on
   * another unit, such as the Tombstone hero's.
   */
  static boolean hidesHealthBar(GameTables tables, String unit) {
    for (String table : List.of("characters", "buildings")) {
      if (tables.table(table).has(unit)) {
        GameRow row = tables.table(table).row(unit);
        return row.bool("HideHealthbar") && row.strings("OnStartingClientActions").isEmpty();
      }
    }
    return false;
  }

  /**
   * The bar an action running on a character shows over it, or null for none: the Royal Chef's
   * cooking on its king tower, the Dagger Duchess's charges on her towers. The read makes no action
   * holder for an entity that has none.
   */
  private static ActionMeter meter(WorldEntity entity) {
    ActionHolder holder = entity.madeActionHolder();
    if (holder == null) {
      return null;
    }
    for (ActionInstance instance : holder.running()) {
      if (instance instanceof ChefCooking.Run cooking) {
        return new ActionMeter(ActionMeter.Kind.COOKING, cooking.bar(), cooking.fullBar(), 0);
      }
      if (instance instanceof BurstAttack.Run burst) {
        return charges(burst);
      }
    }
    return null;
  }

  /**
   * The Dagger Duchess's charges as a bar of one segment per charge, counted in milliseconds of
   * recharge: each charge held fills one recharge time, and the recharge timer fills the next
   * segments by its share of the recharge time, as many as one recharge adds.
   */
  private static ActionMeter charges(BurstAttack.Run burst) {
    BurstAttack.Columns columns = burst.columns();
    int rechargeTime = Math.max(1, columns.rechargeTimeMs());
    int refill = Math.min(burst.rechargeMs(), rechargeTime) * columns.rechargeIncrement();
    return new ActionMeter(
        ActionMeter.Kind.CHARGES,
        burst.charges() * rechargeTime + refill,
        columns.maxChargeCount() * rechargeTime,
        columns.maxChargeCount());
  }

  private static List<UnitStatus> statuses(WorldEntity entity) {
    List<UnitStatus> statuses = new ArrayList<>();
    if (entity instanceof CharacterEntity character && character.isClone()) {
      statuses.add(new UnitStatus(UnitStatus.Kind.CLONE, "Clone", -1, -1, null, -1));
    }
    for (var instance : entity.getBuffs().items()) {
      if (instance.getRemaining() == 0) continue;
      BuffData buff = instance.getBuff();
      UnitStatus.Kind kind =
          FREEZES.contains(buff.name())
              ? UnitStatus.Kind.FROZEN
              : STUNS.contains(buff.name()) ? UnitStatus.Kind.STUNNED : UnitStatus.Kind.OTHER;
      statuses.add(
          new UnitStatus(
              kind,
              buff.cloneBuff() ? "Clone setup" : buff.name(),
              instance.getRemaining(),
              instance.getTotal(),
              sourceName(instance.getSource()),
              instance.getSide()));
    }
    return List.copyOf(statuses);
  }

  private static String sourceName(SpawnHost source) {
    if (source == null) return null;
    if (source instanceof WorldEntity unit) return unit.getData().name() + " #" + unit.getId();
    if (source instanceof AreaEffectEntity area) return area.getData().name() + " #" + area.getId();
    if (source instanceof ProjectileEntity projectile)
      return projectile.getData().name() + " #" + projectile.getId();
    return source.name();
  }

  private static EntityView projectile(ProjectileEntity projectile) {
    return new EntityView(
        projectile.getId(),
        EntityView.Kind.PROJECTILE,
        projectile.getSide(),
        projectile.getData().name(),
        projectile.level(),
        projectile.getX(),
        projectile.getY(),
        projectile.getData().radius(),
        0,
        0,
        0,
        0,
        false,
        false,
        -1,
        false,
        false,
        0,
        0,
        0,
        false,
        0,
        0,
        0,
        0,
        0,
        0,
        projectile.getAimX(),
        projectile.getAimY(),
        // The impact's area is a box when its row has a RadiusY, and the flying body is one when
        // its row has a ProjectileRadiusY: half widths across the arena, half heights along it.
        projectile.getData().radiusY(),
        projectile.getData().projectileRadiusY() >= 1 ? projectile.getData().projectileRadius() : 0,
        projectile.getData().projectileRadiusY(),
        0,
        null,
        null,
        List.of(),
        UnitRole.NONE,
        EntityView.Bar.HEALTH);
  }

  private static EntityView areaEffect(AreaEffectEntity area) {
    int lifetime =
        area.getLifetimeOverride() >= 0
            ? area.getLifetimeOverride()
            : area.getData().lifeDurationMs();
    return new EntityView(
        area.getId(),
        EntityView.Kind.AREA_EFFECT,
        area.side(),
        area.getData().name(),
        PackedLevel.level(area.getPackedLevel()),
        area.getX(),
        area.getY(),
        area.getData().radius(),
        0,
        0,
        0,
        0,
        false,
        false,
        -1,
        false,
        false,
        0,
        0,
        0,
        false,
        0,
        0,
        0,
        0,
        area.getCountdown(),
        lifetime,
        0,
        0,
        0,
        0,
        0,
        0,
        null,
        null,
        List.of(),
        UnitRole.NONE,
        EntityView.Bar.HEALTH);
  }

  private static BattleFrame.SideView side(BattleSession session, LadderMatch match, int side) {
    MatchSide matchSide = match.side(side);
    List<BattleFrame.CardView> hand = new ArrayList<>(Arrays.asList(new BattleFrame.CardView[4]));
    for (int slot = 0; slot < Hand.SLOTS; slot++) {
      MatchCard card = session.handCard(side, slot);
      if (card != null) {
        hand.set(
            slot,
            new BattleFrame.CardView(
                card.name(),
                card.cost(),
                session.handCardLevel(side, slot),
                session.isPending(side, slot),
                session.cardUnavailableReason(side, slot)));
      }
    }
    MatchCard next = session.nextCard(side);
    List<BattleFrame.AbilityView> abilities = new ArrayList<>(BattleSession.ABILITY_SLOTS);
    for (int slot = 0; slot < BattleSession.ABILITY_SLOTS; slot++) {
      abilities.add(session.abilityView(side, slot));
    }
    return new BattleFrame.SideView(
        side,
        matchSide.getElixir(),
        matchSide.wholeElixir(),
        match.crowns(side),
        Collections.unmodifiableList(hand),
        next == null
            ? null
            : new BattleFrame.CardView(
                next.name(), next.cost(), session.nextCardLevel(side), false, "not in hand"),
        Collections.unmodifiableList(abilities));
  }
}
