package ceui.pixiv.image;

import coil3.network.okhttp.OkHttpNetworkFetcher;
import coil3.network.NetworkFetcher;
import okhttp3.Call;
import okio.Path;
import java.io.File;

/**
 * Bridge to access Coil + Okio APIs that Kotlin 2.1.20 has resolution issues
 * with (KMP JAR file facades and @JvmName companion extensions).
 */
public class CoilFactoryBridge {
    public static NetworkFetcher.Factory createImageFetcherFactory(Call.Factory callFactory) {
        return OkHttpNetworkFetcher.factory(callFactory);
    }

    public static Path okioPathFromFile(File file) {
        return Path.get(file);
    }
}
