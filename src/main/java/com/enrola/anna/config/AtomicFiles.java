package com.enrola.anna.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes an override file so that a crash leaves either the old file or the new one, never a torn
 * one: the text goes to a temporary file beside the target, is forced to disk, and replaces the
 * target in one atomic move. The settings and the quick replies are written this way.
 */
public final class AtomicFiles {

  private static final Logger log = LoggerFactory.getLogger(AtomicFiles.class);

  private AtomicFiles() {}

  /** Replaces {@code target} with {@code content}; a failed write leaves no temporary file. */
  public static void write(Path target, String content) {
    Path temporary = null;
    try {
      Path directory = target.toAbsolutePath().getParent();
      Files.createDirectories(directory);
      temporary = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
      writeDurably(temporary, content);
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException("could not write " + target, e);
    } finally {
      deleteIfLeft(temporary);
    }
  }

  /** The bytes reach the disk before the move makes them the file. */
  private static void writeDurably(Path file, String content) throws IOException {
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
      ByteBuffer bytes = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
      while (bytes.hasRemaining()) {
        channel.write(bytes);
      }
      channel.force(true);
    }
  }

  private static void deleteIfLeft(Path temporary) {
    if (temporary == null) {
      return;
    }
    try {
      Files.deleteIfExists(temporary);
    } catch (IOException e) {
      log.warn("could not delete {}: {}", temporary, e.getMessage());
    }
  }
}
