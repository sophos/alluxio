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

import org.junit.Assert;
import org.junit.Test;
import software.amazon.awssdk.services.s3.model.GetBucketAclResponse;
import software.amazon.awssdk.services.s3.model.Grant;
import software.amazon.awssdk.services.s3.model.Grantee;
import software.amazon.awssdk.services.s3.model.Permission;
import software.amazon.awssdk.services.s3.model.Type;

import java.util.Arrays;

/**
 * Tests for {@link S3AUtils} methods.
 */
public final class S3AUtilsTest {
  private static final String ID = "123456789012";
  private static final String OTHER_ID = "987654321098";

  private static final String ALL_USERS_URI =
      "http://acs.amazonaws.com/groups/global/AllUsers";
  private static final String AUTH_USERS_URI =
      "http://acs.amazonaws.com/groups/global/AuthenticatedUsers";

  private static GetBucketAclResponse acl(Grant... grants) {
    return GetBucketAclResponse.builder().grants(Arrays.asList(grants)).build();
  }

  private static Grant userGrant(String id, Permission perm) {
    return Grant.builder()
        .grantee(Grantee.builder().type(Type.CANONICAL_USER).id(id).build())
        .permission(perm).build();
  }

  private static Grant groupGrant(String groupUri, Permission perm) {
    return Grant.builder()
        .grantee(Grantee.builder().type(Type.GROUP).uri(groupUri).build())
        .permission(perm).build();
  }

  @Test
  public void translateUserPermissions() {
    Assert.assertEquals((short) 0500,
        S3AUtils.translateBucketAcl(acl(userGrant(ID, Permission.READ)), ID));
    Assert.assertEquals((short) 0000,
        S3AUtils.translateBucketAcl(acl(userGrant(ID, Permission.READ)), OTHER_ID));
    Assert.assertEquals((short) 0200,
        S3AUtils.translateBucketAcl(acl(userGrant(ID, Permission.WRITE)), ID));
    Assert.assertEquals((short) 0700,
        S3AUtils.translateBucketAcl(acl(userGrant(ID, Permission.FULL_CONTROL)), ID));
  }

  @Test
  public void translateGroupPermissions() {
    Assert.assertEquals((short) 0500,
        S3AUtils.translateBucketAcl(acl(groupGrant(ALL_USERS_URI, Permission.READ)), OTHER_ID));
    Assert.assertEquals((short) 0200,
        S3AUtils.translateBucketAcl(acl(groupGrant(AUTH_USERS_URI, Permission.WRITE)), OTHER_ID));
    Assert.assertEquals((short) 0700,
        S3AUtils.translateBucketAcl(
            acl(groupGrant(ALL_USERS_URI, Permission.FULL_CONTROL)), OTHER_ID));
  }

  @Test
  public void translateNullIdGrantee() {
    // A CanonicalUser grantee with a null id matches no userId.
    Grant nullIdGrant = Grant.builder()
        .grantee(Grantee.builder().type(Type.CANONICAL_USER).id(null).build())
        .permission(Permission.READ)
        .build();
    Assert.assertEquals((short) 0000,
        S3AUtils.translateBucketAcl(acl(nullIdGrant), OTHER_ID));
  }

  @Test
  public void translateReadAcpIgnored() {
    // Permissions outside READ/WRITE/FULL_CONTROL contribute no posix bits.
    Assert.assertEquals((short) 0000,
        S3AUtils.translateBucketAcl(acl(userGrant(ID, Permission.READ_ACP)), ID));
  }

  @Test
  public void translateCombinedReadAndWrite() {
    Assert.assertEquals((short) 0700,
        S3AUtils.translateBucketAcl(
            acl(userGrant(ID, Permission.READ), userGrant(ID, Permission.WRITE)),
            ID));
  }
}
