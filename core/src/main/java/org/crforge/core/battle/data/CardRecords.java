/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.data;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.SpellVariant;
import org.crforge.core.battle.unit.UnitData;

/**
 * The cards of the battle: a deploy card, a match card, a card's champion and the card groups, from
 * the card tables.
 */
final class CardRecords extends RowReader {

  private static final String SPELLS_CHARACTERS = "spells_characters";

  private static final String SPELLS_OTHER = "spells_other";

  private static final String SPELLS_BUILDINGS = "spells_buildings";

  private static final String SPELLS_EVOLVED = "spells_evolved";

  private static final String SPELLS_HERO_FORM = "spells_hero_form";

  /** The card tables a card row is looked up in, in turn. */
  private static final List<String> CARD_TABLES =
      List.of(SPELLS_CHARACTERS, SPELLS_BUILDINGS, SPELLS_OTHER, SPELLS_EVOLVED, SPELLS_HERO_FORM);

  /** The card forms by name; a form's number is the one the evolution field matches. */
  private static final Map<String, Integer> CARD_FORMS =
      Map.of("BasicForm", 0, "EvoForm", 1, "HeroForm", 2, "AutoChessForm", 3, "FlexSlot", 5);

  private static final String CARD_GROUPS = "card_groups";

  /** The class a card row names to be played as one of its options. */
  private static final String SPELL_VARIANT_CLASS = "LogicBattleSpellVariantData";

  /** How many ten-thousandths an option's trigger is stored as, for each unit of the column. */
  private static final int TRIGGER_SCALE = 10;

  /**
   * The columns of a spell card the cast does not model yet: a Mirror, which a match plays as the
   * card it repeats instead, a first projectile of its own, and a variant card's class and its
   * projected summon, which a match plays as the option picked instead. A spell that sets one is
   * refused. The action run as the spell is cast, which only the evolved Goblin Barrel sets (its
   * decoy barrel), is read: the cast runs it, and its row is built or refused then.
   */
  private static final List<String> UNMODELLED_SPELL_COLUMNS =
      List.of("Mirror", "CustomFirstProjectile", "CustomClassType", "UseProjectedTimeSummon");

  CardRecords(GameTables tables, BattleRecords records) {
    super(tables, records);
  }

