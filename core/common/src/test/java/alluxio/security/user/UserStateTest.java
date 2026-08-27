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

package alluxio.security.user;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import alluxio.conf.Configuration;
import alluxio.conf.InstancedConfiguration;
import alluxio.conf.PropertyKey;
import alluxio.security.User;
import alluxio.security.authentication.AuthType;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import javax.security.auth.Subject;

/**
 * Unit test for {@link UserState}.
 */
public final class UserStateTest {
  private final InstancedConfiguration mConfiguration = Configuration.copyGlobal();

  @Rule
  public TemporaryFolder mFolder = new TemporaryFolder();

  /**
   * Tests whether we can get login user with conf in SIMPLE mode.
   */
  @Test
  public void getSimpleLoginUser() throws Exception {
    mConfiguration.set(PropertyKey.SECURITY_AUTHENTICATION_TYPE, AuthType.SIMPLE);

    UserState s = UserState.Factory.create(mConfiguration);
    User loginUser = s.getUser();

    assertNotNull(loginUser);
    assertEquals(System.getProperty("user.name"), loginUser.getName());
  }

  /**
   * Tests whether we can get login user with conf in SIMPLE mode, when user name is provided by
   * the application through configuration.
   */
  @Test
  public void getSimpleLoginUserProvidedByApp() throws Exception {
    mConfiguration.set(PropertyKey.SECURITY_AUTHENTICATION_TYPE, AuthType.SIMPLE);
    mConfiguration.set(PropertyKey.SECURITY_LOGIN_USERNAME, "alluxio-user");

    UserState s = UserState.Factory.create(mConfiguration);
    User loginUser = s.getUser();

    assertNotNull(loginUser);
    assertEquals("alluxio-user", loginUser.getName());
  }

  /**
   * Tests whether we can get login user with conf in SIMPLE mode, when a user list is provided by
   * by the application through configuration.
   */
  @Test
  public void getSimpleLoginUserListProvidedByApp() throws Exception {
    mConfiguration.set(PropertyKey.SECURITY_AUTHENTICATION_TYPE, AuthType.SIMPLE);
    mConfiguration.set(PropertyKey.SECURITY_LOGIN_USERNAME, "alluxio-user, superuser");

    UserState s = UserState.Factory.create(mConfiguration);
    User loginUser = s.getUser();

    // The user list is considered as a single user name.
    assertNotNull(loginUser);
    assertEquals("alluxio-user, superuser", loginUser.getName());
  }

  /**
   * Tests whether we can get login user with conf in SIMPLE mode, when user name is set to an
   * empty string in the application configuration. In this case, login should return the OS user
   * instead of empty string.
   */
  @Test
  public void getSimpleLoginUserWhenNotProvidedByApp() throws Exception {
    mConfiguration.set(PropertyKey.SECURITY_AUTHENTICATION_TYPE, AuthType.SIMPLE);
    mConfiguration.unset(PropertyKey.SECURITY_LOGIN_USERNAME);

    UserState s = UserState.Factory.create(mConfiguration);
    User loginUser = s.getUser();

    assertNotNull(loginUser);
    assertEquals(System.getProperty("user.name"), loginUser.getName());
  }

  /**
   * Tests whether we can get login user with conf in CUSTOM mode.
   */
  @Test
  public void getCustomLoginUser() throws Exception {
    mConfiguration.set(PropertyKey.SECURITY_AUTHENTICATION_TYPE, AuthType.CUSTOM);

    UserState s = UserState.Factory.create(mConfiguration);
    User loginUser = s.getUser();

    assertNotNull(loginUser);
    assertEquals(System.getProperty("user.name"), loginUser.getName());
  }

  /**
   * Tests whether we can get login user with conf in CUSTOM mode, when user name is provided by
   * the application through configuration.
   */
  @Test
  public void getCustomLoginUserProvidedByApp() throws Exception {
    mConfiguration.set(PropertyKey.SECURITY_AUTHENTICATION_TYPE, AuthType.CUSTOM);
    mConfiguration.set(PropertyKey.SECURITY_LOGIN_USERNAME, "alluxio-user");

    UserState s = UserState.Factory.create(mConfiguration);
    User loginUser = s.getUser();

    assertNotNull(loginUser);
    assertEquals("alluxio-user", loginUser.getName());
  }

  /**
   * Tests whether we can get login user with conf in CUSTOM mode, when user name is set to an
   * empty string in the application configuration. In this case, login should return the OS user
   * instead of empty string.
   */
  @Test
  public void getCustomLoginUserWhenNotProvidedByApp() throws Exception {
    mConfiguration.set(PropertyKey.SECURITY_AUTHENTICATION_TYPE, AuthType.CUSTOM);
    mConfiguration.unset(PropertyKey.SECURITY_LOGIN_USERNAME);

    UserState s = UserState.Factory.create(mConfiguration);
    User loginUser = s.getUser();

    assertNotNull(loginUser);
    assertEquals(System.getProperty("user.name"), loginUser.getName());
  }

