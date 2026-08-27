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

import alluxio.AlluxioURI;
import alluxio.ConfigurationRule;
import alluxio.conf.Configuration;
import alluxio.conf.InstancedConfiguration;
import alluxio.conf.PropertyKey;
import alluxio.underfs.ObjectUnderFileSystem;
import alluxio.underfs.UfsMode;
import alluxio.underfs.UnderFileSystemConfiguration;
import alluxio.underfs.options.DeleteOptions;

import com.google.common.util.concurrent.ListeningExecutorService;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.services.s3.model.GetBucketAclRequest;
import software.amazon.awssdk.services.s3.model.GetBucketAclResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.Owner;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.StorageClass;
import software.amazon.awssdk.core.sync.RequestBody;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

/**
 * Unit tests for the {@link S3AUnderFileSystem}.
 */
public class S3AUnderFileSystemTest {
  private static final String PATH = "path";
  private static final String SRC = "src";
  private static final String DST = "dst";
  private static final InstancedConfiguration CONF = Configuration.copyGlobal();

  private static final String BUCKET_NAME = "bucket";
  private static final String DEFAULT_OWNER = "";
  private static final short DEFAULT_MODE = 0700;

  private S3AUnderFileSystem mS3UnderFileSystem;
  private S3Client mS3Client;
  private S3AsyncClient mAsyncClient;
  private ListeningExecutorService mExecutor;
  private S3TransferManager mTransferManager;

  @Rule
  public final ExpectedException mThrown = ExpectedException.none();

  @Before
  public void before() {
    mS3Client = Mockito.mock(S3Client.class);
    mExecutor = Mockito.mock(ListeningExecutorService.class);
    mTransferManager = Mockito.mock(S3TransferManager.class);
    mAsyncClient = Mockito.mock(S3AsyncClient.class);
    mS3UnderFileSystem =
        new S3AUnderFileSystem(new AlluxioURI("s3a://" + BUCKET_NAME),
            mS3Client, mAsyncClient, BUCKET_NAME,
            mExecutor, mTransferManager,
            UnderFileSystemConfiguration.defaults(CONF), false, null);
  }

  @Test
  public void deleteNonRecursiveOnSdkException() throws IOException {
    Mockito.when(mS3Client.listObjectsV2(ArgumentMatchers.any(ListObjectsV2Request.class)))
        .thenThrow(SdkException.builder().message("boom").build());

    mThrown.expect(AlluxioS3Exception.class);
    mS3UnderFileSystem.deleteDirectory(PATH, DeleteOptions.defaults().setRecursive(false));
  }

  @Test
  public void deleteRecursiveOnSdkException() throws IOException {
    Mockito.when(mS3Client.listObjectsV2(ArgumentMatchers.any(ListObjectsV2Request.class)))
        .thenThrow(SdkException.builder().message("boom").build());

    mThrown.expect(AlluxioS3Exception.class);
    mS3UnderFileSystem.deleteDirectory(PATH, DeleteOptions.defaults().setRecursive(true));
  }

  @Test
  public void isFile404() throws IOException {
    Mockito.when(mS3Client.headObject(ArgumentMatchers.any(HeadObjectRequest.class)))
        .thenThrow(NoSuchKeyException.builder().statusCode(404).build());

    Assert.assertFalse(mS3UnderFileSystem.isFile(SRC));
  }

  @Test
  public void isFileException() throws IOException {
    Mockito.when(mS3Client.headObject(ArgumentMatchers.any(HeadObjectRequest.class)))
        .thenThrow((S3Exception) S3Exception.builder().statusCode(403).message("Forbidden")
            .build());

    mThrown.expect(AlluxioS3Exception.class);
    Assert.assertFalse(mS3UnderFileSystem.isFile(SRC));
  }

  @Test
  public void renameOnSdkException() throws IOException {
    Mockito.when(mS3Client.headObject(ArgumentMatchers.any(HeadObjectRequest.class)))
        .thenThrow(SdkException.builder().message("boom").build());

    mThrown.expect(AlluxioS3Exception.class);
    mS3UnderFileSystem.renameFile(SRC, DST);
  }

