// The Dart holder for the J4b cross-family gate: lock <dir>/lock exactly as
// the cljd arm of host-lock! does, write <signal-dir>/acquired, hold until
// <signal-dir>/stop exists, then unlock and close.
import 'dart:io';

void main(List<String> args) {
  final raf = File('${args[0]}/lock').openSync(mode: FileMode.append);
  raf.lockSync();
  File('${args[1]}/acquired').writeAsStringSync('acquired');
  while (!File('${args[1]}/stop').existsSync()) {
    sleep(const Duration(milliseconds: 20));
  }
  raf.unlockSync();
  raf.closeSync();
}
