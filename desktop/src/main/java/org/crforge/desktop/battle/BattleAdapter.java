package org.crforge.desktop.battle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.IntFunction;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.match.Hand;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.match.Timeline;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.unit.AreaEffectEntity;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.TowerEntity;
import org.crforge.core.battle.unit.UnitData;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.target.TargetView;

/**
 * Reads a battle core session into what the renderers draw: one {@link EntityView} per entity of
 * the holder's live list and the match's sides and clock. The battle core knows nothing of the
 * renderers; this is the only place the desktop module reads its entities.
 *
 * <p>Nothing is derived that the battle does not hold: every value is read from the battle's own
 * objects as they stand between two steps, and nothing is written back.
 */
public final class BattleAdapter {

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
    for (BattleEntity entity : session.getBattle().getBattle().getHolder().entities()) {
      EntityView view = entity(entity);
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
        session.getScenarioCase() == null ? null : session.getScenarioCase().name(),
        session.messages(sideNames));
  }

  /**
   * One entity as the renderers draw it, or null for an object that stands nowhere on the arena,
   * such as an action owner.
   */
  public static EntityView entity(BattleEntity entity) {
    if (entity instanceof WorldEntity world) {
      return character(world);
    }
    if (entity instanceof ProjectileEntity projectile) {
      return projectile(projectile);
    }
    if (entity instanceof AreaEffectEntity area) {
      return areaEffect(area);
    }
    return null;
  }

  private static EntityView character(WorldEntity entity) {
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
    return new EntityView(
        entity.getId(),
        kind,
        entity.side(),
        data.name(),
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
        0,
        0,
        0,
        0,
        character == null ? 0 : character.getSpeedBudget(),
        character == null ? null : character.getUnit());
  }

  private static EntityView projectile(ProjectileEntity projectile) {
    return new EntityView(
        projectile.getId(),
        EntityView.Kind.PROJECTILE,
        projectile.getSide(),
        projectile.getData().name(),
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
        0,
        null);
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
        null);
  }

  private static BattleFrame.SideView side(BattleSession session, LadderMatch match, int side) {
    MatchSide matchSide = match.side(side);
    List<BattleFrame.CardView> hand = new ArrayList<>(Arrays.asList(new BattleFrame.CardView[4]));
    for (int slot = 0; slot < Hand.SLOTS; slot++) {
      MatchCard card = session.handCard(side, slot);
      if (card != null) {
        hand.set(
            slot,
            new BattleFrame.CardView(card.name(), card.cost(), session.isPending(side, slot)));
      }
    }
    MatchCard next = session.nextCard(side);
    return new BattleFrame.SideView(
        side,
        matchSide.getElixir(),
        matchSide.wholeElixir(),
        match.crowns(side),
        Collections.unmodifiableList(hand),
        next == null ? null : new BattleFrame.CardView(next.name(), next.cost(), false));
  }
}
