package com.scalar.dl.tools.common;

import java.util.Objects;

/**
 * The exception thrown by the ScalarDL cleanup tools.
 *
 * <p>It is constructed from a {@link ScalarDlToolsError} so that the message is prefixed with a
 * structured error code (e.g. {@code DL-TOOLS-1001}) and the originating {@link Category} is
 * preserved.
 */
public class ScalarDlCleanupException extends RuntimeException {

  private final Category category;

  public ScalarDlCleanupException(String message, Category category) {
    super(message);
    this.category = Objects.requireNonNull(category);
  }

  public ScalarDlCleanupException(String message, Throwable cause, Category category) {
    super(message, cause);
    this.category = Objects.requireNonNull(category);
  }

  public ScalarDlCleanupException(ScalarDlToolsError error, Object... args) {
    this(Objects.requireNonNull(error).buildMessage(args), error.getCategory());
  }

  public ScalarDlCleanupException(ScalarDlToolsError error, Throwable cause, Object... args) {
    this(Objects.requireNonNull(error).buildMessage(args), cause, error.getCategory());
  }

  public Category getCategory() {
    return category;
  }
}
