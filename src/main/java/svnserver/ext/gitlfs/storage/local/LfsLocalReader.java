/**
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.ext.gitlfs.storage.local;

import com.sun.nio.sctp.InvalidStreamException;
import org.apache.commons.codec.binary.Hex;
import org.jetbrains.annotations.NotNull;
import svnserver.ext.gitlfs.storage.LfsReader;

import java.io.*;
import java.util.Arrays;

/**
 * Local storage writer.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public class LfsLocalReader implements LfsReader {
  @NotNull
  private final File file;
  @NotNull
  private final byte[] md5;
  private final long size;
  private final long offset;

  public LfsLocalReader(@NotNull File file) throws IOException {
    this.file = file;
    try (RandomAccessFile stream = new RandomAccessFile(file, "r")) {
      byte[] header = new byte[LfsLocalStorage.HEADER.length];
      stream.readFully(header);
      if (!Arrays.equals(header, LfsLocalStorage.HEADER)) {
        throw new InvalidStreamException("Invalid stream header: " + file.getPath());
      }
      size = stream.readLong();
      md5 = new byte[stream.readByte()];
      stream.readFully(md5);
      offset = stream.getFilePointer();
    }
  }

  @NotNull
  @Override
  public InputStream openStream() throws IOException {
    final FileInputStream stream = new FileInputStream(file);
    //noinspection ResultOfMethodCallIgnored
    stream.skip(offset);
    return stream;
  }

  @Override
  public long getSize() {
    return size;
  }

  @NotNull
  @Override
  public String getMd5() {
    return Hex.encodeHexString(md5);
  }
}
