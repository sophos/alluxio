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

import alluxio.conf.AlluxioConfiguration;
import alluxio.conf.Configuration;
import alluxio.conf.PropertyKey;
import alluxio.security.authentication.AuthenticationProvider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Ticker;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.security.sasl.AuthenticationException;

/**
 * Kubernetes TokenReview based authentication provider.
 */
public class K8sTokenAuthenticationProvider implements AuthenticationProvider {
  private static final Logger LOG = LoggerFactory.getLogger(K8sTokenAuthenticationProvider.class);
  private static final String SERVICE_ACCOUNT_USERNAME_PREFIX = "system:serviceaccount:";
  private static final String USER_PLACEHOLDER = "{user}";
  private static final String TRINO_USER_PREFIX = "trino-";

  private final String mAudience;
  private final String mNamespace;
  private final Pattern mServiceAccountNamePattern;
  private final TokenReviewer mTokenReviewer;
  private final Ticker mTicker;
  private final long mCacheTtlNanos;
  private final Map<String, Long> mSuccessCache = new ConcurrentHashMap<>();

  /**
   * Creates a provider using the global Alluxio configuration.
   */
  public K8sTokenAuthenticationProvider() {
    this(Configuration.global(), Ticker.systemTicker());
  }

  K8sTokenAuthenticationProvider(AlluxioConfiguration conf, Ticker ticker) {
    this(conf, createTokenReviewer(conf), ticker);
  }

  K8sTokenAuthenticationProvider(AlluxioConfiguration conf, TokenReviewer reviewer, Ticker ticker) {
    mAudience = requireNonEmpty(conf.getString(PropertyKey.SECURITY_AUTHENTICATION_K8S_AUDIENCE),
        PropertyKey.SECURITY_AUTHENTICATION_K8S_AUDIENCE);
    mNamespace = requireNonEmpty(
        conf.getString(PropertyKey.SECURITY_AUTHENTICATION_K8S_SERVICE_ACCOUNT_NAMESPACE),
        PropertyKey.SECURITY_AUTHENTICATION_K8S_SERVICE_ACCOUNT_NAMESPACE);
    mServiceAccountNamePattern = compileTemplate(requireNonEmpty(
        conf.getString(PropertyKey.SECURITY_AUTHENTICATION_K8S_SERVICE_ACCOUNT_NAME_TEMPLATE),
        PropertyKey.SECURITY_AUTHENTICATION_K8S_SERVICE_ACCOUNT_NAME_TEMPLATE));
    mCacheTtlNanos = TimeUnit.MILLISECONDS.toNanos(
        conf.getMs(PropertyKey.SECURITY_AUTHENTICATION_K8S_CACHE_TTL));
    mTokenReviewer = Preconditions.checkNotNull(reviewer, "reviewer");
    mTicker = Preconditions.checkNotNull(ticker, "ticker");
  }

  @Override
  public void authenticate(String user, String password) throws AuthenticationException {
    if (Strings.isNullOrEmpty(user)) {
      throw new AuthenticationException("Missing claimed user");
    }
    if (Strings.isNullOrEmpty(password)) {
      throw new AuthenticationException("Missing Kubernetes service account token");
    }

    String cacheKey = user + '\0' + password;
    long now = mTicker.read();
    Long expiresAt = mSuccessCache.get(cacheKey);
    if (expiresAt != null) {
      if (expiresAt > now) {
        return;
      }
      mSuccessCache.remove(cacheKey, expiresAt);
    }

    TokenReviewResponse response;
    try {
      response = mTokenReviewer.review(password, mAudience);
    } catch (IOException e) {
      AuthenticationException exception =
          new AuthenticationException("Kubernetes TokenReview failed: " + e.getMessage());
      exception.initCause(e);
      throw exception;
    }

    TokenReviewStatus status = response == null ? null : response.status;
    if (status == null) {
      throw new AuthenticationException("Kubernetes TokenReview returned no status");
    }
    if (!status.authenticated) {
      throw new AuthenticationException(Strings.isNullOrEmpty(status.error)
          ? "Kubernetes TokenReview rejected the token" : status.error);
    }
    if (status.audiences == null || !status.audiences.contains(mAudience)) {
      throw new AuthenticationException(String.format(
          "Kubernetes TokenReview audience mismatch. Expected %s but got %s",
          mAudience, status.audiences));
    }
    if (status.user == null || Strings.isNullOrEmpty(status.user.username)) {
      throw new AuthenticationException("Kubernetes TokenReview returned no service account user");
    }
    if (!matchesClaimedUser(status.user.username, user)) {
      throw new AuthenticationException(String.format(
          "Kubernetes service account %s does not match claimed user %s",
          status.user.username, user));
    }

    if (mCacheTtlNanos > 0) {
      mSuccessCache.put(cacheKey, now + mCacheTtlNanos);
    }
  }

