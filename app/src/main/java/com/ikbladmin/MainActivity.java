package com.ikbladmin;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StrictMode;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStructure;
import android.view.Window;
import android.view.WindowManager;
import android.view.autofill.AutofillValue;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ValueCallback<Uri[]> uploadMessage;
    private static final int FCR = 1;
    private static final int REQUEST_PERMISSIONS = 100;

    // Untuk kamera
    private Uri cameraImageUri = null;
    private boolean isCameraRequest = false;

    private static final String APP_URL = "https://curva.web.id/ikbl-presensi/";
    private static final String APP_HOST = "curva.web.id";

    @TargetApi(Build.VERSION_CODES.O)
    private static class NoAutofillWebView extends WebView {
        public NoAutofillWebView(Context context) { super(context); }
        @Override public int getImportantForAutofill() { return View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS; }
        @Override public void onProvideAutofillVirtualStructure(ViewStructure structure, int flags) {}
        @Override public void autofill(SparseArray<AutofillValue> values) {}
    }

    public class WebAppInterface {
        Context mContext;
        WebAppInterface(Context c) { mContext = c; }

        @JavascriptInterface
        public void downloadPDFBase64(String base64Data, String fileName) {
            try {
                String base64 = base64Data.split(",")[1];
                byte[] pdfAsBytes = Base64.decode(base64, 0);
                Uri pdfUri = null;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues contentValues = new ContentValues();
                    contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                    contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
                    contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

                    pdfUri = mContext.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues);
                    if (pdfUri != null) {
                        OutputStream os = mContext.getContentResolver().openOutputStream(pdfUri);
                        if (os != null) {
                            os.write(pdfAsBytes);
                            os.flush();
                            os.close();
                        }
                    }
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    File file = new File(dir, fileName);
                    FileOutputStream os = new FileOutputStream(file, false);
                    os.write(pdfAsBytes);
                    os.flush();
                    os.close();
                    pdfUri = Uri.fromFile(file);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        StrictMode.VmPolicy.Builder builder = new StrictMode.VmPolicy.Builder();
                        StrictMode.setVmPolicy(builder.build());
                    }
                }

                if (pdfUri != null) {
                    final Uri finalUri = pdfUri;
                    ((AppCompatActivity) mContext).runOnUiThread(() -> {
                        Toast.makeText(mContext, "Berhasil! Membuka KTA...", Toast.LENGTH_SHORT).show();
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW);
                            intent.setDataAndType(finalUri, "application/pdf");
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            Intent chooser = Intent.createChooser(intent, "Buka KTA dengan:");
                            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            mContext.startActivity(chooser);
                        } catch (Exception e) {
                            Toast.makeText(mContext, "Tidak ada aplikasi pembaca PDF di HP ini.", Toast.LENGTH_LONG).show();
                        }
                    });
                }
            } catch (Exception e) {
                ((AppCompatActivity) mContext).runOnUiThread(() ->
                    Toast.makeText(mContext, "Gagal menyimpan PDF: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
        }

        @JavascriptInterface
        public void startNativeScanner() {
            ((AppCompatActivity) mContext).runOnUiThread(() -> {
                try {
                    GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                            .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_ALL_FORMATS)
                            .enableAutoZoom()
                            .build();

                    GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(mContext, options);

                    scanner.startScan()
                            .addOnSuccessListener(barcode -> {
                                String rawValue = barcode.getRawValue();
                                if (rawValue != null) {
                                    try {
                                        ToneGenerator toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
                                        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
                                    } catch (Exception e) {}

                                    String safe = rawValue.replace("\\", "\\\\").replace("'", "\\'")
                                            .replace("\n", "").replace("\r", "");
                                    webView.evaluateJavascript("javascript:if(typeof onBarcodeScanned === 'function'){onBarcodeScanned('" + safe + "');}", null);
                                }
                            })
                            .addOnCanceledListener(() -> { })
                            .addOnFailureListener(e -> {
                                Toast.makeText(mContext, "Gagal memuat Scanner: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            });
                } catch (Exception e) {
                    Toast.makeText(mContext, "Error Scanner: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }

        // =====================================
        // BAGIKAN TEKS (UNDANGAN) KE WHATSAPP
        // =====================================
        @JavascriptInterface
        public void shareText(String text) {
            ((AppCompatActivity) mContext).runOnUiThread(() -> {
                try {
                    Intent wa = new Intent(Intent.ACTION_SEND);
                    wa.setType("text/plain");
                    wa.putExtra(Intent.EXTRA_TEXT, text);
                    wa.setPackage("com.whatsapp");
                    mContext.startActivity(wa);
                } catch (Exception e) {
                    try {
                        Intent send = new Intent(Intent.ACTION_SEND);
                        send.setType("text/plain");
                        send.putExtra(Intent.EXTRA_TEXT, text);
                        mContext.startActivity(Intent.createChooser(send, "Bagikan undangan"));
                    } catch (Exception e2) {
                        Toast.makeText(mContext, "Tidak ada aplikasi untuk berbagi.", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setSystemBarColors();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#79c5e7"));

        View bottomWhiteBg = new View(this);
        bottomWhiteBg.setBackgroundColor(Color.WHITE);
        int navHeight = (int) (100 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, navHeight);
        bottomParams.gravity = Gravity.BOTTOM;
        root.addView(bottomWhiteBg, bottomParams);

        FrameLayout contentWrapper = new FrameLayout(this);
        contentWrapper.setFitsSystemWindows(true);

        webView = new NoAutofillWebView(this);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.addJavascriptInterface(new WebAppInterface(this), "Android");

        webView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_UP) {
                if (!v.hasFocus()) v.requestFocus();
            }
            return false;
        });

        contentWrapper.addView(webView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(contentWrapper, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // SPLASH SCREEN
        final RelativeLayout splashLayout = new RelativeLayout(this);
        splashLayout.setBackgroundColor(Color.WHITE);

        final VideoView videoView = new VideoView(this);
        String videoPath = "android.resource://" + getPackageName() + "/" + R.raw.splash_video;
        videoView.setVideoURI(Uri.parse(videoPath));

        RelativeLayout.LayoutParams videoParams = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT);
        videoParams.addRule(RelativeLayout.CENTER_IN_PARENT);
        videoView.setLayoutParams(videoParams);
        splashLayout.addView(videoView);

        final View startCover = new View(this);
        startCover.setBackgroundColor(Color.WHITE);
        splashLayout.addView(startCover, new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));

        final View endCover = new View(this);
        endCover.setBackgroundColor(Color.WHITE);
        endCover.setAlpha(0f);
        splashLayout.addView(endCover, new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));

        root.addView(splashLayout, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        disableAutofill(root, contentWrapper);

        videoView.setOnPreparedListener(mp -> {
            mp.setOnInfoListener((mp1, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    startCover.setVisibility(View.GONE);
                    return true;
                }
                return false;
            });
            int videoWidth = mp.getVideoWidth();
            int videoHeight = mp.getVideoHeight();
            int screenWidth = splashLayout.getWidth();
            int screenHeight = splashLayout.getHeight();
            float videoProportion = (float) videoWidth / (float) videoHeight;
            float screenProportion = (float) screenWidth / (float) screenHeight;
            ViewGroup.LayoutParams lp = videoView.getLayoutParams();
            if (videoProportion > screenProportion) {
                lp.width = (int) (screenHeight * videoProportion);
                lp.height = screenHeight;
            } else {
                lp.width = screenWidth;
                lp.height = (int) (screenWidth / videoProportion);
            }
            videoView.setLayoutParams(lp);
        });

        videoView.setOnCompletionListener(mp -> endCover.animate().alpha(1f).setDuration(200).withEndAction(() -> {
            videoView.setVisibility(View.GONE);
            splashLayout.animate().alpha(0f).setDuration(400).withEndAction(() -> splashLayout.setVisibility(View.GONE));
        }));

        videoView.setOnErrorListener((mp, what, extra) -> {
            splashLayout.setVisibility(View.GONE);
            return true;
        });
        videoView.start();

        cekDanMintaIzin();

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setLoadsImagesAutomatically(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webSettings.setSaveFormData(false);
        webSettings.setSavePassword(false);
        webView.clearFormData();

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        }

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                if (url.startsWith("blob:") || url.startsWith("data:")) return;
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);
                String cookies = CookieManager.getInstance().getCookie(url);
                request.addRequestHeader("cookie", cookies);
                request.addRequestHeader("User-Agent", userAgent);
                String fileName = "IKBL_File";
                if (contentDisposition != null) {
                    try {
                        String[] parts = contentDisposition.split("filename=");
                        if (parts.length > 1) fileName = parts[1].replace("\"", "").trim();
                    } catch (Exception e) {}
                }
                request.setTitle(fileName);
                request.allowScanningByMediaScanner();
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(request);
                    Toast.makeText(getApplicationContext(), "Mengunduh file...", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(getApplicationContext(), "Gagal memproses unduhan: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    try {
                        Uri origin = Uri.parse(request.getOrigin().toString());
                        if (!APP_HOST.equals(origin.getHost())) { request.deny(); return; }
                        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            String[] resources = request.getResources();
                            java.util.ArrayList<String> allowed = new java.util.ArrayList<>();
                            for (String res : resources) {
                                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) allowed.add(res);
                            }
                            if (!allowed.isEmpty()) request.grant(allowed.toArray(new String[0]));
                            else request.deny();
                        } else request.deny();
                    } catch (Exception e) { request.deny(); }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (uploadMessage != null) { uploadMessage.onReceiveValue(null); uploadMessage = null; }
                uploadMessage = filePathCallback;
                isCameraRequest = false;
                cameraImageUri = null;

                boolean wantCamera = fileChooserParams != null && fileChooserParams.isCaptureEnabled();

                // ===== MODE KAMERA =====
                if (wantCamera) {
                    if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.CAMERA}, REQUEST_PERMISSIONS);
                        Toast.makeText(MainActivity.this, "Izinkan akses kamera, lalu klik Kamera lagi.", Toast.LENGTH_LONG).show();
                        uploadMessage.onReceiveValue(null);
                        uploadMessage = null;
                        return true;
                    }
                    try {
                        File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                        if (dir != null && !dir.exists()) dir.mkdirs();
                        File photoFile = File.createTempFile("IKBL_" + System.currentTimeMillis(), ".jpg", dir);
                        cameraImageUri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", photoFile);

                        Intent camIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        camIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri);
                        camIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                        isCameraRequest = true;
                        startActivityForResult(camIntent, FCR);
                        return true;
                    } catch (IOException | RuntimeException e) {
                        Toast.makeText(MainActivity.this, "Gagal membuka kamera: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        isCameraRequest = false;
                        uploadMessage.onReceiveValue(null);
                        uploadMessage = null;
                        return true;
                    }
                }

                // ===== MODE GALERI (langsung ke galeri) =====
                try {
                    Intent galleryIntent = new Intent(Intent.ACTION_PICK);
                    galleryIntent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
                    startActivityForResult(galleryIntent, FCR);
                    return true;
                } catch (Exception e) {
                    // Fallback ke pemilih file jika tidak ada aplikasi galeri
                    try {
                        Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                        fallback.addCategory(Intent.CATEGORY_OPENABLE);
                        fallback.setType("image/*");
                        startActivityForResult(Intent.createChooser(fallback, "Pilih Foto"), FCR);
                        return true;
                    } catch (Exception e2) {
                        uploadMessage.onReceiveValue(null);
                        uploadMessage = null;
                        return true;
                    }
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    view.loadUrl(url); return true;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    return true;
                } catch (Exception e) { return false; }
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
                disableAutofill(null, null);
            }
        });

        webView.loadUrl(APP_URL);
    }

    private void cekDanMintaIzin() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_PERMISSIONS);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_PERMISSIONS);
            }
        }
    }

    private void disableAutofill(View root, View contentWrapper) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getWindow().getDecorView().setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            if (webView != null) webView.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            if (root != null) root.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            if (contentWrapper != null) contentWrapper.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }
    }

    private void setSystemBarColors() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(Color.parseColor("#79c5e7"));
            window.setNavigationBarColor(Color.WHITE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                int flags = window.getDecorView().getSystemUiVisibility();
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                window.getDecorView().setSystemUiVisibility(flags);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FCR) {
            if (uploadMessage == null) return;
            Uri[] results = null;

            if (resultCode == RESULT_OK) {
                if (isCameraRequest) {
                    if (cameraImageUri != null) results = new Uri[]{cameraImageUri};
                } else if (data != null) {
                    String dataString = data.getDataString();
                    if (dataString != null) results = new Uri[]{Uri.parse(dataString)};
                }
            }

            uploadMessage.onReceiveValue(results);
            uploadMessage = null;
            isCameraRequest = false;
            cameraImageUri = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        if (uploadMessage != null) { uploadMessage.onReceiveValue(null); uploadMessage = null; }
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
