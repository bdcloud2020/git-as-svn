/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.auth;

import org.eclipse.collections.api.block.function.primitive.BooleanFunction;
import org.eclipse.jetty.util.TopologicalSort;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import svnserver.StringHelper;
import svnserver.UserType;
import svnserver.repository.RepositoryMapping;
import svnserver.repository.VcsAccess;

import java.util.*;

/**
 * This ACL reuses SVN's authz syntax as much as possible: http://svnbook.red-bean.com/nightly/en/svn.serverconfig.pathbasedauthz.html
 *
 * @author Marat Radchenko <marat@slonopotamus.org>
 */
public final class ACL implements VcsAccess {
  @NotNull
  static final String EveryoneMarker = "*";
  @NotNull
  static final String AnonymousMarker = "$anonymous";
  @NotNull
  static final String AuthenticatedMarker = "$authenticated";
  @NotNull
  private static final String AuthenticatedPrefix = AuthenticatedMarker + ":";
  @NotNull
  private static final String GroupPrefix = "@";
  /**
   * Special marker that means "ACL entry is not bound to specific branch".
   */
  @NotNull
  private static final String NoBranch = "";
  private static final char BranchPathSeparator = ':';

  @NotNull
  private final Map<String, Set<String>> user2groups = new HashMap<>();
  @NotNull
  private final Set<String> anonymousGroups = new HashSet<>();
  @NotNull
  private final Map<UserType, Set<String>> authenticatedGroups = new EnumMap<>(UserType.class);

  @NotNull
  private final NavigableMap<String, Map<String, Map<ACLEntry, AccessMode>>> path2branch2acl = new TreeMap<>();

  public ACL(@NotNull Map<String, String[]> group2users, @NotNull Map<String, Map<String, String>> branchPath2Member2AccessMode) {
    this("", group2users, branchPath2Member2AccessMode);
  }

  public ACL(@NotNull String contextName, @NotNull Map<String, String[]> group2users, @NotNull Map<String, Map<String, String>> branchPath2Member2AccessMode) {
    final Map<String, Set<String>> group2Users = expandGroups(contextName, group2users);

    for (Map.Entry<String, Set<String>> group : group2Users.entrySet()) {
      for (String member : group.getValue()) {
        final String groupName = group.getKey();
        if (member.equals(AnonymousMarker))
          anonymousGroups.add(groupName);
        else if (member.equals(AuthenticatedMarker)) {
          for (UserType userType : UserType.values())
            authenticatedGroups.computeIfAbsent(userType, type -> new HashSet<>()).add(groupName);
        } else if (member.startsWith(AuthenticatedPrefix)) {
          final UserType userType = UserType.valueOf(member.substring(AuthenticatedPrefix.length()));
          authenticatedGroups.computeIfAbsent(userType, type -> new HashSet<>()).add(groupName);
        } else
          user2groups.computeIfAbsent(member, s -> new HashSet<>()).add(groupName);
      }
    }

    for (Map.Entry<String, Map<String, String>> branchPathEntry : branchPath2Member2AccessMode.entrySet()) {
      final String branchPath = branchPathEntry.getKey();

      final int branchSep = branchPath.indexOf(BranchPathSeparator);

      final String branch;
      final String path;

      if (branchSep < 0) {
        branch = NoBranch;
        path = branchPath;
      } else if (branchSep == 0) {
        throw new IllegalArgumentException(String.format("[%s] Branch name in ACL entry must not be empty: %s", contextName, branchPath));
      } else {
        branch = branchPath.substring(0, branchSep);
        path = branchSep < branchPath.length() - 1
            ? branchPath.substring(branchSep + 1)
            // Empty path is invalid and will be properly error-reported by code below
            : "";
      }

      if (!path.startsWith("/"))
        throw new IllegalArgumentException(String.format("[%s] Path in ACL entry must start with slash (/): %s", contextName, branchPath));

      if (path.endsWith("/") && path.length() > 1)
        throw new IllegalArgumentException(String.format("[%s] Path in ACL entry must not end with slash (/): %s", contextName, branchPath));

      if (branchPathEntry.getValue().isEmpty())
        throw new IllegalArgumentException(String.format("[%s] ACL entry is empty: %s", contextName, branchPath));

      for (Map.Entry<String, String> aclEntry : branchPathEntry.getValue().entrySet()) {
        final AccessMode accessMode = AccessMode.fromString(aclEntry.getValue());
        addAclEntry(contextName, branch, path, aclEntry.getKey(), accessMode, group2users.keySet());
      }
    }
  }

