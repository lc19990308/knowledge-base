package dev.aiqa.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development-only local store. Never exposes the root through HTTP and refuses keys that escape it.
 */
@Component
@ConditionalOnProperty(name = "aiqa.object-store.type", havingValue = "local", matchIfMissing = true)
public class LocalObjectStore implements ObjectStore {

  private final Path root;

  public LocalObjectStore(@Value("${aiqa.object-store.local-root}") String root) {
    this.root = Path.of(root).toAbsolutePath().normalize();
  }

  private Path resolve(String objectKey) throws IOException {
    Path target = root.resolve(objectKey).normalize();
    if (!target.startsWith(root)) {
      throw new IOException("对象键非法");
    }
    return target;
  }

  @Override
  public void put(String objectKey, InputStream content, long contentLength) throws IOException {
    Path target = resolve(objectKey);
    Files.createDirectories(target.getParent());
    Path temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
    try {
      try (content) {
        Files.copy(content, temp, StandardCopyOption.REPLACE_EXISTING);
      }
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException | RuntimeException ex) {
      try {
        Files.deleteIfExists(temp);
      } catch (IOException | RuntimeException cleanupFailure) {
        ex.addSuppressed(cleanupFailure);
      }
      throw ex;
    }
  }

  @Override
  public InputStream get(String objectKey) throws IOException {
    return Files.newInputStream(resolve(objectKey));
  }

  @Override
  public void delete(String objectKey) throws IOException {
    Files.deleteIfExists(resolve(objectKey));
  }
}
