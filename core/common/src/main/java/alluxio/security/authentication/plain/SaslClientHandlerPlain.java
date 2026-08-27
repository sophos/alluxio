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

import alluxio.conf.AlluxioConfiguration;
import alluxio.conf.PropertyKey;
import alluxio.exception.status.UnauthenticatedException;
import alluxio.grpc.ChannelAuthenticationScheme;
import alluxio.security.User;
import alluxio.security.authentication.AbstractSaslClientHandler;
import alluxio.security.authentication.AuthenticationUtils;
import alluxio.security.authentication.SaslClientHandler;
import alluxio.security.login.K8sTokenLoginModule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Set;
import javax.security.auth.Subject;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslException;

/**
 * {@link SaslClientHandler} implementation for Plain/Custom schemes.
 */
public class SaslClientHandlerPlain extends AbstractSaslClientHandler {
  private static final Logger LOG = LoggerFactory.getLogger(SaslClientHandlerPlain.class);

  /**
   * Creates {@link SaslClientHandler} instance for Plain/Custom.
   *
   * @param subject client subject
   * @param conf Alluxio configuration
   * @throws UnauthenticatedException
   */
  public SaslClientHandlerPlain(Subject subject, AlluxioConfiguration conf)
      throws UnauthenticatedException {
    super(ChannelAuthenticationScheme.SIMPLE);
    if (subject == null) {
      throw new UnauthenticatedException("client subject not provided");
    }
    String connectionUser = null;
    String password = "noPassword";

    Set<User> users = subject.getPrincipals(User.class);
    if (users != null && !users.isEmpty()) {
      connectionUser = users.iterator().next().getName();
    }

    // If a K8s projected-token path is configured, fresh-read the token from
    // disk on every SASL bind instead of pulling the (possibly stale) copy
    // that K8sTokenLoginModule snapshotted at JAAS login time.
    //
    // Rationale: kubelet atomically swaps projected SA tokens at ~80% of
    // their TTL, but JAAS login only runs once per process. A Trino pod
    // alive past the token TTL (1h by default) holds a stale credential in
    // its Subject, and every later channel re-auth fails with
    // "UNAUTHENTICATED: service account token has expired" (seen live on
    // 2026-04-23 after the Alluxio masters rolled for a -Xmx change —
    // Trino pods were 3h21m old and their cached gRPC channels had to
    // re-authenticate against the fresh masters with tokens that the TTL
    // had long since invalidated).
    //
    // Channel auth is not a hot path (handler built per channel open /
    // reconnect, not per RPC), so the extra file read is negligible, and
    // the on-disk token is the authoritative source. We deliberately do
    // NOT mutate the Subject's private credentials — the Subject is
    // shared, possibly read-only, and nothing downstream in this code path
    // consumes it once we pick up the fresh token here.
    String tokenPath = conf.getString(PropertyKey.SECURITY_AUTHENTICATION_K8S_CLIENT_TOKEN_PATH);
    if (tokenPath != null && !tokenPath.isEmpty()) {
      try {
        password = K8sTokenLoginModule.readTokenFromDisk(tokenPath);
      } catch (IOException e) {
        throw new UnauthenticatedException(
            "Failed to re-read Kubernetes token at " + tokenPath + ": " + e.getMessage(), e);
      }
    } else {
      Set<String> credentials = subject.getPrivateCredentials(String.class);
      if (credentials != null && !credentials.isEmpty()) {
        password = credentials.iterator().next();
      }
    }

    // Determine the impersonation user
    String impersonationUser = AuthenticationUtils.getImpersonationUser(subject, conf);

    mSaslClient = createSaslClient(connectionUser, password, impersonationUser);
  }

  /**
   * Creates {@link SaslClientHandler} instance for Plain/Custom.
   *
   * @param username user name
   * @param password password
   * @param impersonationUser impersonation user
   * @throws UnauthenticatedException
   */
  public SaslClientHandlerPlain(String username, String password, String impersonationUser)
      throws UnauthenticatedException {
    super(ChannelAuthenticationScheme.SIMPLE);
    mSaslClient = createSaslClient(username, password, impersonationUser);
  }

  private SaslClient createSaslClient(String username, String password, String impersonationUser)
      throws UnauthenticatedException {
    try {
      return Sasl.createSaslClient(new String[] {PlainSaslServerProvider.MECHANISM},
          impersonationUser, null, null, new HashMap<String, String>(),
          new PlainSaslClientCallbackHandler(username, password));
    } catch (SaslException e) {
      throw new UnauthenticatedException(e.getMessage(), e);
    }
  }
}