  /**
   * A troop or building card's placement as the battle reads it, from the spells characters table
   * or the spells buildings table: the units it summons, built from their own rows, how many, its
   * formation and where it may be placed. A building card is played as a troop card is; its unit is
   * a buildings-table row, whose footprint the placement snaps and searches over.
   *
   * <p>A card that names no unit - no summoned character, no second group and no list - but a
   * projectile or an area effect is a spell, whichever table it is in. Deploying as a spell changes
   * nothing else for a card: it only moves a projectile spell's start and, for a card with a unit
   * and an area effect, the search's snap. A card with a unit and a projectile that deploys as a
   * spell is refused.
   *
   * <p>A card with a unit may cast a projectile as well, which the Mega Knight's does: the cast
   * launches it before the units are made, from the placed point less five times the king tower's
   * collision radius along the length, whichever side plays, at three times that radius, onto the
   * placed point. A card with a unit and an area effect that deploys as a spell, as the Electro
   * Wizard and the Ice Wizard do with the wizard in their list, makes the area effect at the placed
   * point after its units, its search not snapped to its unit. One that does not deploy as a spell,
   * or casts a projectile as well, is refused: no card has one.
   *
   * <p>A card with no count summons one. The level index a card may carry is not read, as the game
   * never reads it: the summoned units take the level the card is played at. A card may summon a
   * list of characters, each at an offset of its own, in place of its groups or after its first
   * group, as the evolved Skeleton Army's general follows its soldiers; one that lists them besides
   * a second group but no first is refused, as no card does. The card's deploy time of its own is
   * read nowhere on the placement's path, and is not read here.
   *
   * @param name the card row's name
   */
  DeployCard card(String name) {
    GameRow row = cardRow(name);
    // The card's unit is its summoned character, else its second group, else its list; a card with
    // none of them that casts is a spell, whichever table it is in. A card with a unit keeps the
    // troop path, and its refusals, and casts its projectile besides.
    boolean namesUnit =
        set(row, "SummonCharacter")
            || set(row, "SummonCharacterSecond")
            || set(row, "SummonCharactersList");
    boolean casts = set(row, "Projectile") || set(row, "AreaEffectObject");
    if (!namesUnit && casts) {
      return spell(row);
    }
    // A card with a unit and an area effect makes the area effect at the placed point after its
    // units, as the cast makes them in that order. Deployed as a spell, as the wizards are, its
    // search does not snap to its unit; without that, or with a projectile
    // as well, it is in no row and is refused.
    boolean areaEffect = set(row, "AreaEffectObject");
    if (areaEffect && (!row.bool("SpellAsDeploy") || set(row, "Projectile"))) {
      throw new UnsupportedOperationException(
          name
              + " makes an area effect as well as a unit, without deploying as a spell or with a"
              + " projectile besides, which the cast does not model");
    }
    if (casts && !areaEffect && row.bool("SpellAsDeploy")) {
      throw new UnsupportedOperationException(
          name
              + " deploys as a spell with a unit and a projectile, which moves the projectile's"
              + " start and is not modelled");
    }
    List<DeployCard.Listed> listed = listed(row);
    boolean namesCharacter = !row.string("SummonCharacter").isEmpty();
    if (!listed.isEmpty() && !namesCharacter && set(row, "SummonCharacterSecond")) {
      throw new UnsupportedOperationException(
          name
              + " lists its characters besides a second group but no first, which no card does, not"
              + " modelled");
    }
    checkArgument(!listed.isEmpty() || namesCharacter, () -> name + " summons no character");
    String second = row.string("SummonCharacterSecond");
    // The card's first unit, which the map check and the search read: its summoned character, else
    // its list's first. A card that names both places its groups first and its list after them.
    UnitData summoned =
        namesCharacter ? records.unit(row.string("SummonCharacter")) : listed.get(0).unit();
    return new DeployCard(
        row.name(),
        summoned,
        Math.max(row.intValue("SummonNumber"), 1),
        second.isEmpty() ? null : records.unit(second),
        row.intValue("SummonCharacterSecondCount"),
        row.intValue("SummonRadius"),
        row.intValue("SummonWidth"),
        row.intValue("SummonDeployDelay"),
        row.intValue("SummonDeployDelaySecond"),
        row.bool("CanDeployOnEnemySide"),
        row.bool("CanPlaceOnBuildings"),
        row.bool("CanPlaceOnWater"),
        row.bool("FullLaneDeploy"),
        row.bool("TouchdownLimitedDeploy"),
        row.intValue("DeployWTileMargin"),
        row.intValue("DeployStartY"),
        row.intValue("DeployEndY"),
        set(row, "Projectile") ? row.string("Projectile") : null,
        areaEffect ? row.string("AreaEffectObject") : null,
        // A unit that tunnels and morphs as it surfaces is searched for as its morph.
        tunnelMorph(summoned),
        areaEffect,
        row.intValue("Radius"),
        row.intValue("MultipleProjectiles"),
        row.intValue("ProjectileWaves"),
        row.intValue("ProjectileWaveInterval"),
        row.intValue("ProjectileInterval"),
        listed,
        row.bool("CharactersOffsetsXMirrored"),
        row.bool("IsAGroup"),
        null,
        namesCharacter);
  }

  /**
   * A card as a play whose item names another card places it: the card's units, formation and list,
   * with the staggers between the units of its two groups that the named card's row sets (0 where
   * it sets none). The construction reads the two staggers from the card the play's item names and
   * everything else from the card it resolves to. A Mirror's item names the Mirror, whose row sets
   * no stagger, so a Mirror's play deploys every unit of the card it repeats at once.
   *
   * @param card the card the item resolves to, which the play places
   * @param named the card row the play's item names
   */
  DeployCard withStaggerOfCard(DeployCard card, String named) {
    GameRow row = cardRow(named);
    return card.withStaggers(
        row.intValue("SummonDeployDelay"), row.intValue("SummonDeployDelaySecond"));
  }

  /**
   * The champion a champion slot finds for a card: its linked champion character when that is a
   * champion, which only a hero form names - the Goblins' names the banner its last goblin leaves -
   * else the champion the card summons ({@link DeployCard#champion()}); null for none.
   *
   * @param name the card row's name
   */
  UnitData cardChampion(String name) {
    GameRow row = cardRow(name);
    if (set(row, "LinkedChampionCharacter")) {
      UnitData linked = records.unit(row.string("LinkedChampionCharacter"));
      if (linked.champion()) {
        return linked;
      }
    }
    return card(name).champion();
  }

