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

import alluxio.conf.AlluxioConfiguration;
import alluxio.conf.PropertyKey;
import alluxio.underfs.ObjectLowLevelOutputStream;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListeningExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.StorageClass;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Object-storage low-level output stream for AWS S3, driving its own multipart upload through the
 * SDK v2 sync {@link S3Client}. Rewritten on SDK v2 in Phase 2.2 (CSA-21975); the
 * {@code PartETag} (v1) → {@link CompletedPart} (v2) rename is the visible change in the parts
 * list.
 */
@NotThreadSafe
public class S3ALowLevelOutputStream extends ObjectLowLevelOutputStream {
  private static final Logger LOG = LoggerFactory.getLogger(S3ALowLevelOutputStream.class);
  private static final String OCTET_STREAM = "application/octet-stream";

  /** Server side encrypt enabled. */
  private final boolean mSseEnabled;
  /**
   * S3 storage class for every PUT / multipart init this stream issues. {@code null} means
   * "leave the field off the request" — the bucket default applies. Configured once via
   * {@link S3AUnderFileSystem#mStorageClass} on stream construction.
   */
  @Nullable
  private final StorageClass mStorageClass;
  /** The SDK v2 S3 client to interact with S3. */
  protected S3Client mClient;
  /** Completed parts collected as each {@link #uploadPartInternal} returns. */
  private final List<CompletedPart> mTags = Collections.synchronizedList(new ArrayList<>());

  /** The upload id of this multipart upload. */
  protected volatile String mUploadId;

  private String mContentHash;

  /**
   * @param bucketName the name of the bucket
   * @param key the key of the file
   * @param s3Client the SDK v2 S3 client to upload the file with
   * @param executor a thread pool executor
   * @param ufsConf the object store under file system configuration
   * @param storageClass S3 storage class for every PUT / multipart init this stream issues,
   *                     or {@code null} to leave it off the request (bucket default applies)
   */
  public S3ALowLevelOutputStream(
      String bucketName,
      String key,
      S3Client s3Client,
      ListeningExecutorService executor,
      AlluxioConfiguration ufsConf,
      @Nullable StorageClass storageClass) {
    super(bucketName, key, executor,
        ufsConf.getBytes(PropertyKey.UNDERFS_S3_STREAMING_UPLOAD_PARTITION_SIZE), ufsConf);
    mClient = Preconditions.checkNotNull(s3Client);
    mSseEnabled = ufsConf.getBoolean(PropertyKey.UNDERFS_S3_SERVER_SIDE_ENCRYPTION_ENABLED);
    mStorageClass = storageClass;
  }

  /**
   * {@inheritDoc}
   *
   * <p>{@code md5} is intentionally ignored. S3 Express One Zone directory buckets reject
   * {@code Content-MD5} with {@code HTTP 501 "This bucket does not support Content Md5 header"}
   * (CSA-22413), and SDK v2 already attaches a CRC32 checksum by default, so the header is
   * redundant on regular buckets too. The parameter stays in the signature because
   * {@link alluxio.underfs.ObjectLowLevelOutputStream} declares it for every object-store
   * implementation (COS, OSS, ...) — dropping it there is out of scope for the S3 module.
   */
  @Override
  protected void uploadPartInternal(
      File file,
      int partNumber,
      boolean isLastPart,
      @Nullable String md5)
      throws IOException {
    try {
      UploadPartRequest.Builder reqBuilder = UploadPartRequest.builder()
          .bucket(mBucketName)
          .key(mKey)
          .uploadId(mUploadId)
          .partNumber(partNumber)
          .contentLength(file.length());
      // v1's UploadPartRequest.setLastPart(boolean) was client-side validation only;
      // v2 doesn't model it. The server decides what's "last" at CompleteMultipartUpload time.
      UploadPartResponse resp = getClient().uploadPart(reqBuilder.build(),
          RequestBody.fromFile(file));
      mTags.add(CompletedPart.builder().partNumber(partNumber).eTag(resp.eTag()).build());
    } catch (SdkException e) {
      LOG.debug("failed to upload part.", e);
      throw new IOException(String.format(
          "failed to upload part. key: %s part number: %s uploadId: %s",
          mKey, partNumber, mUploadId), e);
    }
  }

