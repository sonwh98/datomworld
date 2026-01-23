library yin_vm;

/// A simple implementation of the Yin VM in Dart.
/// Ported from src/cljc/yin/vm.cljc

// ============================================================
// Utilities
// ============================================================

int _idCounter = 0;

String gensymId([String prefix = "id"]) {
  _idCounter++;
  return "$prefix-$_idCounter";
}

// ============================================================
// Module System
// ============================================================

class YinModule {
  static final Map<String, Map<String, dynamic>> _registry = {};

  static void register(String moduleName, Map<String, dynamic> bindings) {
    _registry[moduleName] = bindings;
  }

  static dynamic resolveSymbol(String symbol) {
    if (!symbol.contains('/')) return null;
    final parts = symbol.split('/');
    final ns = parts[0];
    final name = parts[1];
    final module = _registry[ns];
    return module?[name];
  }

  static bool isEffect(dynamic x) {
    return x is Map && x.containsKey('effect');
  }
}

// ============================================================
// State
// ============================================================

class YinState {
  final dynamic control;
  final Map<String, dynamic> environment;
  final Map<String, dynamic> store;
  final dynamic continuation;
  final dynamic value;
  final Map<String, dynamic> parked;

  YinState({
    this.control,
    required this.environment,
    required this.store,
    this.continuation,
    this.value,
    this.parked = const {},
  });

  YinState copyWith({
    dynamic control = _Undefined,
    Map<String, dynamic>? environment,
    Map<String, dynamic>? store,
    dynamic continuation = _Undefined,
    dynamic value = _Undefined,
    Map<String, dynamic>? parked,
  }) {
    return YinState(
      control: control == _Undefined ? this.control : control,
      environment: environment ?? this.environment,
      store: store ?? this.store,
      continuation: continuation == _Undefined ? this.continuation : continuation,
      value: value == _Undefined ? this.value : value,
      parked: parked ?? this.parked,
    );
  }

  static const _Undefined = Object();
}

// ============================================================
// Stream Logic
// ============================================================

class YinStream {
  static Map<String, dynamic> makeStreamData(int capacity) {
    return {
      'type': 'stream',
      'buffer': [],
      'capacity': capacity,
      'takers': [],
      'closed': false,
    };
  }

  static bool isClosed(Map stream) => stream['closed'] == true;
  static bool hasTakers(Map stream) => (stream['takers'] as List).isNotEmpty;
  static bool isEmpty(Map stream) => (stream['buffer'] as List).isEmpty;

  static Map takeValue(Map stream) {
    final buffer = List.from(stream['buffer']);
    final val = buffer.removeAt(0);
    final newStream = Map<String, dynamic>.from(stream);
    newStream['buffer'] = buffer;
    return {'val': val, 'stream': newStream};
  }

  static Map popTaker(Map stream) {
    final takers = List.from(stream['takers']);
    final taker = takers.removeAt(0);
    final newStream = Map<String, dynamic>.from(stream);
    newStream['takers'] = takers;
    return {'taker': taker, 'stream': newStream};
  }

  static Map addValue(Map stream, dynamic val) {
    final buffer = List.from(stream['buffer']);
    buffer.add(val);
    final newStream = Map<String, dynamic>.from(stream);
    newStream['buffer'] = buffer;
    return newStream;
  }

  static Map addTaker(Map stream, dynamic parkedCont) {
    final takers = List.from(stream['takers']);
    takers.add(parkedCont);
    final newStream = Map<String, dynamic>.from(stream);
    newStream['takers'] = takers;
    return newStream;
  }

  // VM Stream Operations

  static List vmStreamMake(YinState state, int capacity) {
    final id = gensymId("stream");
    final streamData = makeStreamData(capacity);
    final newStore = Map<String, dynamic>.from(state.store);
    newStore[id] = streamData;
    final streamRef = {'type': 'stream-ref', 'id': id};
    return [streamRef, state.copyWith(store: newStore)];
  }