  @NotNull
  private static Map<String, Set<String>> expandGroups(@NotNull String contextName, @NotNull Map<String, String[]> groups) {
    final String[] sorted = groups.keySet().toArray(new String[0]);

    final TopologicalSort<String> topoSort = new TopologicalSort<>();

    for (Map.Entry<String, String[]> group : groups.entrySet())
      for (String member : group.getValue())
        if (member.startsWith(GroupPrefix))
          topoSort.addDependency(group.getKey(), member.substring(GroupPrefix.length()));

    // This will throw exception in case of cycles, this is what we want
    topoSort.sort(sorted);

    final Map<String, Set<String>> group2Users = new HashMap<>();
    for (String group : sorted) {
      for (String member : groups.get(group)) {
        final Set<String> expandedMembers = group2Users.computeIfAbsent(group, s -> new HashSet<>());
        if (member.startsWith(GroupPrefix)) {
          final String subgroup = member.substring(GroupPrefix.length());
          final Set<String> subgroupMembers = group2Users.get(subgroup);
          if (subgroupMembers == null)
            throw new IllegalArgumentException(String.format("[%s] Group %s references nonexistent group %s", contextName, group, subgroup));

          expandedMembers.addAll(subgroupMembers);
        } else
          expandedMembers.add(member);
      }
    }

    return group2Users;
  }

  private void addAclEntry(@NotNull String contextName, @NotNull String branch, @NotNull String path, @NotNull String entryString, @NotNull AccessMode accessMode, @NotNull Set<String> allGroups) {
    final ACLEntry entry;
    if (entryString.equals(EveryoneMarker))
      entry = EveryoneACLEntry.Instance;
    else if (entryString.equals(AnonymousMarker))
      entry = AnonymousACLEntry.Instance;
    else if (entryString.equals(AuthenticatedMarker))
      entry = AuthenticatedACLEntry.Instance;
    else if (entryString.startsWith(AuthenticatedPrefix)) {
      final UserType userType = UserType.valueOf(entryString.substring(AuthenticatedPrefix.length()));
      entry = new UserTypeEntry(userType);
    } else if (entryString.startsWith(GroupPrefix)) {
      final String group = entryString.substring(GroupPrefix.length());

      if (!allGroups.contains(group))
        throw new IllegalArgumentException(String.format("[%s] ACL entry %s uses unknown group %s: ", contextName, path, group));

      entry = new GroupACLEntry(group);
    } else
      entry = new UserACLEntry(entryString);

    if (path2branch2acl.computeIfAbsent(StringHelper.normalizeDir(path), s -> new HashMap<>()).computeIfAbsent(branch, s -> new HashMap<>()).put(entry, accessMode) != null)
      throw new IllegalArgumentException(String.format("[%s] Duplicate ACL entry %s: %s", contextName, path, entryString));
  }

  @Override
  public boolean canRead(@NotNull User user, @NotNull String branch, @NotNull String path) {
    return doCheck(user, branch, path, AccessMode::allowsRead);
  }

  @Override
  public boolean canWrite(@NotNull User user, @NotNull String branch, @NotNull String path) {
    return doCheck(user, branch, path, AccessMode::allowsWrite);
  }

