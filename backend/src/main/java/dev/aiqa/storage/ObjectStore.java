package dev.aiqa.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Private, immutable object keys. Local directory stand-in for development only. */
public interface ObjectStore {

  void put(String objectKey, InputStream content, long contentLength) throws IOException;

  InputStream get(String objectKey) throws IOException;

  void delete(String objectKey) throws IOException;
}
