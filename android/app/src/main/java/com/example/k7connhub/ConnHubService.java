package com.example.k7connhub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.NetworkInterface;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import fi.iki.elonen.NanoHTTPD;

public class ConnHubService extends Service {
    private WebServer server;
    private File tempDir;

    // Text Data
    private String textData = "";
    private long textTs = 0;
    private final Object textLock = new Object();

    // File Data
    private static class FileEntry {
        String name;
        long size;
        FileEntry(String n, long s) { name = n; size = s; }
    }
    private final List<FileEntry> filesData = new ArrayList<>();
    private long filesTs = 0;
    private final Object filesLock = new Object();

    @Override
    public void onCreate() {
        super.onCreate();
        tempDir = new File(getFilesDir(), "temp");
        if (!tempDir.exists()) tempDir.mkdirs(); // make temp dir
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, "ConnHub")
                .setContentTitle("ConnHub Server")
                .setContentText("HTTP Server is running...")
                .setSmallIcon(R.drawable.icon_service)
                .build();
        startForeground(1, notification);

        int port = intent.getIntExtra("port", 8000);
        boolean delFiles = intent.getBooleanExtra("delFiles", false);
        boolean showIpv6 = intent.getBooleanExtra("showIpv6", false);

        // delete file if required
        if (delFiles) {
            File[] files = tempDir.listFiles();
            if (files != null) for (File f : files) f.delete();
            filesData.clear();
        } else {
            loadExistingFiles();
        }

