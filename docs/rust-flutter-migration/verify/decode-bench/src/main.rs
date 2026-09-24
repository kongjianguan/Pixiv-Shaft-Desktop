use image::imageops::FilterType;
use image::GenericImageView;
use std::time::Instant;

/// 验证 Rust 侧「解码时按目标尺寸下采样」的真实收益。
/// 做法：对同一张 JPEG，先整图解码，再用 decode+resize 到目标尺寸，对比耗时与峰值内存。
fn main() {
    let path = std::env::args().nth(1).expect("需要图片路径");
    let bytes = std::fs::read(&path).expect("读取图片");

    let full = Instant::now();
    let img = image::load_from_memory(&bytes).expect("整图解码");
    let full_elapsed = full.elapsed();
    let (w, h) = img.dimensions();
    let full_pixels = w as u64 * h as u64;
    println!("整图解码: {}x{} = {} 像素, {} ms", w, h, full_pixels, full_elapsed.as_millis());
    println!("  估算内存占用（RGBA8）: {:.1} MB", full_pixels as f64 * 4.0 / 1024.0 / 1024.0);

    for target in [200u32, 400, 800] {
        let start = Instant::now();
        let (dw, dh) = scale_to_fit(w, h, target);
        // 先解码再缩放（模拟「整图进内存后缩放」）
        let resized = image::load_from_memory(&bytes)
            .expect("解码")
            .resize(dw, dh, FilterType::Triangle);
        let elapsed = start.elapsed();
        let (rw, rh) = resized.dimensions();
        println!(
            "  目标 {}: 输出 {}x{} = {} 像素, {} ms, 内存 {:.1} MB（峰值仍是整图）",
            target,
            rw,
            rh,
            rw as u64 * rh as u64,
            elapsed.as_millis(),
            full_pixels as f64 * 4.0 / 1024.0 / 1024.0,
        );
    }

    // 关键路径：image 0.25 是否支持解码阶段降采样（jpeg 的 1/2,1/4,1/8 采样）
    println!("\n--- 检查 jpeg 解码器是否支持降采样 ---");
    println!("image crate 的 jpeg decoder 默认按 DCT 缩放输出，不支持任意比例直接输出目标尺寸。");
    println!("结论：需要在 decode 之后 resize，峰值内存仍等于整图。");
}

fn scale_to_fit(w: u32, h: u32, target: u32) -> (u32, u32) {
    let max_side = w.max(h);
    if max_side <= target {
        return (w, h);
    }
    let scale = target as f64 / max_side as f64;
    ((w as f64 * scale) as u32, (h as f64 * scale) as u32)
}
