import '../cljd-out/dao/stream-cljd-test_test.dart' as stream_cljd;
import '../cljd-out/dao/stream-test_test.dart' as stream;
import '../cljd-out/yin/vm/parity-test_test.dart' as parity;
import '../cljd-out/dao/db-test_test.dart' as db;
import '../cljd-out/dao/db/in-memory-test_test.dart' as db_in_memory;
import '../cljd-out/dao/repl-test_test.dart' as repl;
import '../cljd-out/dao/stream/apply-test_test.dart' as apply;
import '../cljd-out/yin/vm/ast-walker-test_test.dart' as ast_walker;
import '../cljd-out/yin/vm/engine-test_test.dart' as engine;
import '../cljd-out/yin/vm/macro-test_test.dart' as macro;
import '../cljd-out/yin/vm/register-test_test.dart' as register;
import '../cljd-out/yin/vm/semantic-test_test.dart' as semantic;
import '../cljd-out/yin/vm/stack-test_test.dart' as stack;

void main() {
  print('Starting Dart Runner...');
  
  stream_cljd.main();
  stream.main();
  parity.main();
  db.main();
  db_in_memory.main();
  repl.main();
  apply.main();
  ast_walker.main();
  engine.main();
  macro.main();
  register.main();
  semantic.main();
  stack.main();

  print('All tests registered with package:test');
}