  /**
   * The characters a card lists, each with its offsets at its index in the two offset lists, which
   * the placement reads unchecked: a list longer than either is refused. Each waits before it
   * deploys the entry of the card's delay list at its index, or the list's last entry past its end;
   * a card without the list gives none.
   */
  private List<DeployCard.Listed> listed(GameRow row) {
    List<String> names = row.strings("SummonCharactersList");
    List<Integer> xs = row.ints("SummonCharactersOffsetsX");
    List<Integer> ys = row.ints("SummonCharactersOffsetsY");
    if (xs.size() < names.size() || ys.size() < names.size()) {
      throw new UnsupportedOperationException(
          row.name() + " lists more characters than offsets, which the placement reads past");
    }
    List<Integer> delays = row.ints("SummonCharactersDelayList");
    List<DeployCard.Listed> listed = new ArrayList<>();
    for (int j = 0; j < names.size(); j++) {
      int delay = delays.isEmpty() ? 0 : delays.get(Math.min(j, delays.size() - 1));
      listed.add(new DeployCard.Listed(records.unit(names.get(j)), xs.get(j), ys.get(j), delay));
    }
    return listed;
  }

  /** The row a unit that tunnels morphs into as it surfaces, or null for none. */
  private UnitData tunnelMorph(UnitData unit) {
    return unit.spawnPathfindMorph() == null ? null : records.unit(unit.spawnPathfindMorph());
  }

  /**
   * A spell card: no unit, the projectile it casts or the area effect it creates, and the unit its
   * placement is searched for - its projectile's spawned character, which the Goblin Barrel has.
   * The offsets a spell card may list for its characters are not read by the cast.
   */
  private DeployCard spell(GameRow row) {
    for (String column : UNMODELLED_SPELL_COLUMNS) {
      if (set(row, column)) {
        throw new UnsupportedOperationException(
            row.name() + " sets " + column + ", which the spell's cast does not model");
      }
    }
    String projectile = set(row, "Projectile") ? row.string("Projectile") : null;
    String areaEffect = set(row, "AreaEffectObject") ? row.string("AreaEffectObject") : null;
    UnitData searchUnit = null;
    if (projectile != null) {
      GameRow projectileRow = tables.table(PROJECTILES).row(projectile);
      if (set(projectileRow, "SpawnCharacter")) {
        searchUnit = records.unit(projectileRow.string("SpawnCharacter"));
        UnitData morph = tunnelMorph(searchUnit);
        if (morph != null) {
          searchUnit = morph;
        }
      }
    }
    return new DeployCard(
        row.name(),
        null,
        0,
        null,
        0,
        0,
        0,
        0,
        0,
        row.bool("CanDeployOnEnemySide"),
        row.bool("CanPlaceOnBuildings"),
        row.bool("CanPlaceOnWater"),
        row.bool("FullLaneDeploy"),
        row.bool("TouchdownLimitedDeploy"),
        row.intValue("DeployWTileMargin"),
        row.intValue("DeployStartY"),
        row.intValue("DeployEndY"),
        projectile,
        areaEffect,
        searchUnit,
        row.bool("SpellAsDeploy"),
        row.intValue("Radius"),
        row.intValue("MultipleProjectiles"),
        row.intValue("ProjectileWaves"),
        row.intValue("ProjectileWaveInterval"),
        row.intValue("ProjectileInterval"),
        List.of(),
        false,
        false,
        set(row, "OnExecuteAction") ? row.string("OnExecuteAction") : null,
        false);
  }

  /** A card's row, looked up in the card tables in turn. */
  private GameRow cardRow(String name) {
    return tables.table(cardTable(name)).row(name);
  }

  /** The first card table that has a card. */
  private String cardTable(String name) {
    for (String table : CARD_TABLES) {
      if (tables.table(table).has(name)) {
        return table;
      }
    }
    throw new IllegalArgumentException("the game tables have no card " + name);
  }

