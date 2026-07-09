package com.scalar.dl.tools.cleanup;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A simple append-only, crash-safe log of string payloads backed by a single file.
 *
 * <p>Each {@link #append} adds one payload, stored as a single JSON string per line so that
 * payloads containing arbitrary characters (including line breaks) round-trip faithfully. Appends
 * are serialized, so multiple threads may append concurrently. {@link #load} returns every appended
 * payload in insertion order (an empty list if the file does not exist), and {@link #clear} removes
 * the file.
 *
 * <p>The file persists across process restarts, so a resumed run still sees payloads written by a
 * previous run.
 */
@ThreadSafe
public final class AppendOnlyLog {

  private static final ObjectMapper mapper = new ObjectMapper();

  private final Path file;
  private final Object lock = new Object();

  public AppendOnlyLog(Path file) {
    this.file = file;
  }

  /** Appends a payload to the log, creating the file if it does not yet exist. */
  public void append(String payload) {
    synchronized (lock) {
      try {
        // Encode the payload as a JSON string so that payloads containing whitespace or line breaks
        // are written on a single line and round-trip through load() without corruption.
        String line = mapper.writeValueAsString(payload) + "\n";
        Files.write(
            file,
            line.getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to append to the log file " + file, e);
      }
    }
  }

  /**
   * Returns an unmodifiable list of all appended payloads in insertion order, or an empty list if
   * the log file does not exist.
   */
  public List<String> load() {
    synchronized (lock) {
      if (!Files.exists(file)) {
        return Collections.emptyList();
      }
      try {
        List<String> payloads = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
          if (!line.isEmpty()) {
            payloads.add(mapper.readValue(line, String.class));
          }
        }
        return Collections.unmodifiableList(payloads);
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to read the log file " + file, e);
      }
    }
  }

  /** Removes the log file if it exists. */
  public void clear() {
    synchronized (lock) {
      try {
        Files.deleteIfExists(file);
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to delete the log file " + file, e);
      }
    }
  }
}
