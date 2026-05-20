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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import alluxio.conf.Configuration;
import alluxio.conf.InstancedConfiguration;
import alluxio.conf.PropertyKey;
import alluxio.util.FormatUtils;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.util.concurrent.Callable;

/**
 * Unit tests for the {@link S3ALowLevelOutputStream}.
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest(S3ALowLevelOutputStream.class)
@SuppressWarnings("unchecked")
public class S3ALowLevelOutputStreamTest {
  private static final String BUCKET_NAME = "testBucket";
  private static final String PARTITION_SIZE = "8MB";
  private static final String KEY = "testKey";
  private static final String UPLOAD_ID = "testUploadId";
  private static InstancedConfiguration sConf = Configuration.modifiableGlobal();

  private S3Client mMockS3Client;
  private ListeningExecutorService mMockExecutor;
  private BufferedOutputStream mMockOutputStream;
  private ListenableFuture<CompletedPart> mMockTag;

  private S3ALowLevelOutputStream mStream;

  /**
   * Sets the properties and configuration before each test runs.
   */
  @Before
  public void before() throws Exception {
    mockS3ClientAndExecutor();
    mockFileAndOutputStream();

    sConf.set(PropertyKey.UNDERFS_S3_STREAMING_UPLOAD_PARTITION_SIZE, PARTITION_SIZE);
    mStream = new S3ALowLevelOutputStream(BUCKET_NAME, KEY, mMockS3Client, mMockExecutor, sConf);
  }

  @Test
  public void writeByte() throws Exception {
    mStream.write(1);

    mStream.close();
    Mockito.verify(mMockOutputStream).write(new byte[] {1}, 0, 1);
    Mockito.verify(mMockExecutor, never()).submit(any(Callable.class));
    Mockito.verify(mMockS3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    Mockito.verify(mMockS3Client, never())
        .createMultipartUpload(any(CreateMultipartUploadRequest.class));
    Mockito.verify(mMockS3Client, never())
        .completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
    assertTrue(mStream.getContentHash().isPresent());
    assertEquals("putTag", mStream.getContentHash().get());
  }

  @Test
  public void writeByteArrayForSmallFile() throws Exception {
    int partSize = (int) FormatUtils.parseSpaceSize(PARTITION_SIZE);
    byte[] b = new byte[partSize];

    mStream.write(b, 0, b.length);
    Mockito.verify(mMockOutputStream).write(b, 0, b.length);

    mStream.close();
    Mockito.verify(mMockExecutor, never()).submit(any(Callable.class));
    Mockito.verify(mMockS3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    Mockito.verify(mMockS3Client, never())
        .createMultipartUpload(any(CreateMultipartUploadRequest.class));
    Mockito.verify(mMockS3Client, never())
        .completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
    assertTrue(mStream.getContentHash().isPresent());
    assertEquals("putTag", mStream.getContentHash().get());
  }

  @Test
  public void writeByteArrayForLargeFile() throws Exception {
    int partSize = (int) FormatUtils.parseSpaceSize(PARTITION_SIZE);
    byte[] b = new byte[partSize + 1];
    assertEquals(mStream.getPartNumber(), 1);
    mStream.write(b, 0, b.length);
    assertEquals(mStream.getPartNumber(), 2);
    Mockito.verify(mMockS3Client)
        .createMultipartUpload(any(CreateMultipartUploadRequest.class));
    Mockito.verify(mMockOutputStream).write(b, 0, b.length - 1);
    Mockito.verify(mMockOutputStream).write(b, b.length - 1, 1);
    Mockito.verify(mMockExecutor).submit(any(Callable.class));

    mStream.close();
    assertEquals(mStream.getPartNumber(), 3);
    Mockito.verify(mMockS3Client)
        .completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
    assertTrue(mStream.getContentHash().isPresent());
    assertEquals("multiTag", mStream.getContentHash().get());
  }

  @Test
  public void createEmptyFile() throws Exception {
    mStream.close();
    Mockito.verify(mMockExecutor, never()).submit(any(Callable.class));
    Mockito.verify(mMockS3Client, never())
        .createMultipartUpload(any(CreateMultipartUploadRequest.class));
    Mockito.verify(mMockS3Client, never())
        .completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
    Mockito.verify(mMockS3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    assertTrue(mStream.getContentHash().isPresent());
    assertEquals("putTag", mStream.getContentHash().get());
  }

  @Test
  public void flush() throws Exception {
    int partSize = (int) FormatUtils.parseSpaceSize(PARTITION_SIZE);
    byte[] b = new byte[2 * partSize - 1];

    mStream.write(b, 0, b.length);
    Mockito.verify(mMockS3Client)
        .createMultipartUpload(any(CreateMultipartUploadRequest.class));
    Mockito.verify(mMockOutputStream).write(b, 0, partSize);
    Mockito.verify(mMockOutputStream).write(b, partSize, partSize - 1);
    Mockito.verify(mMockExecutor).submit(any(Callable.class));

    mStream.flush();
    Mockito.verify(mMockExecutor, times(2)).submit(any(Callable.class));
    Mockito.verify(mMockTag, times(2)).get();

    mStream.close();
    Mockito.verify(mMockS3Client)
        .completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
    assertTrue(mStream.getContentHash().isPresent());
    assertEquals("multiTag", mStream.getContentHash().get());
  }

  @Test
  public void close() throws Exception {
    mStream.close();
    Mockito.verify(mMockS3Client, never())
        .createMultipartUpload(any(CreateMultipartUploadRequest.class));
    Mockito.verify(mMockS3Client, never())
        .completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
    assertTrue(mStream.getContentHash().isPresent());
    assertEquals("putTag", mStream.getContentHash().get());
  }

  /**
   * Mocks the S3 client and executor.
   */
  private void mockS3ClientAndExecutor() throws Exception {
    mMockS3Client = PowerMockito.mock(S3Client.class);

    CreateMultipartUploadResponse initResult = CreateMultipartUploadResponse.builder()
        .uploadId(UPLOAD_ID).build();
    when(mMockS3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
        .thenReturn(initResult);

    when(mMockS3Client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
        .thenAnswer((InvocationOnMock invocation) -> {
          UploadPartRequest req = invocation.getArgument(0);
          return UploadPartResponse.builder().eTag("partTag-" + req.partNumber()).build();
        });

    CompleteMultipartUploadResponse completeResult = CompleteMultipartUploadResponse.builder()
        .eTag("multiTag").build();
    when(mMockS3Client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
        .thenReturn(completeResult);

    PutObjectResponse putResult = PutObjectResponse.builder().eTag("putTag").build();
    when(mMockS3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenReturn(putResult);

    mMockTag = (ListenableFuture<CompletedPart>) PowerMockito.mock(ListenableFuture.class);
    when(mMockTag.get())
        .thenReturn(CompletedPart.builder().partNumber(1).eTag("someTag").build());
    mMockExecutor = Mockito.mock(ListeningExecutorService.class);
    when(mMockExecutor.submit(any(Callable.class))).thenReturn(mMockTag);
  }

  /**
   * Mocks file-related classes.
   */
  private void mockFileAndOutputStream() throws Exception {
    // Back the mocked File with a real (empty) temp path so the v2 SDK's RequestBody.fromFile
    // can call Files.size on it without NPE. The S3 client is mocked, so the file content is
    // never actually uploaded.
    Path realTmp = Files.createTempFile("s3alowlevel", ".bin");
    realTmp.toFile().deleteOnExit();
    File file = Mockito.mock(File.class);
    Mockito.when(file.toPath()).thenReturn(realTmp);
    Mockito.when(file.length()).thenReturn(0L);
    Mockito.when(file.delete()).thenReturn(true);
    Mockito.when(file.getPath()).thenReturn(realTmp.toString());
    PowerMockito.whenNew(File.class).withAnyArguments().thenReturn(file);

    mMockOutputStream = PowerMockito.mock(BufferedOutputStream.class);
    PowerMockito.whenNew(BufferedOutputStream.class)
        .withArguments(Mockito.any(DigestOutputStream.class)).thenReturn(mMockOutputStream);

    FileOutputStream outputStream = PowerMockito.mock(FileOutputStream.class);
    PowerMockito.whenNew(FileOutputStream.class).withArguments(file).thenReturn(outputStream);
  }
}
