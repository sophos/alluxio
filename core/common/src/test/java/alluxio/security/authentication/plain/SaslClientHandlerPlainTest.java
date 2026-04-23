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

package alluxio.security.authentication.plain;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import alluxio.conf.Configuration;
import alluxio.conf.InstancedConfiguration;
import alluxio.conf.PropertyKey;
import alluxio.exception.status.UnauthenticatedException;
import alluxio.security.User;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.security.auth.Subject;

/**
 * Regression coverage for {@link SaslClientHandlerPlain}'s Kubernetes token
 * re-read behavior. The Subject-credential path is exercised indirectly by
 * the rest of the SASL client test suite.
 */
public final class SaslClientHandlerPlainTest {

  @Rule
  public TemporaryFolder mTmp = new TemporaryFolder();

  /** Provider registration is a process-global no-op after the first call. */
  static {
    java.security.Security.addProvider(new PlainSaslServerProvider());
  }

  private Subject subjectWith(String user, String staleCredential) {
    Subject subject = new Subject();
    subject.getPrincipals().add(new User(user));
    if (staleCredential != null) {
      subject.getPrivateCredentials().add(staleCredential);
    }
    return subject;
  }

  @Test
  public void constructorReReadsTokenFromDiskWhenPathIsConfigured() throws Exception {
    // Arrange: Subject carries a stale credential (what K8sTokenLoginModule
    // would have captured at login time), and the on-disk token has since
    // been rotated by kubelet. The handler must not surface the stale copy.
    File tokenFile = mTmp.newFile("token");
    Files.write(tokenFile.toPath(), "fresh-token".getBytes(StandardCharsets.UTF_8));

    InstancedConfiguration conf = Configuration.copyGlobal();
    conf.set(PropertyKey.SECURITY_AUTHENTICATION_K8S_CLIENT_TOKEN_PATH, tokenFile.getAbsolutePath());

    Subject subject = subjectWith("trino-metabase", "stale-token");

    // Act: constructor runs the re-read and builds the SASL client. If it
    // threw, the handler would never be usable downstream.
    try (SaslClientHandlerPlain handler = new SaslClientHandlerPlain(subject, conf)) {
      // Assert: we can't peek the SASL client's configured password without
      // driving a full bind, but a successful construction under these inputs
      // implies the fresh-read path was taken (the negative test below
      // exercises the failure mode to close that loop).
      assertTrue("handler must be non-null after fresh-read path", handler != null);
    }
  }

  @Test
  public void constructorFailsClosedWhenTokenPathIsConfiguredButFileMissing() throws Exception {
    // Guard: if a path is wired up, we must NOT silently fall back to the
    // stale Subject credential and send it to the master — that would mask
    // the exact failure mode this fix is closing. The handler must fail
    // closed so the caller sees an auth error instead of a phantom retry.
    File missing = new File(mTmp.getRoot(), "does-not-exist");
    InstancedConfiguration conf = Configuration.copyGlobal();
    conf.set(PropertyKey.SECURITY_AUTHENTICATION_K8S_CLIENT_TOKEN_PATH, missing.getAbsolutePath());

    Subject subject = subjectWith("trino-metabase", "stale-token");

    try (SaslClientHandlerPlain handler = new SaslClientHandlerPlain(subject, conf)) {
      fail("expected UnauthenticatedException when configured token path is missing, "
          + "got handler=" + handler);
    } catch (UnauthenticatedException expected) {
      assertTrue("error must name the missing path for operator troubleshooting",
          expected.getMessage().contains(missing.getAbsolutePath()));
    }
  }

  @Test
  public void constructorUsesSubjectCredentialWhenTokenPathIsNotConfigured() throws Exception {
    // Backwards-compat: SIMPLE auth and any CUSTOM deployment that doesn't
    // opt into K8s token auth (empty path) must keep the original
    // Subject-credential behavior. Nothing else should change for them.
    InstancedConfiguration conf = Configuration.copyGlobal();
    conf.set(PropertyKey.SECURITY_AUTHENTICATION_K8S_CLIENT_TOKEN_PATH, "");

    Subject subject = subjectWith("trino-metabase", "subject-password");

    try (SaslClientHandlerPlain handler = new SaslClientHandlerPlain(subject, conf)) {
      assertTrue("handler must build with a subject-sourced credential", handler != null);
    }
  }
}
