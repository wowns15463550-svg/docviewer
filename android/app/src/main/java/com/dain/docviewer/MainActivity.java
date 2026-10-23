package com.dain.docviewer;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final int REQ_PICK = 1001;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    private volatile Uri currentUri;
    private volatile String currentMime = "application/octet-stream";
    private volatile long token = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(0xFFF5C400);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        web = new WebView(this);
        setContentView(web);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/intent/", new WebViewAssetLoader.PathHandler() {
                    @Override
                    public WebResourceResponse handle(String path) {
                        return openCurrent();
                    }
                })
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // 앱 안의 주소만 허용하고 바깥 사이트로는 이동하지 않음
                return !HOST.equals(request.getUrl().getHost());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(pick, "파일 선택"), REQ_PICK);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "NativeBridge");

        handleIntent(getIntent());
        web.loadUrl("https://" + HOST + "/assets/index.html");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (handleIntent(intent)) {
            web.evaluateJavascript("window.__onIntentFile && window.__onIntentFile()", null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && fileCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    /** 파일 앱에서 넘어온 인텐트에서 파일 주소를 꺼낸다. 파일이 있으면 true */
    @SuppressWarnings("deprecation")
    private boolean handleIntent(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(action)) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(action)) {
            uri = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri == null) return false;

        String type = intent.getType();
        if (type == null || type.isEmpty() || type.contains("*")) {
            type = getContentResolver().getType(uri);
        }
        currentUri = uri;
        currentMime = (type != null && !type.isEmpty()) ? type : "application/octet-stream";
        token++;
        return true;
    }

    private WebResourceResponse openCurrent() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-store");
        Uri uri = currentUri;
        if (uri != null) {
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in != null) {
                    return new WebResourceResponse(currentMime, null, 200, "OK", headers, in);
                }
            } catch (Exception ignored) {
            }
        }
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers,
                new ByteArrayInputStream(new byte[0]));
    }

    private String displayName(Uri uri) {
        String name = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    name = c.getString(0);
                }
            } catch (Exception ignored) {
            }
        }
        if (name == null || name.isEmpty()) {
            name = uri.getLastPathSegment();
        }
        if (name == null || name.isEmpty()) {
            name = "file";
        }
        // 확장자가 없으면 형식(mime)으로 추정해서 붙여 준다
        if (!name.contains(".")) {
            String m = currentMime;
            String ext = null;
            if ("application/pdf".equals(m)) ext = "pdf";
            else if (m.contains("spreadsheetml.sheet")) ext = "xlsx";
            else if (m.equals("application/vnd.ms-excel")) ext = "xls";
            else if (m.contains("csv")) ext = "csv";
            else if (m.equals("image/jpeg")) ext = "jpg";
            else if (m.equals("image/png")) ext = "png";
            else if (m.equals("image/webp")) ext = "webp";
            else if (m.equals("image/gif")) ext = "gif";
            else if (m.contains("hwp+zip") || m.contains("hwpx")) ext = "hwpx";
            else if (m.contains("hwp")) ext = "hwp";
            if (ext != null) name = name + "." + ext;
        }
        return name;
    }

    private class Bridge {
        @JavascriptInterface
        public String getIntentInfo() {
            Uri uri = currentUri;
            if (uri == null) return "null";
            try {
                JSONObject o = new JSONObject();
                o.put("name", displayName(uri));
                o.put("mime", currentMime);
                o.put("t", token);
                return o.toString();
            } catch (Exception e) {
                return "null";
            }
        }
    }
}