  static Map vmStreamTake(YinState state, Map streamRef, dynamic continuation) {
    final streamId = streamRef['id'];
    final store = state.store;
    final stream = store[streamId];

    if (stream == null) throw Exception("Invalid stream reference: $streamRef");

    if (!isEmpty(stream)) {
      final res = takeValue(stream);
      final val = res['val'];
      final newStream = res['stream'];
      final newStore = Map<String, dynamic>.from(store);
      newStore[streamId] = newStream;
      return {
        'value': val,
        'state': state.copyWith(store: newStore, value: val)
      };
    } else if (isClosed(stream)) {
      return {'value': null, 'state': state.copyWith(value: null)};
    } else {
      return {'park': true, 'stream-id': streamId, 'state': state};
    }
  }

  static Map vmStreamPut(YinState state, Map streamRef, dynamic val) {
    final streamId = streamRef['id'];
    final store = state.store;
    final stream = store[streamId];

    if (stream == null) throw Exception("Invalid stream reference: $streamRef");
    if (isClosed(stream)) throw Exception("Cannot put to closed stream");

    if (hasTakers(stream)) {
      final res = popTaker(stream);
      final taker = res['taker'];
      final newStream = res['stream'];
      final newStore = Map<String, dynamic>.from(store);
      newStore[streamId] = newStream;
      return {
        'value': val,
        'state': state.copyWith(store: newStore, value: val),
        'resume-taker': taker,
        'resume-value': val
      };
    } else {
      final newStream = addValue(stream, val);
      final newStore = Map<String, dynamic>.from(store);
      newStore[streamId] = newStream;
      return {
        'value': val,
        'state': state.copyWith(store: newStore, value: val)
      };
    }
  }
}

// ============================================================
// Primitives
// ============================================================

final Map<String, Function> primitives = {
  '+': (a, b) => a + b,
  '-': (a, b) => a - b,
  '*': (a, b) => a * b,
  '/': (a, b) => a / b,
  '=': (a, b) => a == b,
  '<': (a, b) => a < b,
  '>': (a, b) => a > b,
  'not': (a) => !a,
  'nil?': (a) => a == null,
  'empty?': (a) => (a as Iterable).isEmpty,
  'first': (a) => (a as List).first,
  'rest': (a) => (a as List).skip(1).toList(),
  'conj': (coll, x) => (coll as List).toList()..add(x), // naive
  'assoc': (m, k, v) => (m as Map).cast<String, dynamic>()..putIfAbsent(k as String, () => v), // naive
  'get': (m, k) => (m as Map)[k],
  'vec': (coll) => (coll as Iterable).toList(),
  'yin/def': (k, v) => {'effect': 'vm/store-put', 'key': k, 'val': v},
};

// ============================================================
// VM
// ============================================================

class YinVM {
  static YinState makeState(Map<String, dynamic> env) {
    return YinState(
      control: null,
      environment: env,
      store: {},
      continuation: null,
      value: null,
    );
  }

