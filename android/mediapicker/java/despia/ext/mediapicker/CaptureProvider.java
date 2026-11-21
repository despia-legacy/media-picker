package despia.ext.mediapicker;

import androidx.core.content.FileProvider;

/**
 * Hands the system camera app a write URI for one capture (TakePicture / CaptureVideo), and nothing
 * else: not exported, URI grants only, and its paths file (res/xml/nx_mediapicker_paths.xml) names only
 * files/nativeext/mediapicker/capture/. Its own subclass, so its authority
 * (&lt;package&gt;.nx.mediapicker.capture) never collides with the base app's or another extension's.
 */
public final class CaptureProvider extends FileProvider {
}
