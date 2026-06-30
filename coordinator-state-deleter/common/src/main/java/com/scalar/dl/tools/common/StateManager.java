package com.scalar.dl.tools.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.annotation.Nullable;

/**
 * Base class for checkpoint state managers that atomically load and persist a state file.
 *
 * <p>Each subcommand ({@code ledger-finalize-records}, {@code auditor-finalize-records}, {@code
 * coordinator-state-cleanup}, {@code request-proof-cleanup}) extends this class to manage its own
 * checkpoint state. The state file is stored as {@code <checkpointDir>/<subdirectory>/state.json}
 * and is written atomically to ensure crash-safety.
 *
 * @param <T> the state type, which must be Jackson-serializable
 */
public abstract class StateManager<T> {

  private static final String STATE_FILE = "state.json";
  private static final ObjectMapper mapper = new ObjectMapper();

  private final Path stateDir;
  private final Class<T> stateClass;

  /**
   * Creates a state manager for the given checkpoint directory and subdirectory.
   *
   * @param checkpointDir the root checkpoint directory
   * @param subdirectory the subcommand-specific subdirectory
   * @param stateClass the class of the state object for Jackson deserialization
   */
  protected StateManager(Path checkpointDir, String subdirectory, Class<T> stateClass) {
    this.stateDir = checkpointDir.resolve(subdirectory);
    this.stateClass = stateClass;
  }

  /**
   * Loads the persisted state from the checkpoint directory.
   *
   * @return the deserialized state, or {@code null} if no state file exists
   * @throws CoordinatorStateDeleterException if the state file exists but cannot be read or parsed
   */
  @Nullable
  public T load() {
    Path statePath = stateDir.resolve(STATE_FILE);
    if (!Files.exists(statePath)) {
      return null;
    }
    try {
      byte[] bytes = Files.readAllBytes(statePath);
      return mapper.readValue(bytes, stateClass);
    } catch (IOException e) {
      throw new CoordinatorStateDeleterException(
          CoordinatorStateDeleterError.STATE_LOAD_FAILED, e, statePath);
    }
  }

  /**
   * Atomically persists the given state via write-temp-and-rename. Creates the state directory if
   * it does not exist.
   *
   * @param state the state object to persist
   * @throws CoordinatorStateDeleterException if the state cannot be serialized or written
   */
  public void persist(T state) {
    try {
      Files.createDirectories(stateDir);
      byte[] content = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(state);
      FileUtils.writeAtomic(stateDir.resolve(STATE_FILE), content);
    } catch (IOException e) {
      throw new CoordinatorStateDeleterException(
          CoordinatorStateDeleterError.STATE_PERSIST_FAILED, e, stateDir.resolve(STATE_FILE));
    }
  }

  /** Returns the state directory ({@code <checkpointDir>/<subdirectory>}). */
  public Path getStateDir() {
    return stateDir;
  }
}
