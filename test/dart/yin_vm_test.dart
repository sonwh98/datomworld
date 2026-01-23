import 'package:flutter_test/flutter_test.dart';
import '../../src/dart/yin_vm.dart';

void main() {
  group('Yin VM Tests', () {
    late YinState initialState;
    setUp(() {
      initialState = YinVM.makeState({});
    });

    test('Arithmetic: (+ 1 2)', () {
      print('Running Arithmetic Test: (+ 1 2)');
      final ast = {
        'type': 'application',
        'operator': {'type': 'variable', 'name': '+'},
        'operands': [
          {'type': 'literal', 'value': 1},
          {'type': 'literal', 'value': 2}
        ]
      };

      final result = YinVM.run(initialState, ast);
      print('Result: ${result.value}');
      expect(result.value, equals(3));
    });

    test('Identity Lambda: ((fn [x] x) 42)', () {
      print('Running Identity Lambda Test: ((fn [x] x) 42)');
      final ast = {
        'type': 'application',
        'operator': {
          'type': 'lambda',
          'params': ['x'],
          'body': {'type': 'variable', 'name': 'x'}
        },
        'operands': [
          {'type': 'literal', 'value': 42}
        ]
      };

      final result = YinVM.run(initialState, ast);
      print('Result: ${result.value}');
      expect(result.value, equals(42));
    });

    test('Stream: Make/Put/Take', () {
      print('Running Stream Test: Make/Put/Take');
      // ((fn [s] ((fn [_] (stream/take s)) (stream/put s 99))) (stream/make 10))
      final ast = {
        'type': 'application',
        'operator': {
          'type': 'lambda',
          'params': ['s'],
          'body': {
            'type': 'application',
            'operator': {
              'type': 'lambda',
              'params': ['_'],
              'body': {
                'type': 'stream/take',
                'source': {'type': 'variable', 'name': 's'}
              }
            },
            'operands': [
              {
                'type': 'stream/put',
                'target': {'type': 'variable', 'name': 's'},
                'val': {'type': 'literal', 'value': 99}
              }
            ]
          }
        },
        'operands': [
          {'type': 'stream/make', 'buffer': 10}
        ]
      };

      final result = YinVM.run(initialState, ast);
      print('Result: ${result.value}');
      expect(result.value, equals(99));
    });
  });
}