  @Test
  public void createCredentialsFromConf() throws Exception {
    Map<PropertyKey, Object> conf = new HashMap<>();
    conf.put(PropertyKey.S3A_ACCESS_KEY, "key1");
    conf.put(PropertyKey.S3A_SECRET_KEY, "key2");
    try (Closeable c = new ConfigurationRule(conf, CONF).toResource()) {
      UnderFileSystemConfiguration ufsConf = UnderFileSystemConfiguration.defaults(CONF);
      AwsCredentialsProvider credentialsProvider =
          S3AUnderFileSystem.createAwsCredentialsProvider(ufsConf);
      AwsCredentials creds = credentialsProvider.resolveCredentials();
      Assert.assertEquals("key1", creds.accessKeyId());
      Assert.assertEquals("key2", creds.secretAccessKey());
      Assert.assertTrue(credentialsProvider instanceof StaticCredentialsProvider);
    }
  }

  @Test
  public void createCredentialsFromDefault() throws Exception {
    // Unset AWS properties if present
    Map<PropertyKey, Object> conf = new HashMap<>();
    conf.put(PropertyKey.S3A_ACCESS_KEY, null);
    conf.put(PropertyKey.S3A_SECRET_KEY, null);
    try (Closeable c = new ConfigurationRule(conf, CONF).toResource()) {
      UnderFileSystemConfiguration ufsConf = UnderFileSystemConfiguration.defaults(CONF);
      AwsCredentialsProvider credentialsProvider =
          S3AUnderFileSystem.createAwsCredentialsProvider(ufsConf);
      Assert.assertTrue(credentialsProvider instanceof DefaultCredentialsProvider);
    }
  }

  private static ListBucketsResponse listBucketsRespWithOwner(String id, String displayName) {
    return ListBucketsResponse.builder()
        .owner(Owner.builder().id(id).displayName(displayName).build())
        .build();
  }

  @Test
  public void getPermissionsCached() {
    Mockito.when(mS3Client.listBuckets())
        .thenReturn(listBucketsRespWithOwner("0", "test"));
    Mockito.when(mS3Client.getBucketAcl(Mockito.any(GetBucketAclRequest.class)))
        .thenReturn(GetBucketAclResponse.builder().build());
    mS3UnderFileSystem.getPermissions();
    mS3UnderFileSystem.getPermissions();
    Mockito.verify(mS3Client).listBuckets();
    Mockito.verify(mS3Client).getBucketAcl(Mockito.any(GetBucketAclRequest.class));
  }

  @Test
  public void getPermissionsDefault() {
    Mockito.when(mS3Client.listBuckets())
        .thenThrow(SdkException.builder().message("boom").build());
    ObjectUnderFileSystem.ObjectPermissions permissions = mS3UnderFileSystem.getPermissions();
    Assert.assertEquals(DEFAULT_OWNER, permissions.getGroup());
    Assert.assertEquals(DEFAULT_OWNER, permissions.getOwner());
    Assert.assertEquals(DEFAULT_MODE, permissions.getMode());
  }

  @Test
  public void getPermissionsWithMapping() throws Exception {
    Map<PropertyKey, Object> conf = new HashMap<>();
    conf.put(PropertyKey.UNDERFS_S3_OWNER_ID_TO_USERNAME_MAPPING, "111=altname");
    try (Closeable c = new ConfigurationRule(conf, CONF).toResource()) {
      S3AUnderFileSystem s3UnderFileSystem =
              new S3AUnderFileSystem(new AlluxioURI("s3a://" + BUCKET_NAME),
                  mS3Client, mAsyncClient, BUCKET_NAME,
                  mExecutor, mTransferManager,
                  UnderFileSystemConfiguration.defaults(CONF), false, null);

      Mockito.when(mS3Client.listBuckets())
          .thenReturn(listBucketsRespWithOwner("111", "test"));
      Mockito.when(mS3Client.getBucketAcl(Mockito.any(GetBucketAclRequest.class)))
          .thenReturn(GetBucketAclResponse.builder().build());
      ObjectUnderFileSystem.ObjectPermissions permissions = s3UnderFileSystem.getPermissions();

      Assert.assertEquals("altname", permissions.getOwner());
      Assert.assertEquals("altname", permissions.getGroup());
      Assert.assertEquals(0, permissions.getMode());
    }
  }

