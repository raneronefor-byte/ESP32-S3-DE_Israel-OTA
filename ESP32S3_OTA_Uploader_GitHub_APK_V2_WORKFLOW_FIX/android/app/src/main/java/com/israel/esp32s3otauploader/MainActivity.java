package com.israel.esp32s3otauploader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_OPEN_BIN = 1201;
    private static final String PREFS = "ota_prefs";

    private EditText ipEdit;
    private EditText pathEdit;
    private EditText keyEdit;
    private TextView selectedFileText;
    private TextView statusText;
    private ProgressBar progressBar;
    private Button uploadButton;
    private Button selectButton;
    private Button testButton;

    private Uri selectedBinUri;
    private String selectedBinName = "firmware.bin";
    private long selectedBinSize = -1L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadPrefs();
        setStatus("Selecciona el archivo .cpp.bin compilado y pulsa Subir firmware.");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scrollView.addView(root);

        TextView title = new TextView(this);
        title.setText("ESP32-S3 OTA Uploader");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, 0, 0, dp(10));
        root.addView(title, matchWrap());

        TextView note = new TextView(this);
        note.setText("Esta app sube solo el archivo .cpp.bin al OTA Web de la placa. No subas bootloader.bin ni partitions.bin.");
        note.setTextSize(14);
        note.setPadding(0, 0, 0, dp(12));
        root.addView(note, matchWrap());

        ipEdit = labeledEdit(root, "IP o URL de la placa", "192.168.1.120", InputType.TYPE_CLASS_TEXT);
        pathEdit = labeledEdit(root, "Ruta OTA", "/ota", InputType.TYPE_CLASS_TEXT);
        keyEdit = labeledEdit(root, "Clave OTA", "esp32ota", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);

        Button saveButton = new Button(this);
        saveButton.setText("Guardar IP / ruta / clave");
        saveButton.setOnClickListener(v -> {
            savePrefs();
            toast("Configuración guardada");
        });
        root.addView(saveButton, matchWrap());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, dp(4));
        root.addView(row, matchWrap());

        selectButton = new Button(this);
        selectButton.setText("Seleccionar .bin");
        selectButton.setOnClickListener(v -> openBinPicker());
        row.addView(selectButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        testButton = new Button(this);
        testButton.setText("Probar conexión");
        testButton.setOnClickListener(v -> testConnection());
        row.addView(testButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        selectedFileText = new TextView(this);
        selectedFileText.setText("Archivo: ninguno");
        selectedFileText.setTextSize(15);
        selectedFileText.setPadding(0, dp(8), 0, dp(8));
        root.addView(selectedFileText, matchWrap());

        uploadButton = new Button(this);
        uploadButton.setText("Subir firmware OTA");
        uploadButton.setOnClickListener(v -> confirmUpload());
        root.addView(uploadButton, matchWrap());

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setPadding(0, dp(14), 0, dp(8));
        root.addView(progressBar, matchWrap());

        statusText = new TextView(this);
        statusText.setTextSize(15);
        statusText.setPadding(0, dp(10), 0, dp(10));
        root.addView(statusText, matchWrap());

        TextView help = new TextView(this);
        help.setText("Uso:\n" +
                "1) En la placa activa Wi-Fi y Servidor Web/OTA.\n" +
                "2) Comprueba la IP que muestra la placa. Puedes cambiarla arriba.\n" +
                "3) Selecciona el archivo terminado en .cpp.bin.\n" +
                "4) Pulsa Subir firmware OTA y espera a que termine.\n\n" +
                "La app usa el selector de archivos de Android, así Android concede acceso al archivo seleccionado sin depender del selector del navegador web.");
        help.setTextSize(13);
        help.setPadding(0, dp(12), 0, 0);
        root.addView(help, matchWrap());

        setContentView(scrollView);
    }

    private EditText labeledEdit(LinearLayout root, String labelText, String hint, int inputType) {
        TextView label = new TextView(this);
        label.setText(labelText);
        label.setTextSize(13);
        label.setPadding(0, dp(8), 0, 0);
        root.addView(label, matchWrap());

        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setHint(hint);
        edit.setInputType(inputType);
        root.addView(edit, matchWrap());
        return edit;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void loadPrefs() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        ipEdit.setText(prefs.getString("ip", "192.168.1.120"));
        pathEdit.setText(prefs.getString("path", "/ota"));
        keyEdit.setText(prefs.getString("key", "esp32ota"));
    }

    private void savePrefs() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("ip", ipEdit.getText().toString().trim())
                .putString("path", normalizePath(pathEdit.getText().toString().trim()))
                .putString("key", keyEdit.getText().toString().trim())
                .apply();
    }

    private void openBinPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(Intent.createChooser(intent, "Seleccionar firmware .cpp.bin"), REQ_OPEN_BIN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN_BIN && resultCode == RESULT_OK && data != null && data.getData() != null) {
            selectedBinUri = data.getData();
            final int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            try {
                getContentResolver().takePersistableUriPermission(selectedBinUri, flags);
            } catch (Exception ignored) {
                // Algunos proveedores no permiten permisos persistentes. El archivo sigue disponible para esta sesión.
            }
            selectedBinName = queryName(selectedBinUri);
            selectedBinSize = querySize(selectedBinUri);
            selectedFileText.setText("Archivo: " + selectedBinName + "  " + formatBytes(selectedBinSize));
            if (!selectedBinName.toLowerCase(Locale.ROOT).endsWith(".bin")) {
                setStatus("Aviso: el archivo seleccionado no termina en .bin. Asegúrate de usar el .cpp.bin del firmware.");
            } else if (selectedBinName.toLowerCase(Locale.ROOT).contains("bootloader") || selectedBinName.toLowerCase(Locale.ROOT).contains("partition")) {
                setStatus("Aviso: este parece bootloader/partitions. Para OTA normalmente debes subir solo el .cpp.bin del sketch.");
            } else {
                setStatus("Archivo preparado. Pulsa Subir firmware OTA.");
            }
        }
    }

    private String queryName(Uri uri) {
        String name = "firmware.bin";
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String value = cursor.getString(idx);
                    if (value != null && value.trim().length() > 0) name = value.trim();
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return name;
    }

    private long querySize(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !cursor.isNull(idx)) return cursor.getLong(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return -1L;
    }

    private void confirmUpload() {
        if (selectedBinUri == null) {
            toast("Primero selecciona el archivo .cpp.bin");
            return;
        }
        savePrefs();
        String msg = "Vas a subir:\n" + selectedBinName + "\n\nA la placa:\n" + buildBaseUrl() + "\n\nNo apagues la placa durante la actualización.";
        new AlertDialog.Builder(this)
                .setTitle("Confirmar OTA")
                .setMessage(msg)
                .setPositiveButton("Subir", (dialog, which) -> uploadFirmware())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void setBusy(boolean busy) {
        uploadButton.setEnabled(!busy);
        selectButton.setEnabled(!busy);
        testButton.setEnabled(!busy);
    }

    private void uploadFirmware() {
        if (!isNetworkConnected()) {
            setStatus("Aviso: Android no detecta red activa. Conecta el móvil/tablet a la misma Wi-Fi que la placa.");
        }
        setBusy(true);
        progressBar.setProgress(0);
        setStatus("Preparando archivo...");
        executor.execute(() -> {
            File cached = null;
            try {
                cached = copyUriToCache(selectedBinUri, selectedBinName);
                final File firmwareFile = cached;
                final long fileLen = firmwareFile.length();
                mainHandler.post(() -> setStatus("Archivo preparado: " + formatBytes(fileLen) + ". Subiendo..."));

                String[] paths = uniquePaths(normalizePath(pathEdit.getText().toString().trim()));
                String[] fields = new String[]{"update", "firmware", "file"};
                UploadResult last = null;
                for (String path : paths) {
                    for (String field : fields) {
                        mainHandler.post(() -> setStatus("Probando subida a " + path + " con campo " + field + "..."));
                        last = postMultipart(firmwareFile, selectedBinName, path, field);
                        if (last.success) {
                            UploadResult ok = last;
                            mainHandler.post(() -> {
                                progressBar.setProgress(100);
                                setStatus("OTA enviada correctamente. Respuesta HTTP " + ok.code + ". Si la placa no reinicia sola, espera 10 segundos y pulsa RESET una vez.");
                                setBusy(false);
                            });
                            return;
                        }
                    }
                }
                UploadResult fail = last;
                mainHandler.post(() -> {
                    String text = fail == null ? "Error desconocido" : fail.message;
                    setStatus("No se pudo completar OTA. " + text + "\nComprueba IP, ruta /ota o /update, clave, Wi-Fi y que el Servidor Web/OTA esté activado en la placa.");
                    setBusy(false);
                });
            } catch (Exception ex) {
                mainHandler.post(() -> {
                    setStatus("Error: " + ex.getMessage());
                    setBusy(false);
                });
            } finally {
                if (cached != null) cached.delete();
            }
        });
    }

    private UploadResult postMultipart(File firmware, String filename, String path, String fieldName) {
        String boundary = "ESP32S3OTABoundary" + System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(buildUrlForPath(path));
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Connection", "close");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            String safeFilename = filename.replace("\"", "_");
            String header = "--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + safeFilename + "\"\r\n" +
                    "Content-Type: application/octet-stream\r\n\r\n";
            String footer = "\r\n--" + boundary + "--\r\n";
            byte[] headerBytes = header.getBytes("UTF-8");
            byte[] footerBytes = footer.getBytes("UTF-8");
            long totalLen = headerBytes.length + firmware.length() + footerBytes.length;
            conn.setFixedLengthStreamingMode(totalLen);

            try (OutputStream raw = new BufferedOutputStream(conn.getOutputStream());
                 FileInputStream in = new FileInputStream(firmware)) {
                raw.write(headerBytes);
                byte[] buf = new byte[32 * 1024];
                long sent = 0;
                long lastUi = 0;
                int n;
                while ((n = in.read(buf)) >= 0) {
                    raw.write(buf, 0, n);
                    sent += n;
                    long now = System.currentTimeMillis();
                    if (now - lastUi > 350) {
                        lastUi = now;
                        final int percent = (int) Math.min(99, (sent * 100L) / Math.max(1L, firmware.length()));
                        final long sentFinal = sent;
                        mainHandler.post(() -> {
                            progressBar.setProgress(percent);
                            setStatus("Subiendo... " + percent + "%  (" + formatBytes(sentFinal) + " / " + formatBytes(firmware.length()) + ")");
                        });
                    }
                }
                raw.write(footerBytes);
                raw.flush();
            }

            int code = conn.getResponseCode();
            String response = readResponse(conn);
            boolean ok = code >= 200 && code < 400;
            return new UploadResult(ok, code, "HTTP " + code + " " + response);
        } catch (IOException ex) {
            String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            if (msg.toLowerCase(Locale.ROOT).contains("unexpected end") || msg.toLowerCase(Locale.ROOT).contains("connection reset")) {
                return new UploadResult(true, 0, "La placa cerró la conexión. Puede ser normal si empezó a reiniciar tras el OTA.");
            }
            return new UploadResult(false, -1, msg);
        } catch (Exception ex) {
            return new UploadResult(false, -1, ex.getMessage() == null ? ex.toString() : ex.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String readResponse(HttpURLConnection conn) {
        InputStream stream = null;
        try {
            stream = conn.getInputStream();
        } catch (Exception ignored) {
            stream = conn.getErrorStream();
        }
        if (stream == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() < 500) sb.append(line).append(' ');
            }
            return sb.toString().trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private void testConnection() {
        savePrefs();
        setBusy(true);
        setStatus("Probando conexión...");
        executor.execute(() -> {
            String[] paths = uniquePaths(normalizePath(pathEdit.getText().toString().trim()));
            StringBuilder result = new StringBuilder();
            boolean ok = false;
            for (String p : paths) {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(buildUrlForPath(p));
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(7000);
                    conn.setReadTimeout(7000);
                    conn.setRequestMethod("GET");
                    int code = conn.getResponseCode();
                    result.append(p).append(": HTTP ").append(code).append('\n');
                    if (code >= 200 && code < 500) ok = true;
                } catch (Exception ex) {
                    result.append(p).append(": ").append(ex.getMessage()).append('\n');
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
            boolean finalOk = ok;
            mainHandler.post(() -> {
                setStatus(finalOk ? "Conexión encontrada:\n" + result : "No se pudo conectar:\n" + result);
                setBusy(false);
            });
        });
    }

    private File copyUriToCache(Uri uri, String filename) throws IOException {
        File outFile = new File(getCacheDir(), "firmware_upload_" + System.currentTimeMillis() + ".bin");
        ContentResolver resolver = getContentResolver();
        try (InputStream in = new BufferedInputStream(resolver.openInputStream(uri));
             FileOutputStream out = new FileOutputStream(outFile)) {
            if (in == null) throw new IOException("No se pudo abrir el archivo seleccionado");
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
        }
        if (outFile.length() <= 0) throw new IOException("El archivo seleccionado está vacío o no se puede leer");
        return outFile;
    }

    private String buildBaseUrl() {
        String raw = ipEdit.getText().toString().trim();
        if (raw.length() == 0) raw = "192.168.1.120";
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            raw = "http://" + raw;
        }
        while (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        return raw;
    }

    private String buildUrlForPath(String path) throws Exception {
        String base = buildBaseUrl();
        String cleanPath = normalizePath(path);
        String key = keyEdit.getText().toString().trim();
        String sep = cleanPath.contains("?") ? "&" : "?";
        return base + cleanPath + sep + "key=" + URLEncoder.encode(key, "UTF-8");
    }

    private String normalizePath(String path) {
        if (path == null || path.trim().length() == 0) return "/ota";
        path = path.trim();
        if (!path.startsWith("/")) path = "/" + path;
        return path;
    }

    private String[] uniquePaths(String preferred) {
        preferred = normalizePath(preferred);
        if ("/ota".equals(preferred)) return new String[]{"/ota", "/update"};
        if ("/update".equals(preferred)) return new String[]{"/update", "/ota"};
        return new String[]{preferred, "/ota", "/update"};
    }

    private boolean isNetworkConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception ignored) {
            return true;
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 0) return "";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        return String.format(Locale.ROOT, "%.2f MB", mb);
    }

    private void setStatus(String s) {
        statusText.setText("Estado: " + s);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static class UploadResult {
        final boolean success;
        final int code;
        final String message;
        UploadResult(boolean success, int code, String message) {
            this.success = success;
            this.code = code;
            this.message = message;
        }
    }
}
