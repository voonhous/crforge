package org.crforge.core.battle.unit;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.EntityActions;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionOwner;
import org.crforge.core.battle.action.DamageType;
import org.crforge.core.battle.data.ActionBinding;
import org.crforge.core.battle.spawn.SpawnArguments;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.combat.HitPoints;

/**
 * An object that only owns actions: a position, a side, a level and an action holder, and nothing
 * the arena sees. It stands in for the objects the data runs spawn rows from - an area effect left
 * by a death, an event building - which the battle does not have yet.
 *
 * <p>It is an entity of the area-effect kind, so its id is in that band and its action passes come
 * before every projectile's and character's in each pending pass. It has no component, is not in
 * the spatial index, cannot be targeted and is never removed. Asked by a spawner, it answers as an
 * object that is not a character.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the kind and its id band, the action passes and the answers a spawn reads - its"
            + " position, side and packed level, not a character. Supplied: the object itself,"
            + " which stands in for an area effect or an event building without their own update,"
            + " lifetime or removal.")
public final class ActionOwnerEntity extends BattleEntity implements ActionOwner, SpawnHost {

  private final BattleWorld world;
  private final String name;
  private final int side;

  private final int x;
  private final int y;

  /** The owner's level, packed; what a spawn from it re-bases on its children's rarity. */
  private final int packedLevel;

  private final ActionHolder actionHolder;

  /** The variables the owner's actions write. */
  private final Map<Integer, Integer> variables = new HashMap<>();

  /**
   * @param world the battle the owner belongs to
   * @param name the owner's name
   * @param side the owner's side
   * @param x its position along the width, in game units
   * @param y its position along the length, in game units
   * @param packedLevel its level, packed
   */
  public ActionOwnerEntity(
      BattleWorld world, String name, int side, int x, int y, int packedLevel) {
    super(KIND_AREA_EFFECT);
    this.world = world;
    this.name = name;
    this.side = side;
    this.x = x;
    this.y = y;
    this.packedLevel = packedLevel;
    this.actionHolder = new ActionHolder(this, world.getHolder()::isInPendingPass);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public int x() {
    return x;
  }

  @Override
  public int y() {
    return y;
  }

  @Override
  public int side() {
    return side;
  }

  @Override
  public int kind() {
    return getKind();
  }

  /** Not a character: a spawn from it reads none of a character's own columns. */
  @Override
  public boolean isCharacter() {
    return false;
  }

  @Override
  public int prestige() {
    return 0;
  }

  @Override
  public int packedLevel() {
    return packedLevel;
  }

  @Override
  public ActionHolder actionHolder() {
    return actionHolder;
  }

  @Override
  public EntityActions actions() {
    return actionHolder;
  }

  @Override
  public int spawnCharacters(SpawnArguments arguments) {
    return world.spawnCharacters(this, arguments);
  }

  /**
   * What an action row built for the owner reads from it: the battle's variable keys and an empty
   * tag word. It answers no expression, as it stands in for objects whose functions are not
   * modelled; a row with one is refused when it is built.
   */
  public ActionBinding binding() {
    return new ActionBinding() {
      @Override
      public IntSupplier expression(String text) {
        throw new UnsupportedOperationException(
            name + " stands in for an object and answers no expression: " + text);
      }

      @Override
      public int variableKey(String variable) {
        return world.declaredVariable(variable);
      }

      @Override
      public LongSupplier tags() {
        return () -> 0;
      }
    };
  }

  @Override
  public HitPoints actionHitPoints() {
    return null;
  }

  @Override
  public int variable(int key) {
    return variables.getOrDefault(key, 0);
  }

  @Override
  public void setVariable(int key, int value) {
    variables.put(key, value);
  }

  @Override
  public void killBy(ActionOwner killer) {
    throw new UnsupportedOperationException(name + " stands in for an object that is never killed");
  }

  @Override
  public void queueTypedHit(ActionOwner source, int amount, DamageType type) {
    throw new UnsupportedOperationException(name + " stands in for an object that takes no hits");
  }

  /** Never removed: it stands for its object for the whole battle. */
  @Override
  public boolean isRemovable() {
    return false;
  }
}
