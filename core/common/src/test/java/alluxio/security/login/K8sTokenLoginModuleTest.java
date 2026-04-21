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

package alluxio.security.login;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import alluxio.conf.Configuration;
import alluxio.conf.InstancedConfiguration;
import alluxio.conf.PropertyKey;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import javax.security.auth.Subject;
import javax.security.auth.login.LoginException;

/**
 * Tests {@link K8sTokenLoginModule} in isolation and through the
 * {@link LoginModuleConfiguration} CUSTOM chain.
 */
public final class K8sTokenLoginModuleTest {

  @Rule
  public ExpectedException mThrown = ExpectedException.none();

  @Rule
  public TemporaryFolder mTmp = new TemporaryFolder();

  private K8sTokenLoginModule newModule(Subject subject, String tokenPath) {
    InstancedConfiguration conf = Configuration.copyGlobal();
    conf.set(PropertyKey.SECURITY_AUTHENTICATION_K8S_CLIENT_TOKEN_PATH, tokenPath);
    K8sTokenLoginModule module = new K8sTokenLoginModule(conf);
    module.initialize(subject, null, Collections.emptyMap(), Collections.emptyMap());
    return module;
  }

  @Test
  public void loginReturnsFalseWhenPathUnset() throws Exception {
    Subject subject = new Subject();
    K8sTokenLoginModule module = newModule(subject, "");

    assertFalse("empty path must opt out cleanly", module.login());
    assertFalse("no credential must be committed when opt-out", module.commit());
    assertTrue(subject.getPrivateCredentials(String.class).isEmpty());
  }

  @Test
  public void loginFailsWhenFileMissing() throws Exception {
    Subject subject = new Subject();
    K8sTokenLoginModule module = newModule(subject,
        new File(mTmp.getRoot(), "does-not-exist").getAbsolutePath());

    mThrown.expect(LoginException.class);
    mThrown.expectMessage("Failed to read Kubernetes token");
    module.login();
  }

  @Test
  public void loginFailsWhenFileEmpty() throws Exception {
    File empty = mTmp.newFile("empty");
    Subject subject = new Subject();
    K8sTokenLoginModule module = newModule(subject, empty.getAbsolutePath());

    mThrown.expect(LoginException.class);
    mThrown.expectMessage("is empty");
    module.login();
  }

  @Test
  public void loginAttachesTokenToSubjectPrivateCredentials() throws Exception {
    File tokenFile = mTmp.newFile("token");
    Files.write(tokenFile.toPath(), "eyJhbGciOiJSUzI1NiJ9.payload.signature\n"
        .getBytes(StandardCharsets.UTF_8));

    Subject subject = new Subject();
    K8sTokenLoginModule module = newModule(subject, tokenFile.getAbsolutePath());

    assertTrue(module.login());
    assertTrue(module.commit());

    assertEquals(1, subject.getPrivateCredentials(String.class).size());
    String credential = subject.getPrivateCredentials(String.class).iterator().next();
    assertEquals("eyJhbGciOiJSUzI1NiJ9.payload.signature", credential);
  }

  @Test
  public void logoutRemovesTokenFromSubject() throws Exception {
    File tokenFile = mTmp.newFile("token");
    Files.write(tokenFile.toPath(), "jwt-body".getBytes(StandardCharsets.UTF_8));

    Subject subject = new Subject();
    K8sTokenLoginModule module = newModule(subject, tokenFile.getAbsolutePath());

    assertTrue(module.login());
    assertTrue(module.commit());
    assertFalse(subject.getPrivateCredentials(String.class).isEmpty());

    assertTrue(module.logout());
    assertTrue("logout must strip the token credential",
        subject.getPrivateCredentials(String.class).isEmpty());
  }

  @Test
  public void abortClearsPendingToken() throws Exception {
    File tokenFile = mTmp.newFile("token");
    Files.write(tokenFile.toPath(), "jwt-body".getBytes(StandardCharsets.UTF_8));

    Subject subject = new Subject();
    K8sTokenLoginModule module = newModule(subject, tokenFile.getAbsolutePath());

    assertTrue(module.login());
    assertTrue(module.abort());
    assertFalse("commit after abort must be a no-op", module.commit());
    assertTrue(subject.getPrivateCredentials(String.class).isEmpty());
  }

  @Test
  public void commitOnReadOnlySubjectThrows() throws Exception {
    File tokenFile = mTmp.newFile("token");
    Files.write(tokenFile.toPath(), "jwt-body".getBytes(StandardCharsets.UTF_8));

    Subject subject = new Subject();
    K8sTokenLoginModule module = newModule(subject, tokenFile.getAbsolutePath());
    assertTrue(module.login());
    subject.setReadOnly();

    mThrown.expect(LoginException.class);
    mThrown.expectMessage("read-only");
    module.commit();
  }
}
