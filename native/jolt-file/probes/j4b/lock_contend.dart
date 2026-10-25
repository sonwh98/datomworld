// The Dart contender for the J4b cross-family gate: the same call the cljd
// arm of dao.space.store.fs/host-lock! makes (RandomAccessFile.lockSync,
// FileLock.exclusive, non-blocking) on <dir>/lock. Prints
// "RESULT refused <error>" or "RESULT acquired" (then unlocks and closes).
import 'dart:io';

void main(List<String> args) {
  final raf = File('${args[0]}/lock').openSync(mode: FileMode.append);
  try {
    raf.lockSync();
    print('RESULT acquired');
    raf.unlockSync();
  } catch (e) {
    print('RESULT refused $e');
  } finally {
    raf.closeSync();
  }
}
