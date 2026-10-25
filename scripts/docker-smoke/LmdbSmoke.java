import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Env;

/** Root-written LMDB data remains readable and writable after the UID migration and restart. */
public class LmdbSmoke {
  private static ByteBuffer buffer(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    return ByteBuffer.allocateDirect(bytes.length).put(bytes).flip();
  }

  public static void main(String[] args) throws Exception {
    Path directory = Path.of("/data/ddata/isolation-smoke");
    Files.createDirectories(directory);
    try (Env<ByteBuffer> env = Env.create().setMapSize(16 * 1024 * 1024).setMaxDbs(1)
        .open(directory.toFile())) {
      var db = env.openDbi("migration", DbiFlags.MDB_CREATE);
      if (!args[0].equals("seed")) {
        try (var tx = env.txnRead()) {
          var value = db.get(tx, buffer("state"));
          String expected = args[0].equals("migrate") ? "root-written" : "nonroot-written";
          if (value == null || !expected.equals(StandardCharsets.UTF_8.decode(value).toString())) {
            throw new AssertionError("LMDB did not preserve " + expected);
          }
        }
      }
      try (var tx = env.txnWrite()) {
        db.put(tx, buffer("state"), buffer(args[0].equals("seed") ? "root-written" : "nonroot-written"));
        tx.commit();
      }
    }
    System.out.println("PASS LMDB: " + args[0]);
  }
}
