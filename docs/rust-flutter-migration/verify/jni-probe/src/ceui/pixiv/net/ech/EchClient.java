package ceui.pixiv.net.ech;

import java.io.File;

/**
 * 通过真实 JNI 加载 prebuilt dylib，验证现有 ECH 链路在当前机器上可用。
 *
 * 类名必须是 EchClient：JNI 符号名 Java_ceui_pixiv_net_ech_EchClient_nativeInit
 * 由类名推导，改类名就会 UnsatisfiedLinkError。
 *
 * 用法（仓库根目录）：
 *   javac -d out jni-probe/src/ceui/pixiv/net/ech/EchClient.java
 *   java -Dech.repo.root=$PWD -cp out ceui.pixiv.net.ech.EchClient
 */
public class EchClient {

    static {
        String root = System.getProperty("ech.repo.root");
        File prebuilt = new File(root, "rust/ech/prebuilt/libech.dylib");
        File built = new File(root, "rust/ech/target/release/libech.dylib");
        File chosen = prebuilt.isFile() ? prebuilt : built;
        System.out.println("加载 dylib: " + chosen.getName()
                + " (" + chosen.length() + " 字节)");
        System.load(chosen.getAbsolutePath());
    }

    private static native boolean nativeInit();

    private static native String nativeRequest(
            String method, String url, String[] headers, byte[] body);

    public static void main(String[] args) {
        System.out.println("nativeInit() -> " + nativeInit());

        String[] headers = {
            "user-agentPixivIOSApp/8.6.10 (iOS 26.5; iPhone16,2)",
            "app-osios",
            "app-os-version26.5",
            "app-version8.6.10",
        };
        String[] urls = {
            "https://app-api.pixiv.net/v1/illust/ranking?mode=day&date=2026-09-01",
            "https://www.pixiv.net/ajax/top/illust?mode=all",
        };
        for (String url : urls) {
            System.out.println("--- GET " + url);
            String json = nativeRequest("GET", url, headers, null);
            System.out.println("  " + (json.length() > 300 ? json.substring(0, 300) : json));
        }
    }
}
