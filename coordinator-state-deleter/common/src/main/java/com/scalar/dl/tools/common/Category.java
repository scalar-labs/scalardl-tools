package com.scalar.dl.tools.common;

import java.util.Objects;

/**
 * The category of an error raised by the coordinator-state-deletion tools.
 *
 * <p>The category id is embedded in the error code (see {@link ScalarDlToolsError#buildCode()}) so
 * that the class of an error is recognizable from its code alone.
 */
public enum Category {
  /** An error caused by incorrect usage, such as an invalid argument or configuration. */
  USER_ERROR("1"),
  /** An error caused by an unexpected internal condition, such as an I/O or data error. */
  INTERNAL_ERROR("2");

  private final String id;

  Category(String id) {
    Objects.requireNonNull(id, "The id must not be null.");
    if (id.length() != 1) {
      throw new IllegalArgumentException("The length of the id must be 1.");
    }
    this.id = id;
  }

  public String getId() {
    return id;
  }
}