  private boolean doCheck(@NotNull User user, @NotNull String branch, @NotNull String path, @NotNull BooleanFunction<AccessMode> checker) {
    String pathToCheck = path;

    while (true) {
      final Map.Entry<String, Map<String, Map<ACLEntry, AccessMode>>> pathEntry = RepositoryMapping.getMapped(path2branch2acl, pathToCheck);

      if (pathEntry == null)
        break;

      for (String b : new String[]{branch, NoBranch}) {
        final Map<ACLEntry, AccessMode> branchPathEntry = pathEntry.getValue().get(b);
        if (branchPathEntry == null)
          continue;

        final CheckResult checkResult = check(user, checker, branchPathEntry);
        if (checkResult == CheckResult.Deny)
          return false;
        else if (checkResult == CheckResult.Allow)
          return true;
      }

      // We didn't find a matching entry, so need to go up in hierarchy
      final int prevSlash = pathEntry.getKey().lastIndexOf('/', pathEntry.getKey().length() - 2);
      if (prevSlash < 0)
        break;
      pathToCheck = pathEntry.getKey().substring(0, prevSlash);
    }

    return false;
  }

  @NotNull
  private ACL.CheckResult check(@NotNull User user, @NotNull BooleanFunction<AccessMode> checker, @NotNull Map<ACLEntry, AccessMode> entries) {
    CheckResult result = CheckResult.Unspecified;

    for (Map.Entry<ACLEntry, AccessMode> aclEntry : entries.entrySet()) {
      if (!aclEntry.getKey().matches(user))
        continue;

      if (checker.booleanValueOf(aclEntry.getValue()))
        // Explicit allow is stronger than explicit deny
        return CheckResult.Allow;

      result = CheckResult.Deny;
    }

    return result;
  }

  private enum CheckResult {
    Unspecified,
    Allow,
    Deny
  }

  private enum AccessMode {
    none,
    r,
    rw;

    @NotNull
    static AccessMode fromString(@Nullable String value) {
      if (value == null || value.isEmpty())
        return none;

      return valueOf(value);
    }

    boolean allowsRead() {
      return this != none;
    }

    boolean allowsWrite() {
      return this == rw;
    }
  }

  private enum EveryoneACLEntry implements ACLEntry {
    Instance;

    @Override
    public boolean matches(@NotNull User user) {
      return true;
    }
  }

  private enum AnonymousACLEntry implements ACLEntry {
    Instance;

    @Override
    public boolean matches(@NotNull User user) {
      return user.isAnonymous();
    }
  }

  private enum AuthenticatedACLEntry implements ACLEntry {
    Instance;

    @Override
    public boolean matches(@NotNull User user) {
      return !user.isAnonymous();
    }
  }

  private interface ACLEntry {
    boolean matches(@NotNull User user);
  }

  private static final class UserTypeEntry implements ACLEntry {
    @NotNull
    private final UserType userType;

    private UserTypeEntry(@NotNull UserType userType) {
      this.userType = userType;
    }

    @Override
    public boolean matches(@NotNull User user) {
      return !user.isAnonymous() && user.getType().equals(userType);
    }
  }

  private static final class UserACLEntry implements ACLEntry {
    @NotNull
    private final String user;

    private UserACLEntry(@NotNull String user) {
      this.user = user;
    }

    @Override
    public boolean matches(@NotNull User user) {
      return !user.isAnonymous() && user.getUsername().equals(this.user);
    }

    @Override
    public int hashCode() {
      return user.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object o) {
      return o instanceof UserACLEntry && user.equals(((UserACLEntry) o).user);
    }
  }

  private final class GroupACLEntry implements ACLEntry {
    @NotNull
    private final String group;

    private GroupACLEntry(@NotNull String group) {
      this.group = group;
    }

    @Override
    public boolean matches(@NotNull User user) {
      if (user.isAnonymous())
        return anonymousGroups.contains(group);

      if (authenticatedGroups.getOrDefault(user.getType(), Collections.emptySet()).contains(group))
        return true;

      return user2groups.getOrDefault(user.getUsername(), Collections.emptySet()).contains(group);
    }

    @Override
    public int hashCode() {
      return group.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object o) {
      return o instanceof GroupACLEntry && group.equals(((GroupACLEntry) o).group);
    }
  }
}
