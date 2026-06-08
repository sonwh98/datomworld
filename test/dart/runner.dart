import '../cljd-out/agent/tzu-test_test.dart' as agent_tzu;
import '../cljd-out/dao/stream/http-test_test.dart' as http;
import '../cljd-out/dao/stream/whatsapp-test_test.dart' as whatsapp;
import '../cljd-out/dao/stream-test_test.dart' as stream;
import '../cljd-out/yin/vm/parity-test_test.dart' as parity;
import '../cljd-out/dao/db-test_test.dart' as db;
import '../cljd-out/dao/db/in-memory-test_test.dart' as db_in_memory;
import '../cljd-out/yin/repl-test_test.dart' as repl;
import '../cljd-out/dao/stream/apply-test_test.dart' as apply;
import '../cljd-out/dao/stream/transit-test_test.dart' as transit;
import '../cljd-out/dao/gui/compiler-cljd-test_test.dart' as dao_gui_compiler;
import '../cljd-out/dao/postgraphics/flutter-cljd-test_test.dart' as postgraphics_flutter;
import '../cljd-out/dao/postgraphics/v3-compliance-repro-test_test.dart' as postgraphics_v3_repro;
import '../cljd-out/yin/vm/ast-walker-test_test.dart' as ast_walker;
import '../cljd-out/yin/vm/engine-test_test.dart' as engine;
import '../cljd-out/yin/vm/macro-test_test.dart' as macro;
import '../cljd-out/yin/vm/register-test_test.dart' as register;
import '../cljd-out/yin/vm/semantic-test_test.dart' as semantic;
import '../cljd-out/yin/vm/stack-test_test.dart' as stack;
import '../cljd-out/datomworld/demo/continuation-handoff-test_test.dart' as continuation_handoff;

void main() {
  print('Starting Dart Runner...');
  
  agent_tzu.main();
  http.main();
  whatsapp.main();
  stream.main();
  parity.main();
  db.main();
  db_in_memory.main();
  repl.main();
  apply.main();
  transit.main();
  dao_gui_compiler.main();
  postgraphics_flutter.main();
  postgraphics_v3_repro.main();
  ast_walker.main();
  engine.main();
  macro.main();
  register.main();
  semantic.main();
  stack.main();
  continuation_handoff.main();

  print('All tests registered with package:test');
}
