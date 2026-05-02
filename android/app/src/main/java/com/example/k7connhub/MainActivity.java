package com.example.k7connhub;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    // UI component
    private TextView logView;
    private RecyclerView ipList;
    private EditText portInput;
    private CheckBox checkDel, checkIpv6;
    private Button startBtn;

    // Runner variables
    private boolean isRunning = false;
    private ItemAdapter ipAdapter;
    private final SVCC1 bus = SVCC1.getChan();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.view_main);

        // UI Bindings
        logView = findViewById(R.id.log_view);
        ipList = findViewById(R.id.ip_list);
        portInput = findViewById(R.id.port_input);
        checkDel = findViewById(R.id.check_del);
        checkIpv6 = findViewById(R.id.check_ipv6);
        startBtn = findViewById(R.id.start_btn);

        // RecyclerView/ItemAdapter Setup
        ipAdapter = new ItemAdapter(new ItemAdapter.OnItemActionListener() {
            @Override
            public void onItemClick(String item) {
            }
            @Override
            public void onCopyClick(String item) {
                // copy to clipboard
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("IP Address", item);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(MainActivity.this, "Copied: " + item, Toast.LENGTH_SHORT).show();
            }
        });
        ipList.setLayoutManager(new LinearLayoutManager(this));
        ipList.setAdapter(ipAdapter);

        // Request Notification Permission (Android 13+)
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        // Server Toggle
        startBtn.setOnClickListener(v -> {
            Intent intent = new Intent(this, ConnHubService.class);
            if (isRunning) {
                stopService(intent);
                startBtn.setText("START");
                isRunning = false;

            } else {
                int port = 8000;
                try { port = Integer.parseInt(portInput.getText().toString()); } catch (Exception ignored) {}

                // put parameters
                intent.putExtra("port", port);
                intent.putExtra("delFiles", checkDel.isChecked());
                intent.putExtra("showIpv6", checkIpv6.isChecked());
                startForegroundService(intent);

                startBtn.setText("STOP");
                isRunning = true;
                logView.setText("Starting server...\n");
            }
        });

        // Event Bus Listeners
        bus.StringSlots[0].observe(this, log -> {
            if (log != null && !log.isEmpty()) {
                logView.append(log + "\n"); // str[0] is server log
            }
        });
        bus.StringSlots[1].observe(this, ips -> {
            if (ips != null) {
                List<String> list = ips.isEmpty() ? new ArrayList<>() : Arrays.asList(ips.split("\n")); // str[1] is IP

                // ItemAdapter update
                ipAdapter.items.clear();
                ipAdapter.items.addAll(list);
                ipAdapter.updateItems();
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (isRunning) {
            stopService(new Intent(this, ConnHubService.class)); // stop when app ends
        }
        super.onDestroy();
    }
}