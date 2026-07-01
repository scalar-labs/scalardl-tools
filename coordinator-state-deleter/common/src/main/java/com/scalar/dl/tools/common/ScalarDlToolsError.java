package com.scalar.dl.tools.common;

import java.util.Objects;

/**
 * A structured error definition for the ScalarDL coordinator-state-deletion tools.
 *
 * <p>This mirrors the error framework used by ScalarDL and ScalarDB: every error carries a {@link
 * Category}, a component name, a unique id, a message, a probable cause, and a suggested solution.
 * Implementations are typically enums (see {@link CoordinatorStateDeleterError}).
 */
public interface ScalarDlToolsError {

  String getComponentName();

  Category getCategory();

  String getId();

  String getMessage();

  String getCause();

  String getSolution();

  // This method validates the error. It is called in the constructor of the enum to ensure that the
  // error is valid.
  default void validate(
      String componentName,
      Category category,
      String id,
      String message,
      String cause,
      String solution) {
    Objects.requireNonNull(componentName);
    Objects.requireNonNull(category);

    Objects.requireNonNull(id);
    if (id.length() != 3) {
      throw new IllegalArgumentException("The length of the id must be 3.");
    }

    Objects.requireNonNull(message);
    Objects.requireNonNull(cause);
    Objects.requireNonNull(solution);
  }

  /**
   * Builds the error code. The code is built as follows:
   *
   * <p>{@code <componentName>-<categoryId><id>}
   *
   * @return the built code
   */
  default String buildCode() {
    return getComponentName() + "-" + getCategory().getId() + getId();
  }

  /**
   * Builds the error message with the given arguments. The message is built as follows:
   *
   * <p>{@code <componentName>-<categoryId><id>: <message>}
   *
   * @param args the arguments to be formatted into the message
   * @return the formatted message
   */
  default String buildMessage(Object... args) {
    return buildCode()
        + ": "
        + (args == null || args.length == 0 ? getMessage() : String.format(getMessage(), args));
  }
}
