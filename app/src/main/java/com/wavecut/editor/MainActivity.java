package com.wavecut.editor;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public final class MainActivity extends Activity {
    private static final int REQUEST_OPEN_AUDIO = 1001;
    private static final int REQUEST_SAVE_WAV = 1002;
    private static final int BUFFER_SIZE = 64 * 1024;

    private WebView webView;
    private ValueCallback<Uri[]> fileChooserCallback;
    private File exportTempFile;
    private FileOutputStream exportStream;
    private String exportFilename = "wavecut-export.wav";

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.rgb(10, 14, 24));
        getWindow().setNavigationBarColor(Color.rgb(10, 14, 24));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(10, 14, 24));
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams fileChooserParams) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = callback;

                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("audio/*");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                try {
                    startActivityForResult(intent, REQUEST_OPEN_AUDIO);
                    return true;
                } catch (Exception e) {
                    fileChooserCallback = null;
                    Toast.makeText(MainActivity.this, "No audio picker is available.", Toast.LENGTH_LONG).show();
                    return false;
                }
            }
        });

        webView.addJavascriptInterface(new AndroidBridge(), "Android");
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_OPEN_AUDIO) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                result = new Uri[]{uri};
                try {
                    getContentResolver().takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
                } catch (SecurityException ignored) {
                    // The one-time grant is sufficient when a provider does not support persistence.
                }
            }

            if (fileChooserCallback != null) {
                fileChooserCallback.onReceiveValue(result);
                fileChooserCallback = null;
            }
            return;
        }

        if (requestCode == REQUEST_SAVE_WAV) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && exportTempFile != null) {
                Uri destination = data.getData();
                try (InputStream in = new FileInputStream(exportTempFile);
                     OutputStream out = getContentResolver().openOutputStream(destination, "w")) {
                    if (out == null) {
                        throw new IllegalStateException("Unable to open the selected destination.");
                    }
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                    Toast.makeText(this, "Saved " + exportFilename, Toast.LENGTH_LONG).show();
                    notifyExportResult(true, "Saved successfully");
                } catch (Exception e) {
                    Toast.makeText(this, "Save failed: " + safeMessage(e), Toast.LENGTH_LONG).show();
                    notifyExportResult(false, safeMessage(e));
                }
            } else {
                notifyExportResult(false, "Save cancelled");
            }
            cleanupExportFile();
        }
    }

    private void notifyExportResult(boolean ok, String message) {
        if (webView == null) return;
        String safe = message == null ? "" : message.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
        String script = "window.WaveCut && window.WaveCut.onNativeExportResult(" + ok + ", '" + safe + "');";
        webView.post(() -> webView.evaluateJavascript(script, null));
    }

    private static String safeMessage(Exception e) {
        String value = e.getMessage();
        return value == null || value.trim().isEmpty() ? e.getClass().getSimpleName() : value;
    }

    private synchronized void cleanupExportStream() {
        if (exportStream != null) {
            try {
                exportStream.close();
            } catch (Exception ignored) {
            }
            exportStream = null;
        }
    }

    private synchronized void cleanupExportFile() {
        cleanupExportStream();
        if (exportTempFile != null && exportTempFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            exportTempFile.delete();
        }
        exportTempFile = null;
    }

    @Override
    protected void onDestroy() {
        cleanupExportFile();
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
        if (webView != null) {
            webView.removeJavascriptInterface("Android");
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private final class AndroidBridge {
        @JavascriptInterface
        public void toast(String message) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public synchronized boolean beginWavExport(String requestedFilename) {
            cleanupExportFile();
            try {
                String cleaned = requestedFilename == null ? "wavecut-export.wav" : requestedFilename.trim();
                if (cleaned.isEmpty()) cleaned = "wavecut-export.wav";
                if (!cleaned.toLowerCase().endsWith(".wav")) cleaned += ".wav";
                cleaned = cleaned.replaceAll("[\\\\/:*?\"<>|]", "-");
                exportFilename = cleaned;

                exportTempFile = new File(getCacheDir(), "wavecut-pending-export.wav");
                exportStream = new FileOutputStream(exportTempFile, false);
                return true;
            } catch (Exception e) {
                cleanupExportFile();
                return false;
            }
        }

        @JavascriptInterface
        public synchronized boolean appendWavExportChunk(String base64Chunk) {
            if (exportStream == null || base64Chunk == null) return false;
            try {
                byte[] bytes = Base64.decode(base64Chunk, Base64.DEFAULT);
                exportStream.write(bytes);
                return true;
            } catch (Exception e) {
                cleanupExportFile();
                return false;
            }
        }

        @JavascriptInterface
        public synchronized boolean finishWavExport() {
            if (exportStream == null || exportTempFile == null) return false;
            try {
                exportStream.flush();
                exportStream.getFD().sync();
                cleanupExportStream();
            } catch (Exception e) {
                cleanupExportFile();
                return false;
            }

            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("audio/wav");
                intent.putExtra(Intent.EXTRA_TITLE, exportFilename);
                try {
                    startActivityForResult(intent, REQUEST_SAVE_WAV);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "No save location is available.", Toast.LENGTH_LONG).show();
                    notifyExportResult(false, "No save location is available");
                    cleanupExportFile();
                }
            });
            return true;
        }

        @JavascriptInterface
        public synchronized void cancelWavExport() {
            cleanupExportFile();
        }
    }
}
