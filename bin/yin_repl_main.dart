import 'dart:io';
import '../lib/cljd-out/yin/repl/main.dart' as repl;

void main(List<String> args) async {
  try {
    await repl.main(args);
  } catch (e) {
    stderr.writeln(e.toString());
    exit(1);
  }
}