  /**
   * Verifies that CUSTOM auth runs the CUSTOM JAAS chain -- specifically, that
   * {@link alluxio.security.login.K8sTokenLoginModule} executes and attaches the
   * projected ServiceAccount token to the Subject's private credentials. Without
   * this, SaslClientHandlerPlain sends the "noPassword" sentinel and the master
   * rejects every worker/client handshake under CUSTOM with
   * "[invalid bearer token, unknown]".
   *
   * <p>Token path is written to {@link Configuration#global()} (not the local
   * {@link #mConfiguration}) because JAAS instantiates K8sTokenLoginModule via
   * its no-arg constructor, which snapshots {@code Configuration.global()} at
   * module construction time -- matching how the property actually lands in
   * prod (JVM {@code -D} system properties are picked up by
   * {@code Configuration.reloadProperties()} into the global singleton).
   */
  @Test
  public void getCustomLoginUserLoadsK8sToken() throws Exception {
    File tokenFile = mFolder.newFile("k8s-token");
    String token = "eyJhbGciOiJSUzI1NiJ9.PAYLOAD.signature";
    Files.write(tokenFile.toPath(), token.getBytes(StandardCharsets.UTF_8));

    mConfiguration.set(PropertyKey.SECURITY_AUTHENTICATION_TYPE, AuthType.CUSTOM);
    Configuration.set(PropertyKey.SECURITY_AUTHENTICATION_K8S_CLIENT_TOKEN_PATH,
        tokenFile.getAbsolutePath());
    try {
      UserState s = UserState.Factory.create(mConfiguration);
      User loginUser = s.getUser();
      assertNotNull(loginUser);

      Subject subject = s.getSubject();
      Set<String> credentials = subject.getPrivateCredentials(String.class);
      assertEquals("CUSTOM login chain should attach the K8s projected token as a "
          + "String credential on the Subject", 1, credentials.size());
      assertEquals(token, credentials.iterator().next());
    } finally {
      Configuration.unset(PropertyKey.SECURITY_AUTHENTICATION_K8S_CLIENT_TOKEN_PATH);
    }
  }

  /**
   * Regression test for the production shape: CUSTOM auth with BOTH a
   * configured login username AND a K8s token path set. AppLoginModule is
   * SUFFICIENT, so once it succeeds (any non-empty username does) JAAS
   * short-circuits and skips every subsequent module's login(). If
   * K8sTokenLoginModule is appended AFTER AppLoginModule it never runs, the
   * token never lands on the Subject, and SaslClientHandlerPlain sends
   * "noPassword" -- which the master-side K8sTokenAuthenticationProvider
   * forwards to kube-apiserver's TokenReview, which rejects it as
   * "[invalid bearer token, unknown]". Guards the CUSTOM chain order so K8S
   * runs before APP and the token is committed alongside the user principal.
   */
  @Test
  public void getCustomLoginUserLoadsK8sTokenEvenWhenUsernameProvided() throws Exception {
    File tokenFile = mFolder.newFile("k8s-token");
    String token = "eyJhbGciOiJSUzI1NiJ9.PAYLOAD.signature";
    Files.write(tokenFile.toPath(), token.getBytes(StandardCharsets.UTF_8));

    mConfiguration.set(PropertyKey.SECURITY_AUTHENTICATION_TYPE, AuthType.CUSTOM);
    mConfiguration.set(PropertyKey.SECURITY_LOGIN_USERNAME, "alluxio");
    Configuration.set(PropertyKey.SECURITY_AUTHENTICATION_K8S_CLIENT_TOKEN_PATH,
        tokenFile.getAbsolutePath());
    try {
      UserState s = UserState.Factory.create(mConfiguration);
      User loginUser = s.getUser();
      assertNotNull(loginUser);
      assertEquals("alluxio", loginUser.getName());

      Subject subject = s.getSubject();
      Set<String> credentials = subject.getPrivateCredentials(String.class);
      assertEquals("CUSTOM chain with username + token path must still attach the "
          + "K8s projected token (K8S_TOKEN_LOGIN must precede APP_LOGIN in the chain)",
          1, credentials.size());
      assertEquals(token, credentials.iterator().next());
    } finally {
      Configuration.unset(PropertyKey.SECURITY_AUTHENTICATION_K8S_CLIENT_TOKEN_PATH);
    }
  }

  // TODO(dong): getKerberosLoginUserTest()

  @Test
  public void securityEnabled() throws Exception {
    mConfiguration.set(PropertyKey.SECURITY_AUTHENTICATION_TYPE, AuthType.NOSASL);

    // without security, the user will be blank.
    User u = UserState.Factory.create(mConfiguration).getUser();
    Assert.assertEquals("", u.getName());
  }
}
