// 手写 dart:ffi 路线的实测脚本。用 dart 直接跑（不需要 Flutter engine / Xcode）。
// 覆盖：同步阻塞、tokio 卸载、持续数据流吞吐、零拷贝 vs 拷贝的字节开销。

import 'dart:ffi' as ffi;
import 'dart:io' show Platform;
import 'dart:isolate';
import 'dart:async';
import 'dart:typed_data';

typedef _AddC = ffi.Int64 Function(ffi.Int64, ffi.Int64);
typedef _AddD = int Function(int, int);

typedef _BlockCpuSyncC = ffi.Int64 Function(ffi.Uint32);
typedef _BlockCpuSyncD = int Function(int);

typedef _WorkersC = ffi.IntPtr Function();
typedef _WorkersD = int Function();

typedef _Emit = ffi.Void Function(ffi.Pointer<ffi.Uint8>, ffi.IntPtr);
typedef _AsyncCpuC = ffi.Void Function(
    ffi.Pointer<ffi.NativeFunction<_Emit>>, ffi.Uint32);
typedef _AsyncCpuD = void Function(
    ffi.Pointer<ffi.NativeFunction<_Emit>>, int);

typedef _AsyncIoC = ffi.Void Function(
    ffi.Pointer<ffi.NativeFunction<_Emit>>, ffi.Uint64);
typedef _AsyncIoD = void Function(
    ffi.Pointer<ffi.NativeFunction<_Emit>>, int);

typedef _StartStreamC = ffi.Void Function(
    ffi.Pointer<ffi.NativeFunction<_Emit>>, ffi.Uint64, ffi.Uint64, ffi.IntPtr);
typedef _StartStreamD = void Function(
    ffi.Pointer<ffi.NativeFunction<_Emit>>, int, int, int);

typedef _FloodC = ffi.Void Function(
    ffi.Pointer<ffi.NativeFunction<_Emit>>, ffi.Uint64, ffi.IntPtr);
typedef _FloodD = void Function(
    ffi.Pointer<ffi.NativeFunction<_Emit>>, int, int);

typedef _CountC = ffi.Uint64 Function();
typedef _CountD = int Function();

typedef _AllocC = ffi.Pointer<ffi.Uint8> Function(ffi.IntPtr, ffi.Uint8);
typedef _AllocD = ffi.Pointer<ffi.Uint8> Function(int, int);

typedef _FreeC = ffi.Void Function(ffi.Pointer<ffi.Uint8>, ffi.IntPtr);
typedef _FreeD = void Function(ffi.Pointer<ffi.Uint8>, int);

typedef _SumC = ffi.Uint64 Function(ffi.Pointer<ffi.Uint8>, ffi.IntPtr);
typedef _SumD = int Function(ffi.Pointer<ffi.Uint8>, int);

typedef _FillC = ffi.Void Function(ffi.Pointer<ffi.Uint8>, ffi.IntPtr, ffi.Uint8);
typedef _FillD = void Function(ffi.Pointer<ffi.Uint8>, int, int);

class Bridge {
  late final ffi.DynamicLibrary _lib;
  late final _AddD add;
  late final _BlockCpuSyncD blockCpuSync;
  late final _WorkersD tokioWorkers;
  late final _AsyncCpuD asyncCpu;
  late final _AsyncIoD asyncIo;
  late final _StartStreamD startStream;
  late final _FloodD flood;
  late final _CountD streamCount;
  late final _AllocD allocBytes;
  late final _FreeD freeBytes;
  late final _SumD sumBytes;
  late final _FillD fillBytes;

  Bridge(String path) {
    _lib = ffi.DynamicLibrary.open(path);
    add = _lib.lookupFunction<_AddC, _AddD>('add');
    blockCpuSync =
        _lib.lookupFunction<_BlockCpuSyncC, _BlockCpuSyncD>('block_cpu_sync');
    tokioWorkers = _lib.lookupFunction<_WorkersC, _WorkersD>('tokio_workers');
    asyncCpu = _lib.lookupFunction<_AsyncCpuC, _AsyncCpuD>('async_cpu');
    asyncIo = _lib.lookupFunction<_AsyncIoC, _AsyncIoD>('async_io');
    startStream = _lib.lookupFunction<_StartStreamC, _StartStreamD>('start_stream');
    flood = _lib.lookupFunction<_FloodC, _FloodD>('flood');
    streamCount = _lib.lookupFunction<_CountC, _CountD>('stream_count');
    allocBytes = _lib.lookupFunction<_AllocC, _AllocD>('alloc_bytes');
    freeBytes = _lib.lookupFunction<_FreeC, _FreeD>('free_bytes');
    sumBytes = _lib.lookupFunction<_SumC, _SumD>('sum_bytes');
    fillBytes = _lib.lookupFunction<_FillC, _FillD>('fill_bytes');
  }
}

