package fr.futsalfoot.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.Window;
import android.view.WindowManager;
import androidx.core.content.FileProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {
    private WebView webView;

    private String xmlEscape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private class AndroidBridge {
        @JavascriptInterface
        public void shareWordModel(String club, String commissioner, String security) {
            runOnUiThread(() -> {
                try {
                    File file = new File(getCacheDir(), "rapport-futsal-" + System.currentTimeMillis() + ".docx");
                    String details =
                            "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Club visité : </w:t></w:r>" +
                            "<w:r><w:t>" + xmlEscape(club) + "</w:t></w:r></w:p>";
                    String commissionerLine =
                            "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Commissaire : </w:t></w:r>" +
                            "<w:r><w:t>" + xmlEscape(commissioner) + "</w:t></w:r></w:p>";
                    try (InputStream raw = getAssets().open("rapport-futsal-modele.docx");
                         ZipInputStream zin = new ZipInputStream(raw);
                         ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(file))) {
                        ZipEntry entry;
                        while ((entry = zin.getNextEntry()) != null) {
                            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                            byte[] buffer = new byte[8192];
                            int length;
                            while ((length = zin.read(buffer)) > 0) bytes.write(buffer, 0, length);
                            byte[] content = bytes.toByteArray();
                            if ("word/document.xml".equals(entry.getName())) {
                                String document = new String(content, StandardCharsets.UTF_8);
                                int bodyStart = document.indexOf("<w:body");
                                int insertAt = bodyStart >= 0 ? document.indexOf(">", bodyStart) + 1 : -1;
                                if (insertAt <= 0) throw new IllegalStateException("Structure Word non reconnue");
                                document = document.substring(0, insertAt) + details + document.substring(insertAt);
                                int presentationAt = document.indexOf("Présentation du commissaire");
                                if (presentationAt < 0) presentationAt = document.indexOf("Présentation du commissaire du club");
                                if (presentationAt < 0) throw new IllegalStateException("La ligne de présentation du commissaire est introuvable dans le modèle Word");
                                int paragraphEnd = document.indexOf("</w:p>", presentationAt);
                                if (paragraphEnd < 0) throw new IllegalStateException("Fin de paragraphe Word introuvable");
                                paragraphEnd += "</w:p>".length();
                                document = document.substring(0, paragraphEnd) + commissionerLine + document.substring(paragraphEnd);
                                int accueilAt = document.indexOf("Accueil par le Président");
                                if (accueilAt < 0) throw new IllegalStateException("La ligne « Accueil par le Président » est introuvable dans le modèle Word");
                                int accueilStart = document.lastIndexOf("<w:p", accueilAt);
                                int accueilEnd = document.indexOf("</w:p>", accueilAt);
                                if (accueilStart < 0 || accueilEnd < 0) throw new IllegalStateException("Paragraphe d’accueil introuvable");
                                accueilEnd += "</w:p>".length();
                                int pPrEnd = document.indexOf("</w:pPr>", accueilStart);
                                String paragraphPrefix = "<w:p>";
                                if (pPrEnd >= 0 && pPrEnd < accueilAt) {
                                    paragraphPrefix = document.substring(accueilStart, pPrEnd + "</w:pPr>".length());
                                }
                                String accueilReplacement = paragraphPrefix +
                                        "<w:r><w:t>Accueil par le </w:t></w:r>" +
                                        "<w:r><w:t>" + xmlEscape(security) + "</w:t></w:r></w:p>";
                                document = document.substring(0, accueilStart) + accueilReplacement + document.substring(accueilEnd);
                                content = document.getBytes(StandardCharsets.UTF_8);
                            }
                            ZipEntry outEntry = new ZipEntry(entry.getName());
                            zout.putNextEntry(outEntry);
                            zout.write(content);
                            zout.closeEntry();
                            zin.closeEntry();
                        }
                    }
                    Uri uri = FileProvider.getUriForFile(MainActivity.this,
                            getPackageName() + ".fileprovider", file);
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "Envoyer le rapport Word"));
                } catch (Exception e) {
                    android.widget.Toast.makeText(MainActivity.this,
                            "Impossible de préparer le rapport Word : " + e.getMessage(),
                            android.widget.Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setDatabaseEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(true);
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        setContentView(webView);

        if (savedInstanceState == null) {
            webView.loadUrl("file:///android_asset/index.html");
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}