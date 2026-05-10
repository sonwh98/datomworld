import 'dart:io';
import '../lib/cljd-out/yin/repl-main/cljd.dart' as repl;

void main(List<String> args) async {
  try {
    await repl.run_main(args);
  } catch (e) {
    stderr.writeln(e.toString());
    exit(1);
  }
}
