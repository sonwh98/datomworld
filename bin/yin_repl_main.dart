import 'dart:io';
import '../lib/cljd-out/yin/shell/main.dart' as shell;

void main(List<String> args) async {
  try {
    await shell.main(args);
  } catch (e) {
    stderr.writeln(e.toString());
    exit(1);
  }
}