/// 把一个 Dart 回调包成 C 函数指针，供 Rust 侧跨线程调用。
/// NativeCallable.listener 允许被任意线程多次调用（_isolate 版本不行）。
class Emitter {
  final void Function(Uint8List?) onFrame;
  late final ffi.NativeCallable<_Emit> _callable;
  ffi.Pointer<ffi.NativeFunction<_Emit>> get nativeFunction =>
      _callable.nativeFunction;

  Emitter(this.onFrame) {
    _callable = ffi.NativeCallable<_Emit>.listener(_emit);
  }

  void _emit(ffi.Pointer<ffi.Uint8> ptr, int len) {
    if (len < 0) {
      onFrame(null); // 结束哨兵
      return;
    }
    // 这一行就是「拷贝路径」的成本所在：从 Rust 内存复制成 Dart Uint8List。
    onFrame(Uint8List.fromList(ptr.asTypedList(len)));
  }

  void close() => _callable.close();
}

Future<void> main() async {
  final dylibPath = Platform.environment['PROBE_DYLIB'] ??
      '/Users/he/workspace/git_repo/Pixiv-Shaft-Desktop/.verify/probe/target/release/libprobe_bridge.dylib';
  final b = Bridge(dylibPath);

  print('== 环境 ==');
  print('dart:ffi dylib = $dylibPath');
  print('add(2,3) = ${b.add(2, 3)}');
  print('tokio worker 线程数 = ${b.tokioWorkers()}');

  // ---- 1. 同步阻塞：直接占用调用线程 ----
  print('\n== 1. 同步调用会占用调用线程 ==');
  final sw = Stopwatch()..start();
  b.blockCpuSync(300);
  print('block_cpu_sync(300ms) 阻塞调用线程 ${sw.elapsedMilliseconds}ms'
      '（等价于在 UI isolate 上跑 300ms，掉帧 18 帧）');

  // ---- 2. 异步卸载到 tokio ----
  print('\n== 2. 异步卸载到 tokio ==');
  final cpuDone = Completer<String>();
  final cpuEmitter = Emitter((frame) {
    if (frame == null) return;
    if (!cpuDone.isCompleted) {
      cpuDone.complete(String.fromCharCodes(frame));
    }
  });
  final swAsync = Stopwatch()..start();
  b.asyncCpu(cpuEmitter.nativeFunction, 300);
  final returnedAfter = swAsync.elapsedMicroseconds;
  final cpuResult = await cpuDone.future;
  print('async_cpu 调用返回耗时 ${returnedAfter}us（未被阻塞）');
  print('async_cpu 结果 ${cpuResult.substring(0, 12)}… '
      '总耗时 ${swAsync.elapsedMilliseconds}ms');

  final ioDone = Completer<String>();
  final ioEmitter = Emitter((frame) {
    if (frame != null && !ioDone.isCompleted) {
      ioDone.complete(String.fromCharCodes(frame));
    }
  });
  await Future<void>.delayed(const Duration(milliseconds: 10));
  b.asyncIo(ioEmitter.nativeFunction, 100);
  print('async_io 结果 = ${await ioDone.future}');
  cpuEmitter.close();
  ioEmitter.close();

  // ---- 3. 持续数据流吞吐 & UI 影响 ----
  print('\n== 3. 持续数据流（下载进度模型）==');
  await _streamBench(b, frames: 2000, intervalUs: 0, payloadLen: 64, label: '背压缺失 flood 2k 帧/64B');
  await _streamBench(b, frames: 2000, intervalUs: 500, payloadLen: 64, label: '节流 2k 帧/500us/64B');
  await _streamBench(b, frames: 200, intervalUs: 0, payloadLen: 262144, label: '图片块 200 帧/256KB');

  // ---- 4. UI isolate 是否被数据流压垮 ----
  print('\n== 4. 数据流洪峰期间 UI isolate 是否掉帧 ==');
  await _uiResponsiveness(b);

  // ---- 5. 零拷贝 vs 拷贝 ----
  print('\n== 5. 大字节流：零拷贝 vs 拷贝 ==');
  _bytesBench(b);

  print('\n全部实测完成。');
}

