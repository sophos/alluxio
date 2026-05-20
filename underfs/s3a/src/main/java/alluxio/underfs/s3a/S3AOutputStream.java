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

import alluxio.underfs.ContentHashable;
import alluxio.util.CommonUtils;
import alluxio.util.io.PathUtils;

import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.StorageClass;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.CompletedFileUpload;
import software.amazon.awssdk.transfer.s3.model.FileUpload;
import software.amazon.awssdk.transfer.s3.model.UploadFileRequest;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Stream that buffers writes to a local temp file and uploads it through the SDK v2
 * {@link S3TransferManager} on {@link #close()}. The TM picks part size and parallelism
 * automatically; we block on {@link FileUpload#completionFuture()} to preserve the synchronous
 * {@code OutputStream} contract. Rewritten on SDK v2 in Phase 2.2 (CSA-21975).
 */
@NotThreadSafe
public class S3AOutputStream extends OutputStream implements ContentHashable {
  private static final Logger LOG = LoggerFactory.getLogger(S3AOutputStream.class);
  private static final String OCTET_STREAM = "application/octet-stream";

  private final boolean mSseEnabled;

  /**
   * S3 storage class for the {@link PutObjectRequest} the TransferManager wraps. {@code null}
   * means "leave the field off the request" — the bucket default applies. Configured once via
   * {@link S3AUnderFileSystem#mStorageClass} on stream construction.
   */
  @Nullable
  private final StorageClass mStorageClass;

  /** Bucket name of the Alluxio S3 bucket. */
  private final String mBucketName;

  /** Key of the file when it is uploaded to S3. */
  protected final String mKey;

  /** The local file that will be uploaded when the stream is closed. */
  private final File mFile;

  /** Flag to indicate this stream has been closed, to ensure close is only done once. */
  private boolean mClosed = false;

  /**
   * SDK v2 transfer manager used to upload the file. Configured once in
   * {@link S3AUnderFileSystem#createInstance}; we don't tune part size or parallelism per stream.
   */
  protected S3TransferManager mManager;

  /** The output stream to a local file where the file will be buffered until closed. */
  private OutputStream mLocalOutputStream;

  /** The MD5 hash of the file. */
  private MessageDigest mHash;

  private String mContentHash;

  /**
   * @param bucketName the name of the bucket
   * @param key the key of the file
   * @param manager the SDK v2 transfer manager to upload the file with
   * @param tmpDirs a list of temporary directories
   * @param sseEnabled whether or not server side encryption is enabled
   * @param storageClass S3 storage class for the wrapped PUT, or {@code null} to leave it off
   *                     the request (bucket default applies)
   */
  public S3AOutputStream(String bucketName, String key, S3TransferManager manager,
      List<String> tmpDirs, boolean sseEnabled, @Nullable StorageClass storageClass)
      throws IOException {
    Preconditions.checkArgument(bucketName != null && !bucketName.isEmpty(), "Bucket name must "
        + "not be null or empty.");
    mBucketName = bucketName;
    mKey = key;
    mManager = manager;
    mSseEnabled = sseEnabled;
    mStorageClass = storageClass;
    mFile = new File(PathUtils.concatPath(CommonUtils.getTmpDir(tmpDirs), UUID.randomUUID()));
    try {
      mHash = MessageDigest.getInstance("MD5");
      mLocalOutputStream =
          new BufferedOutputStream(new DigestOutputStream(new FileOutputStream(mFile), mHash));
    } catch (NoSuchAlgorithmException e) {
      LOG.warn("Algorithm not available for MD5 hash.", e);
      mHash = null;
      mLocalOutputStream = new BufferedOutputStream(new FileOutputStream(mFile));
    }
  }

  @Override
  public void write(int b) throws IOException {
    mLocalOutputStream.write(b);
  }

  @Override
  public void write(byte[] b) throws IOException {
    mLocalOutputStream.write(b, 0, b.length);
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    mLocalOutputStream.write(b, off, len);
  }

  @Override
  public void flush() throws IOException {
    mLocalOutputStream.flush();
  }

  @Override
  public void close() throws IOException {
    if (mClosed) {
      return;
    }
    mLocalOutputStream.close();
    String path = getUploadPath();
    try {
      PutObjectRequest.Builder reqBuilder = PutObjectRequest.builder()
          .bucket(mBucketName)
          .key(path)
          .contentLength(mFile.length())
          .contentType(OCTET_STREAM);
      if (mSseEnabled) {
        reqBuilder.serverSideEncryption(ServerSideEncryption.AES256);
      }
      if (mStorageClass != null) {
        reqBuilder.storageClass(mStorageClass);
      }
      if (mHash != null) {
        reqBuilder.contentMD5(Base64.getEncoder().encodeToString(mHash.digest()));
      }

      UploadFileRequest uploadReq = UploadFileRequest.builder()
          .putObjectRequest(reqBuilder.build())
          .source(mFile.toPath())
          .build();
      FileUpload upload = getTransferManager().uploadFile(uploadReq);
      CompletedFileUpload result = upload.completionFuture().join();
      mContentHash = result.response().eTag();
    } catch (Exception e) {
      LOG.error("Failed to upload {}", path, e);
      throw new IOException(e);
    } finally {
      if (!mFile.delete()) {
        LOG.error("Failed to delete temporary file @ {}", mFile.getPath());
      }
      // Set the closed flag — close can be retried until mFile.delete is called successfully.
      mClosed = true;
    }
  }

  /**
   * @return the path in S3 to upload the file to
   */
  protected String getUploadPath() {
    return mKey;
  }

  /**
   * @return the SDK v2 transfer manager
   */
  protected S3TransferManager getTransferManager() {
    return mManager;
  }

  @Override
  public Optional<String> getContentHash() {
    return Optional.ofNullable(mContentHash);
  }
}
