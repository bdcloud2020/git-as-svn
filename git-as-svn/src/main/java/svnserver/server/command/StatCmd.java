package svnserver.server.command;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import svnserver.parser.SvnServerWriter;
import svnserver.server.SessionContext;

import java.io.IOException;

/**
 * Change current path in repository.
 * <p>
 * <pre>
 * stat
 *    params:   ( path:string [ rev:number ] )
 *    response: ( ? entry:dirent )
 *    dirent:   ( name:string kind:node-kind size:number has-props:bool
 *    created-rev:number [ created-date:string ]
 *    [ last-author:string ] )
 *    New in svn 1.2.  If path is non-existent, an empty response is returned.
 * </pre>
 *
 * @author a.navrotskiy
 */
public class StatCmd extends BaseCmd<StatCmd.Params> {
  public static class Params {
    @NotNull
    private final String path;
    @NotNull
    private final int[] rev;

    public Params(@NotNull String path, @NotNull int[] rev) {
      this.path = path;
      this.rev = rev;
    }

    @Nullable
    public Integer getRev() {
      return rev.length < 1 ? null : rev[0];
    }
  }

  @NotNull
  @Override
  public Class<Params> getArguments() {
    return Params.class;
  }

  @Override
  protected void processCommand(@NotNull SessionContext context, @NotNull Params args) throws IOException {
    final SvnServerWriter writer = context.getWriter();
    writer
        .listBegin()
        .word("success")
        .listBegin()
        .listBegin()
        .listBegin()
        .word("dir") // kind
        .number(100500) // size
        .bool(false) // has properties
        .number(42) // last change revision
        .listBegin().string("2014-08-11T11:57:36.023610Z").listEnd() // last change date
        .listBegin().string("bozaro").listEnd() // last change author
        .listEnd()
        .listEnd()
        .listEnd()
        .listEnd();
  }
}