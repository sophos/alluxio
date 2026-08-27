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

package alluxio.security.authentication.k8s;

import static org.junit.Assert.assertEquals;

import alluxio.conf.Configuration;
import alluxio.conf.InstancedConfiguration;
import alluxio.conf.PropertyKey;

import com.google.common.base.Ticker;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.security.sasl.AuthenticationException;

/**
 * Tests {@link K8sTokenAuthenticationProvider}.
 */
public final class K8sTokenAuthenticationProviderTest {
  @Rule
  public ExpectedException mThrown = ExpectedException.none();

  @Test
  public void authenticateSuccessUsesCache() throws Exception {
    InstancedConfiguration conf = createBaseConf();
    conf.set(PropertyKey.SECURITY_AUTHENTICATION_K8S_CACHE_TTL, "1sec");
    ManualTicker ticker = new ManualTicker();
    CountingTokenReviewer reviewer =
        new CountingTokenReviewer(successResponse("team", "trino-metabase-sa"));
    K8sTokenAuthenticationProvider provider =
        new K8sTokenAuthenticationProvider(conf, reviewer, ticker);

    provider.authenticate("trino-metabase", "token");
    provider.authenticate("trino-metabase", "token");
    assertEquals(1, reviewer.mCalls);

    ticker.advance(2, TimeUnit.SECONDS);
    provider.authenticate("trino-metabase", "token");
    assertEquals(2, reviewer.mCalls);
  }

  @Test
  public void authenticateFailsWhenAudienceDoesNotMatch() throws Exception {
    InstancedConfiguration conf = createBaseConf();
    K8sTokenAuthenticationProvider.TokenReviewResponse response =
        successResponse("team", "trino-metabase-sa");
    response.status.audiences = Collections.singletonList("other-audience");
    K8sTokenAuthenticationProvider provider =
        new K8sTokenAuthenticationProvider(conf, new CountingTokenReviewer(response),
            new ManualTicker());

    mThrown.expect(AuthenticationException.class);
    mThrown.expectMessage("audience mismatch");
    provider.authenticate("trino-metabase", "token");
  }

  @Test
  public void authenticateFailsWhenNamespaceDoesNotMatch() throws Exception {
    InstancedConfiguration conf = createBaseConf();
    K8sTokenAuthenticationProvider provider =
        new K8sTokenAuthenticationProvider(conf,
            new CountingTokenReviewer(successResponse("other", "trino-metabase-sa")),
            new ManualTicker());

    mThrown.expect(AuthenticationException.class);
    mThrown.expectMessage("namespace mismatch");
    provider.authenticate("trino-metabase", "token");
  }

  @Test
  public void authenticateFailsWhenClaimDoesNotMatchServiceAccount() throws Exception {
    InstancedConfiguration conf = createBaseConf();
    K8sTokenAuthenticationProvider provider =
        new K8sTokenAuthenticationProvider(conf,
            new CountingTokenReviewer(successResponse("team", "trino-ng-sa")),
            new ManualTicker());

    mThrown.expect(AuthenticationException.class);
    mThrown.expectMessage("does not match claimed user");
    provider.authenticate("trino-metabase", "token");
  }

  @Test
  public void authenticateFailsWhenTokenReviewRejectsToken() throws Exception {
    InstancedConfiguration conf = createBaseConf();
    K8sTokenAuthenticationProvider.TokenReviewResponse response =
        new K8sTokenAuthenticationProvider.TokenReviewResponse();
    response.status = new K8sTokenAuthenticationProvider.TokenReviewStatus();
    response.status.authenticated = false;
    response.status.error = "Credentials are expired";
    K8sTokenAuthenticationProvider provider =
        new K8sTokenAuthenticationProvider(conf, new CountingTokenReviewer(response),
            new ManualTicker());

    mThrown.expect(AuthenticationException.class);
    mThrown.expectMessage("Credentials are expired");
    provider.authenticate("trino-metabase", "token");
  }

  @Test
  public void constructorRequiresNamespace() {
    InstancedConfiguration conf = createBaseConf();
    conf.unset(PropertyKey.SECURITY_AUTHENTICATION_K8S_SERVICE_ACCOUNT_NAMESPACE);

    mThrown.expect(IllegalArgumentException.class);
    mThrown.expectMessage(
        PropertyKey.SECURITY_AUTHENTICATION_K8S_SERVICE_ACCOUNT_NAMESPACE.getName());
    new K8sTokenAuthenticationProvider(conf, new CountingTokenReviewer(
        successResponse("team", "trino-metabase-sa")), new ManualTicker());
  }