  @Test
  public void getPermissionsNoMapping() throws Exception {
    Map<PropertyKey, Object> conf = new HashMap<>();
    conf.put(PropertyKey.UNDERFS_S3_OWNER_ID_TO_USERNAME_MAPPING, "111=userid");
    try (Closeable c = new ConfigurationRule(conf, CONF).toResource()) {
      S3AUnderFileSystem s3UnderFileSystem =
              new S3AUnderFileSystem(new AlluxioURI("s3a://" + BUCKET_NAME),
                  mS3Client, mAsyncClient, BUCKET_NAME,
                  mExecutor, mTransferManager,
                  UnderFileSystemConfiguration.defaults(CONF), false, null);

      Mockito.when(mS3Client.listBuckets())
          .thenReturn(listBucketsRespWithOwner("0", "test"));
      Mockito.when(mS3Client.getBucketAcl(Mockito.any(GetBucketAclRequest.class)))
          .thenReturn(GetBucketAclResponse.builder().build());
      ObjectUnderFileSystem.ObjectPermissions permissions = s3UnderFileSystem.getPermissions();

      Assert.assertEquals("test", permissions.getOwner());
      Assert.assertEquals("test", permissions.getGroup());
      Assert.assertEquals(0, permissions.getMode());
    }
  }

  @Test
  public void getOperationMode() {
    Map<String, UfsMode> physicalUfsState = new Hashtable<>();
    // Check default
    Assert.assertEquals(UfsMode.READ_WRITE,
        mS3UnderFileSystem.getOperationMode(physicalUfsState));
    physicalUfsState.put(new AlluxioURI("swift://" + BUCKET_NAME).getRootPath(),
        UfsMode.NO_ACCESS);
    Assert.assertEquals(UfsMode.READ_WRITE,
        mS3UnderFileSystem.getOperationMode(physicalUfsState));
    // Check setting NO_ACCESS mode
    physicalUfsState.put(new AlluxioURI("s3a://" + BUCKET_NAME).getRootPath(),
        UfsMode.NO_ACCESS);
    Assert.assertEquals(UfsMode.NO_ACCESS,
        mS3UnderFileSystem.getOperationMode(physicalUfsState));
    // Check setting READ_ONLY mode
    physicalUfsState.put(new AlluxioURI("s3a://" + BUCKET_NAME).getRootPath(),
        UfsMode.READ_ONLY);
    Assert.assertEquals(UfsMode.READ_ONLY,
        mS3UnderFileSystem.getOperationMode(physicalUfsState));
    // Check setting READ_WRITE mode
    physicalUfsState.put(new AlluxioURI("s3a://" + BUCKET_NAME).getRootPath(),
        UfsMode.READ_WRITE);
    Assert.assertEquals(UfsMode.READ_WRITE,
        mS3UnderFileSystem.getOperationMode(physicalUfsState));
  }

  @Test
  public void stripPrefixIfPresent() {
    Assert.assertEquals("", mS3UnderFileSystem.stripPrefixIfPresent("s3a://" + BUCKET_NAME));
    Assert.assertEquals("", mS3UnderFileSystem.stripPrefixIfPresent("s3a://" + BUCKET_NAME + "/"));
    Assert.assertEquals("test/",
        mS3UnderFileSystem.stripPrefixIfPresent("s3a://" + BUCKET_NAME + "/test/"));
    Assert.assertEquals("test", mS3UnderFileSystem.stripPrefixIfPresent("test"));
    Assert.assertEquals("test/", mS3UnderFileSystem.stripPrefixIfPresent("test/"));
    Assert.assertEquals("test/", mS3UnderFileSystem.stripPrefixIfPresent("/test/"));
    Assert.assertEquals("test", mS3UnderFileSystem.stripPrefixIfPresent("/test"));
    Assert.assertEquals("", mS3UnderFileSystem.stripPrefixIfPresent(""));
    Assert.assertEquals("", mS3UnderFileSystem.stripPrefixIfPresent("/"));
  }

  @Test
  public void getNullLastModifiedTime() throws IOException {
    Mockito.when(mS3Client.headObject(ArgumentMatchers.any(HeadObjectRequest.class)))
        .thenReturn(HeadObjectResponse.builder().contentLength(0L).build());
    // throw NPE before https://github.com/Alluxio/alluxio/pull/14641
    mS3UnderFileSystem.getObjectStatus(PATH);
  }

