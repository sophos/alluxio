/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.master.file;

import static org.junit.Assert.assertTrue;

import alluxio.AlluxioURI;
import alluxio.conf.Configuration;
import alluxio.conf.PropertyKey;
import alluxio.exception.AccessControlException;
import alluxio.exception.BlockInfoException;
import alluxio.exception.FileAlreadyCompletedException;
import alluxio.exception.FileAlreadyExistsException;
import alluxio.exception.FileDoesNotExistException;
import alluxio.exception.InvalidFileSizeException;
import alluxio.exception.InvalidPathException;
import alluxio.master.file.contexts.ExistsContext;
import alluxio.master.file.contexts.MountContext;

import org.gaul.s3proxy.junit.S3ProxyRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;

/**
 * Unit tests for {@link FileSystemMaster}.
 */
public final class FileSystemMasterS3UfsTest extends FileSystemMasterTestBase {
  private static final Logger LOG = LoggerFactory.getLogger(FileSystemMasterS3UfsTest.class);
  private static final String TEST_BUCKET = "test-bucket";
  private static final String TEST_FILE = "test_file";
  private static final String TEST_DIRECTORY = "test_directory";
  private static final String TEST_CONTENT = "test_content";
  private static final AlluxioURI UFS_ROOT = new AlluxioURI("s3://test-bucket/");
  private static final AlluxioURI MOUNT_POINT = new AlluxioURI("/s3_mount");
  private S3Client mS3Client;
  @Rule
  public S3ProxyRule mS3Proxy = S3ProxyRule.builder()
      .withPort(8001)
      .withCredentials("_", "_")
      .build();

  @Override
  public void before() throws Exception {
    Configuration.set(PropertyKey.UNDERFS_S3_ENDPOINT, "localhost:8001");
    Configuration.set(PropertyKey.UNDERFS_S3_ENDPOINT_REGION, "us-west-2");
    Configuration.set(PropertyKey.UNDERFS_S3_DISABLE_DNS_BUCKETS, true);
    Configuration.set(PropertyKey.S3A_ACCESS_KEY, mS3Proxy.getAccessKey());
    Configuration.set(PropertyKey.S3A_SECRET_KEY, mS3Proxy.getSecretKey());

    // SDK v2 + S3Proxy compatibility: force path-style addressing, disable the SDK's default
    // checksum-mode header (S3Proxy returns 501), and pin the client to us-east-1 so v2 doesn't
    // emit a LocationConstraint header on CreateBucket (S3Proxy returns 400 on that). The
    // alluxio s3a UFS still talks to S3Proxy as us-west-2; this is just the local test seed.
    mS3Client = S3Client.builder()
        .endpointOverride(mS3Proxy.getUri())
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(mS3Proxy.getAccessKey(), mS3Proxy.getSecretKey())))
        .serviceConfiguration(S3Configuration.builder()
            .pathStyleAccessEnabled(true)
            .checksumValidationEnabled(false)
            .build())
        .build();
    mS3Client.createBucket(CreateBucketRequest.builder().bucket(TEST_BUCKET).build());

    super.before();
  }

  @Ignore
  @Test
  public void basicWrite()
      throws FileDoesNotExistException, FileAlreadyExistsException, AccessControlException,
      IOException, InvalidPathException, BlockInfoException, InvalidFileSizeException,
      FileAlreadyCompletedException {
    // Not testable:
    // when you create a directory, there's nothing created correspondingly in S3
    // when you create a file, you need to open it on the client side to write the content,
    // which is out of the scope of this testing.
  }

  @Test
  public void basicSync()
      throws FileDoesNotExistException, FileAlreadyExistsException, AccessControlException,
      IOException, InvalidPathException {
    mFileSystemMaster.mount(MOUNT_POINT, UFS_ROOT, MountContext.defaults());
    mS3Client.putObject(
        PutObjectRequest.builder().bucket(TEST_BUCKET).key(TEST_FILE).build(),
        RequestBody.fromString(TEST_CONTENT));
    assertTrue(mFileSystemMaster.exists(MOUNT_POINT.join(TEST_FILE), ExistsContext.defaults()));
  }

  @Override
  public void after() throws Exception {
    if (mS3Client != null) {
      mS3Client.close();
      mS3Client = null;
    }
    super.after();
  }
}
