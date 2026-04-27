import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'dart:ui';

import 'package:datomworld_cljd/cljd-out/dao/flow/flutter.dart' as flutter_cljd;

void main() {
  test('FlowPainter can be instantiated and paints without crashing on empty frame', () {
    // 1. Create the ValueNotifier with an empty frame (empty Clojure vector / Dart list)
    final notifier = ValueNotifier<dynamic>([]);
    
    // 2. Instantiate the painter
    final painter = flutter_cljd.FlowPainter(notifier);
    
    // 3. Create a PictureRecorder and Canvas to catch paint calls
    final recorder = PictureRecorder();
    final canvas = Canvas(recorder);
    
    // 4. Paint
    painter.paint(canvas, const Size(400, 400));
    
    // 5. Verify the picture ends correctly
    final picture = recorder.endRecording();
    expect(picture, isNotNull);
    
    // If we wanted to test a full cube, we would need to mock the cljd.core.Keyword
    // and op map structure, but confirming it binds and paints without throwing is
    // the critical smoke test boundary.
  });
}
