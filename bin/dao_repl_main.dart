import 'dart:io';
import '../lib/cljd-out/dao/repl-main.dart' as repl;

void main(List<String> args) {
  try {
    repl.run_main(args);
  } catch (e) {
    exit(1);
  }
}
