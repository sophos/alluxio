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

import software.amazon.awssdk.services.s3.model.GetBucketAclResponse;
import software.amazon.awssdk.services.s3.model.Grant;
import software.amazon.awssdk.services.s3.model.Grantee;
import software.amazon.awssdk.services.s3.model.Permission;
import software.amazon.awssdk.services.s3.model.Type;

/**
 * Util functions for S3A under file system.
 */
public final class S3AUtils {
  // S3 ACL "AllUsers" and "AuthenticatedUsers" group URIs as documented in
  // https://docs.aws.amazon.com/AmazonS3/latest/userguide/acl-overview.html#specifying-grantee.
  private static final String ALL_USERS_GROUP_URI =
      "http://acs.amazonaws.com/groups/global/AllUsers";
  private static final String AUTHENTICATED_USERS_GROUP_URI =
      "http://acs.amazonaws.com/groups/global/AuthenticatedUsers";

  /**
   * Translates an SDK v2 {@link GetBucketAclResponse} to an Alluxio owner mode.
   * A grant for {@code userId} (or for the AllUsers/AuthenticatedUsers group) contributes r+x,
   * w, or rwx depending on whether the permission is {@code READ}, {@code WRITE}, or
   * {@code FULL_CONTROL} respectively. {@code READ_ACP} / {@code WRITE_ACP} grants are not
   * folded into the posix mode.
   *
   * @param acl the SDK v2 GetBucketAcl response
   * @param userId the S3 canonical user id of the Alluxio owner
   * @return the translated posix mode in short format
   */
  public static short translateBucketAcl(GetBucketAclResponse acl, String userId) {
    short mode = (short) 0;
    for (Grant grant : acl.grants()) {
      Permission perm = grant.permission();
      Grantee grantee = grant.grantee();
      if (!isUserIdInGrantee(grantee, userId)) {
        continue;
      }
      if (perm == null) {
        continue;
      }
      switch (perm) {
        case READ:
          mode |= (short) 0500;
          break;
        case WRITE:
          mode |= (short) 0200;
          break;
        case FULL_CONTROL:
          mode |= (short) 0700;
          break;
        default:
          // READ_ACP / WRITE_ACP / UNKNOWN_TO_SDK_VERSION — not relevant for owner posix mode.
          break;
      }
    }
    return mode;
  }

  private static boolean isUserIdInGrantee(Grantee grantee, String userId) {
    if (grantee == null) {
      return false;
    }
    if (grantee.type() == Type.CANONICAL_USER) {
      return grantee.id() != null && grantee.id().equals(userId);
    }
    if (grantee.type() == Type.GROUP) {
      return ALL_USERS_GROUP_URI.equals(grantee.uri())
          || AUTHENTICATED_USERS_GROUP_URI.equals(grantee.uri());
    }
    return false;
  }

  private S3AUtils() {} // prevent instantiation
}