  @Test
  public void resolveStorageClassUnsetReturnsNull() {
    Assert.assertNull(
        S3AUnderFileSystem.resolveStorageClass(UnderFileSystemConfiguration.defaults(CONF)));
  }

  @Test
  public void resolveStorageClassWhitespaceReturnsNull() throws Exception {
    Map<PropertyKey, Object> overrides = new HashMap<>();
    overrides.put(PropertyKey.UNDERFS_S3_STORAGE_CLASS, "   ");
    try (Closeable c = new ConfigurationRule(overrides, CONF).toResource()) {
      Assert.assertNull(
          S3AUnderFileSystem.resolveStorageClass(UnderFileSystemConfiguration.defaults(CONF)));
    }
  }

  @Test
  public void resolveStorageClassStandardIa() throws Exception {
    Map<PropertyKey, Object> overrides = new HashMap<>();
    overrides.put(PropertyKey.UNDERFS_S3_STORAGE_CLASS, "STANDARD_IA");
    try (Closeable c = new ConfigurationRule(overrides, CONF).toResource()) {
      Assert.assertEquals(StorageClass.STANDARD_IA,
          S3AUnderFileSystem.resolveStorageClass(UnderFileSystemConfiguration.defaults(CONF)));
    }
  }

  @Test
  public void resolveStorageClassExpressOnezone() throws Exception {
    Map<PropertyKey, Object> overrides = new HashMap<>();
    overrides.put(PropertyKey.UNDERFS_S3_STORAGE_CLASS, "EXPRESS_ONEZONE");
    try (Closeable c = new ConfigurationRule(overrides, CONF).toResource()) {
      Assert.assertEquals(StorageClass.EXPRESS_ONEZONE,
          S3AUnderFileSystem.resolveStorageClass(UnderFileSystemConfiguration.defaults(CONF)));
    }
  }

  @Test
  public void resolveStorageClassRejectsUnknown() throws Exception {
    Map<PropertyKey, Object> overrides = new HashMap<>();
    overrides.put(PropertyKey.UNDERFS_S3_STORAGE_CLASS, "DEFINITELY_NOT_A_CLASS");
    try (Closeable c = new ConfigurationRule(overrides, CONF).toResource()) {
      mThrown.expect(IllegalArgumentException.class);
      mThrown.expectMessage("DEFINITELY_NOT_A_CLASS");
      S3AUnderFileSystem.resolveStorageClass(UnderFileSystemConfiguration.defaults(CONF));
    }
  }

  /**
   * End-to-end check that a configured storage class lands on the {@code PutObjectRequest}
   * issued by {@code createEmptyObject}. Covers the path that creates "directory marker"
   * objects — the simplest write path with no streaming involved.
   */
  @Test
  public void createEmptyObjectAppliesConfiguredStorageClass() {
    S3AUnderFileSystem ufs = new S3AUnderFileSystem(new AlluxioURI("s3a://" + BUCKET_NAME),
        mS3Client, mAsyncClient, BUCKET_NAME, mExecutor, mTransferManager,
        UnderFileSystemConfiguration.defaults(CONF), false, StorageClass.STANDARD_IA);

    Mockito.when(mS3Client.putObject(ArgumentMatchers.any(PutObjectRequest.class),
        ArgumentMatchers.any(RequestBody.class)))
        .thenReturn(PutObjectResponse.builder().build());

    Assert.assertTrue(ufs.createEmptyObject("some/marker"));

    org.mockito.ArgumentCaptor<PutObjectRequest> captor =
        org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
    Mockito.verify(mS3Client).putObject(captor.capture(), ArgumentMatchers.any(RequestBody.class));
    Assert.assertEquals(StorageClass.STANDARD_IA, captor.getValue().storageClass());
  }

  @Test
  public void createEmptyObjectOmitsStorageClassWhenUnconfigured() {
    Mockito.when(mS3Client.putObject(ArgumentMatchers.any(PutObjectRequest.class),
        ArgumentMatchers.any(RequestBody.class)))
        .thenReturn(PutObjectResponse.builder().build());

    Assert.assertTrue(mS3UnderFileSystem.createEmptyObject("some/marker"));

    org.mockito.ArgumentCaptor<PutObjectRequest> captor =
        org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
    Mockito.verify(mS3Client).putObject(captor.capture(), ArgumentMatchers.any(RequestBody.class));
    Assert.assertNull(captor.getValue().storageClass());
  }
}
