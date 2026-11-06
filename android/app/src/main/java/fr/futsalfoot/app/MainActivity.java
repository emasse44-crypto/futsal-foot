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

    private String replaceWordParagraph(String document, String searchText, String replacementText) {
        int cursor = 0;
        while (true) {
            int start = document.indexOf("<w:p", cursor);
            if (start < 0) break;
            int startTagEnd = document.indexOf(">", start);
            int end = document.indexOf("</w:p>", startTagEnd);
            if (startTagEnd < 0 || end < 0) break;
            String paragraph = document.substring(start, end + "</w:p>".length());
            String plain = paragraph.replaceAll("<[^>]+>", "")
                    .replace("&amp;", "&").replace("&lt;", "<")
                    .replace("&gt;", ">").replace("&quot;", "\"")
                    .replace("&apos;", "'");
            if (plain.contains(searchText)) {
                String properties = "";
                int pPrStart = paragraph.indexOf("<w:pPr");
                if (pPrStart >= 0) {
                    int pPrEnd = paragraph.indexOf("</w:pPr>", pPrStart);
                    if (pPrEnd >= 0) properties = paragraph.substring(pPrStart, pPrEnd + "</w:pPr>".length());
                }
                String replacement = "<w:p>" + properties +
                        "<w:r><w:t xml:space=\"preserve\">" + xmlEscape(replacementText) +
                        "</w:t></w:r></w:p>";
                return document.substring(0, start) + replacement +
                        document.substring(end + "</w:p>".length());
            }
            cursor = end + "</w:p>".length();
        }
        throw new IllegalStateException("Paragraphe introuvable dans le modèle Word : " + searchText);
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
                                document = replaceWordParagraph(document,
                                        "Présentation du commissaire",
                                        "Présentation du commissaire du club visité : " + commissioner);
                                document = replaceWordParagraph(document,
                                        "Accueil par le Président",
                                        "Accueil par le " + security);
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