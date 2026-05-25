package com.scalar.dl.tools.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Shared file-system utilities for I/O operations. */
public final class FileUtils {

  private FileUtils() {}

  /**
   * Atomically writes content to the target path via write-temp-and-rename.
   *
   * <p>Writes to a temporary sibling file ({@code <target>.tmp}) first, then renames it to the
   * target path. This ensures that the target file is never left in a partially-written state, even
   * if the process crashes mid-write. The temporary file is cleaned up on failure.
   *
   * @param target the path to write to
   * @param content the bytes to write
   * @throws IOException if the write or rename fails
   * @throws IllegalArgumentException if the target path has no file name
   */
  public static void writeAtomic(Path target, byte[] content) throws IOException {
    Path fileName = target.getFileName();
    if (fileName == null) {
      throw new IllegalArgumentException("The target path must have a file name");
    }
    Path tmp = target.resolveSibling(fileName.toString() + ".tmp");
    try {
      Files.write(tmp, content);
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      try {
        Files.deleteIfExists(tmp);
      } catch (IOException suppressed) {
        e.addSuppressed(suppressed);
      }
      throw e;
    }
  }
}
