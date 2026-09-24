use image::GenericImageView;
use std::time::Instant;

/// 验证 JPEG 解码阶段降采样（DCT scaling）的真实收益。
/// jpeg-decoder 支持 1/1, 1/2, 1/4, 1/8 的解码时缩放，
/// 意味着不必把整图展开进内存就能得到缩小后的位图。
fn main() {
    let path = std::env::args().nth(1).expect("需要 JPEG 路径");
    let bytes = std::fs::read(&path).expect("读取");

    // 整图解码（对照）
    let start = Instant::now();
    let img = image::load_from_memory(&bytes).expect("整图解码");
    let full_ms = start.elapsed().as_millis();
    let (w, h) = img.dimensions();
    let full_px = w as u64 * h as u64;
    println!(
        "整图解码: {}x{} = {} 像素, {} ms, RGBA 内存 {:.1} MB",
        w,
        h,
        full_px,
        full_ms,
        full_px as f64 * 4.0 / 1024.0 / 1024.0
    );

    // 解码阶段降采样：传入目标尺寸，解码器内部选 1/8,1/4,1/2,1 的 DCT 缩放因子
    let full_w = w as u16;
    let full_h = h as u16;
    for (label, div) in [("1/2", 2u16), ("1/4", 4u16), ("1/8", 8u16)] {
        let target_w = full_w / div;
        let target_h = full_h / div;
        let start = Instant::now();
        let mut decoder = jpeg_decoder::Decoder::new(std::io::Cursor::new(&bytes));
        let actual = decoder.scale(target_w, target_h).expect("scale");
        let pixels = decoder.decode().expect("decode");
        let elapsed = start.elapsed();
        let px = actual.0 as u64 * actual.1 as u64;
        let mem = pixels.len() as f64 / 1024.0 / 1024.0;
        println!(
            "  解码时缩放 {}: 请求 {}x{} → 实际 {}x{} = {} 像素, {} ms, 实际缓冲 {:.1} MB (像素减少 {:.0}%)",
            label,
            target_w,
            target_h,
            actual.0,
            actual.1,
            px,
            elapsed.as_millis(),
            mem,
            (1.0 - px as f64 / full_px as f64) * 100.0
        );
    }
}
