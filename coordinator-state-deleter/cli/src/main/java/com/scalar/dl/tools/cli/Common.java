package com.scalar.dl.tools.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.dl.tools.common.Category;
import com.scalar.dl.tools.common.CoordinatorStateDeleterException;
import javax.annotation.Nullable;

/**
 * Shared helpers for emitting the CLI's machine-readable JSON output on stdout, following the same
 * {@code status_code}/{@code output}/{@code error_message} convention used by the ScalarDL client
 * CLI.
 */
public final class Common {

  static final String STATUS_CODE_KEY = "status_code";
  static final String OUTPUT_KEY = "output";
  static final String ERROR_MESSAGE_KEY = "error_message";
  static final String COMPLETION_TOKEN_KEY = "completion_token";
  static final String OK_STATUS = "OK";

  private static final ObjectMapper mapper =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private Common() {}

  /**
   * Prints a success result as {@code {"status_code":"OK","output":<value>}}.
   *
   * @param value the payload to embed under {@code output}, or {@code null} for no payload
   */
  public static void printOutput(@Nullable JsonNode value) {
    ObjectNode json = mapper.createObjectNode();
    json.put(STATUS_CODE_KEY, OK_STATUS);
    json.set(OUTPUT_KEY, value);
    printJson(json);
  }

  /**
   * Prints an error result as {@code {"status_code":<Category>,"error_message":<message>}}. The
   * {@code status_code} is the {@link Category} of a {@link CoordinatorStateDeleterException}
   * ({@code USER_ERROR} or {@code INTERNAL_ERROR}); any other (unexpected) exception is reported as
   * {@code INTERNAL_ERROR}. The structured error code (e.g. {@code DL-TOOLS-1004}) travels in the
   * message.
   *
   * @param e the exception that aborted the command
   */
  public static void printError(Exception e) {
    ObjectNode json = mapper.createObjectNode();
    json.put(STATUS_CODE_KEY, resolveCategory(e).name());
    json.put(ERROR_MESSAGE_KEY, e.getMessage() != null ? e.getMessage() : e.toString());
    printJson(json);
  }

  private static Category resolveCategory(Exception e) {
    if (e instanceof CoordinatorStateDeleterException) {
      return ((CoordinatorStateDeleterException) e).getCategory();
    }
    return Category.INTERNAL_ERROR;
  }

  /** Builds the {@code {"completion_token":<token>}} payload emitted by the finalize commands. */
  public static JsonNode completionTokenOutput(String token) {
    return mapper.createObjectNode().put(COMPLETION_TOKEN_KEY, token);
  }

  /** Serializes the given node and writes it to stdout. */
  private static void printJson(JsonNode json) {
    try {
      System.out.println(mapper.writeValueAsString(json));
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to serialize JSON output", e);
    }
  }
}
