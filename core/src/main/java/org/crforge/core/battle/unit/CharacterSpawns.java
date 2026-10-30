/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.AirToGround;
import org.crforge.core.battle.action.Clone;
import org.crforge.core.battle.action.SpawnGuard;
import org.crforge.core.battle.action.WarpCharacter;
import org.crforge.core.battle.deploy.CardPlacement;
import org.crforge.core.battle.deploy.Formation;
import org.crforge.core.battle.deploy.PlacementSearch;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.spawn.SpawnArguments;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.battle.spawn.SpawnPassable;
import org.crforge.core.battle.spawn.SpawnPerform;
import org.crforge.core.battle.spawn.SpawnPlacement;
import org.crforge.core.battle.spawn.SpawnRow;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.crforge.core.pathfinding.grid.CellTests;
import org.crforge.core.pathfinding.grid.LaneAssignment;
import org.crforge.core.pathfinding.grid.Relocation;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.target.TargetView;

/**
 * The characters the battle makes after its card plays: a spawner's live spawn, a buff's spawn, a
 * carrier's riders, a projectile's impact spawn, a spawn action's characters, a guard, an area
 * effect's spawner, an activation's and a ghost's summons, a clone, a warp's landing, and the death
 * spawn on a dying object's ring; each child placed, registered with the holder and told to the
 * observers. The counts a row's children are numbered by live here.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the death spawn's children flying back to the ring, held by the reference"
            + " battle golem_death_pushes_minipekka_pekka; a ring turned over by the dying"
            + " object's lane and team, its children given a fixed priority, held by"
            + " card_SkeletonBalloon and evo_skeletonballoon_vs_musketeer; a single child on the"
            + " dying object's point, held by giant_skeleton_bomb_pushes_knight and"
            + " card_GiantSkeleton; several death spawn children in front of the dying object and"
            + " a building spawner's children in front of it, held by card_Tombstone and"
            + " card_GoblinHut; a child without hit points, the Rage Barbarian's bottle,"
            + " deploying for its row's deploy time though its spawn does not ask, held by"
            + " card_RageBarbarian. Refused: a least radius that draws, a child without hit"
            + " points that has a range, a spawner's turned or drawn ring and a spawner child"
            + " without hit points, a building, pathing or starting an action.")
public final class CharacterSpawns {

  private final BattleWorld world;

  CharacterSpawns(BattleWorld world) {
    this.world = world;
  }

  /** How many children each source has spawned so far, by the source's name. */
  private final Map<String, Integer> spawnCounts = new HashMap<>();

  /** How many clones the battle has made of each unit, by its name. */
  private final Map<String, Integer> cloneCounts = new HashMap<>();

  /** How many characters the battle has made, which a summon's name carries. */
  private int charactersMade;

  /**
   * Morphs a unit that has surfaced into its row's morph, as the arrival of a tunnel does: the new
   * object is made on the unit's point with its level, lane and share of its hit points, a building
   * facing as the unit did; it is queued with its registration visit in the state it is made in,
   * then set to the unit's deploying state through its setter. The damage on its way to the unit
   * passes to the new object. The unit leaves at the closing cleanup.
   *
   * <p>A morph hands the new object to the removal notice of every entity that referenced the old
   * one, whose replacement path clears a continuous-damage attacker's ramp; that path is not
   * established, so a morph of a unit such an attacker references is refused.
   *
   * <p>Like any object the holder admits, the new object is started at the closing cleanup's fold:
   * its row's starting action - the Goblin Drill's damage area, or the evolved Goblin Drill's
   * relocation - is scheduled then, and runs in its first pending pass of the next tick.
   *
   * @param old the unit that surfaced
   */
  void morph(CharacterEntity old) {
    UnitData data = spawnedRow(old.getData().spawnPathfindMorph());
    for (WorldEntity entity : world.presentNow()) {
      if (entity.getData().attackSequence().mode() != AttackSequence.MODE_NONE
          && entity.getTargeting().getReference() == old.getTargetView()) {
        throw new UnsupportedOperationException(
            entity.name()
                + " is a continuous-damage attacker referencing "
                + old.name()
                + " as it morphs, whose replacement is not modelled");
      }
    }
    CharacterEntity made = CharacterEntity.morphedFrom(old, data);
    made.startOnAdmission();
    world.getHolder().addRegistered(made);
    made.startDeployingAfterMorph();
    old.morphedAway();
    world.events().morphed(old, made);
  }

  /**
   * One firing of a character's spawner: its row's spawn character, as many as the firing makes,
   * each created for the spawner's side at its level re-based on the child's rarity, walking at
   * once when it has a speed - or deploying for its own deploy time when the spawner's row gives
   * its children their deploy - with no first-tick immunity, registered inside the post-hook pass
   * with its registration visit, and joining the live list at the tick's closing cleanup.
   *
   * <p>With a radius the children stand on its ring, as a death spawn's do. With none each stands
   * in front of the spawner, the spawner's collision radius and its own away toward the enemy, at
   * the first quarter turn of that offset the in-front test accepts, or one unit right of the
   * spawner where it accepts none.
   *
   * <p>A row that sets an angle shift turns the ring by it and by the angle the spawner faces, so
   * the Night Witch's Bats stand at its sides whichever way it walks. A unit's spawner fires as a
   * building's does; a stun holds its timer, and a wave due on the tick the unit dies still comes.
   *
   * <p>Refused rather than guessed: a ring drawn from the spawner's least radius, and a child
   * without hit points, a building, one that paths to its point or one with a starting action of
   * its own.
   *
   * @param spawner the character whose spawner fires
   * @param row the row the firing makes: the spawn character whose turn it is
   * @param count how many children the firing makes
   * @param radius the ring's radius, or 0 to place in front
   */
  void liveSpawn(CharacterEntity spawner, String row, int count, int radius) {
    UnitData data = spawner.getData();
    UnitData child = spawnedRow(row);
    if (radius != 0 && data.deathSpawnMinRadius() != 0) {
      throw new UnsupportedOperationException(
          spawner.name()
              + "'s spawner draws the ring its children stand on, which is not modelled");
    }
    if (child.hitpoints() <= 0
        || child.building()
        || child.spawnPathfindSpeed() != 0
        || child.onStartingAction() != null) {
      throw new UnsupportedOperationException(
          spawner.name()
              + "'s spawner makes "
              + child.name()
              + ", without hit points, a building, pathing to its point or starting an action,"
              + " which is not modelled");
    }
    int fromX = spawner.getView().getX();
    int fromY = spawner.getView().getY();
    for (int i = 0; i < count; i++) {
      int[] at =
          SpawnPlacement.position(
              fromX,
              fromY,
              i,
              count,
              false,
              radius,
              ringTurn(spawner),
              data.collisionRadius() + child.collisionRadius(),
              spawner.side() & 1,
              world.getTileMap().width() * TileMap.CELL_UNITS,
              (px, py) ->
                  SpawnPassable.passable(world.getTileMap(), px, py, child.collisionRadius()));
      int x = inset(at[0], world.getTileMap().width());
      int y = inset(at[1], world.getTileMap().height());
      int made = spawnCounts.merge(spawner.name(), 1, Integer::sum) - 1;
      CharacterEntity spawned =
          CharacterEntity.spawned(
              world,
              child,
              spawner.name() + "_" + made,
              spawner.side(),
              x,
              y,
              PackedLevel.level(PackedLevel.pack(spawner.getPackedLevel(), child.rarity())));
      // A row whose children deploy sets each deploying for its own deploy time.
      if (data.spawnCharacterWithDeploy()) {
        spawned.startDeploying();
      }
      cloneSpawn(spawner, spawned);
      world.getHolder().addRegistered(spawned);
      world.events().characterSpawned(spawner, spawned, x, y);
      // The spawner's last act on each child: its running actions hear of it.
      spawner.actions().childSpawned(spawned.getId());
    }
  }

  /**
   * One firing of a buff's spawner, through the character spawner: one child of the buff's spawn
   * object, made for its carrier's side at the carrier's level re-based on the child's rarity, in
   * front of the carrier as a character's spawner places it - the carrier's collision radius and
   * its own away toward the enemy, at the first quarter turn the in-front test accepts - with no
   * deploy and no first-tick immunity, registered at once with its registration visit, and joining
   * the live list at the next cleanup's fold. A clone carrier's child is a clone.
   *
   * <p>A child of the carrier's own row whose row limits its group, made by a carrier in a group,
   * is linked into the carrier's chain right after it. A buff whose spawner needs its carrier alive
   * files the carrier's id with the child: the fold admits it only while the carrier is listed and
   * not removable.
   *
   * <p>A firing with the carrier's chain already at the group's limit makes nothing; the visit
   * still counts it as one of the instance's firings.
   *
   * <p>A child whose carrier has left or is leaving by the fold is released there instead of
   * admitted: it leaves the chain and never joins the battle.
   *
   * <p>Refused rather than guessed: a carrier that is a tower or a building; and a child without
   * hit points, a building, one that paths to its point or one with a starting action of its own.
   *
   * @param carrier the entity that carries the buff
   * @param instance the instance whose spawner fires
   */
  void buffSpawn(WorldEntity carrier, BuffInstance instance) {
    BuffData buff = instance.getBuff();
    if (!(carrier instanceof CharacterEntity spawner) || spawner.getData().building()) {
      throw new UnsupportedOperationException(
          carrier.name()
              + " carries "
              + buff.name()
              + ", whose spawner fires on a tower or a building, which is not modelled");
    }
    UnitData data = spawner.getData();
    UnitData child = world.getRecords().unit(buff.spawnObject());
    if (child.hitpoints() <= 0
        || child.building()
        || child.spawnPathfindSpeed() != 0
        || child.onStartingAction() != null) {
      throw new UnsupportedOperationException(
          buff.name()
              + "'s spawner makes "
              + child.name()
              + ", without hit points, a building, pathing to its point or starting an action,"
              + " which is not modelled");
    }
    // The group: a carrier of the child's own row that is in a group counts its chain, from its
    // first unit to its last, and the call ends once the chain holds the limit. Nothing is made
    // then, and no one hears of it; the visit spends the firing all the same. A unit that died
    // still counts until it is released, as it leaves the chain only then.
    boolean grouped =
        child.groupMaxSize() >= 1 && spawner.inChain() && data.name().equals(child.name());
    if (grouped && spawner.chainSize() >= child.groupMaxSize()) {
      return;
    }
    int[] at =
        SpawnPlacement.position(
            spawner.getView().getX(),
            spawner.getView().getY(),
            0,
            1,
            false,
            0,
            data.collisionRadius() + child.collisionRadius(),
            spawner.side() & 1,
            world.getTileMap().width() * TileMap.CELL_UNITS,
            (px, py) ->
                SpawnPassable.passable(world.getTileMap(), px, py, child.collisionRadius()));
    int x = inset(at[0], world.getTileMap().width());
    int y = inset(at[1], world.getTileMap().height());
    int made = spawnCounts.merge(spawner.name(), 1, Integer::sum) - 1;
    CharacterEntity spawned =
        CharacterEntity.spawned(
            world,
            child,
            spawner.name() + "_" + made,
            spawner.side(),
            x,
            y,
            PackedLevel.level(PackedLevel.pack(spawner.getPackedLevel(), child.rarity())));
    if (grouped) {
      spawner.linkSpawnIntoChain(spawned);
    }
    cloneSpawn(spawner, spawned);
    // With SpawnerAliveRequired the spawner's id is filed beside the child, which the fold reads.
    world.getHolder().addRegistered(spawned, buff.spawnerAliveRequired() ? spawner.getId() : 0);
    world.events().characterSpawned(spawner, spawned, x, y);
  }

  /**
   * The riders of a character whose row attaches its spawner's children, made as it enters the
   * deploying state: in a card play, before the character itself is handed to the holder, so they
   * take the lower ids and every pass visits them before it; for a played unit that waits its turn,
   * in its state visit once the wait has run out, so they follow it. As many as the row's spawn
   * number, on the ring of its spawn radius - turned by its angle shift and facing when it sets one
   * - or on the parent's point with no radius, each at the parent's level re-based on its rarity,
   * set deploying for the parent's deploy time and facing as the parent faces, registered at once
   * with its registration visit, which sees no parent yet, and attached to the parent after it:
   * from its next movement visit it is placed around the parent.
   *
   * <p>Refused rather than guessed: a rider without hit points, a building, one that paths to its
   * point, one with a starting action, one that has riders of its own, and a ring drawn from the
   * least radius.
   *
   * @param parent the character entering the deploying state
   */
  void attachRiders(CharacterEntity parent) {
    UnitData data = parent.getData();
    UnitData child = spawnedRow(data.spawnCharacter());
    if (child.hitpoints() <= 0
        || child.building()
        || child.spawnPathfindSpeed() != 0
        || child.onStartingAction() != null
        || child.spawnAttach()
        || data.deathSpawnMinRadius() != 0) {
      throw new UnsupportedOperationException(
          parent.name()
              + "'s riders "
              + child.name()
              + " lack hit points, are a building, path to their point, start an action, carry"
              + " riders or stand on a drawn ring, which is not modelled");
    }
    int count = data.spawnNumber();
    int radius = data.spawnRadius();
    int fromX = parent.getView().getX();
    int fromY = parent.getView().getY();
    for (int i = 0; i < count; i++) {
      // Its angle on the ring before any turn, which gives its share of the arc it rides on.
      int angle = (count - 1 - i) * 360 / count;
      int[] at =
          SpawnPlacement.position(
              fromX,
              fromY,
              i,
              count,
              true,
              radius,
              ringTurn(parent),
              data.collisionRadius() + child.collisionRadius(),
              parent.side() & 1,
              world.getTileMap().width() * TileMap.CELL_UNITS,
              (px, py) ->
                  SpawnPassable.passable(world.getTileMap(), px, py, child.collisionRadius()));
      int x = inset(at[0], world.getTileMap().width());
      int y = inset(at[1], world.getTileMap().height());
      int made = spawnCounts.merge(parent.name(), 1, Integer::sum) - 1;
      CharacterEntity rider =
          CharacterEntity.spawned(
              world,
              child,
              parent.name() + "_" + made,
              parent.side(),
              x,
              y,
              PackedLevel.level(PackedLevel.pack(parent.getPackedLevel(), child.rarity())));
      if (data.deployTimeMs() != 0) {
        rider.deployFor(data.deployTimeMs());
        rider.getView().setDirX(parent.getView().getDirX());
        rider.getView().setDirY(parent.getView().getDirY());
      }
      world.getHolder().addRegistered(rider);
      world.events().characterSpawned(parent, rider, x, y);
      rider.attachTo(parent, angle);
      world.events().riderAttached(parent, rider, i, angle);
    }
  }

  /**
   * The degrees a spawner's ring is turned by: for a row that sets an angle shift, the shift plus
   * the angle the source faces, its heading as whole degrees; for any other row, none.
   */
  private static int ringTurn(WorldEntity source) {
    int shift = source.getData().spawnAngleShift();
    if (shift == 0) {
      return 0;
    }
    return shift + FixedMath.angleOfVector(source.getView().getDirX(), source.getView().getDirY());
  }

  /**
   * The character spawn of a projectile's impact: its children in the card formation around the
   * impact point, the spread their collision radius when there are several, each created kept
   * inside the arena, at the projectile's level, deploying for the row's deploy time when it has
   * one, and registered with its registration visit; only then is a child on the ground moved off
   * the water, onto the point the relocation gives where the child then stands, so the step its
   * registration visit took is kept. Each child joins the live list at the next cleanup's fold,
   * which starts it: its row's starting action is scheduled then. A clone's projectile makes each
   * child a clone of 1 hit point of 1, the clone setter running after its deploy countdown and
   * before the add that registers it. A deflected projectile whose row names a deflected spawn
   * makes that in place of its spawned character, as many of them, for the side the deflection gave
   * it: the hero Barbarian Barrel's roll, turned, lets out the barbarian without the hero's ability
   * for the deflector's side, which no champion slot follows.
   *
   * @param projectile the projectile that landed
   * @param x the impact point along the width
   * @param y the impact point along the length
   */
  public void impactSpawn(ProjectileEntity projectile, int x, int y) {
    ProjectileData data = projectile.getData();
    // Deflected once or more, the impact takes the row's deflected spawn when it names one.
    UnitData child =
        spawnedRow(
            projectile.getDeflections() >= 1 && data.deflectedCharacterSpawn() != null
                ? data.deflectedCharacterSpawn()
                : data.spawnCharacter());
    if (child.hitpoints() <= 0 || child.building() || child.spawnPathfindSpeed() != 0) {
      throw new UnsupportedOperationException(
          data.name()
              + "'s impact makes "
              + child.name()
              + ", without hit points, a building or pathing to its point, which is not"
              + " modelled");
    }
    int count = data.spawnCharacterCount();
    int w = world.getTileMap().width();
    int h = world.getTileMap().height();
    int lane = LaneAssignment.lane(world.getTileMap(), x, y, -1, 0);
    for (int i = 0; i < count; i++) {
      int[] offset =
          Formation.offset(
              i,
              count,
              count == 1 ? 0 : child.collisionRadius(),
              0,
              (projectile.side() & 1) == 0 ? 1 : 0,
              lane,
              child.spawnAngleShift(),
              0,
              true,
              Standard1v1Battle.LANE_BASED_DEPLOY_SEQUENCE);
      int cx = inset(x + offset[0], w);
      int cy = inset(y + offset[1], h);
      // The creation takes the child's lane from where it is made with the impact point's x as the
      // reference and no flag: a child made across the centre column from the impact, whose nearest
      // road is the impact's own, is swapped to the impact's side.
      int childLane = LaneAssignment.lane(world.getTileMap(), cx, cy, x, 0);
      int made = spawnCounts.merge(projectile.name(), 1, Integer::sum) - 1;
      CharacterEntity spawned =
          CharacterEntity.spawned(
              world,
              child,
              projectile.name() + "_" + made,
              projectile.side(),
              cx,
              cy,
              PackedLevel.level(PackedLevel.pack(projectile.getPackedLevel(), child.rarity())),
              childLane);
      // A fixed priority per child: the k-th is taken as (20k)^2 nearer by a selection.
      if (data.spawnConstPriority()) {
        spawned.getView().setSquaredDistanceReduction((i * 20) * (i * 20));
      }
      if (data.spawnCharacterDeployTimeMs() >= 1) {
        spawned.deployFor(data.spawnCharacterDeployTimeMs());
      }
      // A clone's projectile makes a clone: the clone setter takes the projectile's mark, before
      // the add registers the child.
      if (projectile.isClone()) {
        spawned.markClone(null);
      }
      // The child is queued by the add that registers it now: it joins the live list at the next
      // cleanup's fold, which starts it, scheduling its row's starting action then. It carries the
      // projectile's play, so a champion slot that follows the play follows it.
      spawned.setDeployIndex(projectile.getDeployIndex());
      // The impact stamps it with the projectile's cast.
      spawned.setCastId(projectile.castId());
      spawned.startOnAdmission();
      world.getHolder().addRegistered(spawned);
      world.events().characterSpawned(projectile, spawned, cx, cy);
      // The relocation reads where the child stands after its registration visit, so the step
      // that visit took is kept, and runs only for a child on the ground (its height and height
      // offset).
      GridEntity view = spawned.getView();
      if (view.getZ() + view.getHeightOffset() == 0) {
        int packed =
            Relocation.relocate(
                world.getGrid().getWidth(),
                world.getGrid().getHeight(),
                view.getX(),
                view.getY(),
                -1,
                world.getGrid()::water);
        view.setX(Relocation.unpackX(packed));
        view.setY(Relocation.unpackY(packed));
      }
    }
  }

  /**
   * What the buffs a dying object carries leave as it dies, each listed instance in turn from the
   * first: a buff with a death spawn makes its characters through the character spawner, unless the
   * dying object is a building. The instances stay listed; they leave with the object.
   *
   * <p>The children stand in front of the dying object, the dying object's collision radius and the
   * child's away toward its enemy, or on its point for a row that spawns on the same location. Each
   * is made at the instance's level re-based on the child's rarity, for the dying object's opponent
   * when the row spawns for the enemy, else for its side; it deploys when the row delays its
   * deploy, and for the child's own deploy time, facing the way the dying object faced; it has no
   * first-tick immunity. It is registered with its registration visit inside the pass of the death
   * and joins the live list at the tick's closing cleanup, whose fold starts it as it starts any
   * object the spawner queued: its row's starting action - the Witch Mother's curse hog's jump
   * check - is scheduled then and runs in its first pending pass of the next tick.
   *
   * <p>Refused rather than guessed: a ring (a death spawn radius), and a child that is a building
   * or paths to its point. A dying row that spawns the same unit instead is refused with the death
   * columns.
   */
  void buffDeathSpawns(WorldEntity dying) {
    if (dying.getBuffs() == null || dying.getData().building()) {
      return;
    }
    for (BuffInstance instance : new ArrayList<>(dying.getBuffs().items())) {
      BuffData buff = instance.getBuff();
      if (buff.deathSpawn() == null) {
        continue;
      }
      UnitData child = spawnedRow(buff.deathSpawn());
      if (buff.deathSpawnRadius() != 0 || child.building() || child.spawnPathfindSpeed() != 0) {
        throw new UnsupportedOperationException(
            dying.name()
                + " died carrying "
                + buff.name()
                + ", whose death spawn "
                + child.name()
                + " stands on a ring, is a building or paths to its point, which is not"
                + " modelled");
      }
      int side = buff.deathSpawnIsEnemy() ? dying.side() ^ 1 : dying.side();
      int fromX = dying.getView().getX();
      int fromY = dying.getView().getY();
      List<CharacterEntity> made = new ArrayList<>();
      for (int i = 0; i < buff.deathSpawnCount(); i++) {
        int[] at =
            SpawnPlacement.position(
                fromX,
                fromY,
                i,
                buff.deathSpawnCount(),
                buff.deathSpawnSameLocation(),
                0,
                dying.getData().collisionRadius() + child.collisionRadius(),
                dying.side() & 1,
                world.getTileMap().width() * TileMap.CELL_UNITS,
                (px, py) ->
                    SpawnPassable.passable(world.getTileMap(), px, py, child.collisionRadius()));
        int x = inset(at[0], world.getTileMap().width());
        int y = inset(at[1], world.getTileMap().height());
        int count = spawnCounts.merge(dying.name(), 1, Integer::sum) - 1;
        CharacterEntity spawned =
            CharacterEntity.spawned(
                world,
                child,
                dying.name() + "_" + count,
                side,
                x,
                y,
                PackedLevel.level(PackedLevel.pack(instance.getPackedLevel(), child.rarity())));
        if (buff.deathSpawnDeployDelay()) {
          spawned.startDeploying();
        }
        if (child.deployTimeMs() != 0) {
          // A child made with a deploy time faces the way the dying object faced.
          spawned.deployFor(child.deployTimeMs());
          spawned.getView().setDirX(dying.getView().getDirX());
          spawned.getView().setDirY(dying.getView().getDirY());
        }
        cloneSpawn(dying, spawned);
        // The spawner queues the child for the holder; the fold that takes it in starts it.
        spawned.startOnAdmission();
        world.getHolder().addRegistered(spawned);
        world.events().characterSpawned(dying, spawned, x, y);
        made.add(spawned);
      }
      world.events().buffDeathSpawn(dying, instance, made);
    }
  }

  /**
   * The death spawn: the row's death spawn character, as many as its count, each created for the
   * dying object's side at its level re-based on the child's rarity, walking at once when it has a
   * speed and hit points, deploying for its own deploy time when it has none, or deploying for the
   * row's death spawn deploy time when the row has one; registered inside the pass of the kill with
   * its registration visit, untargetable at first, and joining the live list at the tick's closing
   * cleanup.
   *
   * <p>With a radius the children stand on its ring - child {@code i} of {@code n} at angle {@code
   * (n - 1 - i) * 360 / n}, turned by the row's angle shift and the angle the dying object faces
   * when the row sets a shift - the ring untested for passability; a least radius equal to the
   * radius draws nothing. A row that pushes its children puts each on the dying object and flies it
   * back to its ring point. A row that gives its children a fixed priority turns its ring over
   * across the width when the dying object stands in lane 1 and along the length for the top team,
   * asking the lane before each child, and the i-th child is taken as (80i)^2 nearer by a
   * selection. With no radius a single child stands on the dying object, and several stand together
   * in front of it, the dying object's collision radius and the child's away toward the enemy, the
   * first quarter turn of that offset the in-front test accepts; where it accepts none, the
   * children stand one unit right of the dying object.
   *
   * <p>A row with a second death spawn row makes its children after the first row's, on the same
   * ring: the ring is divided by both counts, the first row starts from the row's angle shift and
   * the second half a step past the opposite side, neither turned by the dying object's facing; a
   * second row with no radius stands in front of the dying object even as a single child.
   *
   * <p>Refused rather than guessed: a child that is a building with hit points, which replaces the
   * dying object, paths to its point or has a starting action of its own; a least radius below the
   * radius, which draws each child's ring radius from the battle's random source - no row sets one;
   * and a single child without hit points that has a range, which is pulled back half its range
   * along the dying object's facing - every such row has none, so no run holds it.
   */
  void deathSpawn(WorldEntity dying, UnitData data) {
    if (data.deathSpawnCharacter() == null) {
      return;
    }
    int count = data.deathSpawnCount();
    if (data.deathSpawnCharacter2() == null) {
      deathSpawnRow(dying, data, data.deathSpawnCharacter(), count, count == 1, -1, 0);
      return;
    }
    // A second row shares the first row's ring: both divide it by the sum of the two counts. The
    // first starts from the row's angle shift, the second half a step past the opposite side,
    // and neither is turned by the way the dying object faces. The second row is made after the
    // first, always with its in-front offset.
    int total = count + data.deathSpawnCount2();
    if (total < 1) {
      throw new UnsupportedOperationException(
          dying.name() + "'s two death spawn rows count no child in all, which is not modelled");
    }
    int shift = data.spawnAngleShift();
    int second = shift + ((180 + 360 / total / 2) & 0xffff);
    deathSpawnRow(dying, data, data.deathSpawnCharacter(), count, count == 1, total, shift);
    deathSpawnRow(
        dying, data, data.deathSpawnCharacter2(), data.deathSpawnCount2(), false, total, second);
  }

  /**
   * One row of the death spawn: {@code count} children of the row, nothing for a count below 1.
   *
   * @param dying the dying object
   * @param data its row
   * @param row the row the children are made of
   * @param count how many
   * @param noOffset true for no in-front offset, as for a single child of the only or first row
   * @param total the children of the whole ring, which the angle step divides by, or -1 for this
   *     row's own count and the ring turned by the dying object's angle shift and facing
   * @param angleBase the angle the ring starts from with a total, in degrees
   */
  private void deathSpawnRow(
      WorldEntity dying,
      UnitData data,
      String row,
      int count,
      boolean noOffset,
      int total,
      int angleBase) {
    int radius = data.deathSpawnRadius();
    UnitData child = spawnedRow(row);
    // A building with hit points replaces the dying object instead; one without, a bomb, is made.
    if ((child.building() && child.hitpoints() > 0)
        || child.spawnPathfindSpeed() != 0
        || child.onStartingAction() != null) {
      throw new UnsupportedOperationException(
          dying.name()
              + "'s death spawn "
              + child.name()
              + " replaces it, paths to its point or starts an action, which is not modelled");
    }
    // A least radius draws the ring for each child between it and the radius; every row that sets
    // one sets it to the radius, so the bound is 0, nothing is drawn and the ring is the radius.
    if (data.deathSpawnMinRadius() != 0 && data.deathSpawnMinRadius() != radius) {
      throw new UnsupportedOperationException(
          dying.name() + "'s death spawn draws its ring radius, which is not modelled");
    }
    if (data.deathSpawnDeployTimeMs() < 0) {
      throw new UnsupportedOperationException(
          dying.name() + "'s death spawn has a negative deploy time, which is not modelled");
    }
    int fromX = dying.getView().getX();
    int fromY = dying.getView().getY();
    for (int i = 0; i < count; i++) {
      int[] at;
      if (radius != 0) {
        at =
            total == -1
                ? SpawnPlacement.position(
                    fromX,
                    fromY,
                    i,
                    count,
                    false,
                    radius,
                    ringTurn(dying),
                    SpawnPlacement.NO_REACH,
                    0,
                    0,
                    (px, py) -> true)
                : SpawnPlacement.ring(fromX, fromY, i, count, total, angleBase, radius);
        // A ring whose children take a fixed priority asks the lane of the dying object's point
        // before each child, and is turned over by it and by the dying object's team.
        if (data.spawnConstPriority()) {
          int lane = LaneAssignment.lane(world.getTileMap(), fromX, fromY, -1, 0);
          world.events().ringLaneAsked(dying, fromX, fromY, lane);
          at = SpawnPlacement.mirrored(at, fromX, fromY, lane, dying.side() & 1);
        }
      } else {
        // A single child has no in-front offset; several share the one in-front point.
        at =
            SpawnPlacement.position(
                fromX,
                fromY,
                i,
                count,
                noOffset,
                0,
                dying.getData().collisionRadius() + child.collisionRadius(),
                dying.side() & 1,
                world.getTileMap().width() * TileMap.CELL_UNITS,
                (px, py) ->
                    SpawnPassable.passable(world.getTileMap(), px, py, child.collisionRadius()));
        if (count == 1 && child.hitpoints() <= 0 && child.range() != 0) {
          // Pulled back by half its range along the dying object's facing; every such row has a
          // range of 0, so no run holds the pullback.
          throw new UnsupportedOperationException(
              dying.name()
                  + "'s death spawn is an object without hit points with a range, which is not"
                  + " modelled");
        }
      }
      int x = inset(at[0], world.getTileMap().width());
      int y = inset(at[1], world.getTileMap().height());
      int made = spawnCounts.merge(dying.name(), 1, Integer::sum) - 1;
      CharacterEntity spawned =
          CharacterEntity.spawned(
              world,
              child,
              dying.name() + "_" + made,
              dying.side(),
              x,
              y,
              PackedLevel.level(PackedLevel.pack(dying.getPackedLevel(), child.rarity())));
      if (radius != 0 && data.deathSpawnPushback()) {
        spawned.flyBackFrom(fromX, fromY);
      }
      // A fixed priority per child: the i-th is taken as (80i)^2 nearer by a selection.
      if (data.spawnConstPriority()) {
        spawned.getView().setSquaredDistanceReduction((i * 80) * (i * 80));
      }
      if (child.hitpoints() <= 0 && child.deployTimeMs() >= 1) {
        spawned.startDeploying();
      }
      if (data.deathSpawnDeployTimeMs() > 0) {
        spawned.deployFor(data.deathSpawnDeployTimeMs());
        // A child made with a deploy time faces the way the dying object faced, as the spawner's
        // setter leaves it.
        spawned.getView().setDirX(dying.getView().getDirX());
        spawned.getView().setDirY(dying.getView().getDirY());
      }
      cloneSpawn(dying, spawned);
      // Where it is made: on its point, or on the dying object for one that flies back.
      int madeX = spawned.getView().getX();
      int madeY = spawned.getView().getY();
      world.getHolder().addRegistered(spawned);
      // A character whose row sets the inherited ignore list hands its place in every id list to
      // each child: whatever has listed it, as a body that hit it or its carrier, lists the child.
      if (data.deathInheritIgnoreList() && dying instanceof CharacterEntity) {
        inheritIdLists(dying, spawned);
      }
      if (BattleWorld.DEATH_SPAWN_IMMUNE_FIRST_TICK) {
        spawned.startSpawnImmunity();
      }
      world.events().characterSpawned(dying, spawned, madeX, madeY);
    }
  }

  /**
   * A child taking a dying object's place in the id lists: every other object of the live list
   * whose id list holds the dying object's id lists the child's too. The objects that keep such a
   * list are a projectile, the ids its flying body has hit (with the riders of a carrier it hit)
   * and its chained hops' targets, and an area effect, the ids its projectiles were dropped onto;
   * the list of any other object is written only by such hand-overs - this one, a rider let go
   * under its parent's inherited list, a morph - so it never holds an id. Refused rather than
   * guessed: an area effect's list holding the dying object, which no run holds (the only rows that
   * hand their place over are riders, which no area effect chooses).
   *
   * @param dying the dying object
   * @param child the child made in its place
   */
  private void inheritIdLists(WorldEntity dying, WorldEntity child) {
    int held = dying.getId();
    int added = child.getId();
    for (BattleEntity entity : world.getHolder().entities()) {
      if (entity == child) {
        continue;
      }
      if (entity instanceof ProjectileEntity projectile) {
        if (projectile.getHitIds().contains(held)) {
          projectile.getHitIds().add(added);
        }
      } else if (entity instanceof AreaEffectEntity areaEffect
          && areaEffect.struckListHolds(held)) {
        throw new UnsupportedOperationException(
            child.name()
                + " would join the id list of "
                + areaEffect.name()
                + " in place of "
                + dying.name()
                + ", which is not modelled");
      }
    }
  }

  /**
   * A guard-spawning run's start on an area effect: the guard and its run.
   *
   * <p>In order: the point behind the area effect's, toward its own side, by the row's distance,
   * moved off water; the guard of the row's character created there, kept 250 inside the arena, for
   * the area effect's side, at its level re-based on the guard's rarity; the guard's run listed on
   * it with its tags, starting from the area effect's point; the guard's id and its registration
   * visit at once, the guard still walking as the level setter leaves it; the facing toward the
   * area effect's point; and its deploy, through its setter and the combat gate after it. It joins
   * the live list at the tick's closing cleanup and is first visited on the next tick.
   *
   * @param areaEffect the area effect the first run started on
   * @param action the row
   * @param phase the pending pass the first run started in
   */
  void spawnGuard(AreaEffectEntity areaEffect, SpawnGuard action, int phase) {
    SpawnGuard.Columns columns = action.getColumns();
    UnitData row = spawnedRow(columns.spawnData());
    areaEffect.refuseClonesSpawn("spawns " + row.name());
    if (row.onStartingAction() != null) {
      throw new UnsupportedOperationException(
          action.name() + " makes " + row.name() + ", which starts an action, not modelled");
    }
    int x = areaEffect.getX();
    int y = areaEffect.getY();
    int sign = (areaEffect.side() & 1) == 0 ? 1 : -1;
    int behind = y - columns.appearBehindAtDistance() * sign;
    int packed =
        Relocation.relocate(
            world.getGrid().getWidth(),
            world.getGrid().getHeight(),
            x,
            behind,
            -1,
            world.getGrid()::water);
    int gx = Relocation.unpackX(packed);
    int gy = Relocation.unpackY(packed);
    int made = spawnCounts.merge(areaEffect.name(), 1, Integer::sum) - 1;
    CharacterEntity guard =
        CharacterEntity.spawned(
            world,
            row,
            areaEffect.name() + "_" + made,
            areaEffect.side(),
            inset(gx, world.getTileMap().width()),
            inset(gy, world.getTileMap().height()),
            PackedLevel.level(PackedLevel.pack(areaEffect.getPackedLevel(), row.rarity())));
    guard.actionHolder().list(action.guardRun(guard.guardHost(), x, y));
    world.getHolder().addRegistered(guard);
    world.events().guardRegistered(guard);
    guard.faceToward(x, y);
    guard.deployAfterRegistration();
    world.events().guardStarted(areaEffect, action.name(), phase, guard, x, behind, gx, gy);
  }

  /** The blocked cell value a warp's landing avoids: not placeable, and no lane. */
  private static final int WARP_BLOCKED_CELL = 0x10;

  /** The rows a warp's landing searches each way for an allowed cell. */
  private static final int WARP_SEARCH_ROWS = 4;

  /**
   * A warp's perform: the unit's position plus the offset, negated for side 1, clamped into the
   * arena; a landing cell the row avoids replaced by the nearest allowed cell of its column within
   * four rows, the nearer half-row neighbour first at each distance, at that cell's centre, or by
   * the landing row's centre when none is allowed; the position written once; then the pending
   * damage reset, the route emptied and the reference dropped, as the row asks. The reset drops the
   * unit as the target of every projectile aimed at it, as entering a pathfinding state does; one
   * whose row keeps its target through it keeps the unit (see {@link
   * #resetPendingDamageAtWarp(CharacterEntity, String)}).
   *
   * @param unit the unit
   * @param action the row
   * @param phase the pending pass it runs in
   */
  void warp(CharacterEntity unit, WarpCharacter action, int phase) {
    WarpCharacter.Columns columns = action.getColumns();
    GridEntity view = unit.getView();
    int startX = view.getX();
    int startY = view.getY();
    int sign = unit.side() == 0 ? 1 : -1;
    int x = columns.warpX() * sign + startX;
    int y = columns.warpY() * sign + startY;
    int maxX = world.getGrid().getWidth() * TileMap.CELL_UNITS - 1;
    int maxY = world.getGrid().getHeight() * TileMap.CELL_UNITS - 1;
    x = x > 0 ? Math.min(x, maxX) : 0;
    y = y > 0 ? Math.min(y, maxY) : 0;
    if (columns.avoidWater() || columns.avoidBlocked()) {
      int col = x / TileMap.CELL_UNITS;
      int row = y / TileMap.CELL_UNITS;
      if (!warpLandingAllowed(columns, col, row)) {
        // The nearer half-row neighbour first, at each distance.
        int first = y - row * TileMap.CELL_UNITS >= TileMap.CELL_UNITS / 2 ? 1 : -1;
        int found = row;
        for (int k = 1; k <= WARP_SEARCH_ROWS; k++) {
          if (warpLandingAllowed(columns, col, row + first * k)) {
            found = row + first * k;
            break;
          }
          if (warpLandingAllowed(columns, col, row - first * k)) {
            found = row - first * k;
            break;
          }
        }
        y = found * TileMap.CELL_UNITS + TileMap.CELL_UNITS / 2;
      }
    }
    TargetView referenceBefore = unit.getUnit().targeting().getReference();
    unit.warpTo(x, y);
    if (columns.resetPendingDamage()) {
      resetPendingDamageAtWarp(unit, action.name());
    }
    if (columns.resetPath()) {
      unit.resetRoute();
    }
    if (columns.resetTarget()) {
      unit.resetTargetAfterWarp();
    }
    world
        .events()
        .warped(
            unit,
            action.name(),
            phase,
            startX,
            startY,
            referenceBefore == null ? null : referenceBefore.name());
  }

  /**
   * A warp's pending damage reset, the instant warp's and the flying warp's start alike: the unit's
   * reset hook, the same as entering a pathfinding state. The damage on its way to the unit is set
   * to 0, then every projectile aimed at it whose row allows it loses it as its target, handing
   * nothing back; each flies on to its aim, where the unit last stood for it, and lands on nothing.
   *
   * <p>A projectile aimed at the unit whose row keeps its target through the reset
   * (AllowResetTarget cleared) is left alone: it keeps the unit as its target, goes on re-aiming at
   * it wherever the warp takes it, and lands on it, its arrival handing back its own part of the
   * emptied amount. The rows that keep their target deal no damage, so that part is 0 and the
   * hand-back changes nothing. One with damage on its way, whose hand-back from the emptied amount
   * no reference holds, is refused.
   *
   * @param unit the unit
   * @param action the warp row's name
   */
  void resetPendingDamageAtWarp(CharacterEntity unit, String action) {
    for (BattleEntity entity : world.getHolder().entities()) {
      if (entity instanceof ProjectileEntity p
          && p.getTarget() == unit
          && !p.getData().allowResetTarget()
          && p.registeredPendingAmount() != 0) {
        throw new UnsupportedOperationException(
            action
                + " warps "
                + unit.name()
                + " with "
                + p.name()
                + " aimed at it, whose row keeps its target through the reset with damage on its"
                + " way, not modelled");
      }
    }
    unit.getView().setPendingDamageAmount(0);
    world.dropProjectilesAimedAt(unit);
  }

  /**
   * Whether a warp may land on a cell: inside the arena, not water when the row avoids water, and
   * not the blocked value when it avoids blocked cells.
   */
  private boolean warpLandingAllowed(WarpCharacter.Columns columns, int col, int row) {
    if (row < 0 || col >= world.getGrid().getWidth() || row >= world.getGrid().getHeight()) {
      return false;
    }
    if (columns.avoidWater() && (world.getGrid().tiles(col, row) & TileMap.WATER_BIT) != 0) {
      return false;
    }
    return !(columns.avoidBlocked() && world.getGrid().tiles(col, row) == WARP_BLOCKED_CELL);
  }

  /** How far beyond its own radius the nearest other building is looked for, in game units. */
  private static final int BESIDE_BUILDING_SEARCH = 2000;

  /** The edge-to-edge gap below which a building of the characters table leaves contact. */
  private static final int BESIDE_BUILDING_GAP = 500;

  /**
   * Whether another building stands beside a building of the characters table, which takes it out
   * of contact: the nearest building other than itself - a listed character or crown tower that is
   * a building, a dying one still listed among them - is looked for over the live list in its
   * order, taken when its squared distance from the building's point is below the best so far and
   * below its own radius plus 2000, squared; the building is beside it when their distance, less
   * both radii, is below 500. A building found nowhere within reach leaves it in contact.
   *
   * @param self the building of the characters table that asks
   * @return true when the nearest other building is within 500 of it edge to edge
   */
  boolean besideAnotherBuilding(WorldEntity self) {
    GridEntity own = self.getView();
    int x = own.getX();
    int y = own.getY();
    GridEntity nearest = null;
    int best = Integer.MAX_VALUE;
    for (BattleEntity entity : world.getHolder().entities()) {
      if (!(entity instanceof WorldEntity other) || other == self) {
        continue;
      }
      GridEntity view = other.getView();
      if (!view.isBuilding()) {
        continue;
      }
      // Squared in 32 bits as the search takes it; the reach is compared without a sign.
      int dx = x - view.getX();
      int dy = y - view.getY();
      int squared = dx * dx + dy * dy;
      if (squared >= best) {
        continue;
      }
      int reach = view.getCollisionRadius() + BESIDE_BUILDING_SEARCH;
      if (Integer.compareUnsigned(squared, reach * reach) < 0) {
        best = squared;
        nearest = view;
      }
    }
    if (nearest == null) {
      return false;
    }
    int distance = FixedMath.guardedDistance(x - nearest.getX(), y - nearest.getY());
    return distance - own.getCollisionRadius() - nearest.getCollisionRadius() < BESIDE_BUILDING_GAP;
  }

  /**
   * The spawner: creates the children a spawn row's block describes, each one after the other.
   *
   * <p>For each child, in order: where it stands (on the point, one unit right of it over water, or
   * on the ring), kept 250 inside the arena; its creation, for the source's side, in the lane of
   * its own position; its level, the row's or the source's, re-based on the child's own rarity;
   * walking at once as the level setter leaves it, or deploying when the row asks or when the child
   * has no hit points and a deploy time, for the row's own deploy time when it has one; its id and
   * its registration visit at once, inside the pass that runs the spawn, over this tick's index, so
   * a push from a unit already standing there moves it; its first-tick immunity; and the action it
   * runs as it is spawned, which starts at once when it has no delay, since a pending pass is in
   * progress. It joins the live list at the tick's closing cleanup and is first visited on the next
   * tick.
   *
   * <p>A creation that ignores effects is the same creation: the flag only skips the spawn effect,
   * which is presentation.
   *
   * <p>A ring whose children take a fixed priority asks the lane of the point before each child and
   * is turned over across the width in lane 1 and along the length for the top team, and the i-th
   * child is taken as (80i)^2 nearer by a selection; a ring that pushes its children puts each on
   * the point and flies it back to its ring point. A child of a source other than a character made
   * with a deploy time faces along the length toward the enemy, a vector of length 1.
   *
   * <p>A ring around a character source reads the source's own row: an angle shift turns the ring
   * by the shift and the angle the source faces, as a character's own spawner turns it, and a row
   * whose death spawn pushes its children makes the ring push them although the spawn does not ask.
   * The source's running actions hear of each child, by its id, as the last step of its making, as
   * they hear of what a character's own spawner makes: the evolved Witch's soul drain counts the
   * skeletons of her interval spawn as hers.
   *
   * <p>Refused rather than guessed: a morph, a spawn for the other side, a ring around a character
   * whose row draws each child's radius or attaches its children, and a unit that paths to its
   * spawn point. A child without a speed stands where it is made, and one without hit points is
   * taken like any other. A child with a starting action of its own starts it as the cleanup's fold
   * admits it: the action is scheduled then, outside every pending pass.
   *
   * @param source the object the children are spawned from
   * @param arguments the block the row's perform works out
   * @return the children, in the order they were made
   */
  public List<SpawnHost> spawnCharacters(SpawnHost source, SpawnArguments arguments) {
    UnitData data = arguments.configuration();
    refuseUnestablished(source, arguments, data);
    // A character source's own row: its angle shift turns a ring, with the angle it faces, and its
    // death spawn's pushback makes a ring push its children when the spawn does not ask.
    WorldEntity ownRow =
        source instanceof WorldEntity entity && entity.isCharacter() ? entity : null;
    int turn = arguments.radius() != 0 && ownRow != null ? ringTurn(ownRow) : 0;
    boolean pushback =
        arguments.spawnPushback() || (ownRow != null && ownRow.getData().deathSpawnPushback());
    List<SpawnHost> made = new ArrayList<>();
    for (int i = 0; i < arguments.count(); i++) {
      int[] at =
          SpawnPlacement.position(
              arguments.x(),
              arguments.y(),
              i,
              arguments.count(),
              arguments.noOffset(),
              arguments.radius(),
              turn,
              SpawnPlacement.NO_REACH,
              0,
              0,
              (x, y) -> SpawnPassable.passable(world.getTileMap(), x, y, data.collisionRadius()));
      if (arguments.radius() != 0 && arguments.constPriority()) {
        // A ring whose children take a fixed priority asks the lane of the point before each
        // child, and is turned over by it and by the source's team.
        int lane = LaneAssignment.lane(world.getTileMap(), arguments.x(), arguments.y(), -1, 0);
        world.events().ringLaneAsked(source, arguments.x(), arguments.y(), lane);
        at = SpawnPlacement.mirrored(at, arguments.x(), arguments.y(), lane, source.side() & 1);
      }
      int x = inset(at[0], world.getTileMap().width());
      int y = inset(at[1], world.getTileMap().height());
      int level =
          arguments.level() == SpawnRow.SOURCE_LEVEL ? source.packedLevel() : arguments.level();
      int count = spawnCounts.merge(source.name(), 1, Integer::sum) - 1;
      CharacterEntity child =
          CharacterEntity.spawned(
              world,
              data,
              source.name() + "_" + count,
              source.side(),
              x,
              y,
              PackedLevel.level(PackedLevel.pack(level, data.rarity())));
      if (arguments.radius() != 0 && pushback) {
        // On the point, flying back to its ring point.
        child.flyBackFrom(arguments.x(), arguments.y());
      }
      // A fixed priority per child: the i-th is taken as (80i)^2 nearer by a selection.
      if (arguments.constPriority()) {
        child.getView().setSquaredDistanceReduction((i * 80) * (i * 80));
      }
      if (arguments.useDeploy() || deploysWithoutAsking(data, arguments.deployTimeMs())) {
        child.startDeploying();
      }
      if (arguments.deployTimeMs() != 0) {
        child.deployFor(arguments.deployTimeMs());
        // A spawn with a deploy time of its own hands the child a character source's facing, as
        // the evolved Goblin Drill's hide goblins face where the drill faced. Any other source
        // hands it a vector of its team's direction along the length, of length 1, not 256.
        if (source instanceof CharacterEntity character) {
          child.getView().setDirX(character.getView().getDirX());
          child.getView().setDirY(character.getView().getDirY());
        } else {
          child.getView().setDirX(0);
          child.getView().setDirY(AreaEffectEntity.yDirection(source.side()));
        }
      }
      // The spawner's clone setter, last before the hand-over: the child of a clone is a clone.
      cloneSpawn(source, child);
      // The child is registered now and joins the live list at the next cleanup's fold, which
      // starts it: its row's starting action is scheduled then.
      child.startOnAdmission();
      world.getHolder().addRegistered(child);
      if (arguments.deathSpawn() && BattleWorld.DEATH_SPAWN_IMMUNE_FIRST_TICK) {
        child.startSpawnImmunity();
      }
      world.events().characterSpawned(source, child, x, y);
      if (arguments.action() != null) {
        child
            .actionHolder()
            .schedule(arguments.action(), ActionHolder.OWN_DELAY, false, source.actionHolder());
      }
      made.add(child);
      // The spawner's last act on each child: the source's running actions hear of it.
      ActionHolder actions = source.actionHolder();
      if (actions != null) {
        actions.childSpawned(child.getId());
      }
    }
    return made;
  }

  /**
   * Whether the spawner starts a child deploying although its spawn does not ask for a deploy: a
   * row without hit points, a bomb or a bottle, whose deploy time (the spawn's own when positive,
   * else the row's) is at least 1 and which does not walk to its spawn point. The spawner's deploy
   * decision (the row's Hitpoints at level 0, the deploy time, SpawnPathfindSpeed) is or-ed with
   * the spawn's own deploy flag before the state is set to deploying.
   */
  private static boolean deploysWithoutAsking(UnitData data, int spawnDeployTimeMs) {
    int deployTimeMs = spawnDeployTimeMs > 0 ? spawnDeployTimeMs : data.deployTimeMs();
    return data.hitpoints() == 0 && deployTimeMs >= 1 && data.spawnPathfindSpeed() == 0;
  }

  /**
   * The one-child positional spawner, as a Goblin Hut's life state calls it: one child of the row
   * on the point, or one unit right of it where the in-front test refuses the point, kept 250
   * inside the arena; created for the source's side at the source's level re-based on the child's
   * rarity, deploying for its row's deploy time, untargetable at first, and queued with no
   * registration visit, so it joins the live list at the tick's closing cleanup and is first
   * visited on the next tick.
   *
   * <p>Refused rather than guessed: a building, a unit that paths to its spawn point or limits its
   * group, and a unit with a starting action of its own.
   *
   * @param source what spawns
   * @param row the child's row
   * @param x the point along the width
   * @param y the point along the length
   */
  void spawnOne(CharacterEntity source, String row, int x, int y) {
    UnitData data = spawnedRow(row);
    if (data.building() || data.spawnPathfindSpeed() != 0 || data.onStartingAction() != null) {
      throw new UnsupportedOperationException(
          source.name()
              + " spawns "
              + row
              + ", a building, a unit that paths to its point or one with a starting action,"
              + " which is not modelled");
    }
    int[] at =
        SpawnPlacement.position(
            x,
            y,
            0,
            1,
            true,
            0,
            (px, py) -> SpawnPassable.passable(world.getTileMap(), px, py, data.collisionRadius()));
    int cx = inset(at[0], world.getTileMap().width());
    int cy = inset(at[1], world.getTileMap().height());
    int count = spawnCounts.merge(source.name(), 1, Integer::sum) - 1;
    CharacterEntity child =
        CharacterEntity.spawned(
            world,
            data,
            source.name() + "_" + count,
            source.side(),
            cx,
            cy,
            PackedLevel.level(PackedLevel.pack(source.getPackedLevel(), data.rarity())));
    child.startDeploying();
    world.getHolder().add(child);
    if (BattleWorld.DEATH_SPAWN_IMMUNE_FIRST_TICK) {
      child.startSpawnImmunity();
    }
    world.events().characterSpawned(source, child, cx, cy);
  }

  /**
   * The character an ability leaves on its unit's spot as it fires: one child of the row, on the
   * unit's position, or one unit right of it where the in-front test refuses the point, kept 250
   * inside the arena; created for the unit's side at its level re-based on the child's rarity,
   * deploying for its row's deploy time, untargetable at first, and queued with no registration
   * visit, so it joins the live list at the tick's closing cleanup and is first visited on the next
   * tick. A bomb - a building without hit points - dies at the end of that deploy.
   *
   * <p>Refused rather than guessed: a building with hit points, a unit that paths to its spawn
   * point or has a starting action of its own, and an object without hit points that has a range,
   * which the spawner pulls back by half of it.
   *
   * @param unit the unit whose ability fired
   * @param row the child's row
   */
  void activationSpawn(CharacterEntity unit, String row) {
    UnitData data = spawnedRow(row);
    if ((data.building() && data.hitpoints() > 0)
        || data.spawnPathfindSpeed() != 0
        || data.onStartingAction() != null
        || data.hitpoints() <= 0 && data.range() != 0) {
      throw new UnsupportedOperationException(
          unit.name()
              + "'s ability leaves "
              + row
              + ", a building with hit points, a unit that paths to its point or starts an action,"
              + " or an object without hit points with a range, which is not modelled");
    }
    int[] at =
        SpawnPlacement.position(
            unit.getView().getX(),
            unit.getView().getY(),
            0,
            1,
            true,
            0,
            (px, py) -> SpawnPassable.passable(world.getTileMap(), px, py, data.collisionRadius()));
    int x = inset(at[0], world.getTileMap().width());
    int y = inset(at[1], world.getTileMap().height());
    int count = spawnCounts.merge(unit.name(), 1, Integer::sum) - 1;
    CharacterEntity child =
        CharacterEntity.spawned(
            world,
            data,
            unit.name() + "_" + count,
            unit.side(),
            x,
            y,
            PackedLevel.level(PackedLevel.pack(unit.getPackedLevel(), data.rarity())));
    child.startDeploying();
    world.getHolder().add(child);
    if (BattleWorld.DEATH_SPAWN_IMMUNE_FIRST_TICK) {
      child.startSpawnImmunity();
    }
    world.events().characterSpawned(unit, child, x, y);
  }

  /**
   * The order an area effect's spawner takes its directions in: the numbers below the count, then
   * fifty pairs of draws of the battle's random source for each, at least one pair, each pair below
   * the count swapping the two entries it names.
   *
   * @param areaEffect the area effect
   * @param total how many directions its lifetime holds
   * @return the order
   */
  int[] spawnOrder(AreaEffectEntity areaEffect, int total) {
    int before = world.getRandom().getState();
    int[] order = new int[total];
    for (int i = 0; i < total; i++) {
      order[i] = i;
    }
    for (int pair = Math.max(total * 50, 1); pair > 0; pair--) {
      int i = world.getRandom().next(total);
      int j = world.getRandom().next(total);
      if (i != j) {
        int kept = order[i];
        order[i] = order[j];
        order[j] = kept;
      }
    }
    world.events().spawnOrdered(areaEffect, order.clone(), before, world.getRandom().getState());
    return order;
  }

  /**
   * One character of an area effect's spawner. It is placed at the direction and at SpawnMinRadius
   * and a draw of the battle's random source below what the radius leaves after its own collision
   * radius and that least distance, from the area effect's point; the point is moved off water and
   * 250 inside the arena by the relocation. A building of the live list whose circle overlaps the
   * character's, or a cell the standing test refuses, blocks the point, which is then drawn again
   * anywhere in the circle - a direction below 360 and a distance below the radius - up to five
   * times, the last point kept. The character is created there, kept 250 inside the arena, on the
   * area effect's side and at its level re-based on the character's rarity; it deploys for
   * SpawnTime, joins the holder with its registration visit, and becomes a clone for a row that
   * spawns clones.
   *
   * <p>Refused rather than guessed: a building, a unit that paths to its point or starts an action
   * of its own, and a row that does not spawn clones, whose children copy the area effect's clone
   * answer.
   *
   * @param areaEffect the area effect
   * @param angle the direction, in degrees
   * @param radius the radius of the area effect's hits now
   */
  void areaSpawn(AreaEffectEntity areaEffect, int angle, int radius) {
    AreaEffectData row = areaEffect.getData();
    UnitData data = spawnedRow(row.spawnCharacter());
    if (data.building()
        || data.spawnPathfindSpeed() != 0
        || data.onStartingAction() != null
        || !row.spawnClones()) {
      throw new UnsupportedOperationException(
          "the area effect "
              + areaEffect.name()
              + " spawns "
              + data.name()
              + ", a building, a unit that paths to its point or starts an action, or not as a"
              + " clone, which is not modelled");
    }
    int collision = data.collisionRadius();
    int distance =
        row.spawnMinRadius() + world.getRandom().next(radius - collision - row.spawnMinRadius());
    int[] at = spawnPoint(areaEffect, angle, distance, collision);
    int retries = 0;
    while (at[2] != 0 && retries < 5) {
      angle = world.getRandom().next(360);
      distance = world.getRandom().next(radius);
      at = spawnPoint(areaEffect, angle, distance, collision);
      retries++;
    }
    int x = inset(at[0], world.getTileMap().width());
    int y = inset(at[1], world.getTileMap().height());
    int count = spawnCounts.merge(areaEffect.name(), 1, Integer::sum) - 1;
    CharacterEntity child =
        CharacterEntity.spawned(
            world,
            data,
            areaEffect.name() + "_" + count,
            areaEffect.side(),
            x,
            y,
            PackedLevel.level(PackedLevel.pack(areaEffect.packedLevel(), data.rarity())));
    if (row.spawnTimeMs() >= 1) {
      child.deployFor(row.spawnTimeMs());
    }
    world.getHolder().addRegistered(child);
    child.markClone(null);
    world.events().characterSpawned(areaEffect, child, x, y);
    world.events().areaSpawned(areaEffect, child, retries, world.getRandom().getState());
  }

  /**
   * A spawner's point: at the direction and distance from the area effect's point, by the sine
   * table and its quarter turn, each over 1024 toward zero, then relocated off water and 250 inside
   * the arena; blocked by a building of the live list whose circle overlaps the given one, or by a
   * cell the standing test refuses.
   *
   * @return the point and 1 when it is blocked, else 0
   */
  private int[] spawnPoint(AreaEffectEntity areaEffect, int angle, int distance, int collision) {
    int x = areaEffect.x() + FixedMath.sine1024(angle) * distance / 1024;
    int y = areaEffect.y() + FixedMath.sine1024(angle + 90) * distance / 1024;
    int packed =
        Relocation.relocate(
            world.getGrid().getWidth(),
            world.getGrid().getHeight(),
            x,
            y,
            -1,
            world.getGrid()::water);
    x = Relocation.unpackX(packed);
    y = Relocation.unpackY(packed);
    boolean blocked =
        buildingOver(x, y, collision) || (CellTests.cellBlocked(world.getGrid(), x, y) & 1) != 0;
    return new int[] {x, y, blocked ? 1 : 0};
  }

  /** How many half-tile rows the building placement tries after a blocked point. */
  private static final int PLACEMENT_ROWS = 9;

  /**
   * The building placement a spawn row that validates its point as a building's asks for: the point
   * clamped into the arena, kept when no building of the live list other than the owner overlaps
   * the child's circle there and its cell can be stood on. The owner is the object whose action
   * runs the row, so a building that places a building where it stands, as the hero Musketeer's
   * dummy building places the turret, does not block its own point.
   *
   * <p>A blocked point is searched over the next nine half-tile rows of its column, one at a time,
   * stepping down the arena for side 0 and up for side 1 (the Goblins hero's banner dropped on the
   * river moves back onto its own side's bank). The child's footprint gives each row two edges,
   * half the footprint below and above it; a row passes when the cells at both edges can be stood
   * on and no building of the live list overlaps the child's circle at the middle of the two. This
   * overlap test leaves no object out, the owner included. The first passing row places the child
   * at the clamped x and at its lower edge moved a further footprint of rows along the side's way,
   * in whole half tiles.
   *
   * <p>When none of the nine rows passes, the search answers the requested point as it was asked
   * for, unclamped, and the child is spawned there as a row that does not validate its point would
   * spawn it: on a building's circle or a cell that cannot be stood on.
   *
   * @param owner the object whose action spawns the child
   * @param child the row of the child
   * @return the search
   */
  SpawnPerform.PlacementSearch buildingPlacement(WorldEntity owner, UnitData child) {
    return (x, y) -> {
      int clampedX = Math.max(0, Math.min(x, world.getTileMap().width() * TileMap.CELL_UNITS - 1));
      int clampedY = Math.max(0, Math.min(y, world.getTileMap().height() * TileMap.CELL_UNITS - 1));
      int radius = child.collisionRadius();
      int column = clampedX / TileMap.CELL_UNITS;
      int start = clampedY / TileMap.CELL_UNITS;
      if (!buildingOver(clampedX, clampedY, radius, owner) && cellStandable(column, start)) {
        return new int[] {clampedX, clampedY};
      }
      // Side 0 steps down the arena, side 1 up.
      boolean down = (owner.side() & 1) == 0;
      int footprint = PlacementSearch.footprint(child);
      for (int k = 1; k <= PLACEMENT_ROWS; k++) {
        int row = down ? start - k : start + k;
        int low = row - footprint / 2;
        int high = row + footprint / 2;
        if (!cellStandable(column, low) || !cellStandable(column, high)) {
          continue;
        }
        int middle = (low + high) / 2 * TileMap.CELL_UNITS;
        if (buildingOver(clampedX, middle, radius)) {
          continue;
        }
        return new int[] {clampedX, (low + (down ? -footprint : footprint)) * TileMap.CELL_UNITS};
      }
      return new int[] {x, y};
    };
  }

  /**
   * Whether a unit may stand on a half-tile cell: inside the arena, and neither not placeable,
   * blocked nor water. The standing test of a cell rather than of a point; a cell outside the arena
   * cannot be stood on.
   */
  private boolean cellStandable(int column, int row) {
    if (column < 0
        || row < 0
        || column >= world.getGrid().getWidth()
        || row >= world.getGrid().getHeight()) {
      return false;
    }
    return CellTests.cellBlocked(
            world.getGrid(), column * TileMap.CELL_UNITS, row * TileMap.CELL_UNITS)
        == 0;
  }

  /**
   * Whether a building of the live list has a circle that overlaps a circle: its collision radius
   * and the given one together reach further than its centre lies from the point. The squares are
   * taken in 32 bits and compared unsigned; a square distance of the largest integer is passed by.
   */
  private boolean buildingOver(int x, int y, int radius) {
    return buildingOver(x, y, radius, null);
  }

  /**
   * Whether a building of the live list other than the one left out has a circle that overlaps a
   * circle, as {@link #buildingOver(int, int, int)} asks it.
   *
   * @param excluded the object the query passes by, or null to ask every building
   */
  private boolean buildingOver(int x, int y, int radius, BattleEntity excluded) {
    for (BattleEntity entity : world.getHolder().entities()) {
      if (entity == excluded
          || !(entity instanceof WorldEntity building)
          || !building.getTargetView().building()) {
        continue;
      }
      int dx = x - building.getView().getX();
      int dy = y - building.getView().getY();
      int squared = dx * dx + dy * dy;
      int reach = building.getView().getCollisionRadius() + radius;
      if (squared != Integer.MAX_VALUE && Integer.compareUnsigned(squared, reach * reach) < 0) {
        return true;
      }
    }
    return false;
  }

  /** Counts one more character made, which a summon's name carries. */
  void characterMade() {
    charactersMade++;
  }

  /**
   * A summon area's summon: one child of the row on the area's point, or one unit right of it where
   * the in-front test refuses the point, kept 250 inside the arena; created for the area's side at
   * its level re-based on the child's rarity and deploying for its row's deploy time, named after
   * its row and the count of characters made before it. It takes the reference handed over when
   * that is alive and its validator accepts it, faces the point it was summoned toward, is queued
   * with no registration visit, so it joins the live list at the tick's closing cleanup, and runs
   * the combat gate.
   *
   * <p>Refused rather than guessed: a building, a unit that paths to its spawn point or limits its
   * group, and a unit with a starting action that is not presentation alone, which a child starts
   * as it joins the live list; a presentation row is left out.
   *
   * @param area the summon area
   * @param row the child's row
   * @param reference the reference handed over, or null for none
   * @param x the point it faces along the width
   * @param y the point it faces along the length
   */
  void ghostSummon(AreaEffectEntity area, String row, WorldEntity reference, int x, int y) {
    area.refuseClonesSpawn("summons " + row);
    UnitData data = spawnedRow(row);
    if (data.building()
        || data.spawnPathfindSpeed() != 0
        || data.onStartingAction() != null
            && !world.getActions().playsEffect(data.onStartingAction())) {
      throw new UnsupportedOperationException(
          area.name()
              + " summons "
              + row
              + ", a building, a unit that paths to its point or one with a starting action,"
              + " which is not modelled");
    }
    int[] at =
        SpawnPlacement.position(
            area.getX(),
            area.getY(),
            0,
            1,
            true,
            0,
            (px, py) -> SpawnPassable.passable(world.getTileMap(), px, py, data.collisionRadius()));
    int cx = inset(at[0], world.getTileMap().width());
    int cy = inset(at[1], world.getTileMap().height());
    CharacterEntity child =
        CharacterEntity.spawned(
            world,
            data,
            row + "_" + charactersMade,
            area.side(),
            cx,
            cy,
            PackedLevel.level(PackedLevel.pack(area.packedLevel(), data.rarity())));
    child.startDeploying();
    world.getHolder().add(child);
    world.events().ghostSummonMade(area, child);
    child.takeSummonReference(reference);
    child.faceToward(x, y);
    child.summonReveal();
    world.events().ghostSummonSpawned(area, child);
  }

  /**
   * A Clone's creator: makes a clone of a unit on the unit's point, kept inside the arena, of the
   * unit's side and of its row, or of the row its row names as its cloned version, at the Clone's
   * level re-based on that row's rarity, named after the unit and how many clones of it came
   * before. The clone setter gives it 1 hit point of 1; it takes the deploy countdown the unit has
   * left and is set up as a clone through its setter, which with the combat gate after it leaves
   * its movement component on and its targeting component off, faces as the unit faces, and is
   * handed to the holder with its registration visit - in the clone state with no route, which
   * moves nothing - and joins the live list at the tick's closing cleanup, whose fold starts it as
   * any spawn: its own row's starting action is scheduled then. The unit's runs stay the unit's. It
   * takes a copy of every buff the unit carries, the Clone's own buff, just put on the unit, among
   * them, with the time each has left, and none of their start actions runs; then the copies of a
   * row that is not cloned are removed from it, their remove actions run on the clone. Then each
   * run the unit lists is told of the clone, and an air-to-ground run starts its row again on the
   * clone, the unit its cause, which copies the unit's run (see {@link WorldEntity#airToGround}).
   * Then the two move apart: the clone back toward its own side, and the unit, set up as a clone
   * too unless it dashes, forward. Last, for a unit that carries riders, each rider in their order
   * is cloned the same way, through this creator - its clone made on the rider's point, the rider
   * set up as a clone and moving apart too - and its clone attached to the unit's clone on the
   * rider's angle. The clone of a unit that carries riders never enters the deploying state, so it
   * makes no riders of its own. A unit still deploying is set up as a clone with its countdown
   * running, as its clone is: the countdown runs down in the setup, and each move apart that
   * reaches its duration holds its unit still until the countdown has ended, then resumes it.
   *
   * @param original the unit
   * @param instigator the Clone's area effect
   * @param action the Clone's action
   * @return the clone
   */
  CharacterEntity makeClone(CharacterEntity original, AreaEffectEntity instigator, Clone action) {
    UnitData row = original.getData();
    // The clone's row: the unit's own, or the row its row names as its cloned version.
    UnitData cloneRow = original.cloneRow();
    GridEntity at = original.getView();
    int fromX = at.getX();
    int fromY = at.getY();
    int made = cloneCounts.merge(original.name(), 1, Integer::sum) - 1;
    CharacterEntity clone =
        CharacterEntity.cloned(
            world,
            cloneRow,
            original.name() + "_clone" + made,
            original.side(),
            inset(fromX, world.getTileMap().width()),
            inset(fromY, world.getTileMap().height()),
            PackedLevel.level(PackedLevel.pack(instigator.packedLevel(), cloneRow.rarity())));
    clone.markClone(original);
    // The clone takes the deploy countdown the unit has left, before its setup, or after it for a
    // unit that carries riders.
    clone.enterCloneSetup(at.getDeployCountdown(), row.spawnAttach());
    clone.getView().setDirX(at.getDirX());
    clone.getView().setDirY(at.getDirY());
    List<Integer> visits = new ArrayList<>();
    for (int slot : new int[] {CharacterEntity.TARGETING_SLOT, CharacterEntity.MOVEMENT_SLOT}) {
      if (clone.isActive(slot)) {
        visits.add(slot);
      }
    }
    // Handed over as a spawn is: the fold that takes it in at the tick's closing cleanup starts
    // it, scheduling its row's starting action.
    clone.startOnAdmission();
    world.getHolder().addRegistered(clone);
    world.events().cloned(original, clone, instigator, visits);
    clone.getBuffs().copyFrom(original.getBuffs());
    // The creator tells each run the unit lists of the clone, in their order; only an air-to-ground
    // run acts on it: it schedules its row on the clone with the unit as the cause, whose start
    // then copies the unit's run.
    for (ActionInstance run : List.copyOf(original.actionHolder().running())) {
      if (run.getAction() instanceof AirToGround hold) {
        clone.actionHolder().schedule(hold, ActionHolder.OWN_DELAY, false, original.actionHolder());
      }
    }
    clone.startCloneMove(fromX, fromY, action);
    // A unit that dashes, or is in its ability's follow-up, keeps its state and does not move.
    int state = at.getState();
    if (state != GridEntityState.DASHING && state != GridEntityState.ABILITY_FOLLOW_UP) {
      original.enterCloneSetup();
      original.startCloneMove(fromX, fromY, action);
    }
    if (row.spawnAttach()) {
      List<CharacterEntity> riders = List.copyOf(original.riders());
      for (int i = 0; i < riders.size(); i++) {
        CharacterEntity rider = riders.get(i);
        CharacterEntity riderClone = makeClone(rider, instigator, action);
        riderClone.attachTo(clone, rider.getAttachAngle());
        world.events().riderAttached(clone, riderClone, i, rider.getAttachAngle());
      }
    }
    return clone;
  }

  /**
   * A clone's spawn is a clone: the clone setter on the child, the spawner as the unit it stands
   * for. Every spawner asks its source whether it is a clone, the one an action's spawn hands its
   * block to included, so the Skeletons a clone barrel's death action spawns are clones as its
   * death spawns are. The spawner calls it last, after the child's deploying state and deploy
   * countdown, and it sets only the clone mark and the hit points, so a child with a deploy time is
   * a clone made deploying: its countdown runs as any unit's, it takes no part in collision until
   * the countdown ends (the contact rule's deploying clone), and it resumes as any unit then.
   *
   * <p>A clone's area effect as the spawner makes clones too: the spawner asks it as it asks any
   * source, and hands the setter no original, as for every source that is not a character, so the
   * child takes its own shield rule. The death area of a cloned Suspicious Bush spawns its Bush
   * Goblins as clones of 1 hit point.
   */
  private static void cloneSpawn(SpawnHost spawner, CharacterEntity child) {
    if (spawner instanceof AreaEffectEntity area) {
      if (area.isClone()) {
        child.markClone(null);
      }
      return;
    }
    if (!(spawner instanceof CharacterEntity source) || !source.isClone()) {
      return;
    }
    child.markClone(source);
  }

  /**
   * A spawn source's clone answer: a clone, or a clone's area effect.
   *
   * @param source the source
   */
  static boolean isClone(SpawnHost source) {
    return source instanceof CharacterEntity unit && unit.isClone()
        || source instanceof AreaEffectEntity area && area.isClone();
  }

  /** A position kept the creation's inset inside one axis of the arena. */
  static int inset(int value, int cells) {
    return Math.min(
        Math.max(value, CardPlacement.CREATION_INSET),
        cells * TileMap.CELL_UNITS - CardPlacement.CREATION_INSET);
  }

  /**
   * The row of a child made other than by an action's spawn: its spawner's, its death's, its
   * projectile's, its rider's or its morph's. A row that limits the size of its spawn group is
   * refused, as the limit is not traced on these paths.
   */
  private UnitData spawnedRow(String name) {
    UnitData child = world.getRecords().unit(name);
    if (child.groupMaxSize() > 0) {
      throw new UnsupportedOperationException(
          "spawning " + name + " asks for a limit on its group, which is not established");
    }
    return child;
  }

  /** Refuses the parts of a spawn whose behaviour is not established. */
  private static void refuseUnestablished(
      SpawnHost source, SpawnArguments arguments, UnitData data) {
    String refused = null;
    if (arguments.morph()) {
      refused = "a morph";
    } else if (arguments.enemy()) {
      refused = "a spawn for the other side";
    } else if (arguments.radius() != 0
        && source instanceof WorldEntity entity
        && entity.isCharacter()
        && entity.getData().deathSpawnMinRadius() != 0) {
      // Each child's radius would be drawn from the battle's random source.
      refused = "a ring around a character whose row draws each child's radius";
    } else if (arguments.radius() != 0
        && source instanceof WorldEntity entity
        && entity.isCharacter()
        && entity.getData().spawnAttach()
        && !arguments.deathSpawn()) {
      // Each child would be carried by the source, at its index and ring angle.
      refused = "a ring around a character whose row attaches its children";
    } else if (data.spawnPathfindSpeed() != 0) {
      refused = "a unit that paths to its spawn point";
    } else if (data.groupMaxSize() > 0
        && !(source instanceof CharacterEntity character
            && !character.getData().name().equals(data.name()))) {
      // A character spawning another row leaves the limit unused; any other limit is not traced.
      refused = "a limit on its group";
    }
    if (refused != null) {
      throw new UnsupportedOperationException(
          "spawning " + data.name() + " asks for " + refused + ", which is not established");
    }
  }
}
