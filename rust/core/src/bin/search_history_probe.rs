//! 用独立的真实 SQLite 文件验证搜索记录的写入、排序与分组操作。

fn main() {
    pixiv_core::db::use_database("rust/core/target/search-history-probe.db".into());
    pixiv_core::history::clear_searches().expect("清理验证数据库的搜索记录");

    pixiv_core::history::record_search("先搜索", 0).expect("记录第一个关键词");
    std::thread::sleep(std::time::Duration::from_millis(3));
    pixiv_core::history::record_search("后搜索", 0).expect("记录第二个关键词");
    let entries = pixiv_core::history::list_searches(50).expect("读取搜索记录");
    assert_eq!(entries.len(), 2, "搜索记录数量不正确");
    assert_eq!(entries[0].keyword, "后搜索", "搜索记录排序不正确");
    assert!(
        entries[0].search_time > 1_000_000_000_000,
        "时间单位不是毫秒"
    );

    let pinned_id = entries[1].id;
    pixiv_core::history::set_search_pinned(pinned_id, true).expect("置顶搜索记录");
    pixiv_core::history::record_search("先搜索", 0).expect("重复搜索置顶关键词");
    let entries = pixiv_core::history::list_searches(50).expect("读取更新后的搜索记录");
    assert_eq!(entries.len(), 2, "重复搜索产生了新记录");
    assert_eq!(entries[0].id, pinned_id, "重复搜索替换了置顶记录");
    assert!(entries[0].pinned, "重复搜索丢失了置顶状态");

    pixiv_core::history::clear_searches_group(false).expect("清空最近搜索");
    let entries = pixiv_core::history::list_searches(50).expect("读取置顶搜索");
    assert_eq!(entries.len(), 1, "清空最近搜索影响了置顶记录");
    assert!(entries[0].pinned, "剩余记录未置顶");
    pixiv_core::history::clear_searches_group(true).expect("清空置顶搜索");
    assert!(
        pixiv_core::history::list_searches(50)
            .expect("读取清空后的搜索记录")
            .is_empty(),
        "清空后仍有搜索记录"
    );

    pixiv_core::history::record_browse(pixiv_core::history::TYPE_ILLUST, 42, "{}")
        .expect("记录浏览历史");
    let browse = pixiv_core::history::list_browse(pixiv_core::history::TYPE_ILLUST, 50, 0)
        .expect("读取浏览历史");
    let sample = browse
        .iter()
        .find(|entry| entry.target_id == 42)
        .expect("缺少浏览记录");
    assert!(sample.viewed_at > 1_000_000_000_000, "浏览时间单位不是毫秒");

    let report = "搜索与浏览时间为毫秒；搜索排序、重复关键词、保留置顶、分组清空均通过\n";
    print!("{report}");
    std::fs::write("rust/core/target/search-history-probe.txt", report)
        .expect("写入搜索记录验证结果");
}
