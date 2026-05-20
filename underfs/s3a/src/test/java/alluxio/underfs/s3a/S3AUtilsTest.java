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
import com.amazonaws.services.s3.model.CanonicalGrantee;
import com.amazonaws.services.s3.model.GroupGrantee;
import com.amazonaws.services.s3.model.Owner;
import com.amazonaws.services.s3.model.Permission;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import software.amazon.awssdk.services.s3.model.GetBucketAclResponse;
import software.amazon.awssdk.services.s3.model.Grant;
import software.amazon.awssdk.services.s3.model.Grantee;
import software.amazon.awssdk.services.s3.model.Type;

import java.util.Arrays;

/**
 * Tests for {@link S3AUtils} methods.
 */
public final class S3AUtilsTest {
  private static final String NAME = "foo";
  private static final String ID = "123456789012";
  private static final String OTHER_ID = "987654321098";

  private CanonicalGrantee mUserGrantee;
  private AccessControlList mAcl;

  @Before
  public void before() throws Exception {
    // Setup owner.
    mUserGrantee = new CanonicalGrantee(ID);
    mUserGrantee.setDisplayName(NAME);

    // Setup the acl.
    mAcl = new AccessControlList();
    mAcl.setOwner(new Owner(ID, NAME));
  }

  @Test
  public void translateUserReadPermission() {
    mAcl.grantPermission(mUserGrantee, Permission.Read);
    Assert.assertEquals((short) 0500, S3AUtils.translateBucketAcl(mAcl, ID));
    Assert.assertEquals((short) 0000, S3AUtils.translateBucketAcl(mAcl, OTHER_ID));
    mAcl.grantPermission(mUserGrantee, Permission.ReadAcp);
    Assert.assertEquals((short) 0500, S3AUtils.translateBucketAcl(mAcl, ID));
    Assert.assertEquals((short) 0000, S3AUtils.translateBucketAcl(mAcl, OTHER_ID));
  }

  @Test
  public void translateUserWritePermission() {
    mAcl.grantPermission(mUserGrantee, Permission.Write);
    Assert.assertEquals((short) 0200, S3AUtils.translateBucketAcl(mAcl, ID));
    mAcl.grantPermission(mUserGrantee, Permission.Read);
    Assert.assertEquals((short) 0700, S3AUtils.translateBucketAcl(mAcl, ID));
  }

  @Test
  public void translateUserFullPermission() {
    mAcl.grantPermission(mUserGrantee, Permission.FullControl);
    Assert.assertEquals((short) 0700, S3AUtils.translateBucketAcl(mAcl, ID));
    Assert.assertEquals((short) 0000, S3AUtils.translateBucketAcl(mAcl, OTHER_ID));
  }

  @Test
  public void translateEveryoneReadPermission() {
    GroupGrantee allUsersGrantee = GroupGrantee.AllUsers;
    mAcl.grantPermission(allUsersGrantee, Permission.Read);
    Assert.assertEquals((short) 0500, S3AUtils.translateBucketAcl(mAcl, ID));
    Assert.assertEquals((short) 0500, S3AUtils.translateBucketAcl(mAcl, OTHER_ID));
  }

  @Test
  public void translateEveryoneWritePermission() {
    GroupGrantee allUsersGrantee = GroupGrantee.AllUsers;
    mAcl.grantPermission(allUsersGrantee, Permission.Write);
    Assert.assertEquals((short) 0200, S3AUtils.translateBucketAcl(mAcl, ID));
    Assert.assertEquals((short) 0200, S3AUtils.translateBucketAcl(mAcl, OTHER_ID));
  }

  @Test
  public void translateEveryoneFullPermission() {
    GroupGrantee allUsersGrantee = GroupGrantee.AllUsers;
    mAcl.grantPermission(allUsersGrantee, Permission.FullControl);
    Assert.assertEquals((short) 0700, S3AUtils.translateBucketAcl(mAcl, ID));
    Assert.assertEquals((short) 0700, S3AUtils.translateBucketAcl(mAcl, OTHER_ID));
  }

  @Test
  public void translateAuthenticatedUserReadPermission() {
    GroupGrantee authenticatedUsersGrantee = GroupGrantee.AuthenticatedUsers;
    mAcl.grantPermission(authenticatedUsersGrantee, Permission.Read);
    Assert.assertEquals((short) 0500, S3AUtils.translateBucketAcl(mAcl, ID));
    Assert.assertEquals((short) 0500, S3AUtils.translateBucketAcl(mAcl, OTHER_ID));
  }

  @Test
  public void translateAuthenticatedUserWritePermission() {
    GroupGrantee authenticatedUsersGrantee = GroupGrantee.AuthenticatedUsers;
    mAcl.grantPermission(authenticatedUsersGrantee, Permission.Write);
    Assert.assertEquals((short) 0200, S3AUtils.translateBucketAcl(mAcl, ID));
    Assert.assertEquals((short) 0200, S3AUtils.translateBucketAcl(mAcl, OTHER_ID));
  }

