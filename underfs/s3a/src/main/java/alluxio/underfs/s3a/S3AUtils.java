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

import com.amazonaws.services.s3.model.AccessControlList;
import com.amazonaws.services.s3.model.Grant;
import com.amazonaws.services.s3.model.Grantee;
import com.amazonaws.services.s3.model.GroupGrantee;
import com.amazonaws.services.s3.model.Permission;
import software.amazon.awssdk.services.s3.model.GetBucketAclResponse;
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
   * Translates S3 bucket ACL to Alluxio owner mode.
   * v1 SDK overload — kept until {@code S3AUnderFileSystem#getPermissions} is rewritten on
   * the SDK v2 sync client in Phase 2 (CSA-21975), at which point this method is deleted.
   *
   * @param acl the acl of S3 bucket
   * @param userId the S3 user id of the Alluxio owner
   * @return the translated posix mode in short format
   */
  public static short translateBucketAcl(AccessControlList acl, String userId) {
    short mode = (short) 0;
    for (Grant grant : acl.getGrantsAsList()) {
      Permission perm = grant.getPermission();
      Grantee grantee = grant.getGrantee();
      if (perm.equals(Permission.Read)) {
        if (isUserIdInGrantee(grantee, userId)) {
          // If the bucket is readable by the user, add r and x to the owner mode.
          mode |= (short) 0500;
        }
      } else if (perm.equals(Permission.Write)) {
        if (isUserIdInGrantee(grantee, userId)) {
          // If the bucket is writable by the user, +w to the owner mode.
          mode |= (short) 0200;
        }
      } else if (perm.equals(Permission.FullControl)) {
        if (isUserIdInGrantee(grantee, userId)) {
          // If the user has full control to the bucket, +rwx to the owner mode.
          mode |= (short) 0700;
        }
      }
    }
    return mode;
  }

  /**
   * Translates an SDK v2 {@link GetBucketAclResponse} to an Alluxio owner mode.
   * The semantics match the v1 overload exactly: a grant for {@code userId} (or for the
   * AllUsers/AuthenticatedUsers group) contributes r+x, w, or rwx depending on whether the
   * permission is {@code READ}, {@code WRITE}, or {@code FULL_CONTROL} respectively.
   *
   * @param acl the SDK v2 GetBucketAcl response
   * @param userId the S3 canonical user id of the Alluxio owner
   * @return the translated posix mode in short format
   */
  public static short translateBucketAcl(GetBucketAclResponse acl, String userId) {
    short mode = (short) 0;
    for (software.amazon.awssdk.services.s3.model.Grant grant : acl.grants()) {
      software.amazon.awssdk.services.s3.model.Permission perm = grant.permission();
      software.amazon.awssdk.services.s3.model.Grantee grantee = grant.grantee();
      if (!isUserIdInGranteeV2(grantee, userId)) {
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
    return grantee.getIdentifier() != null && grantee.getIdentifier().equals(userId)
        || grantee.equals(GroupGrantee.AllUsers)
        || grantee.equals(GroupGrantee.AuthenticatedUsers);
  }

  private static boolean isUserIdInGranteeV2(
      software.amazon.awssdk.services.s3.model.Grantee grantee, String userId) {
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
