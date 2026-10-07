package org.crforge.core.battle.replay;

/**
 * The fields that name a data content, the data version and its content sha, in the files the
 * replay and reference battle tools read and write: a replay's capture block ({@link
 * ReplayCapture}), a reference corpus listing, an expectations file, a scorecard and a smoke run's
 * identity. A content is the game tables' {@code GameTables.version()} and {@code
 * GameTables.contentSha()}.
 */
public final class ContentFields {

  /** The field naming the data version. */
  public static final String CONTENT_VERSION = "content_version";

  /** The field naming the content sha, as the game tables' headers give it. */
  public static final String CONTENT_SHA = "content_sha";

  private ContentFields() {
    // Constants only
  }
}