  @Test
  public void authenticateAcceptsInternalServiceAccountAsConfiguredUser() throws Exception {
    InstancedConfiguration conf = createBaseConf();
    conf.set(PropertyKey.SECURITY_AUTHENTICATION_K8S_INTERNAL_SERVICE_ACCOUNT_NAME,
        "alluxio-eu-west-1a-sa");
    conf.set(PropertyKey.SECURITY_AUTHENTICATION_K8S_INTERNAL_USER, "alluxio");
    K8sTokenAuthenticationProvider provider = new K8sTokenAuthenticationProvider(conf,
        new CountingTokenReviewer(successResponse("team", "alluxio-eu-west-1a-sa")),
        new ManualTicker());

    provider.authenticate("alluxio", "token");
  }

  @Test
  public void authenticateRejectsInternalServiceAccountWithWrongClaimedUser() throws Exception {
    InstancedConfiguration conf = createBaseConf();
    conf.set(PropertyKey.SECURITY_AUTHENTICATION_K8S_INTERNAL_SERVICE_ACCOUNT_NAME,
        "alluxio-eu-west-1a-sa");
    conf.set(PropertyKey.SECURITY_AUTHENTICATION_K8S_INTERNAL_USER, "alluxio");
    K8sTokenAuthenticationProvider provider = new K8sTokenAuthenticationProvider(conf,
        new CountingTokenReviewer(successResponse("team", "alluxio-eu-west-1a-sa")),
        new ManualTicker());

    mThrown.expect(AuthenticationException.class);
    mThrown.expectMessage("internal service account");
    provider.authenticate("trino-metabase", "token");
  }

  @Test
  public void authenticateStillMatchesTemplateWhenInternalServiceAccountConfigured()
      throws Exception {
    InstancedConfiguration conf = createBaseConf();
    conf.set(PropertyKey.SECURITY_AUTHENTICATION_K8S_INTERNAL_SERVICE_ACCOUNT_NAME,
        "alluxio-eu-west-1a-sa");
    conf.set(PropertyKey.SECURITY_AUTHENTICATION_K8S_INTERNAL_USER, "alluxio");
    K8sTokenAuthenticationProvider provider = new K8sTokenAuthenticationProvider(conf,
        new CountingTokenReviewer(successResponse("team", "trino-metabase-sa")),
        new ManualTicker());

    provider.authenticate("trino-metabase", "token");
  }


  private static InstancedConfiguration createBaseConf() {
    InstancedConfiguration conf = Configuration.copyGlobal();
    conf.set(PropertyKey.SECURITY_AUTHENTICATION_K8S_AUDIENCE, "alluxio-master");
    conf.set(PropertyKey.SECURITY_AUTHENTICATION_K8S_SERVICE_ACCOUNT_NAMESPACE, "team");
    conf.set(PropertyKey.SECURITY_AUTHENTICATION_K8S_SERVICE_ACCOUNT_NAME_TEMPLATE,
        "trino-{user}-sa");
    conf.set(PropertyKey.SECURITY_AUTHENTICATION_K8S_CACHE_TTL, "30sec");
    return conf;
  }

  private static K8sTokenAuthenticationProvider.TokenReviewResponse successResponse(
      String namespace, String serviceAccountName) {
    K8sTokenAuthenticationProvider.TokenReviewResponse response =
        new K8sTokenAuthenticationProvider.TokenReviewResponse();
    response.status = new K8sTokenAuthenticationProvider.TokenReviewStatus();
    response.status.authenticated = true;
    response.status.audiences = Collections.singletonList("alluxio-master");
    response.status.user = new K8sTokenAuthenticationProvider.TokenReviewUser();
    response.status.user.username =
        "system:serviceaccount:" + namespace + ":" + serviceAccountName;
    return response;
  }

  private static final class CountingTokenReviewer
      implements K8sTokenAuthenticationProvider.TokenReviewer {
    private final K8sTokenAuthenticationProvider.TokenReviewResponse mResponse;
    private int mCalls;

    private CountingTokenReviewer(K8sTokenAuthenticationProvider.TokenReviewResponse response) {
      mResponse = response;
    }

    @Override
    public K8sTokenAuthenticationProvider.TokenReviewResponse review(String token, String audience)
        throws IOException {
      mCalls++;
      return mResponse;
    }
  }

  private static final class ManualTicker extends Ticker {
    private final AtomicLong mNanos = new AtomicLong();

    @Override
    public long read() {
      return mNanos.get();
    }

    private void advance(long duration, TimeUnit unit) {
      mNanos.addAndGet(unit.toNanos(duration));
    }
  }
}
