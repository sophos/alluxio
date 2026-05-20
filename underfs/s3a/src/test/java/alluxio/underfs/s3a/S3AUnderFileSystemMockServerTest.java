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

package alluxio.underfs.s3a;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import alluxio.AlluxioURI;
import alluxio.conf.Configuration;
import alluxio.conf.InstancedConfiguration;
import alluxio.file.options.DescendantType;
import alluxio.underfs.UfsLoadResult;
import alluxio.underfs.UfsStatus;
import alluxio.underfs.UnderFileSystemConfiguration;
import alluxio.underfs.UnderFileSystemTestUtil;
import alluxio.underfs.options.ListOptions;

import com.google.common.collect.Iterators;
import org.apache.commons.io.IOUtils;
import org.gaul.s3proxy.junit.S3ProxyRule;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.transfer.s3.S3TransferManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.concurrent.Executors;

/**
 * Unit tests for the {@link S3AUnderFileSystem} using a s3 mock server.
 */
public class S3AUnderFileSystemMockServerTest {
  private static final InstancedConfiguration CONF = Configuration.copyGlobal();

  private static final String TEST_BUCKET = "test-bucket";
  private static final String TEST_FILE = "test_file";
  private static final AlluxioURI TEST_FILE_URI = new AlluxioURI("s3://test-bucket/test_file");
  private static final String TEST_CONTENT = "test_content";

  private S3AUnderFileSystem mS3UnderFileSystem;
  private S3Client mSyncClient;

  @Rule
  public S3ProxyRule mS3Proxy = S3ProxyRule.builder()
      // This is a must to close the behavior gap between native s3 and s3 proxy
      .withBlobStoreProvider("transient")
      .withPort(8001)
      .withCredentials("_", "_")
      .build();

  @Rule
  public final ExpectedException mThrown = ExpectedException.none();

  @Before
  public void before() {
    StaticCredentialsProvider v2Creds = StaticCredentialsProvider.create(
        AwsBasicCredentials.create(mS3Proxy.getAccessKey(), mS3Proxy.getSecretKey()));
    // S3Proxy doesn't honor a LocationConstraint header on CreateBucket. SDK v2 only adds that
    // header when the client region is something other than us-east-1, so pin the test clients
    // (and the seed-bucket call) to us-east-1.
    S3AsyncClient asyncClient = S3AsyncClient.builder()
        .credentialsProvider(v2Creds)
        .endpointOverride(mS3Proxy.getUri())
        .region(Region.US_EAST_1)
        .build();
    mSyncClient = S3Client.builder()
        .credentialsProvider(v2Creds)
        .endpointOverride(mS3Proxy.getUri())
        .region(Region.US_EAST_1)
        .serviceConfiguration(S3Configuration.builder()
            .pathStyleAccessEnabled(true)
            // S3Proxy (jclouds backend) returns 501 on requests carrying SDK v2's default
            // response-checksum-mode header. Production S3 / S3 Express handle it fine.
            .checksumValidationEnabled(false)
            .build())
        .build();
    mSyncClient.createBucket(CreateBucketRequest.builder().bucket(TEST_BUCKET).build());

    S3TransferManager transferManager = S3TransferManager.builder()
        .s3Client(asyncClient).build();
    mS3UnderFileSystem =
        new S3AUnderFileSystem(new AlluxioURI("s3://" + TEST_BUCKET),
            mSyncClient, asyncClient, TEST_BUCKET,
            Executors.newSingleThreadExecutor(), transferManager,
            UnderFileSystemConfiguration.defaults(CONF), false);
  }

  @After
  public void after() {
    mSyncClient = null;
  }

  private void putString(String key, String content) {
    mSyncClient.putObject(
        PutObjectRequest.builder().bucket(TEST_BUCKET).key(key).build(),
        RequestBody.fromString(content));
  }

  @Test
  public void read() throws IOException {
    putString(TEST_FILE, TEST_CONTENT);

    InputStream is =
        mS3UnderFileSystem.open(TEST_FILE_URI.getPath());
    assertEquals(TEST_CONTENT, IOUtils.toString(is, StandardCharsets.UTF_8));
  }

