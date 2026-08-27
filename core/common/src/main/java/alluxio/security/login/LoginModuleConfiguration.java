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

import alluxio.security.User;
import alluxio.security.authentication.AuthType;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.AppConfigurationEntry.LoginModuleControlFlag;
import javax.security.auth.login.Configuration;

/**
 * A JAAS configuration that defines the login modules, by which JAAS uses to login.
 *
 * In implementation, we define several modes (Simple, Kerberos, ...) by constructing different
 * arrays of AppConfigurationEntry, and select the proper array based on the configured mode.
 *
 * Then JAAS login framework use the selected array of AppConfigurationEntry to determine the login
 * modules to be used.
 */
@ThreadSafe
public final class LoginModuleConfiguration extends Configuration {

  private static final Map<String, String> EMPTY_JAAS_OPTIONS = new HashMap<>();

  /** Login module that allows a user name provided by OS. */
  private static final AppConfigurationEntry OS_SPECIFIC_LOGIN =
      new AppConfigurationEntry(LoginModuleConfigurationUtils.OS_LOGIN_MODULE_NAME,
          LoginModuleControlFlag.REQUIRED, EMPTY_JAAS_OPTIONS);

  /** Login module that allows a user name provided by application to be specified. */
  private static final AppConfigurationEntry APP_LOGIN = new AppConfigurationEntry(
      AppLoginModule.class.getName(), LoginModuleControlFlag.SUFFICIENT, EMPTY_JAAS_OPTIONS);

  /** Login module that allows a user name provided by an Alluxio specific login module. */
  private static final AppConfigurationEntry ALLUXIO_LOGIN = new AppConfigurationEntry(
      AlluxioLoginModule.class.getName(), LoginModuleControlFlag.REQUIRED, EMPTY_JAAS_OPTIONS);

  /**
   * Optional login module that loads a Kubernetes projected ServiceAccount token from disk and
   * attaches it to the Subject's private credentials. The downstream SASL handler forwards that
   * credential as the SASL password, which is what the master-side custom authentication provider
   * validates via the Kubernetes TokenReview API. The module opts out silently when the configured
   * token path is empty, so a client using this configuration can still connect to a SIMPLE-auth
   * master.
   */
  private static final AppConfigurationEntry K8S_TOKEN_LOGIN = new AppConfigurationEntry(
      K8sTokenLoginModule.class.getName(), LoginModuleControlFlag.OPTIONAL, EMPTY_JAAS_OPTIONS);

  /**
   * In the {@link AuthType#SIMPLE} mode, JAAS first tries to retrieve the user name set by the
   * application with {@link AppLoginModule}. Upon failure, it uses the OS specific login module to
   * fetch the OS user, and then uses {@link AlluxioLoginModule} to convert it to an Alluxio user
   * represented by {@link User}.
   */
  private static final AppConfigurationEntry[] SIMPLE =
      new AppConfigurationEntry[] {APP_LOGIN, OS_SPECIFIC_LOGIN, ALLUXIO_LOGIN};

  /**
   * In the {@link AuthType#CUSTOM} mode, the module chain is {@link #SIMPLE} prefixed with
   * {@link K8sTokenLoginModule}. The K8s token module is OPTIONAL so a client without a
   * configured token path behaves identically to SIMPLE; when the path is set, the loaded token
   * rides along as a private credential on the Subject.
   *
   * <p>K8S must come FIRST, not last. {@link #APP_LOGIN} is {@code SUFFICIENT}: per JAAS, once a
   * SUFFICIENT module's {@code login()} succeeds the LoginContext short-circuits and skips every
   * subsequent module's {@code login()}. Alluxio always constructs the LoginContext with an
   * application-provided username via {@code AppCallbackHandler}, so {@code APP_LOGIN} invariably
   * succeeds -- which means any module appended after it never runs, its token never lands on the
   * Subject, and {@code SaslClientHandlerPlain} falls back to the {@code "noPassword"} placeholder.
   * The master then POSTs {@code spec.token="noPassword"} to kube-apiserver's TokenReview, which
   * rejects it as {@code "[invalid bearer token, unknown]"}. Putting K8S at index 0 lets it run
   * unconditionally (its OPTIONAL flag means its return value doesn't steer the chain), so by the
   * time APP_LOGIN short-circuits the token is already loaded in module state and gets committed
   * onto the Subject alongside APP_LOGIN's user principal.
   */
  private static final AppConfigurationEntry[] CUSTOM =
      new AppConfigurationEntry[] {K8S_TOKEN_LOGIN, APP_LOGIN, OS_SPECIFIC_LOGIN, ALLUXIO_LOGIN};

  /**
   * Constructs a new {@link LoginModuleConfiguration}.
   */
  public LoginModuleConfiguration() {}

  @Override
  @Nullable
  public AppConfigurationEntry[] getAppConfigurationEntry(String appName) {
    if (appName.equalsIgnoreCase(AuthType.SIMPLE.name())) {
      return SIMPLE;
    } else if (appName.equalsIgnoreCase(AuthType.CUSTOM.name())) {
      return CUSTOM;
    } else if (appName.equalsIgnoreCase(AuthType.KERBEROS.name())) {
      throw new UnsupportedOperationException("Kerberos is not supported currently.");
    }
    return null;
  }
}
