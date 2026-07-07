package com.scalar.dl.tools.common;

import java.util.Objects;

/**
 * The exception thrown by the coordinator-state-deletion tools.
 *
 * <p>It is constructed from a {@link ScalarDlToolsError} so that the message is prefixed with a
 * structured error code (e.g. {@code DL-TOOLS-1001}) and the originating {@link Category} is
 * preserved.
 */
public class CoordinatorStateDeleterException extends RuntimeException {

  private final Category category;

  public CoordinatorStateDeleterException(String message, Category category) {
    super(message);
    this.category = Objects.requireNonNull(category);
  }

  public CoordinatorStateDeleterException(String message, Throwable cause, Category category) {
    super(message, cause);
    this.category = Objects.requireNonNull(category);
  }

  public CoordinatorStateDeleterException(ScalarDlToolsError error, Object... args) {
    this(Objects.requireNonNull(error).buildMessage(args), error.getCategory());
  }

  public CoordinatorStateDeleterException(
      ScalarDlToolsError error, Throwable cause, Object... args) {
    this(Objects.requireNonNull(error).buildMessage(args), cause, error.getCategory());
  }

  public Category getCategory() {
    return category;
  }
}