        // start server
        textTs = System.currentTimeMillis();
        filesTs = System.currentTimeMillis();
        try {
            server = new WebServer(port);
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            logToMain("Server initialized on port " + port);
            broadcastIps(port, showIpv6);
        } catch (Exception e) {
            logToMain("Error starting server: " + e.getMessage());
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private class WebServer extends NanoHTTPD {
        public WebServer(int port) {
            super(port);
        }

        @Override
        public Response serve(IHTTPSession session) {
            String uri = session.getUri();
            Method method = session.getMethod();

            try {
                // 1. Static Files
                if (method == Method.GET && uri.equals("/")) {
                    InputStream is = getAssets().open("index.html");
                    return newChunkedResponse(Response.Status.OK, "text/html; charset=utf-8", is);
                }
                if (method == Method.GET && uri.equals("/favicon.ico")) {
                    InputStream is = getAssets().open("favicon.ico");
                    return newChunkedResponse(Response.Status.OK, "image/x-icon", is);
                }

                // 2. State Sync
                if (method == Method.GET && uri.equals("/api/state")) {
                    long clientTextTs = 0;
                    long clientFilesTs = 0;
                    try {
                        if (session.getParameters().containsKey("text_ts")) clientTextTs = Long.parseLong(session.getParameters().get("text_ts").get(0));
                        if (session.getParameters().containsKey("files_ts")) clientFilesTs = Long.parseLong(session.getParameters().get("files_ts").get(0));
                    } catch (Exception ignored) {}

                    JSONObject resp = new JSONObject();

                    // update text
                    synchronized (textLock) {
                        JSONObject tObj = new JSONObject();
                        if (textTs > clientTextTs) {
                            tObj.put("updated", true); tObj.put("data", textData); tObj.put("ts", textTs);
                        } else { tObj.put("updated", false); }
                        resp.put("text", tObj);
                    }

                    // update file list
                    synchronized (filesLock) {
                        JSONObject fObj = new JSONObject();
                        if (filesTs > clientFilesTs) {
                            fObj.put("updated", true);
                            JSONArray arr = new JSONArray();
                            for (FileEntry f : filesData) {
                                JSONObject o = new JSONObject(); o.put("name", f.name); o.put("size", f.size); arr.put(o);
                            }
                            fObj.put("data", arr); fObj.put("ts", filesTs);
                        } else { fObj.put("updated", false); }
                        resp.put("files", fObj);
                    }

                    // send json response
                    return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", resp.toString());
                }

                // 3. Text Update
                if (method == Method.POST && uri.equals("/api/text")) {
                    Map<String, String> files = new HashMap<>();
                    session.parseBody(files);

                    // get text from body
                    String bodyText = files.get("postData");
                    if (bodyText == null) bodyText = "";

                    // update text
                    synchronized (textLock) {
                        textData = bodyText;
                        textTs = System.currentTimeMillis();
                        JSONObject res = new JSONObject();
                        res.put("ts", textTs);
                        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", res.toString());
                    }
                }

                // 4. File Upload
                if (method == Method.POST && uri.equals("/api/files/upload")) {
                    Map<String, String> files = new HashMap<>();
                    session.parseBody(files);
                    String tempFilePath = files.get("file"); // system temp file path
                    String encodedName = session.getParameters().get("filename").get(0); // Base64 name

                    if (tempFilePath != null && encodedName != null) {
                        File finalFile = new File(tempDir, encodedName);
                        if (finalFile.exists()) finalFile.delete();
                        File tempFileObj = new File(tempFilePath);

                        // change temp file name to stored name
                        if (!tempFileObj.renameTo(finalFile)) {
                            InputStream in = new FileInputStream(tempFileObj);
                            FileOutputStream out = new FileOutputStream(finalFile);
                            byte[] buf = new byte[65536]; // copy if name change failed
                            int len;
                            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                            in.close();
                            out.close();
                            tempFileObj.delete();
                        }

                        // update file list
                        synchronized (filesLock) {
                            Iterator<FileEntry> it = filesData.iterator();
                            while (it.hasNext()) {
                                if (it.next().name.equals(encodedName)) it.remove();
                            }
                            filesData.add(new FileEntry(encodedName, finalFile.length()));
                            filesTs = System.currentTimeMillis();
                        }
                        logToMain("Uploaded: " + encodedName);
                        return newFixedLengthResponse(Response.Status.OK, "text/plain", "ok");
                    }
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing file or filename");
                }

                // 5. Download Single File
                if (method == Method.GET && uri.startsWith("/api/files/download/")) {
                    String encodedName = uri.substring(uri.lastIndexOf('/') + 1);
                    File file = new File(tempDir, encodedName);

                    if (file.exists()) {
                        String realName = session.getParameters().get("name").get(0);
                        if (realName == null) realName = encodedName;
                        String headerName = URLEncoder.encode(realName, "UTF-8").replace("+", "%20"); // URL-safe utf8

                        FileInputStream fis = new FileInputStream(file);
                        Response res = newChunkedResponse(Response.Status.OK, "application/octet-stream", fis);
                        res.addHeader("Content-Disposition", "attachment; filename*=UTF-8''" + headerName);
                        return res;
                    }
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
                }

                // 6. Download All (ZIP)
                if (method == Method.GET && uri.equals("/api/files/download-all")) {
                    File zipFile = new File(getCacheDir(), "files.zip");
                    ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile));

                    // compress to zip
                    synchronized (filesLock) {
                        for (FileEntry entry : filesData) {
                            String realName = entry.name;
                            try {
                                byte[] decodedBytes = Base64.decode(entry.name, Base64.URL_SAFE);
                                realName = new String(decodedBytes, "UTF-8");
                            } catch (Exception ignored) {}

                            // put file to zip
                            zos.putNextEntry(new ZipEntry(realName));
                            FileInputStream fis = new FileInputStream(new File(tempDir, entry.name));
                            byte[] buf = new byte[65536];
                            int len;
                            while ((len = fis.read(buf)) > 0) zos.write(buf, 0, len);
                            fis.close();
                            zos.closeEntry();
                        }
                    }
                    zos.close();

                    // override to delete automatically
                    FileInputStream fis = new FileInputStream(zipFile) {
                        @Override
                        public void close() throws IOException {
                            super.close();
                            if (zipFile.exists()) {
                                zipFile.delete();
                                logToMain("Temp zip file deleted after transfer");
                            }
                        }
                    };

                    // read temp zip and send
                    Response res = newChunkedResponse(Response.Status.OK, "application/zip", fis);
                    res.addHeader("Content-Disposition", "attachment; filename=\"files.zip\"");
                    return res;
                }

                // 7. Delete File
                if (method == Method.DELETE && uri.startsWith("/api/files/delete/")) {
                    String encodedName = uri.substring(uri.lastIndexOf('/') + 1);
                    synchronized (filesLock) {
                        Iterator<FileEntry> it = filesData.iterator();
                        while (it.hasNext()) {
                            if (it.next().name.equals(encodedName)) it.remove();
                        }
                        new File(tempDir, encodedName).delete();
                        filesTs = System.currentTimeMillis();
                    }
                    logToMain("Deleted: " + encodedName);
                    return newFixedLengthResponse(Response.Status.OK, "text/plain", "ok");
                }

                // 8. Delete All
                if (method == Method.DELETE && uri.equals("/api/files/delete-all")) {
                    synchronized (filesLock) {
                        for (FileEntry f : filesData) {
                            new File(tempDir, f.name).delete();
                        }
                        filesData.clear();
                        filesTs = System.currentTimeMillis();
                    }
                    logToMain("Deleted all files");
                    return newFixedLengthResponse(Response.Status.OK, "text/plain", "ok");
                }

            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: " + e.getMessage());
            }

            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
        }
    }

    // Load existing files at temp dir
    private void loadExistingFiles() {
        File[] files = tempDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) filesData.add(new FileEntry(f.getName(), f.length()));
            }
            logToMain("Loaded " + filesData.size() + " existing files");
        }
    }

    // get IP address
    private void broadcastIps(int port, boolean showIpv6) {
        StringBuilder ips = new StringBuilder();
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                for (java.net.InetAddress addr : Collections.list(intf.getInetAddresses())) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        boolean isIPv4 = sAddr.indexOf(':') < 0;
                        if (isIPv4) ips.append("http://").append(sAddr).append(":").append(port).append("\n");
                        else if (showIpv6) ips.append("http://[").append(sAddr).append("]:").append(port).append("\n");
                    }
                }
            }
        } catch (Exception ignored) {}
        SVCC1.getChan().SetString(1, ips.toString());
    }

    // transmit log
    private void logToMain(String msg) {
        Log.d("ConnHub", msg);
        SVCC1.getChan().SetString(0, msg);
    }

    // register service
    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                "ConnHub", "ConnHub Service", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    // stop server when app ends
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }
    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        logToMain("Server stopped.");
        SVCC1.getChan().SetString(1, "");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}