  @Test
  public void translateAuthenticatedUserFullPermission() {
    GroupGrantee authenticatedUsersGrantee = GroupGrantee.AuthenticatedUsers;
    mAcl.grantPermission(authenticatedUsersGrantee, Permission.FullControl);
    Assert.assertEquals((short) 0700, S3AUtils.translateBucketAcl(mAcl, ID));
    Assert.assertEquals((short) 0700, S3AUtils.translateBucketAcl(mAcl, OTHER_ID));
  }

  @Test
  public void translatePermissionWithNullId() {
    // Emulate a corner case when returned grantee does not have ID from some S3 compatible UFS
    mUserGrantee.setIdentifier(null);
    mAcl.grantPermission(mUserGrantee, Permission.Read);
    Assert.assertEquals((short) 0000, S3AUtils.translateBucketAcl(mAcl, OTHER_ID));
  }

  // --- SDK v2 (GetBucketAclResponse) overload tests ---

  private static final String ALL_USERS_URI =
      "http://acs.amazonaws.com/groups/global/AllUsers";
  private static final String AUTH_USERS_URI =
      "http://acs.amazonaws.com/groups/global/AuthenticatedUsers";

  private static GetBucketAclResponse aclV2(Grant... grants) {
    return GetBucketAclResponse.builder().grants(Arrays.asList(grants)).build();
  }

  private static Grant userGrantV2(String id,
      software.amazon.awssdk.services.s3.model.Permission perm) {
    return Grant.builder()
        .grantee(Grantee.builder().type(Type.CANONICAL_USER).id(id).build())
        .permission(perm).build();
  }

  private static Grant groupGrantV2(String groupUri,
      software.amazon.awssdk.services.s3.model.Permission perm) {
    return Grant.builder()
        .grantee(Grantee.builder().type(Type.GROUP).uri(groupUri).build())
        .permission(perm).build();
  }

  @Test
  public void translateUserPermissionsV2() {
    Assert.assertEquals((short) 0500,
        S3AUtils.translateBucketAcl(
            aclV2(userGrantV2(ID, software.amazon.awssdk.services.s3.model.Permission.READ)), ID));
    Assert.assertEquals((short) 0000,
        S3AUtils.translateBucketAcl(
            aclV2(userGrantV2(ID, software.amazon.awssdk.services.s3.model.Permission.READ)),
            OTHER_ID));
    Assert.assertEquals((short) 0200,
        S3AUtils.translateBucketAcl(
            aclV2(userGrantV2(ID, software.amazon.awssdk.services.s3.model.Permission.WRITE)), ID));
    Assert.assertEquals((short) 0700,
        S3AUtils.translateBucketAcl(
            aclV2(userGrantV2(ID,
                software.amazon.awssdk.services.s3.model.Permission.FULL_CONTROL)),
            ID));
  }

  @Test
  public void translateGroupPermissionsV2() {
    Assert.assertEquals((short) 0500,
        S3AUtils.translateBucketAcl(
            aclV2(groupGrantV2(ALL_USERS_URI,
                software.amazon.awssdk.services.s3.model.Permission.READ)),
            OTHER_ID));
    Assert.assertEquals((short) 0200,
        S3AUtils.translateBucketAcl(
            aclV2(groupGrantV2(AUTH_USERS_URI,
                software.amazon.awssdk.services.s3.model.Permission.WRITE)),
            OTHER_ID));
    Assert.assertEquals((short) 0700,
        S3AUtils.translateBucketAcl(
            aclV2(groupGrantV2(ALL_USERS_URI,
                software.amazon.awssdk.services.s3.model.Permission.FULL_CONTROL)),
            OTHER_ID));
  }

  @Test
  public void translateNullIdGranteeV2() {
    // Mirror the v1 nullId corner case: a CanonicalUser grantee with a null id matches no userId.
    Grant nullIdGrant = Grant.builder()
        .grantee(Grantee.builder().type(Type.CANONICAL_USER).id(null).build())
        .permission(software.amazon.awssdk.services.s3.model.Permission.READ)
        .build();
    Assert.assertEquals((short) 0000,
        S3AUtils.translateBucketAcl(aclV2(nullIdGrant), OTHER_ID));
  }

  @Test
  public void translateReadAcpIgnoredV2() {
    // Permissions outside READ/WRITE/FULL_CONTROL contribute no posix bits.
    Assert.assertEquals((short) 0000,
        S3AUtils.translateBucketAcl(
            aclV2(userGrantV2(ID,
                software.amazon.awssdk.services.s3.model.Permission.READ_ACP)),
            ID));
  }
}