  @Test
  public void nestedDirectory() throws Throwable {
    putString("d1/d1/f1", TEST_CONTENT);
    putString("d1/d1/f2", TEST_CONTENT);
    putString("d1/d2/f1", TEST_CONTENT);
    putString("d2/d1/f1", TEST_CONTENT);
    putString("d3/", "");
    putString("d4/", "");
    putString("d4/f1", TEST_CONTENT);
    putString("f1", TEST_CONTENT);
    putString("f2", TEST_CONTENT);

    /*
      Objects:
       d1/
       d1/d1/
       d1/d1/f1
       d1/d1/f2
       d1/d2/
       d1/d2/f1
       d2/
       d2/d1/
       d2/d1/f1
       d3/
       d4/
       d4/f1
       f1
       f2
     */

    UfsStatus[] ufsStatuses = mS3UnderFileSystem.listStatus(
        "/", ListOptions.defaults().setRecursive(true));
    assertNotNull(ufsStatuses);
    assertEquals(14, ufsStatuses.length);

    UfsLoadResult result = UnderFileSystemTestUtil.performListingAsyncAndGetResult(
        mS3UnderFileSystem, "/", DescendantType.ALL);
    Assert.assertEquals(9, result.getItemsCount());

    result = UnderFileSystemTestUtil.performListingAsyncAndGetResult(
        mS3UnderFileSystem, "/", DescendantType.ONE);
    Assert.assertEquals(6, result.getItemsCount());

    result = UnderFileSystemTestUtil.performListingAsyncAndGetResult(
        mS3UnderFileSystem, "d1", DescendantType.NONE);
    assertEquals(1, result.getItemsCount());

    result = UnderFileSystemTestUtil.performListingAsyncAndGetResult(
        mS3UnderFileSystem, "d1/", DescendantType.NONE);
    assertEquals(1, result.getItemsCount());

    result = UnderFileSystemTestUtil.performListingAsyncAndGetResult(
        mS3UnderFileSystem, "d3", DescendantType.NONE);
    assertEquals(1, result.getItemsCount());

    result = UnderFileSystemTestUtil.performListingAsyncAndGetResult(
        mS3UnderFileSystem, "d3/", DescendantType.NONE);
    assertEquals(1, result.getItemsCount());

    result = UnderFileSystemTestUtil.performListingAsyncAndGetResult(
        mS3UnderFileSystem, "d4", DescendantType.NONE);
    assertEquals(1, result.getItemsCount());

    result = UnderFileSystemTestUtil.performListingAsyncAndGetResult(
        mS3UnderFileSystem, "d4/", DescendantType.NONE);
    assertEquals(1, result.getItemsCount());

    result = UnderFileSystemTestUtil.performListingAsyncAndGetResult(
        mS3UnderFileSystem, "f1", DescendantType.NONE);
    assertEquals(1, result.getItemsCount());

    result = UnderFileSystemTestUtil.performListingAsyncAndGetResult(
        mS3UnderFileSystem, "f1/", DescendantType.NONE);
    assertEquals(0, result.getItemsCount());

    result = UnderFileSystemTestUtil.performListingAsyncAndGetResult(
        mS3UnderFileSystem, "f3", DescendantType.NONE);
    assertEquals(0, result.getItemsCount());

    result = UnderFileSystemTestUtil.performListingAsyncAndGetResult(
        mS3UnderFileSystem, "f3/", DescendantType.NONE);
    assertEquals(0, result.getItemsCount());
  }

  @Test
  public void iterator() throws IOException {
    for (int i = 0; i < 5; ++i) {
      for (int j = 0; j < 5; ++j) {
        for (int k = 0; k < 5; ++k) {
          putString(String.format("%d/%d/%d", i, j, k), TEST_CONTENT);
        }
      }
    }

    Iterator<UfsStatus> ufsStatusesIterator = mS3UnderFileSystem.listStatusIterable(
        "/", ListOptions.defaults().setRecursive(true), null, 5);
    UfsStatus[] statusesFromListing =
        mS3UnderFileSystem.listStatus("/", ListOptions.defaults().setRecursive(true));
    assertNotNull(statusesFromListing);
    assertNotNull(ufsStatusesIterator);
    UfsStatus[] statusesFromIterator =
        Iterators.toArray(ufsStatusesIterator, UfsStatus.class);
    Arrays.sort(statusesFromListing, Comparator.comparing(UfsStatus::getName));
    assertArrayEquals(statusesFromIterator, statusesFromListing);
  }
}
