import 'dart:io';
import '../lib/cljd-out/dao/repl-main/cljd.dart' as repl;

void main(List<String> args) {
  try {
    repl.run_main(args);
  } catch (e) {
    stderr.writeln(e.toString());
    exit(1);
  }
}