  static YinState eval(YinState state, [dynamic ast]) {
    final control = state.control;
    final environment = state.environment;
    final continuation = state.continuation;
    final store = state.store;

    final node = ast ?? control;

    // Handle continuation when no control node
    if (node == null && continuation != null) {
      final contType = continuation['type'];
      final frame = continuation['frame'];
      final savedEnv = continuation['environment'];
      final parentCont = continuation['parent'];

      if (contType == 'eval-operator') {
        final fnValue = state.value;
        final updatedFrame = Map<String, dynamic>.from(frame);
        updatedFrame['operator-evaluated?'] = true;
        updatedFrame['fn-value'] = fnValue;

        return state.copyWith(
          control: updatedFrame,
          environment: savedEnv ?? environment,
          continuation: parentCont,
          value: null,
        );
      } else if (contType == 'eval-operand') {
        final operandValue = state.value;
        final evaluatedOperands = List.from(frame['evaluated-operands'] ?? []);
        evaluatedOperands.add(operandValue);
        final updatedFrame = Map<String, dynamic>.from(frame);
        updatedFrame['evaluated-operands'] = evaluatedOperands;

        return state.copyWith(
          control: updatedFrame,
          environment: savedEnv ?? environment,
          continuation: parentCont,
          value: null,
        );
      } else if (contType == 'eval-test') {
        final testValue = state.value;
        final updatedFrame = Map<String, dynamic>.from(frame);
        updatedFrame['evaluated-test?'] = true;

        return state.copyWith(
          control: updatedFrame,
          value: testValue,
          environment: savedEnv ?? environment,
          continuation: parentCont,
        );
      } else if (contType == 'eval-stream-source') {
        final streamRef = state.value;
        final result = YinStream.vmStreamTake(state, streamRef, continuation);
        
        if (result['park'] == true) {
          final streamId = result['stream-id'];
          final parkedCont = {
             'type': 'parked-continuation',
             'id': gensymId("taker"),
             'continuation': parentCont,
             'environment': environment,
          };
          final newStore = Map<String, dynamic>.from(store);
          // Assuming stream in store, add taker
          final stream = newStore[streamId];
          final newStream = YinStream.addTaker(stream, parkedCont);
          newStore[streamId] = newStream;

          return state.copyWith(
            store: newStore,
            value: 'yin/blocked',
            control: null,
            continuation: null,
          );
        } else {
          return (result['state'] as YinState).copyWith(
            control: null,
            continuation: parentCont,
          );
        }
      } else if (contType == 'eval-stream-put-target') {
        final streamRef = state.value;
        final valNode = frame['val']; // 'val' key from frame which is the node
        final newCont = Map<String, dynamic>.from(continuation);
        newCont['type'] = 'eval-stream-put-val';
        newCont['stream-ref'] = streamRef;
        
        return state.copyWith(
          control: valNode,
          continuation: newCont,
        );
      } else if (contType == 'eval-stream-put-val') {
        final val = state.value;
        final streamRef = continuation['stream-ref'];
        final result = YinStream.vmStreamPut(state, streamRef, val);
        
        // Handling resume-taker is omitted for brevity as per cljc impl hint
        // "For now, just complete the put and let scheduler handle resume"
        
        return (result['state'] as YinState).copyWith(
          control: null,
          continuation: parentCont,
        );
      } else {
        throw Exception("Unknown continuation type: $contType");
      }
    }

    // Handle AST Node
    final type = node['type'];

    if (type == 'literal') {
      return state.copyWith(value: node['value'], control: null);
    } else if (type == 'variable') {
      final name = node['name'];
      final value = environment[name] ?? store[name] ?? primitives[name] ?? YinModule.resolveSymbol(name);
      // Primitives check added above for direct primitive access if they are not in environment
      return state.copyWith(value: value, control: null);
    } else if (type == 'lambda') {
      final closure = {
        'type': 'closure',
        'params': node['params'],
        'body': node['body'],
        'environment': environment
      };
      return state.copyWith(value: closure, control: null);
    } else if (type == 'application') {
      final operatorEvaluated = node['operator-evaluated?'] == true;
      final operands = node['operands'] as List;
      final evaluatedOperands = node['evaluated-operands'] as List? ?? [];
      final fnValue = node['fn-value'];

      if (operatorEvaluated && evaluatedOperands.length == operands.length) {
        // Apply function
        if (fnValue is Function) {
          final result = Function.apply(fnValue, evaluatedOperands);
          
          if (YinModule.isEffect(result)) {
             final effectType = result['effect'];
             
             if (effectType == 'vm/store-put') {
                final key = result['key'];
                final val = result['val'];
                final newStore = Map<String, dynamic>.from(store);
                newStore[key] = val;
                return state.copyWith(store: newStore, value: val, control: null);
             } else if (effectType == 'stream/make') {
                final capacity = result['capacity'];
                final res = YinStream.vmStreamMake(state, capacity);
                final streamRef = res[0];
                final newState = res[1] as YinState;
                return newState.copyWith(value: streamRef, control: null);
             } else if (effectType == 'stream/put') {
                // To handle effect stream/put, we'd need to convert effect to VM op
                // But wait, the cljc impl calls stream/handle-put which executes it.
                // Re-implementing handle logic:
                final streamRef = result['stream'];
                final val = result['val'];
                final res = YinStream.vmStreamPut(state, streamRef, val);
                return (res['state'] as YinState).copyWith(value: res['value'], control: null);
             } else if (effectType == 'stream/take') {
                final streamRef = result['stream'];
                // We don't have continuation here easily to reuse vmStreamTake logic entirely
                // but the cljc impl uses stream/handle-take which might return park.
                // If it returns park, we need to park using current continuation?
                // The current continuation is nil here because we are in apply.
                // Wait, if we are in apply, where is the continuation?
                // The cljc impl says "if (:park effect-result) ... :continuation continuation".
                // So we do have continuation in 'state'.
                
                // Let's implement vmStreamTake equivalent for effect
                final res = YinStream.vmStreamTake(state, streamRef, continuation);
                if (res['park'] == true) {
                   final streamId = res['stream-id'];
                   final parkedCont = {
                      'type': 'parked-continuation',
                      'id': gensymId('taker'),
                      'continuation': continuation, // from state
                      'environment': environment
                   };
                   final newStore = Map<String, dynamic>.from(store);
                   final stream = newStore[streamId];
                   newStore[streamId] = YinStream.addTaker(stream, parkedCont);
                   return state.copyWith(
                      store: newStore,
                      value: 'yin/blocked',
                      control: null,
                      continuation: null
                   );
                } else {
                   return (res['state'] as YinState).copyWith(value: res['value'], control: null);
                }
             } else {
                throw Exception("Unknown effect type: $effectType");
             }
          } else {
             return state.copyWith(value: result, control: null);
          }
        } else if (fnValue is Map && fnValue['type'] == 'closure') {
           final params = fnValue['params'] as List;
           final body = fnValue['body'];
           final closureEnv = fnValue['environment'] as Map<String, dynamic>;
           final extendedEnv = Map<String, dynamic>.from(closureEnv);
           for(int i=0; i<params.length; i++) {
             extendedEnv[params[i]] = evaluatedOperands[i];
           }
           return state.copyWith(
             control: body,
             environment: extendedEnv,
             continuation: continuation,
           );
        } else {
           throw Exception("Cannot apply non-function: $fnValue");
        }
      } else if (operatorEvaluated) {
         // Evaluate next operand
         final nextOperand = operands[evaluatedOperands.length];
         return state.copyWith(
           control: nextOperand,
           continuation: {
             'frame': node,
             'parent': continuation,
             'environment': environment,
             'type': 'eval-operand',
           }
         );
      } else {
         // Evaluate operator
         return state.copyWith(
           control: node['operator'],
           continuation: {
             'frame': node,
             'parent': continuation,
             'environment': environment,
             'type': 'eval-operator',
           }
         );
      }
    } else if (type == 'if') {
      if (node['evaluated-test?'] == true) {
        final testValue = state.value;
        final branch = (testValue != false && testValue != null) ? node['consequent'] : node['alternate'];
        return state.copyWith(control: branch);
      } else {
        return state.copyWith(
          control: node['test'],
          continuation: {
            'frame': node,
            'parent': continuation,
            'environment': environment,
            'type': 'eval-test',
          }
        );
      }
    } else if (type == 'stream/make') {
       final capacity = node['buffer'] ?? 1024;
       final res = YinStream.vmStreamMake(state, capacity);
       return (res[1] as YinState).copyWith(value: res[0], control: null);
    } else if (type == 'stream/put') {
       return state.copyWith(
         control: node['target'],
         continuation: {
           'frame': node,
           'parent': continuation,
           'environment': environment,
           'type': 'eval-stream-put-target',
         }
       );
    } else if (type == 'stream/take') {
       return state.copyWith(
         control: node['source'],
         continuation: {
           'frame': node,
           'parent': continuation,
           'environment': environment,
           'type': 'eval-stream-source',
         }
       );
    }

    throw Exception("Unknown AST node type: $type");
  }

  static YinState run(YinState initialState, dynamic ast) {
    var state = initialState.copyWith(control: ast);
    while (state.control != null || state.continuation != null) {
      state = eval(state);
    }
    return state;
  }
}
