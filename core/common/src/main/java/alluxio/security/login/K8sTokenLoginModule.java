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

import alluxio.conf.AlluxioConfiguration;
import alluxio.conf.Configuration;
import alluxio.conf.PropertyKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import javax.annotation.concurrent.NotThreadSafe;
import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;

/**
 * A login module that reads a Kubernetes projected ServiceAccount token from a
 * configured path on disk and attaches it to the {@link Subject}'s private
 * credentials. Downstream SASL handlers (see {@code
 * alluxio.security.authentication.plain.SaslClientHandlerPlain}) pull that
 * credential and send it as the SASL password, which lets the master-side
 * custom authentication provider (see {@code
 * alluxio.security.authentication.k8s.K8sTokenAuthenticationProvider}) validate
 * the caller via the Kubernetes TokenReview API.
 *
 * <p>If {@link PropertyKey#SECURITY_AUTHENTICATION_K8S_CLIENT_TOKEN_PATH} is
 * empty (the default), this module opts out at {@link #login()} time so a
 * binary containing it can still authenticate under {@code SIMPLE} auth.
 */
@NotThreadSafe
public final class K8sTokenLoginModule implements LoginModule {
  private static final Logger LOG = LoggerFactory.getLogger(K8sTokenLoginModule.class);

  private final AlluxioConfiguration mConf;
  private Subject mSubject;
  private String mToken;

  /**
   * Constructs a new {@link K8sTokenLoginModule}. Used by JAAS via reflection.
   */
  public K8sTokenLoginModule() {
    this(Configuration.global());
  }

  /**
   * Package-visible constructor for tests that need to inject configuration
   * without touching the global singleton.
   *
   * @param conf Alluxio configuration
   */
  K8sTokenLoginModule(AlluxioConfiguration conf) {
    mConf = conf;
  }

  @Override
  public void initialize(Subject subject, CallbackHandler callbackHandler,
      Map<String, ?> sharedState, Map<String, ?> options) {
    mSubject = subject;
  }

  /**
   * Reads the projected ServiceAccount token from the configured path into
   * module state. No {@link Subject} mutation happens here; see {@link
   * #commit()}.
   *
   * @return true if a non-empty token was loaded; false if the configured path
   *         is unset so the module opts out cleanly
   * @throws LoginException if the configured path is set but the file cannot
   *         be read or is empty on disk
   */
  @Override
  public boolean login() throws LoginException {
    String path = mConf.getString(PropertyKey.SECURITY_AUTHENTICATION_K8S_CLIENT_TOKEN_PATH);
    if (path == null || path.isEmpty()) {
      return false;
    }
    String token;
    try {
      token = readTokenFromDisk(path);
    } catch (IOException e) {
      throw new LoginException(
          "Failed to read Kubernetes token at " + path + ": " + e.getMessage());
    }
    mToken = token;
    LOG.debug("Loaded Kubernetes token from {} ({} chars)", path, token.length());
    return true;
  }

  /**
   * Reads and trims a projected ServiceAccount token from disk.
   *
   * <p>The value the JAAS login captures here is only useful at the moment
   * login runs: kubelet atomically swaps the projected token well before its
   * TTL (~80% of {@code expirationSeconds}), but nothing inside Alluxio
   * re-runs JAAS login after the pod starts. A long-lived client (Trino
   * coordinator, Alluxio worker) will therefore keep a stale token in its
   * {@link Subject}'s private credentials past the original TTL.
   *
   * <p>To fix that without changing JAAS semantics, the same file-read is
   * exposed as a static helper so the SASL client can re-read on every
   * channel authentication — see {@code
   * alluxio.security.authentication.plain.SaslClientHandlerPlain} for the
   * call site. Keeping the helper here means the login-time and
   * refresh-at-auth-time paths share one definition of "what a valid
   * on-disk token looks like" (non-empty, trimmed of trailing whitespace).
   *
   * @param path filesystem path to the projected token (must be non-null,
   *             non-empty; callers are expected to short-circuit if unset)
   * @return the trimmed token contents, guaranteed non-empty
   * @throws IOException if the file cannot be read or is empty after trim
   */
  public static String readTokenFromDisk(String path) throws IOException {
    String token = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8).trim();
    if (token.isEmpty()) {
      throw new IOException("Kubernetes token at " + path + " is empty");
    }
    return token;
  }

  @Override
  public boolean commit() throws LoginException {
    if (mToken == null) {
      return false;
    }
    if (mSubject.isReadOnly()) {
      throw new LoginException("Cannot commit token: Subject is read-only");
    }
    mSubject.getPrivateCredentials().add(mToken);
    return true;
  }

  @Override
  public boolean abort() throws LoginException {
    mToken = null;
    return true;
  }

  @Override
  public boolean logout() throws LoginException {
    if (mToken == null) {
      return true;
    }
    if (mSubject.isReadOnly()) {
      throw new LoginException("logout Failed: Subject is Readonly.");
    }
    mSubject.getPrivateCredentials().remove(mToken);
    mToken = null;
    return true;
  }
}
