package za.slipwise.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Slipwise: a WebView that runs the budget app from the bundled assets.
 * The app is served from https://appassets.androidplatform.net so it gets a
 * proper secure origin (IndexedDB storage, and calls to the Claude API).
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String HOME = "https://" + HOST + "/assets/index.html";
    private static final int REQ_FILE = 1;
    private static final int REQ_STORAGE = 2;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;

    // A save waiting for the storage permission (Android 9 and older)
    private String pendingName, pendingB64, pendingMime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                // Links to other sites (e.g. console.anthropic.com) open in the phone's browser
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;

                boolean wantsImage = false;
                for (String t : params.getAcceptTypes()) {
                    if (t != null && t.startsWith("image")) wantsImage = true;
                }

                Intent intent = null;
                if (params.isCaptureEnabled()) intent = cameraIntent();
                if (intent == null) intent = pickIntent(wantsImage ? "image/*" : "*/*");

                try {
                    startActivityForResult(intent, REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    // No camera app: fall back to picking a photo
                    try {
                        startActivityForResult(pickIntent(wantsImage ? "image/*" : "*/*"), REQ_FILE);
                    } catch (ActivityNotFoundException e2) {
                        fileCallback.onReceiveValue(null);
                        fileCallback = null;
                        return false;
                    }
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(HOME);
        }
    }

    private Intent cameraIntent() {
        try {
            File dir = new File(getCacheDir(), "camera");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, "slip-" + System.currentTimeMillis() + ".jpg");
            cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return i;
        } catch (Exception e) {
            cameraUri = null;
            return null;
        }
    }

    private Intent pickIntent(String type) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(type);
        return i;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            } else if (cameraUri != null) {
                result = new Uri[]{cameraUri};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
        cameraUri = null;
    }

    /** Called from the page: window.AndroidBridge.saveFile(name, base64, mime) */
    private class Bridge {
        @JavascriptInterface
        public void saveFile(final String name, final String b64, final String mime) {
            runOnUiThread(() -> requestSave(name, b64, mime));
        }
    }

    private void requestSave(String name, String b64, String mime) {
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingName = name;
            pendingB64 = b64;
            pendingMime = mime;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        writeFile(name, b64, mime);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_STORAGE || pendingName == null) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            writeFile(pendingName, pendingB64, pendingMime);
        } else {
            Toast.makeText(this, "Allow storage access to save files", Toast.LENGTH_LONG).show();
        }
        pendingName = pendingB64 = pendingMime = null;
    }

    private void writeFile(String name, String b64, String mime) {
        boolean isImage = mime != null && mime.startsWith("image/");
        String folder = isImage ? "Pictures/Slipwise" : "Download/Slipwise";
        try {
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        (isImage ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_DOWNLOADS) + "/Slipwise");
                Uri collection = isImage
                        ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri uri = getContentResolver().insert(collection, cv);
                if (uri == null) throw new Exception("insert failed");
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) throw new Exception("no stream");
                    os.write(bytes);
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        isImage ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_DOWNLOADS), "Slipwise");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File f = new File(dir, name);
                try (FileOutputStream fo = new FileOutputStream(f)) {
                    fo.write(bytes);
                }
                MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, null, null);
            }
            Toast.makeText(this, "Saved to " + folder, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't save the file", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("window.slipwiseBack ? window.slipwiseBack() : false", value -> {
            if (!"true".equals(value)) {
                if (web.canGoBack()) web.goBack();
                else finish();
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }
}