  private boolean matchesClaimedUser(String reviewedUsername, String claimedUser)
      throws AuthenticationException {
    if (!reviewedUsername.startsWith(SERVICE_ACCOUNT_USERNAME_PREFIX)) {
      throw new AuthenticationException(
          "Unexpected Kubernetes user from TokenReview: " + reviewedUsername);
    }
    String remainder = reviewedUsername.substring(SERVICE_ACCOUNT_USERNAME_PREFIX.length());
    int separator = remainder.indexOf(':');
    if (separator < 0) {
      throw new AuthenticationException(
          "Unexpected Kubernetes service account user format: " + reviewedUsername);
    }
    String namespace = remainder.substring(0, separator);
    String serviceAccountName = remainder.substring(separator + 1);
    if (!mNamespace.equals(namespace)) {
      throw new AuthenticationException(String.format(
          "Kubernetes service account namespace mismatch. Expected %s but got %s",
          mNamespace, namespace));
    }

    Matcher matcher = mServiceAccountNamePattern.matcher(serviceAccountName);
    if (!matcher.matches()) {
      throw new AuthenticationException(String.format(
          "Kubernetes service account %s does not match template", serviceAccountName));
    }

    String templateUser = matcher.group(1);
    return claimedUser.equals(templateUser)
        || claimedUser.equals(TRINO_USER_PREFIX + templateUser);
  }

  private static Pattern compileTemplate(String template) {
    int placeholder = template.indexOf(USER_PLACEHOLDER);
    Preconditions.checkArgument(placeholder >= 0,
        "Service account template must contain %s", USER_PLACEHOLDER);
    Preconditions.checkArgument(template.indexOf(USER_PLACEHOLDER, placeholder + 1) < 0,
        "Service account template must contain exactly one %s", USER_PLACEHOLDER);
    String prefix = template.substring(0, placeholder);
    String suffix = template.substring(placeholder + USER_PLACEHOLDER.length());
    return Pattern.compile(Pattern.quote(prefix) + "(.+)" + Pattern.quote(suffix));
  }

