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
import static org.junit.Assert.assertFalse;

import alluxio.conf.AlluxioConfiguration;
import alluxio.conf.Configuration;
import alluxio.conf.PropertyKey;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.CompletedFileUpload;
import software.amazon.awssdk.transfer.s3.model.FileUpload;
import software.amazon.awssdk.transfer.s3.model.UploadFileRequest;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.util.concurrent.CompletableFuture;

/**
 * Unit tests for the {@link S3AOutputStream}.
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest(S3AOutputStream.class)
public class S3AOutputStreamTest {
  private static final String BUCKET_NAME = "testBucket";
  private static final String KEY = "testKey";
  private static AlluxioConfiguration sConf = Configuration.global();

  private File mFile;
  private BufferedOutputStream mLocalOutputStream;
  private S3AOutputStream mStream;
  private String mContentHash;

  /**
   * Sets the properties and configuration before each test runs.
   */
  @Before
  public void before() throws Exception {
    // Use a real (empty) temp file so the v2 SDK's UploadFileRequest.source(Path) accepts the
    // path. The transfer manager itself is mocked, so the file content is never actually read.
    Path realTmp = Files.createTempFile("s3aoutputstreamtest", ".bin");
    realTmp.toFile().deleteOnExit();
    mFile = Mockito.mock(File.class);
    Mockito.when(mFile.toPath()).thenReturn(realTmp);
    Mockito.when(mFile.length()).thenReturn(0L);
    Mockito.when(mFile.delete()).thenReturn(true);
    Mockito.when(mFile.getPath()).thenReturn(realTmp.toString());
    mLocalOutputStream = Mockito.mock(BufferedOutputStream.class);
    S3TransferManager manager = Mockito.mock(S3TransferManager.class);
    FileUpload upload = Mockito.mock(FileUpload.class);
    mContentHash = "someHash";
    PutObjectResponse putResp = PutObjectResponse.builder().eTag(mContentHash).build();
    CompletedFileUpload completed = CompletedFileUpload.builder().response(putResp).build();
    Mockito.doReturn(CompletableFuture.completedFuture(completed))
        .when(upload).completionFuture();
    Mockito.when(manager.uploadFile(Mockito.any(UploadFileRequest.class))).thenReturn(upload);
    PowerMockito.whenNew(BufferedOutputStream.class)
        .withArguments(Mockito.any(DigestOutputStream.class)).thenReturn(mLocalOutputStream);
    PowerMockito.whenNew(File.class).withArguments(Mockito.anyString()).thenReturn(mFile);
    FileOutputStream outputStream = PowerMockito.mock(FileOutputStream.class);
    PowerMockito.whenNew(FileOutputStream.class).withArguments(mFile).thenReturn(outputStream);
    mStream = new S3AOutputStream(BUCKET_NAME, KEY, manager,
        sConf.getList(PropertyKey.TMP_DIRS),
        sConf.getBoolean(PropertyKey.UNDERFS_S3_SERVER_SIDE_ENCRYPTION_ENABLED),
        null);
    assertFalse(mStream.getContentHash().isPresent());
  }

  /**
   * Tests to ensure {@link S3AOutputStream#write(int)} calls the underlying output stream.
   */
  @Test
  public void writeByte() throws Exception {
    mStream.write(1);
    mStream.close();
    assertEquals(mContentHash, mStream.getContentHash().get());
    Mockito.verify(mLocalOutputStream).write(1);
  }

  /**
   * Tests to ensure {@link S3AOutputStream#write(byte[])} calls the underlying output stream.
   */
  @Test
  public void writeByteArray() throws Exception {
    byte[] b = new byte[10];
    mStream.write(b);
    mStream.close();
    assertEquals(mContentHash, mStream.getContentHash().get());
    Mockito.verify(mLocalOutputStream).write(b, 0, b.length);
  }

  /**
   * Tests to ensure {@link S3AOutputStream#write(byte[], int, int)} calls the underlying
   * output stream.
   */
  @Test
  public void writeByteArrayWithRange() throws Exception {
    byte[] b = new byte[10];
    mStream.write(b, 0, b.length);
    mStream.close();
    assertEquals(mContentHash, mStream.getContentHash().get());
    Mockito.verify(mLocalOutputStream).write(b, 0, b.length);
  }

  /**
   * Tests to ensure {@link File#delete()} is called when the stream is closed.
   */
  @Test
  public void close() throws Exception {
    mStream.close();
    assertEquals(mContentHash, mStream.getContentHash().get());
    Mockito.verify(mFile).delete();
  }

  /**
   * Tests to ensure {@link S3AOutputStream#flush()} calls the underlying output stream.
   */
  @Test
  public void flush() throws Exception {
    mStream.flush();
    mStream.close();
    assertEquals(mContentHash, mStream.getContentHash().get());
    Mockito.verify(mLocalOutputStream).flush();
  }
}
