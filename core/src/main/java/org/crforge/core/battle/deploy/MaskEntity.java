package org.crforge.core.battle.deploy;

/**
 * A character as the deploy mask sees it.
 *
 * @param alive whether it is alive
 * @param side the side that owns it
 * @param x position in game units
 * @param y position in game units
 * @param summonerTower whether it is a princess tower
 * @param summoner whether it is a king tower
 * @param building whether it is a building
 * @param noDeploySizeW the width, in tiles, of the box it closes to the other side; 0 for none
 * @param noDeploySizeH the height of that box
 * @param footprint the tiles its own footprint spans
 */
public record MaskEntity(
    boolean alive,
    int side,
    int x,
    int y,
    boolean summonerTower,
    boolean summoner,
    boolean building,
    int noDeploySizeW,
    int noDeploySizeH,
    int footprint) {

  /** The team of the side: 2 for the neutral side, otherwise the side's lowest bit. */
  int team() {
    return side == 100 ? 2 : side & 1;
  }
}
