package com.example.ku7_connhub;

import android.Manifest;
import android.content.ClipData; // ClipData import
import android.content.ClipboardManager; // ClipboardManager import
import android.content.ComponentName;
import android.content.Context; // Context import
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast; // Toast import
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

// ItemAdapter.OnItemActionListener interface
public class MainActivity extends AppCompatActivity implements ItemAdapter.OnItemActionListener {
    private ItemAdapter itemAdapter;
    private TextView logView;
    private EditText portInput;
    private boolean isWorking = false;
    private boolean svcBound = false;

    // background service messenger
    private Messenger svcMessenger;
    private Messenger recvMessenger = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == 1) {
                logView.append(msg.getData().getString("string"));
            } else if (msg.what == 2) {
                itemAdapter.items.add(msg.getData().getString("string"));
                itemAdapter.updateItems();
            }
        }
    });
    private ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            svcMessenger = new Messenger(binder);
            svcBound = true;
            Message registerMsg = Message.obtain(null, 1);
            registerMsg.replyTo = recvMessenger;
            try {
                svcMessenger.send(registerMsg);
            } catch (Exception e) {
                logView.append(e.toString());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            svcBound = false;
            svcMessenger = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // init main activity
        super.onCreate(savedInstanceState);
        setContentView(R.layout.view_main);
        RecyclerView recyclerView = findViewById(R.id.ip_list);
        logView = findViewById(R.id.log_view);
        portInput = findViewById(R.id.port_input);
        Button startBtn = findViewById(R.id.start_btn);
        startBtn.setOnClickListener(v -> start());
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        itemAdapter = new ItemAdapter(this);
        recyclerView.setAdapter(itemAdapter);

        // init service modules
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
        Intent intent = new Intent(this, ConnHubService.class).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startForegroundService(intent);
        bindService(intent, conn, Context.BIND_AUTO_CREATE);
        svcBound = true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (svcBound) {
            try {
                svcMessenger.send(Message.obtain(null, 2)); // stop the server
            } catch (Exception e) {
                logView.append(e.toString());
            }
            unbindService(conn);
            svcBound = false;
        }
        stopService(new Intent(this, ConnHubService.class));
    }

    @Override
    public void onItemClick(String item) { }

    @Override
    public void onCopyClick(String item) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Copied Text", item);
        if (clipboard != null) {
            clipboard.setPrimaryClip(clip); // copy to clipboard
            Toast.makeText(this, "URL copied to clipboard", Toast.LENGTH_SHORT).show();
        }
    }

    // start server
    private void start() {
        if (!isWorking) {
            int port = 8000;
            try {
                port = Integer.parseInt(portInput.getText().toString());
            } catch (Exception e) {
                // use default port 8000
            }
            isWorking = true;
            Message msg;
            msg = Message.obtain(null, port);
            try {
                svcMessenger.send(msg);
            } catch (Exception e) {
                logView.append(e.toString());
            }
        }
    }
}