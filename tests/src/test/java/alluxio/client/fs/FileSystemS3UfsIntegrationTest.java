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

package alluxio.client.fs;

import static org.junit.Assert.assertEquals;

import alluxio.AlluxioURI;
import alluxio.client.file.FileInStream;
import alluxio.client.file.FileOutStream;
import alluxio.client.file.FileSystem;
import alluxio.conf.PropertyKey;
import alluxio.exception.AlluxioException;
import alluxio.grpc.CreateFilePOptions;
import alluxio.grpc.WritePType;
import alluxio.testutils.BaseIntegrationTest;
import alluxio.testutils.LocalAlluxioClusterResource;

import org.apache.commons.io.IOUtils;
import org.gaul.s3proxy.junit.S3ProxyRule;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class FileSystemS3UfsIntegrationTest extends BaseIntegrationTest {
  private static final String TEST_CONTENT = "TestContents";
  private static final String TEST_FILE = "test_file";
  private static final String TEST_FILE2 = "test_file2";
  private static final int USER_QUOTA_UNIT_BYTES = 1000;

  @Rule
  public S3ProxyRule mS3Proxy = S3ProxyRule.builder()
      .withPort(8001)
      .withCredentials("_", "_")
      .build();

  public LocalAlluxioClusterResource mLocalAlluxioClusterResource =
      new LocalAlluxioClusterResource.Builder()
          .setProperty(PropertyKey.USER_FILE_BUFFER_BYTES, USER_QUOTA_UNIT_BYTES)
          .setProperty(PropertyKey.UNDERFS_S3_ENDPOINT, "localhost:8001")
          .setProperty(PropertyKey.UNDERFS_S3_ENDPOINT_REGION, "us-west-2")
          .setProperty(PropertyKey.UNDERFS_S3_DISABLE_DNS_BUCKETS, true)
          .setProperty(PropertyKey.MASTER_MOUNT_TABLE_ROOT_UFS, "s3://" + TEST_BUCKET)
          .setProperty(PropertyKey.S3A_ACCESS_KEY, mS3Proxy.getAccessKey())
          .setProperty(PropertyKey.S3A_SECRET_KEY, mS3Proxy.getSecretKey())
          .setStartCluster(false)
          .build();
  private FileSystem mFileSystem = null;
  private S3Client mS3Client = null;
  @Rule
  public ExpectedException mThrown = ExpectedException.none();

  private static final String TEST_BUCKET = "test-bucket";

  @Before
  public void before() throws Exception {
    // SDK v2 + S3Proxy compat: force path-style, disable the SDK's default checksum-mode header,
    // and pin the test client to us-east-1 to dodge the LocationConstraint header on
    // CreateBucket. The alluxio s3a UFS still talks to S3Proxy as us-west-2 via the cluster
    // resource properties.
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

    mLocalAlluxioClusterResource.start();
    mFileSystem = mLocalAlluxioClusterResource.get().getClient();
  }

  @After
  public void after() {
    if (mS3Client != null) {
      mS3Client.close();
      mS3Client = null;
    }
  }

  @Test
  public void basicMetadataSync() throws IOException, AlluxioException {
    mS3Client.putObject(
        PutObjectRequest.builder().bucket(TEST_BUCKET).key(TEST_FILE).build(),
        RequestBody.fromString(TEST_CONTENT));
    FileInStream fis = mFileSystem.openFile(new AlluxioURI("/" + TEST_FILE));
    assertEquals(TEST_CONTENT, IOUtils.toString(fis, StandardCharsets.UTF_8));
  }

  @Test
  public void basicWriteThrough() throws IOException, AlluxioException {
    FileOutStream fos = mFileSystem.createFile(
        new AlluxioURI("/" + TEST_FILE2),
        CreateFilePOptions.newBuilder().setWriteType(WritePType.CACHE_THROUGH).build());
    fos.write(TEST_CONTENT.getBytes());
    fos.close();
    try (ResponseInputStream<GetObjectResponse> in = mS3Client.getObject(
        GetObjectRequest.builder().bucket(TEST_BUCKET).key(TEST_FILE2).build())) {
      assertEquals(TEST_CONTENT, IOUtils.toString(in, StandardCharsets.UTF_8));
    }
  }
}
