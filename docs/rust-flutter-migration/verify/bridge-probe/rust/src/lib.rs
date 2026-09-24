//! 手写 dart:ffi 路线的模型实现，用于实测：
//! 1. tokio runtime 是否独立于 Dart isolate
//! 2. 持续数据流（Stream）的吞吐与开销
//! 3. 大字节流的零拷贝路径
//! 回调约定：void (*)(const uint8_t* ptr, intptr_t len)，len == -1 表示流结束。
//! Dart 侧用 NativeCallable.listener 把这个 C 函数指针交给 Rust。

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::OnceLock;
use std::time::{Duration, Instant};
use tokio::runtime::{Builder, Runtime};

static RT: OnceLock<Runtime> = OnceLock::new();

/// 全局 tokio runtime。故意泄漏到 'static（跨 FFI 边界无法安全 Drop）。
/// 关键点：tokio 的 worker 线程由 tokio 自己创建，与 Dart 的任何 isolate 无关。
fn rt() -> &'static Runtime {
    RT.get_or_init(|| {
        Builder::new_multi_thread()
            .worker_threads(4)
            .enable_all()
            .build()
            .expect("tokio runtime")
    })
}

type EmitFn = extern "C" fn(*const u8, isize);

static STREAM_COUNT: AtomicU64 = AtomicU64::new(0);

// ---------------------------------------------------------------------------
// 同步调用：占用调用方线程
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "C" fn add(a: i64, b: i64) -> i64 {
    a + b
}

fn spin(ms: u32) -> u64 {
    let start = Instant::now();
    let mut x: u64 = 0x243F6A8885A308D3;
    while start.elapsed() < Duration::from_millis(ms as u64) {
        x = x.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
    }
    x
}

/// 同步 CPU 密集调用：直接在调用方线程（Dart UI isolate）上跑，会卡 UI。
#[no_mangle]
pub extern "C" fn block_cpu_sync(ms: u32) -> i64 {
    spin(ms) as i64
}

/// tokio worker 线程数，证明 runtime 是多线程且独立存在。
#[no_mangle]
pub extern "C" fn tokio_workers() -> usize {
    rt().metrics().num_workers()
}

// ---------------------------------------------------------------------------
// 异步：卸载到 tokio，结果通过回调回报
// ---------------------------------------------------------------------------

/// 异步 CPU 工作：调用线程立刻返回，实际工作在 tokio 的阻塞池外执行。
#[no_mangle]
pub extern "C" fn async_cpu(cb: EmitFn, ms: u32) {
    rt().spawn(async move {
        // CPU 密集任务显式交给 spawn_blocking，避免饿死 tokio 的 async worker
        let v = tokio::task::spawn_blocking(move || spin(ms))
            .await
            .expect("spawn_blocking");
        let msg = format!("cpu:{}", v);
        cb(msg.as_ptr(), msg.len() as isize);
    });
}

/// 异步 IO 模拟：tokio 睡眠（等价于等一次网络往返）。
#[no_mangle]
pub extern "C" fn async_io(cb: EmitFn, ms: u64) {
    rt().spawn(async move {
        tokio::time::sleep(Duration::from_millis(ms)).await;
        let msg = "io:ok";
        cb(msg.as_ptr(), msg.len() as isize);
    });
}

// ---------------------------------------------------------------------------
// 持续数据流（下载进度 / 分页 / 图片字节流）
// ---------------------------------------------------------------------------

/// frames 帧、每帧 interval_us 微秒、每帧 payload_len 字节。
/// 每一帧一次回调 + 一次 memcpy（Dart 侧若用 Uint8List.fromList）。
#[no_mangle]
pub extern "C" fn start_stream(cb: EmitFn, frames: u64, interval_us: u64, payload_len: usize) {
    rt().spawn(async move {
        let payload = vec![b'x'; payload_len];
        for _ in 0..frames {
            if interval_us > 0 {
                tokio::time::sleep(Duration::from_micros(interval_us)).await;
            }
            cb(payload.as_ptr(), payload.len() as isize);
            STREAM_COUNT.fetch_add(1, Ordering::Relaxed);
        }
        cb(std::ptr::null(), -1); // 结束哨兵
    });
}

#[no_mangle]
pub extern "C" fn stream_count() -> u64 {
    STREAM_COUNT.load(Ordering::Relaxed)
}

/// 无节流地狂发：用来观察 UI isolate 会不会被回调压垮（背压缺失的后果）。
#[no_mangle]
pub extern "C" fn flood(cb: EmitFn, frames: u64, payload_len: usize) {
    rt().spawn(async move {
        let payload = vec![b'x'; payload_len];
        for _ in 0..frames {
            cb(payload.as_ptr(), payload.len() as isize);
            STREAM_COUNT.fetch_add(1, Ordering::Relaxed);
        }
        cb(std::ptr::null(), -1);
    });
}

// ---------------------------------------------------------------------------
// 大字节流：零拷贝 vs 拷贝
// ---------------------------------------------------------------------------

/// 分配一块内存并泄漏所有权，返回裸指针；Dart 用 asTypedList 直接读，0 次 memcpy。
#[no_mangle]
pub extern "C" fn alloc_bytes(len: usize, fill: u8) -> *mut u8 {
    let mut v = vec![fill; len];
    let p = v.as_mut_ptr();
    std::mem::forget(v);
    p
}

/// 归还 alloc_bytes 的内存。忘记调用就泄漏。
#[no_mangle]
pub extern "C" fn free_bytes(ptr: *mut u8, len: usize) {
    if ptr.is_null() {
        return;
    }
    drop(unsafe { Vec::from_raw_parts(ptr, len, len) });
}

/// 让 Rust 读一遍 Dart 给过来的内存，防止 Dart 侧把读取优化掉（基准测试用）。
#[no_mangle]
pub extern "C" fn sum_bytes(ptr: *const u8, len: usize) -> u64 {
    if ptr.is_null() {
        return u64::MAX;
    }
    let s = unsafe { std::slice::from_raw_parts(ptr, len) };
    s.iter().map(|b| *b as u64).sum()
}

/// 拷贝路径的对照：Dart 侧分配 → Rust 填充 → Dart 再拷一份。
#[no_mangle]
pub extern "C" fn fill_bytes(ptr: *mut u8, len: usize, fill: u8) {
    if ptr.is_null() {
        return;
    }
    let s = unsafe { std::slice::from_raw_parts_mut(ptr, len) };
    for b in s.iter_mut() {
        *b = fill;
    }
}
