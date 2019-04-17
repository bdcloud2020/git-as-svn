/**
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.repository;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.tmatesoft.svn.core.SVNException;
import svnserver.repository.git.GitFile;

import java.io.IOException;

/**
 * Directory entry.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public interface VcsEntry {
  @NotNull
  String getFileName();

  @NotNull
  String getFullPath();

  @NotNull
  VcsEntry createChild(@NotNull String name, boolean isDir);

  @Nullable
  GitFile getEntry(@NotNull String name) throws IOException, SVNException;
}
