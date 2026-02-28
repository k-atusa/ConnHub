package com.example.ku7_connhub;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;


// background service
public class ConnHubService extends Service {
    private String CHANNEL_ID = "ConnHubService";
    private Thread svrThread = null;
    private Messenger svcMessenger = new Messenger(new IncomingHandler());
    private Messenger recvMessenger = null;
    private Server server;
    private Server.LogMethod logger = new Server.LogMethod() {
        @Override
        public void addLog(String message) {
            LocalTime now = LocalTime.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
            sendMsg(1, now.format(formatter) + " -" + message + "\n");
        }

        @Override
        public void printIP(String message) {
            sendMsg(2, message);
        }
    };

    // message handler
    private class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == 0) {
                stopServer();
            } else if (msg.what == 1) {
                recvMessenger = msg.replyTo;
            } else {
                startServer(msg.what);
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return svcMessenger.getBinder(); // return binder
    }

    @Override
    public boolean onUnbind(Intent intent) {
        recvMessenger = null; // unbind
        return super.onUnbind(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate(); // create service
        int importance = NotificationManager.IMPORTANCE_LOW;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "service channel", importance);
        channel.setDescription("connhub service channel");
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager != null) { notificationManager.createNotificationChannel(channel); }
    }

    @Override
    public void onDestroy() {
        stopServer(); // stop the server
        super.onDestroy();
    }

    @SuppressLint("ForegroundServiceType")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("ConnHub")
                .setContentText("ConnHub Background Server")
                .setSmallIcon(R.drawable.icon_service) // mini icon
                .build();
        startForeground(1, notification);
        return START_STICKY; // start service
    }

    private void startServer(int port) {
        server = new Server(this, port, logger);
        svrThread = new Thread(new Runnable() {
            @Override
            public void run() { server.startServer(); }
        });
        svrThread.start(); // independent with UI thread
    }

    private void stopServer() {
        if (server != null) {
            server.stopServer();
            server = null;
        }
        if (svrThread != null) {
            svrThread.interrupt();
            svrThread = null;
        }
    }

    private void sendMsg(int code, String msg) {
        try {
            Message doneMsg = Message.obtain(null, code);
            Bundle data = new Bundle();
            data.putString("string", msg);
            doneMsg.setData(data);
            recvMessenger.send(doneMsg); // send message
        } catch (Exception e) {
            //Toast.makeText(getApplicationContext(), e.toString(), Toast.LENGTH_SHORT).show();
        }
    }
}