  @Override
  protected void initMultiPartUploadInternal() throws IOException {
    try {
      CreateMultipartUploadRequest.Builder reqBuilder = CreateMultipartUploadRequest.builder()
          .bucket(mBucketName)
          .key(mKey)
          .contentType(OCTET_STREAM);
      if (mSseEnabled) {
        reqBuilder.serverSideEncryption(ServerSideEncryption.AES256);
      }
      if (mStorageClass != null) {
        reqBuilder.storageClass(mStorageClass);
      }
      CreateMultipartUploadResponse resp = getClient().createMultipartUpload(reqBuilder.build());
      mUploadId = resp.uploadId();
    } catch (SdkException e) {
      LOG.debug("failed to init multi part upload", e);
      throw new IOException("failed to init multi part upload", e);
    }
  }

  @Override
  protected void completeMultiPartUploadInternal() throws IOException {
    try {
      LOG.debug("complete multi part {}", mUploadId);
      // S3 requires parts in ascending partNumber order at complete time. mTags is appended
      // to as each part finishes which may not be in order under concurrent uploads, so sort
      // defensively before sending.
      List<CompletedPart> ordered;
      synchronized (mTags) {
        ordered = new ArrayList<>(mTags);
      }
      ordered.sort((a, b) -> Integer.compare(a.partNumber(), b.partNumber()));
      CompleteMultipartUploadResponse resp = getClient().completeMultipartUpload(
          CompleteMultipartUploadRequest.builder()
              .bucket(mBucketName)
              .key(mKey)
              .uploadId(mUploadId)
              .multipartUpload(CompletedMultipartUpload.builder().parts(ordered).build())
              .build());
      mContentHash = resp.eTag();
    } catch (SdkException e) {
      LOG.debug("failed to complete multi part upload", e);
      throw new IOException(
          String.format("failed to complete multi part upload, key: %s, upload id: %s",
              mKey, mUploadId), e);
    }
  }

  @Override
  protected void abortMultiPartUploadInternal() throws IOException {
    try {
      getClient().abortMultipartUpload(AbortMultipartUploadRequest.builder()
          .bucket(mBucketName)
          .key(mKey)
          .uploadId(mUploadId)
          .build());
    } catch (SdkException e) {
      LOG.debug("failed to abort multi part upload", e);
      throw new IOException(
          String.format("failed to abort multi part upload, key: %s, upload id: %s", mKey,
              mUploadId), e);
    }
  }

  @Override
  protected void createEmptyObject(String key) throws IOException {
    try {
      PutObjectRequest.Builder reqBuilder = PutObjectRequest.builder()
          .bucket(mBucketName)
          .key(key)
          .contentLength(0L)
          .contentType(OCTET_STREAM);
      if (mStorageClass != null) {
        reqBuilder.storageClass(mStorageClass);
      }
      PutObjectResponse resp = getClient().putObject(reqBuilder.build(), RequestBody.empty());
      mContentHash = resp.eTag();
    } catch (SdkException e) {
      throw new IOException(e);
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>{@code md5} is intentionally ignored — see {@link #uploadPartInternal} for why.
   */
  @Override
  protected void putObject(String key, File file, @Nullable String md5) throws IOException {
    try {
      PutObjectRequest.Builder reqBuilder = PutObjectRequest.builder()
          .bucket(mBucketName)
          .key(key)
          .contentLength(file.length())
          .contentType(OCTET_STREAM);
      if (mSseEnabled) {
        reqBuilder.serverSideEncryption(ServerSideEncryption.AES256);
      }
      if (mStorageClass != null) {
        reqBuilder.storageClass(mStorageClass);
      }
      PutObjectResponse resp = getClient().putObject(reqBuilder.build(),
          RequestBody.fromFile(file));
      mContentHash = resp.eTag();
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  protected S3Client getClient() {
    return mClient;
  }

  @Override
  public Optional<String> getContentHash() {
    return Optional.ofNullable(mContentHash);
  }
}
