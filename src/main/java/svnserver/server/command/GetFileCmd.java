package svnserver.server.command;

import org.jetbrains.annotations.NotNull;
import org.tmatesoft.svn.core.SVNException;
import svnserver.parser.SvnServerWriter;
import svnserver.repository.VcsFile;
import svnserver.repository.VcsRepository;
import svnserver.repository.VcsRevision;
import svnserver.server.SessionContext;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

/**
 * Get file content.
 * <p><pre>
 * get-file
 *    params:   ( path:string [ rev:number ] want-props:bool want-contents:bool
 *    ? want-iprops:bool )
 *    response: ( [ checksum:string ] rev:number props:proplist
 *    [ inherited-props:iproplist ] )
 *    If want-contents is specified, then after sending response, server
 *    sends file contents as a series of strings, terminated by the empty
 *    string, followed by a second empty command response to indicate
 *    whether an error occurred during the sending of the file.
 *    NOTE: the standard client doesn't send want-iprops as true, it uses
 *    get-iprops, but does send want-iprops as false to workaround a server
 *    bug in 1.8.0-1.8.8.
 * </pre>
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public class GetFileCmd extends BaseCmd<GetFileCmd.Params> {
  public static class Params {
    private final String path;
    private final int[] rev;
    private final boolean wantProps;
    private final boolean wantContents;

    public Params(String path, int[] rev, boolean wantProps, boolean wantContents) {
      this.path = path;
      this.rev = rev;
      this.wantProps = wantProps;
      this.wantContents = wantContents;
    }
  }

  @NotNull
  @Override
  public Class<Params> getArguments() {
    return Params.class;
  }

  @Override
  protected void processCommand(@NotNull SessionContext context, @NotNull Params args) throws IOException, SVNException {
    SvnServerWriter writer = context.getWriter();
    String fullPath = context.getRepositoryPath(args.path);
    if (fullPath.endsWith("/")) {
      sendError(writer, 200009, "Could not cat all targets because some targets are directories");
      return;
    }
    final VcsRepository repository = context.getRepository();
    final VcsRevision info = repository.getRevisionInfo(getRevision(args.rev, repository.getLatestRevision()));
    final VcsFile fileInfo = info.getFile(fullPath);
    if (fileInfo == null) {
      sendError(writer, 200009, "File not found");
      return;
    }
    if (fileInfo.isDirectory()) {
      sendError(writer, 200009, "Could not cat all targets because some targets are directories");
      return;
    }
    writer
        .listBegin()
        .word("success")
        .listBegin()
        .listBegin().string(fileInfo.getMd5()).listEnd() // md5
        .number(info.getId()) // revision id
        .writeMap(args.wantProps ? fileInfo.getProperties(true) : Collections.emptyMap())
        .listEnd()
        .listEnd();
    if (args.wantContents) {
      try (InputStream stream = fileInfo.openStream()) {
        writer.binary(fileInfo.getSize(), stream);
      }
      writer.string("")
          .listBegin()
          .word("success")
          .listBegin()
          .listEnd()
          .listEnd();
    }
  }

}