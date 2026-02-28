package com.example.ku7_connhub;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Server extends NanoHTTPD {
    public LogMethod logger;
    private int port;
    private Context context;

    // Global storage & Timestamp
    private String textdata = "";
    private long textTs;
    private final Object textLock = new Object();
    private List<String> filedata = new ArrayList<>();
    private long filesTs;
    private final Object filesLock = new Object();

    // Logger Interface
    public interface LogMethod {
        void addLog(String message);
        void printIP(String message);
    }

    public Server(Context c, int p, LogMethod l) {
        super(p);
        context = c;
        port = p;
        logger = l;
    }

    public void startServer() {
        // init local
        File tdir = new File(context.getFilesDir(), "temp");
        try {
            if (tdir.exists()) {
                for (File child : tdir.listFiles()) {
                    if (!child.delete()) { logger.addLog("fail=delete child file"); }
                }
                if (!tdir.delete()) { logger.addLog("fail=delete temp dir"); }
            }
            if (!tdir.mkdir()) { logger.addLog("fail=make temp dir"); }
        } catch (Exception e) {
            logger.addLog(e.toString());
        }

        // Init timestamps & structures
        synchronized (filesLock) {
            filedata.clear();
            filesTs = System.currentTimeMillis();
        }
        synchronized (textLock) {
            textdata = "";
            textTs = System.currentTimeMillis();
        }
        logger.addLog("server initialized");

        // get local ip of server
        getIP();

        // start server
        try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        } catch (Exception e) {
            logger.addLog(e.toString());
        }
        logger.addLog("server starting...");
    }

    // stop server
    public void stopServer() {
        logger.addLog("server stopping...");
        try {
            stop();
        } catch (Exception e) {
            logger.addLog(e.toString());
        }
    }

    // get local ip
    private void getIP() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface networkInterface : interfaces) {
                List<InetAddress> addresses = Collections.list(networkInterface.getInetAddresses());
                for (InetAddress address : addresses) {
                    if (!address.isLoopbackAddress() && address.getHostAddress().contains(".")) {
                        logger.printIP("http://" + address.getHostAddress() + ":" + port);
                    }
                }
            }
        } catch (Exception e) {
            logger.addLog(e.toString());
        }
    }

    // get asset
    private byte[] getAsset(String fileName) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        InputStream inputStream = null;
        try {
            inputStream = context.getAssets().open(fileName);
            byte[] data = new byte[1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, bytesRead);
            }
            return buffer.toByteArray();
        } catch (Exception e) {
            logger.addLog(e.toString());
            return null;
        } finally {
            if (inputStream != null) {
                try { inputStream.close(); } catch (Exception e) { logger.addLog(e.toString()); }
            }
            try { buffer.close(); } catch (Exception e) { logger.addLog(e.toString()); }
        }
    }

    // encode string to hex
    private String encodeHex(String input) {
        StringBuilder sb = new StringBuilder();
        for (byte b : input.getBytes(StandardCharsets.UTF_8)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Override
    public Response serve(IHTTPSession session) {
        // serve static files
        String uri = session.getUri();
        if ("/".equals(uri) || "/index.html".equals(uri)) {
            logger.addLog("API call=index.html");
            String content = new String(getAsset("index.html"), StandardCharsets.UTF_8);
            if (content.isEmpty()) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=UTF-8", "Cannot find index file");
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", content);
        } else if ("/favicon.ico".equals(uri)) {
            byte[] iconData = getAsset("favicon.ico");
            if (iconData == null) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=UTF-8", "Cannot find favicon");
            return newFixedLengthResponse(Response.Status.OK, "image/x-icon", new ByteArrayInputStream(iconData), iconData.length);
        }

        // API endpoints
        if (uri.equals("/api/state")) {
            return handleState(session);
        } else if (uri.equals("/api/text")) {
            return handleText(session);
        } else if (uri.equals("/api/files/upload")) {
            return handleFileUpload(session);
        } else if (uri.startsWith("/api/files/download/")) {
            return handleFileDownload(session);
        } else if (uri.startsWith("/api/files/delete/")) {
            return handleFileDelete(session);
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=UTF-8", "404 Not Found");
    }

    // State sync API
    private Response handleState(IHTTPSession session) {
        if (!Method.GET.equals(session.getMethod())) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain; charset=UTF-8", "Invalid method");
        }

        // Get client timestamps
        Map<String, List<String>> params = session.getParameters();
        long clientTextTs = 0;
        long clientFilesTs = 0;
        try {
            if (params.containsKey("text_ts")) clientTextTs = Long.parseLong(params.get("text_ts").get(0));
            if (params.containsKey("files_ts")) clientFilesTs = Long.parseLong(params.get("files_ts").get(0));
        } catch (Exception e) {
            // Ignore parse errors, use 0
        }

        try {
            JSONObject resp = new JSONObject();

            // Check text
            synchronized (textLock) {
                JSONObject textObj = new JSONObject();
                if (textTs > clientTextTs) {
                    textObj.put("updated", true);
                    textObj.put("data", textdata);
                    textObj.put("ts", textTs);
                } else {
                    textObj.put("updated", false);
                }
                resp.put("text", textObj);
            }

            // Check files
            synchronized (filesLock) {
                JSONObject filesObj = new JSONObject();
                if (filesTs > clientFilesTs) {
                    filesObj.put("updated", true);
                    filesObj.put("data", new JSONArray(filedata));
                    filesObj.put("ts", filesTs);
                } else {
                    filesObj.put("updated", false);
                }
                resp.put("files", filesObj);
            }

            return newFixedLengthResponse(Response.Status.OK, "application/json; charset=UTF-8", resp.toString());
        } catch (Exception e) {
            logger.addLog("State Error: " + e.toString());
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=UTF-8", "Server Error");
        }
    }

    // Text set
    private Response handleText(IHTTPSession session) {
        if (!Method.POST.equals(session.getMethod())) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain; charset=UTF-8", "Invalid method");
        }

        try {
            // 1. extract text
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            String newText = files.values().iterator().hasNext() ? files.values().iterator().next() : "";

            // 2. set text
            long currentTs;
            synchronized (textLock) {
                textdata = newText;
                textTs = System.currentTimeMillis();
                currentTs = textTs;
            }

            // 3. response
            JSONObject res = new JSONObject();
            res.put("ts", currentTs);
            return newFixedLengthResponse(Response.Status.OK, "application/json; charset=UTF-8", res.toString());

        } catch (Exception e) {
            logger.addLog(e.toString());
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain; charset=UTF-8", "Server error");
        }
    }

    // File upload (Bypassing NanoHTTPD's broken parseBody)
    private Response handleFileUpload(IHTTPSession session) {
        if (!Method.POST.equals(session.getMethod())) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain; charset=UTF-8", "Invalid method");
        }

        try {
            // 1. extract boundary from Content-Type
            String contentType = session.getHeaders().get("content-type");
            if (contentType == null || !contentType.contains("boundary=")) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain; charset=UTF-8", "No boundary found");
            }
            String boundaryStr = contentType.substring(contentType.indexOf("boundary=") + 9);
            if (boundaryStr.startsWith("\"") && boundaryStr.endsWith("\"")) {
                boundaryStr = boundaryStr.substring(1, boundaryStr.length() - 1);
            }
            byte[] boundary = ("\r\n--" + boundaryStr).getBytes(StandardCharsets.US_ASCII); // \r\n boundary ends file data
            InputStream in = new BufferedInputStream(session.getInputStream(), 65536);

            // 2. read header with UTF-8
            ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
            int b;
            int state = 0; // finding \r\n\r\n state machine
            while ((b = in.read()) != -1) {
                headerBuf.write(b);
                if (b == '\r' && state == 0) state = 1;
                else if (b == '\n' && state == 1) state = 2;
                else if (b == '\r' && state == 2) state = 3;
                else if (b == '\n' && state == 3) break;
                else state = 0;
            }

            // 3. get filename
            String headerStr = new String(headerBuf.toByteArray(), StandardCharsets.UTF_8);
            String filename = null;
            int fnStart = headerStr.indexOf("filename=\"");
            if (fnStart != -1) {
                fnStart += 10;
                int fnEnd = headerStr.indexOf("\"", fnStart);
                if (fnEnd != -1) {
                    filename = headerStr.substring(fnStart, fnEnd);
                }
            }
            if (filename == null) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain; charset=UTF-8", "Filename not found");
            }

            // 4. open file
            String encodedName = encodeHex(filename);
            File savedFile = new File(context.getFilesDir(), "temp/" + encodedName);
            if (savedFile.exists()) {
                savedFile.delete();
            }
            OutputStream fos = new BufferedOutputStream(new FileOutputStream(savedFile), 65536);

            // 5. write file until boundary
            byte[] tail = new byte[boundary.length];
            int tailIndex = 0;
            int tailCount = 0;

            // 6. refresh tail
            for (int i = 0; i < boundary.length; i++) {
                b = in.read();
                if (b == -1) break;
                tail[i] = (byte) b;
                tailCount++;
            }

            // 7. read until boundary
            while (tailCount == boundary.length) {
                boolean match = true;
                for (int i = 0; i < boundary.length; i++) {
                    if (tail[(tailIndex + i) % boundary.length] != boundary[i]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    break; // boundary found
                }

                // write tail to file
                fos.write(tail[tailIndex]);
                b = in.read();
                if (b == -1) break;
                tail[tailIndex] = (byte) b;
                tailIndex = (tailIndex + 1) % boundary.length;
            }
            fos.flush();
            fos.close();

            // 8. update list
            synchronized (filesLock) {
                filedata.remove(filename);
                filedata.add(filename);
                filesTs = System.currentTimeMillis();
            }

            // 9. response
            Response response = newFixedLengthResponse(Response.Status.OK, "text/plain; charset=UTF-8", "File uploaded");
            response.addHeader("Connection", "close");
            return response;

        } catch (Exception e) {
            logger.addLog("Upload Error: " + e.toString());
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=UTF-8", "Error saving file");
        }
    }

    // File download
    private Response handleFileDownload(IHTTPSession session) {
        if (!Method.GET.equals(session.getMethod())) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain; charset=UTF-8", "Invalid method");
        }

        // get filename
        String uri = session.getUri();
        String filename = uri.substring(uri.lastIndexOf("/") + 1);
        String encodedName = encodeHex(filename);
        File file = new File(context.getFilesDir(), "temp/" + encodedName);
        if (!file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=UTF-8", "File not found");
        }

        try {
            // open file
            FileInputStream fis = new FileInputStream(file);
            Response response = newChunkedResponse(Response.Status.OK, "application/octet-stream", fis);

            // force encoding to UTF-8
            String encodedHeaderName = java.net.URLEncoder.encode(filename, "UTF-8").replaceAll("\\+", "%20");
            response.addHeader("Content-Disposition", "attachment; filename=\"" + encodedHeaderName + "\"; filename*=UTF-8''" + encodedHeaderName);
            return response;

        } catch (Exception e) {
            logger.addLog(e.toString());
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=UTF-8", "Error reading file");
        }
    }

    // File delete
    private Response handleFileDelete(IHTTPSession session) {
        if (!Method.DELETE.equals(session.getMethod())) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain; charset=UTF-8", "Invalid method");
        }

        // get filename
        String uri = session.getUri();
        String filename = uri.substring(uri.lastIndexOf("/") + 1);
        String encodedName = encodeHex(filename);
        File file = new File(context.getFilesDir(), "temp/" + encodedName);
        if (file.exists()) {
            file.delete();
        }

        // Update file list & Timestamp
        synchronized (filesLock) {
            filedata.remove(filename);
            filesTs = System.currentTimeMillis();
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=UTF-8", "File deleted");
    }
}