  /**
   * What the match reads of a card: its cost, its two opening-hand columns, its production stop,
   * whether it is the Mirror, the options a variant card is played as, whether a Mirror repeats it
   * as the card itself or as the row its play was cast as, and the rows it is played as in other
   * forms: each its own match card, with its form and DarkElixirCost. A row's form is its card form
   * column, else the evolved form for a row of the evolved cards, the hero form for a row of the
   * hero forms, else the basic form. The card is looked up in the card tables in turn.
   *
   * @param name the card row's name
   */
  MatchCard matchCard(String name) {
    String table = cardTable(name);
    GameRow row = tables.table(table).row(name);
    int form;
    if (set(row, "CardForm")) {
      Integer named = CARD_FORMS.get(row.string("CardForm"));
      checkArgument(named != null, () -> name + " has the card form " + row.string("CardForm"));
      form = named;
    } else if (table.equals(SPELLS_EVOLVED)) {
      form = MatchCard.EVO_FORM;
    } else if (table.equals(SPELLS_HERO_FORM)) {
      form = MatchCard.HERO_FORM;
    } else {
      form = MatchCard.BASIC_FORM;
    }
    // The list is a single name on a row with one other form.
    List<MatchCard> evolved = new ArrayList<>();
    JsonNode listed = row.value("EvolvedSpells");
    if (listed != null && listed.isArray()) {
      for (JsonNode element : listed) {
        evolved.add(matchCard(row.textElement("EvolvedSpells", element)));
      }
    } else if (!row.string("EvolvedSpells").isEmpty()) {
      evolved.add(matchCard(row.string("EvolvedSpells")));
    }
    return new MatchCard(
        row.name(),
        row.intValue("ManaCost"),
        row.bool("ForceToStartingHand"),
        row.bool("OmitFromStartingHand"),
        row.intValue("ElixirProductionStopTime"),
        row.bool("Mirror"),
        variant(row),
        // Unset, a Mirror repeats the card itself.
        !row.has("MirrorUsesRootSpell") || row.bool("MirrorUsesRootSpell"),
        row.intValue("DarkElixirCost"),
        form,
        evolved);
  }

  /**
   * The options a card is played as when its custom class makes it a variant, or null for any other
   * card. The options keep their order; each one's trigger is stored ten times over, in
   * ten-thousandths of an elixir, and its cost and production stop are its own row's. An option row
   * that is the Mirror or takes its cost from the king's elixir would cost otherwise, and is
   * refused, as is any other custom class.
   */
  private SpellVariant variant(GameRow row) {
    String type = row.string("CustomClassType");
    if (type.isEmpty()) {
      return null;
    }
    if (!type.equals(SPELL_VARIANT_CLASS)) {
      throw new UnsupportedOperationException(
          row.name() + " is of the class " + type + ", which a match does not model");
    }
    List<SpellVariant.Option> options = new ArrayList<>();
    for (JsonNode listed : arrayOf(row, "Options")) {
      JsonNode option = row.tableElement("Options", listed);
      checkArgument(
          option.hasNonNull("SpellData"), () -> row.name() + " has an option with no card");
      GameRow spell = cardRow(row.textField("Options", option, "SpellData"));
      if (spell.bool("Mirror") || spell.bool("ManaCostFromSummonerMana")) {
        throw new UnsupportedOperationException(
            row.name()
                + "'s option "
                + spell.name()
                + " costs other than its own cost, which no option does");
      }
      options.add(
          new SpellVariant.Option(
              spell.name(),
              row.intField("Options", option, "AvailableManaTrigger", 0) * TRIGGER_SCALE,
              row.intField("Options", option, "PrecastPendingTime", 0),
              spell.intValue("ManaCost"),
              spell.intValue("ElixirProductionStopTime")));
    }
    return new SpellVariant(row.bool("UseProjectedTimeSummon"), options);
  }

  /**
   * Whether a card is a troop card: a row of the characters' cards.
   *
   * @param name the card row's name
   */
  boolean troopCard(String name) {
    return tables.table(SPELLS_CHARACTERS).has(name);
  }

  /**
   * Whether a card is a spell card: a row of the other spells' cards.
   *
   * @param name the card row's name
   */
  boolean spellCard(String name) {
    return tables.table(SPELLS_OTHER).has(name);
  }

  /**
   * Whether a card is a spell or building card: a row of the other spells' or the buildings' cards.
   *
   * @param name the card row's name
   */
  boolean spellOrBuildingCard(String name) {
    return tables.table(SPELLS_OTHER).has(name) || tables.table(SPELLS_BUILDINGS).has(name);
  }

  /**
   * The cards a card group lists for a play of a card in any form but the hero form, from the card
   * groups table: its playable cards. A row in the hero form is looked for in the group's heroes
   * instead ({@link #cardGroupHeroes}); its support cards and augments answer only a play of such a
   * card, which the battle does not make.
   *
   * @param name the row's name
   * @return the playable cards, in the row's order
   */
  List<String> cardGroup(String name) {
    GameTable table = tables.table(CARD_GROUPS);
    checkArgument(table.has(name), () -> "the game tables have no card group " + name);
    return table.row(name).strings("PlayableCards");
  }

  /**
   * The cards a card group lists for a play of a row in the hero form, from the card groups table:
   * its heroes, empty for a group that lists none.
   *
   * @param name the row's name
   * @return the hero rows, in the row's order
   */
  List<String> cardGroupHeroes(String name) {
    GameTable table = tables.table(CARD_GROUPS);
    checkArgument(table.has(name), () -> "the game tables have no card group " + name);
    return table.row(name).strings("Heroes");
  }
}