Future<void> _streamBench(
  Bridge b, {
  required int frames,
  required int intervalUs,
  required int payloadLen,
  required String label,
}) async {
  final done = Completer<void>();
  int received = 0;
  final emitter = Emitter((frame) {
    if (frame == null) {
      if (!done.isCompleted) done.complete();
      return;
    }
    received++;
  });
  final sw = Stopwatch()..start();
  b.startStream(emitter.nativeFunction, frames, intervalUs, payloadLen);
  await done.future;
  final ms = sw.elapsedMilliseconds;
  final fps = ms > 0 ? frames * 1000 / ms : 0.0;
  print('$label: 收到 $received 帧, 耗时 ${ms}ms, '
      '${fps.toStringAsFixed(0)} 帧/秒, '
      '单帧 ${ms > 0 ? (ms * 1000 / frames).toStringAsFixed(1) : '?'}us');
  emitter.close();
}

/// 洪峰期间同时用 Timer 测量 UI isolate 的事件循环延迟。
Future<void> _uiResponsiveness(Bridge b) async {
  const frames = 50000;
  final done = Completer<void>();
  final emitter = Emitter((frame) {
    if (frame == null && !done.isCompleted) done.complete();
  });

  // 用 16ms 周期模拟 UI 帧调度，统计实际抖动
  final delays = <int>[];
  final timer = Timer.periodic(const Duration(milliseconds: 16), (t) {
    delays.add(DateTime.now().microsecondsSinceEpoch);
  });

  final sw = Stopwatch()..start();
  b.flood(emitter.nativeFunction, frames, 4096);
  await done.future;
  timer.cancel();
  final ms = sw.elapsedMilliseconds;

  if (delays.length > 2) {
    final gaps = <int>[];
    for (var i = 1; i < delays.length; i++) {
      gaps.add(delays[i] - delays[i - 1]);
    }
    gaps.sort();
    final maxGap = gaps.last;
    final p50 = gaps[gaps.length ~/ 2];
    print('洪峰 ${frames} 帧 x 4KB 期间：共 ${delays.length} 次 UI tick, '
        '间隔中位数 ${p50}us, 最大 ${maxGap}us '
        '(${maxGap > 50000 ? '→ UI 明显卡顿' : '→ UI 未卡死'})');
  } else {
    print('洪峰 ${frames} 帧完成，耗时 ${ms}ms（tick 样本不足）');
  }
  print('flood 吞吐：${(frames * 1000 / (ms == 0 ? 1 : ms)).toStringAsFixed(0)} 帧/秒, '
      '有效带宽 ${(frames * 4096 / 1024 / 1024 * 1000 / (ms == 0 ? 1 : ms)).toStringAsFixed(0)} MB/s');
  emitter.close();
}

void _bytesBench(Bridge b) {
  const int size = 64 * 1024 * 1024; // 64MB，模拟一批大图
  final sw = Stopwatch();

  // 零拷贝：Rust 分配 → Dart 用 asTypedList 直接读（不拥有内存）
  sw.start();
  final ptr = b.allocBytes(size, 0xAB);
  final view = ptr.asTypedList(size);
  final zeroCopyUs = sw.elapsedMicroseconds;
  final sum = b.sumBytes(ptr, size);
  sw.stop();
  print('零拷贝: asTypedList 映射 ${size ~/ 1024 ~/ 1024}MB 用 ${zeroCopyUs}us, '
      '校验和 ${sum == 0xAB * size ? "OK" : "MISMATCH"}');
  b.freeBytes(ptr, size);

  // 拷贝路径：Dart 分配 → Rust 填充 → Dart 复制成新 Uint8List
  sw.reset();
  sw.start();
  final scratch = ffi.Pointer<ffi.Uint8>.fromAddress(
      b.allocBytes(size, 0).address); // Dart 侧的堆外缓冲
  b.fillBytes(scratch, size, 0xAB);
  final copied = Uint8List.fromList(scratch.asTypedList(size));
  final copyUs = sw.elapsedMicroseconds;
  sw.stop();
  print('拷贝路径: 填充+复制 ${size ~/ 1024 ~/ 1024}MB 用 ${copyUs}us, '
      '得到 ${copied.length ~/ 1024 ~/ 1024}MB Dart 堆对象');
  b.freeBytes(scratch, size);

  print('比值: 拷贝/零拷贝 = ${(copyUs / (zeroCopyUs == 0 ? 1 : zeroCopyUs)).toStringAsFixed(1)}x');
}
