package dev.aiqa.storage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * S3-compatible private object store. Keys are opaque UUIDs and never derived from user filenames.
 * Credentials come from the environment or the instance role; nothing is read from project files.
 */
@Component
@ConditionalOnProperty(name = "aiqa.object-store.type", havingValue = "s3")
public class S3ObjectStore implements ObjectStore {

  private final S3Client client;
  private final String bucket;

  public S3ObjectStore(
      @Value("${aiqa.object-store.s3.bucket}") String bucket,
      @Value("${aiqa.object-store.s3.region:}") String region,
      @Value("${aiqa.object-store.s3.endpoint:}") String endpoint,
      @Value("${aiqa.object-store.s3.access-key:}") String accessKey,
      @Value("${aiqa.object-store.s3.secret-key:}") String secretKey,
      @Value("${aiqa.object-store.s3.path-style:true}") boolean pathStyle) {
    this.bucket = bucket;
    software.amazon.awssdk.services.s3.S3ClientBuilder builder =
        S3Client.builder()
            .region(region.isBlank() ? Region.US_EAST_1 : Region.of(region))
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(pathStyle)
                    .build())
            .requestChecksumCalculation(
                software.amazon.awssdk.core.checksums.RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(
                software.amazon.awssdk.core.checksums.ResponseChecksumValidation.WHEN_REQUIRED);
    if (!endpoint.isBlank()) {
      builder.endpointOverride(URI.create(endpoint));
    }
    if (!accessKey.isBlank() && !secretKey.isBlank()) {
      builder.credentialsProvider(
          StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
    } else {
      builder.credentialsProvider(DefaultCredentialsProvider.create());
    }
    this.client = builder.build();
  }

  @Override
  public void put(String objectKey, InputStream content, long contentLength) throws IOException {
    // The upload cap is 100 MiB; buffering makes the length explicit and avoids chunked trailers.
    byte[] bytes = content.readNBytes((int) Math.min(Integer.MAX_VALUE, contentLength));
    client.putObject(
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(objectKey)
            .contentLength((long) bytes.length)
            .build(),
        RequestBody.fromBytes(bytes));
  }

  @Override
  public InputStream get(String objectKey) {
    return client.getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey).build());
  }

  @Override
  public void delete(String objectKey) {
    client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
  }
}