  private static String requireNonEmpty(@Nullable String value, PropertyKey key) {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(value),
        "Property %s must be set", key.getName());
    return value;
  }

  private static TokenReviewer createTokenReviewer(AlluxioConfiguration conf) {
    return new HttpTokenReviewer(
        conf.getString(PropertyKey.SECURITY_AUTHENTICATION_K8S_API_ENDPOINT),
        conf.getString(PropertyKey.SECURITY_AUTHENTICATION_K8S_CA_PATH),
        conf.getString(PropertyKey.SECURITY_AUTHENTICATION_K8S_SERVICE_ACCOUNT_TOKEN_PATH),
        conf.getMs(PropertyKey.NETWORK_CONNECTION_AUTH_TIMEOUT));
  }

  interface TokenReviewer {
    TokenReviewResponse review(String token, String audience) throws IOException;
  }

  static final class HttpTokenReviewer implements TokenReviewer {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String mEndpoint;
    private final String mTokenPath;
    private final long mTimeoutMs;
    @Nullable
    private final SSLContext mSslContext;

    HttpTokenReviewer(String endpoint, String caPath, String tokenPath, long timeoutMs) {
      mEndpoint = Preconditions.checkNotNull(endpoint, "endpoint");
      mTokenPath = Preconditions.checkNotNull(tokenPath, "tokenPath");
      mTimeoutMs = timeoutMs;
      mSslContext = endpoint.startsWith("https://")
          ? createSslContext(caPath)
          : null;
    }

    @Override
    public TokenReviewResponse review(String token, String audience) throws IOException {
      String url = mEndpoint + "/apis/authentication.k8s.io/v1/tokenreviews";
      HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
      connection.setConnectTimeout((int) mTimeoutMs);
      connection.setReadTimeout((int) mTimeoutMs);
      connection.setDoOutput(true);
      connection.setRequestMethod("POST");
      connection.setRequestProperty("Accept", "application/json");
      connection.setRequestProperty("Content-Type", "application/json");
      connection.setRequestProperty("Authorization", "Bearer " + readToken(mTokenPath));
      if (mSslContext != null && connection instanceof HttpsURLConnection) {
        ((HttpsURLConnection) connection).setSSLSocketFactory(mSslContext.getSocketFactory());
      }

      byte[] requestBody = createRequestBody(token, audience);
      try (OutputStream outputStream = connection.getOutputStream()) {
        outputStream.write(requestBody);
      }

      int responseCode = connection.getResponseCode();
      String body;
      try (InputStream stream = responseCode >= 200 && responseCode < 300
          ? connection.getInputStream() : connection.getErrorStream()) {
        body = stream == null ? "" : IOUtils.toString(stream, StandardCharsets.UTF_8);
      }
      if (responseCode < 200 || responseCode >= 300) {
        throw new IOException(String.format(
            "TokenReview request failed with status %s: %s", responseCode, body));
      }
      return OBJECT_MAPPER.readValue(body, TokenReviewResponse.class);
    }

    private static byte[] createRequestBody(String token, String audience) throws IOException {
      ObjectNode root = OBJECT_MAPPER.createObjectNode();
      root.put("apiVersion", "authentication.k8s.io/v1");
      root.put("kind", "TokenReview");
      ObjectNode spec = root.putObject("spec");
      spec.put("token", token);
      spec.putArray("audiences").add(audience);
      return OBJECT_MAPPER.writeValueAsBytes(root);
    }

    private static String readToken(String tokenPath) throws IOException {
      return Files.readString(Paths.get(tokenPath), StandardCharsets.UTF_8).trim();
    }

    @Nullable
    private static SSLContext createSslContext(String caPath) {
      if (Strings.isNullOrEmpty(caPath)) {
        return null;
      }
      try (InputStream inputStream = Files.newInputStream(Paths.get(caPath))) {
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certificates =
            certificateFactory.generateCertificates(inputStream);
        Preconditions.checkArgument(!certificates.isEmpty(),
            "No certificates found at %s", caPath);

        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        int index = 0;
        for (Certificate certificate : certificates) {
          trustStore.setCertificateEntry("kubernetes-" + index, certificate);
          index++;
        }

        TrustManagerFactory trustManagerFactory =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
        return sslContext;
      } catch (Exception e) {
        LOG.error("Failed to initialize Kubernetes API TLS context from {}", caPath, e);
        throw new RuntimeException("Failed to initialize Kubernetes API TLS context", e);
      }
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static final class TokenReviewResponse {
    public TokenReviewStatus status;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static final class TokenReviewStatus {
    public boolean authenticated;
    @Nullable
    public String error;
    @Nullable
    public List<String> audiences = Collections.emptyList();
    @Nullable
    public TokenReviewUser user;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static final class TokenReviewUser {
    @Nullable
    public String username;
  }
}
