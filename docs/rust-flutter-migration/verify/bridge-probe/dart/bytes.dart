// 聚焦测量：桥接层字节传递的拷贝开销。
// 对比三档：zero-copy 映射 / 仅触摸 / 拷贝到 Dart 堆。

import 'dart:ffi' as ffi;
import 'dart:typed_data';

typedef _AllocC = ffi.Pointer<ffi.Uint8> Function(ffi.IntPtr, ffi.Uint8);
typedef _AllocD = ffi.Pointer<ffi.Uint8> Function(int, int);
typedef _FreeC = ffi.Void Function(ffi.Pointer<ffi.Uint8>, ffi.IntPtr);
typedef _FreeD = void Function(ffi.Pointer<ffi.Uint8>, int);
typedef _SumC = ffi.Uint64 Function(ffi.Pointer<ffi.Uint8>, ffi.IntPtr);
typedef _SumD = int Function(ffi.Pointer<ffi.Uint8>, int);

void main() {
  final lib = ffi.DynamicLibrary.open(
      '/Users/he/workspace/git_repo/Pixiv-Shaft-Desktop/.verify/probe/target/release/libprobe_bridge.dylib');
  final alloc = lib.lookupFunction<_AllocC, _AllocD>('alloc_bytes');
  final free = lib.lookupFunction<_FreeC, _FreeD>('free_bytes');
  final sum = lib.lookupFunction<_SumC, _SumD>('sum_bytes');

  const size = 64 * 1024 * 1024;
  const reps = 5;

  // 预热，排除缺页与 lazy zero-fill 的干扰
  final warm = alloc(size, 1);
  sum(warm, size);
  free(warm, size);

  // A. 零拷贝：asTypedList 只是建一个 view，不搬数据
  final viewTimes = <int>[];
  for (var i = 0; i < reps; i++) {
    final p = alloc(size, 0xAB);
    final sw = Stopwatch()..start();
    final view = p.asTypedList(size);
    sw.stop();
    viewTimes.add(sw.elapsedMicroseconds);
    if (view.length != size) throw StateError('view length wrong');
    free(p, size);
  }

  // B. 拷贝到 Dart 堆：Uint8List.fromList（桥接层序列化走的也是这一步）
  final copyTimes = <int>[];
  for (var i = 0; i < reps; i++) {
    final p = alloc(size, 0xAB);
    final src = p.asTypedList(size);
    final sw = Stopwatch()..start();
    final copy = Uint8List.fromList(src);
    sw.stop();
    copyTimes.add(sw.elapsedMicroseconds);
    if (copy.length != size) throw StateError('copy length wrong');
    free(p, size);
  }

  // C. 参照：纯 Dart 堆内 64MB 拷贝（与 FFI 无关的上限参考）
  final a = Uint8List(size);
  final b = Uint8List(size);
  final pureTimes = <int>[];
  for (var i = 0; i < reps; i++) {
    final sw = Stopwatch()..start();
    b.setRange(0, size, a);
    sw.stop();
    pureTimes.add(sw.elapsedMicroseconds);
  }

  int med(List<int> xs) {
    final s = List<int>.from(xs)..sort();
    return s[s.length ~/ 2];
  }

  final v = med(viewTimes);
  final c = med(copyTimes);
  final pu = med(pureTimes);
  print('64MB 单次（中位数, $reps 次）:');
  print('  A 零拷贝 asTypedList 映射 : ${v}us  (${(v / 1000).toStringAsFixed(2)}ms)');
  print('  B 拷贝到 Dart 堆          : ${c}us  (${(c / 1000).toStringAsFixed(2)}ms)');
  print('  C 纯 Dart 堆内 setRange   : ${pu}us  (${(pu / 1000).toStringAsFixed(2)}ms)');
  print('  B/C 比 = ${(c / pu).toStringAsFixed(2)}x   B/A 比 = ${(c / (v == 0 ? 1 : v)).toStringAsFixed(0)}x');
  print('  B 的有效带宽 = ${(size / 1024 / 1024 / (c / 1e6) / 1024).toStringAsFixed(2)} GB/s');
  final perMb = c / (size / 1024 / 1024);
  print('  每 MB 拷贝成本 ≈ ${perMb.toStringAsFixed(1)}us  → 一张 8MB 图 ≈ ${(perMb * 8 / 1000).toStringAsFixed(2)}ms');
}
