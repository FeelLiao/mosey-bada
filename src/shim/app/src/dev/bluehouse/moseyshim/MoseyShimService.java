package dev.bluehouse.moseybridgeshim;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;

public final class MoseyShimService extends Service {
    private static final String TAG = "MoseyShim";
    private static final String AIRDROP_SERVICE = "_airdrop._tcp";
    private static final String AIRDROP_UUID = "0000fcf1-0000-1000-8000-00805f9b34fb";
    private static final String KEYSTORE = "/odm/etc/mosey-shim/mosey-shim.p12";
    private static final char[] KEYSTORE_PASSWORD = "mosey".toCharArray();
    private static final int BRIDGE_PORT = 19539;
    private static final int BRIDGE_TIMEOUT_MS = 30000;
    private static final int MAX_BRIDGE_FRAME = 64 * 1024;
    private static final int EVENT_PORT = 19540;
    private static final int BADA_AIRDROP_PORT = 19541;
    private static final String BADA_WAKE_ACTION = "dev.bluehouse.bada.airdrop.WAKE";
    private static final int NOTIFICATION_ID = 42610;
    private static final String NOTIFICATION_CHANNEL = "mosey_bridge_shim";
    private static final String EXTRA_COUNTRY_CODE = "country_code";
    private static final int WIFI_DISCONNECT_TIMEOUT_MS = 10000;

    private final Set<String> seenServices = new HashSet<>();
    private final Set<String> seenApplePayloads = new HashSet<>();
    private volatile boolean workerStarted;
    private volatile boolean bridgeRadioStarted;
    private int bridgeFailureCount;
    private long nextBridgeAttemptAt;
    private volatile boolean bleAdvertisingStarted;
    private volatile boolean bleScanStarted;
    private volatile String countryCode;
    private String deviceName;
    private String serviceId;
    private WifiManager.MulticastLock multicastLock;
    private WifiManager wifiManager;
    private volatile boolean wifiDisconnectedForMosey;
    private int disconnectedWifiNetworkId = -1;
    private RawMdnsEngine rawMdns;
    private SSLServerSocket httpSocket;
    private int httpPort;
    private BluetoothLeAdvertiser bleAdvertiser;
    private BluetoothLeScanner bleScanner;

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            bleAdvertisingStarted = true;
            Log.i(TAG, "BLE wakeup advertising started: " + settingsInEffect);
        }

        @Override public void onStartFailure(int errorCode) {
            bleAdvertisingStarted = false;
            Log.w(TAG, "BLE wakeup advertising failed: " + errorCode);
        }
    };

    private final ScanCallback appleScanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            ScanRecord record = result.getScanRecord();
            byte[] payload = record == null ? null : record.getManufacturerSpecificData(76);
            if (payload == null || payload.length == 0) {
                return;
            }

            String payloadHex = hex(payload);
            synchronized (seenApplePayloads) {
                if (seenApplePayloads.add(payloadHex)) {
                    Log.i(TAG, "Apple BLE manufacturer data: " + payloadHex
                            + " rssi=" + result.getRssi());
                }
            }

            if ((payload[0] & 0xff) != 5) {
                return;
            }
            if (record.getServiceUuids() != null
                    && record.getServiceUuids().contains(ParcelUuid.fromString(AIRDROP_UUID))) {
                Log.i(TAG, "Skipping own AirDrop BLE advertisement: " + result.getDevice());
                return;
            }

            Log.i(TAG, "Apple AirDrop BLE wakeup seen: " + result.getDevice()
                    + " rssi=" + result.getRssi() + " data=" + payloadHex);
            announceAirDropService();
            sendEvent("{\"type\":\"apple_ble_seen\",\"rssi\":" + result.getRssi()
                    + ",\"manufacturerData\":\"" + payloadHex + "\"}");
        }

        @Override public void onScanFailed(int errorCode) {
            bleScanStarted = false;
            Log.w(TAG, "Apple BLE scan failed: " + errorCode);
        }
    };

    private final BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            Log.i(TAG, "Bluetooth state changed: " + state);
            if (state == BluetoothAdapter.STATE_ON) {
                startBleWakeupAdvertising();
                startAppleBleScan();
            } else if (state == BluetoothAdapter.STATE_OFF
                    || state == BluetoothAdapter.STATE_TURNING_OFF) {
                resetBluetoothOperations();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        deviceName = readDeviceName();
        serviceId = createServiceId();
        wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        startAsForegroundService();
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(bluetoothStateReceiver, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForegroundService();
        String requestedCountry = intent == null ? null
                : normalizeCountryCode(intent.getStringExtra(EXTRA_COUNTRY_CODE));
        if (requestedCountry == null) {
            Log.e(TAG, "No validated country code in start request; stopping for watchdog restart");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        updateCountryCode(requestedCountry);
        startWorkerOnce();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(bluetoothStateReceiver);
        } catch (Throwable ignored) {
        }
        resetBluetoothOperations();
        stopMoseyNetwork();
        if (bridgeRadioStarted) {
            stopBridgeRadio();
            bridgeRadioStarted = false;
        }
        restoreWifiAfterMosey("service destroyed");
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        super.onDestroy();
    }

    private synchronized void startWorkerOnce() {
        if (workerStarted) {
            return;
        }
        workerStarted = true;
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                runShim();
            }
        }, "mosey_shim_service");
        worker.setDaemon(false);
        worker.start();
    }

    private void startAsForegroundService() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                NotificationChannel channel = new NotificationChannel(
                        NOTIFICATION_CHANNEL,
                        "Mosey Bridge",
                        NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Keeps Mosey AirDrop discovery active");
                manager.createNotificationChannel(channel);
            }
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, NOTIFICATION_CHANNEL)
                : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Mosey Bridge")
                .setContentText("AirDrop discovery is active")
                .setOngoing(true)
                .setShowWhen(false)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        Log.i(TAG, "Foreground service active");
    }

    private void runShim() {
        Log.i(TAG, "Priv-app Mosey shim starting as package " + getPackageName());
        startRetryLoop();
        Log.i(TAG, "Priv-app Mosey shim started");
    }

    private String readDeviceName() {
        String model = android.os.Build.MODEL;
        if (model == null || model.length() == 0) {
            model = "Bada";
        }
        return model + " Bada";
    }

    private void startRetryLoop() {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (!bridgeRadioStarted && now >= nextBridgeAttemptAt) {
                        if (startBridgeRadio()) {
                            bridgeFailureCount = 0;
                            nextBridgeAttemptAt = 0;
                        } else {
                            bridgeFailureCount++;
                            long delay = bridgeFailureCount == 1 ? 10000L
                                    : bridgeFailureCount == 2 ? 30000L : 60000L;
                            nextBridgeAttemptAt = android.os.SystemClock.elapsedRealtime() + delay;
                            Log.w(TAG, "Bridge radio retry " + bridgeFailureCount
                                    + " scheduled after " + delay + "ms");
                        }
                    }
                    if (bridgeRadioStarted) {
                        if (httpSocket == null) {
                            try {
                                startHttpsDiscoverServer();
                            } catch (Throwable t) {
                                Log.w(TAG, "Waiting to bind HTTPS /Discover to mosey0", t);
                            }
                        }
                        if (rawMdns == null || !rawMdns.isRunning()) {
                            startMoseyNetwork();
                        } else if (!isMoseyInterfaceReady()) {
                            Log.w(TAG, "mosey0 disappeared; rebuilding radio and raw mDNS");
                            stopMoseyNetwork();
                            bridgeRadioStarted = false;
                            nextBridgeAttemptAt = 0;
                        }
                    }
                    if (!bleAdvertisingStarted) {
                        startBleWakeupAdvertising();
                    }
                    if (!bleScanStarted) {
                        startAppleBleScan();
                    }
                    sleep(5000);
                }
            }
        }, "mosey_shim_retry");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean startBridgeRadio() {
        String activeCountry = countryCode;
        if (activeCountry == null) {
            Log.w(TAG, "Bridge radio start deferred: country code unavailable");
            return false;
        }
        if (!prepareWifiForMosey()) {
            Log.w(TAG, "Bridge radio start deferred: primary Wi-Fi did not disconnect");
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", BRIDGE_PORT), 2000);
            socket.setSoTimeout(BRIDGE_TIMEOUT_MS);
            sendBridgeCommand(socket, 3,
                    activeCountry.getBytes(StandardCharsets.US_ASCII));
            byte[] channels = ByteBuffer.allocate(1 + 3 * 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .put((byte) 3)
                    .putInt(149)
                    .putInt(44)
                    .putInt(6)
                    .array();
            sendBridgeCommand(socket, 1, channels);
            if (!activeCountry.equals(countryCode)) {
                Log.i(TAG, "Country changed during radio startup; discarding " + activeCountry);
                stopBridgeRadio();
                bridgeRadioStarted = false;
                return false;
            }
            bridgeRadioStarted = true;
            Log.i(TAG, "Requested bridge radio start country=" + activeCountry
                    + " channels=[149,44,6]");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Unable to start bridge radio; will retry", t);
            bridgeRadioStarted = false;
            restoreWifiAfterMosey("radio start failed");
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private synchronized boolean prepareWifiForMosey() {
        if (wifiDisconnectedForMosey) {
            return true;
        }
        if (wifiManager == null) {
            Log.w(TAG, "WifiManager unavailable; cannot coordinate STA concurrency");
            return false;
        }

        WifiInfo info = wifiManager.getConnectionInfo();
        if (!isWifiConnected(info)) {
            Log.i(TAG, "Primary Wi-Fi is already disconnected; starting Mosey radio");
            return true;
        }

        int frequency = info.getFrequency();
        if (!usesMoseyBand(frequency)) {
            Log.i(TAG, "Keeping primary Wi-Fi on non-overlapping frequency=" + frequency);
            return true;
        }

        disconnectedWifiNetworkId = info.getNetworkId();
        Log.i(TAG, "Disconnecting primary Wi-Fi before Mosey start: networkId="
                + disconnectedWifiNetworkId + " frequency=" + frequency
                + " channels=[149,44,6]");
        boolean requested = wifiManager.disconnect();
        Log.i(TAG, "WifiManager.disconnect requested=" + requested);

        long deadline = android.os.SystemClock.elapsedRealtime() + WIFI_DISCONNECT_TIMEOUT_MS;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            WifiInfo current = wifiManager.getConnectionInfo();
            if (!isWifiConnected(current)) {
                wifiDisconnectedForMosey = true;
                Log.i(TAG, "Primary Wi-Fi disconnected; Mosey concurrency slot is available");
                return true;
            }
            sleep(250);
        }

        WifiInfo current = wifiManager.getConnectionInfo();
        Log.w(TAG, "Timed out waiting for primary Wi-Fi disconnect: networkId="
                + (current == null ? -1 : current.getNetworkId()) + " frequency="
                + (current == null ? 0 : current.getFrequency()) + " state="
                + (current == null ? "null" : current.getSupplicantState()));
        return false;
    }

    private static boolean isWifiConnected(WifiInfo info) {
        if (info == null || info.getNetworkId() < 0) {
            return false;
        }
        SupplicantState state = info.getSupplicantState();
        return state == SupplicantState.COMPLETED
                || state == SupplicantState.ASSOCIATED
                || state == SupplicantState.ASSOCIATING
                || state == SupplicantState.AUTHENTICATING
                || state == SupplicantState.FOUR_WAY_HANDSHAKE
                || state == SupplicantState.GROUP_HANDSHAKE;
    }

    private static boolean usesMoseyBand(int frequencyMhz) {
        if (frequencyMhz <= 0) {
            return true;
        }
        return (frequencyMhz >= 2400 && frequencyMhz <= 2500)
                || (frequencyMhz >= 4900 && frequencyMhz < 5925);
    }

    @SuppressWarnings("deprecation")
    private synchronized void restoreWifiAfterMosey(String reason) {
        if (!wifiDisconnectedForMosey || wifiManager == null) {
            return;
        }
        boolean requested = wifiManager.reconnect();
        Log.i(TAG, "Restoring primary Wi-Fi after Mosey: reason=" + reason
                + " networkId=" + disconnectedWifiNetworkId
                + " reconnectRequested=" + requested);
        wifiDisconnectedForMosey = false;
        disconnectedWifiNetworkId = -1;
    }

    private synchronized void updateCountryCode(String requestedCountry) {
        if (requestedCountry.equals(countryCode)) {
            return;
        }
        String previous = countryCode;
        if (previous != null && bridgeRadioStarted) {
            stopMoseyNetwork();
            stopBridgeRadio();
        }
        countryCode = requestedCountry;
        bridgeRadioStarted = false;
        bridgeFailureCount = 0;
        nextBridgeAttemptAt = 0;
        Log.i(TAG, "Wi-Fi country updated " + previous + " -> " + requestedCountry);
    }

    private void stopBridgeRadio() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", BRIDGE_PORT), 2000);
            socket.setSoTimeout(BRIDGE_TIMEOUT_MS);
            sendBridgeCommand(socket, 2, new byte[0]);
            Log.i(TAG, "Stopped bridge radio before country update");
        } catch (Throwable t) {
            Log.w(TAG, "Unable to stop bridge radio before country update", t);
        }
    }

    private static String normalizeCountryCode(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.US);
        if (normalized.length() != 2
                || normalized.charAt(0) < 'A' || normalized.charAt(0) > 'Z'
                || normalized.charAt(1) < 'A' || normalized.charAt(1) > 'Z'
                || normalized.equals("NU") || normalized.equals("ZZ")
                || normalized.equals("00")) {
            return null;
        }
        return normalized;
    }

    private void sendBridgeCommand(Socket socket, int command, byte[] params) throws Exception {
        long startedAt = android.os.SystemClock.elapsedRealtime();
        byte[] payload = new byte[1 + params.length];
        payload[0] = (byte) command;
        System.arraycopy(params, 0, payload, 1, params.length);
        OutputStream out = socket.getOutputStream();
        out.write(1);
        writeLe32(out, payload.length);
        out.write(payload);
        out.flush();
        InputStream in = socket.getInputStream();
        byte[] header = new byte[5];
        readFully(in, header);
        if ((header[0] & 0xff) != 2) {
            throw new java.io.IOException("Unexpected bridge frame type: " + (header[0] & 0xff));
        }
        int len = ((header[1] & 0xff) | ((header[2] & 0xff) << 8)
                | ((header[3] & 0xff) << 16) | ((header[4] & 0xff) << 24));
        if (len < 8 || len > MAX_BRIDGE_FRAME) {
            throw new java.io.IOException("Invalid bridge reply length: " + len);
        }
        byte[] reply = new byte[len];
        readFully(in, reply);
        int status = ByteBuffer.wrap(reply, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        int commandStatus = ByteBuffer.wrap(reply, 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN).getInt();
        long elapsed = android.os.SystemClock.elapsedRealtime() - startedAt;
        Log.i(TAG, "Bridge command " + command + " status=" + status
                + " elapsed=" + elapsed + "ms");
        if (status != 0 || commandStatus != 0) {
            throw new java.io.IOException("Bridge command " + command
                    + " failed: status=" + status + " commandStatus=" + commandStatus);
        }
    }

    private void startHttpsDiscoverServer() throws Exception {
        if (httpSocket != null && !httpSocket.isClosed()) return;
        InetAddress localAddress = moseyLinkLocalAddress();
        if (localAddress == null) {
            throw new java.io.IOException("mosey0 link-local address unavailable");
        }
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(KEYSTORE)) {
            keyStore.load(in, KEYSTORE_PASSWORD);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, KEYSTORE_PASSWORD);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);
        SSLServerSocketFactory factory = sslContext.getServerSocketFactory();
        httpSocket = (SSLServerSocket) factory.createServerSocket(0, 50, localAddress);
        httpSocket.setReuseAddress(true);
        httpPort = httpSocket.getLocalPort();
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                serveHttpsLoop();
            }
        }, "mosey_shim_https");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "HTTPS /Discover server listening on "
                + localAddress.getHostAddress() + ":" + httpPort);
    }

    private void serveHttpsLoop() {
        while (true) {
            try {
                final SSLSocket socket = (SSLSocket) httpSocket.accept();
                Thread t = new Thread(new Runnable() {
                    @Override public void run() {
                        handleHttps(socket);
                    }
                }, "mosey_shim_https_client");
                t.setDaemon(true);
                t.start();
            } catch (Throwable t) {
                if (httpSocket != null && !httpSocket.isClosed()) {
                    Log.w(TAG, "HTTPS accept failed", t);
                } else {
                    return;
                }
            }
        }
    }

    private void handleHttps(SSLSocket socket) {
        try (SSLSocket s = socket) {
            InputStream input = s.getInputStream();
            byte[] rawHeader = readHttpHeader(input);
            String headerText = new String(rawHeader, StandardCharsets.US_ASCII);
            String request = headerText.substring(0, headerText.indexOf("\r\n"));
            String path = "/";
            if (request != null) {
                String[] parts = request.split(" ");
                if (parts.length >= 2) {
                    path = parts[1];
                }
            }
            boolean discover = "/Discover".equals(path);
            boolean transfer = "/Ask".equals(path) || "/Upload".equals(path);
            if (transfer) {
                proxyToBada(s, input, rawHeader, headerText, path);
                return;
            }
            byte[] body = discover
                    ? discoverPlist().getBytes(StandardCharsets.UTF_8)
                    : new byte[0];
            int status = discover ? 200 : transfer ? 503 : 404;
            String reason = status == 200 ? "OK" : status == 503
                    ? "Service Unavailable" : "Not Found";
            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 " + status + " " + reason + "\r\n"
                    + "Content-Type: application/x-apple-binary-plist\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        } catch (Throwable t) {
            Log.w(TAG, "HTTPS client failed", t);
        }
    }

    private void proxyToBada(SSLSocket client, InputStream clientInput, byte[] rawHeader,
            String headerText, String path) throws Exception {
        wakeBadaReceiver();
        try (Socket local = connectBada()) {
            local.setSoTimeout(135000);
            OutputStream localOut = local.getOutputStream();
            localOut.write(rawHeader);
            localOut.flush();

            String lower = headerText.toLowerCase(java.util.Locale.US);
            if (lower.contains("expect: 100-continue")) {
                client.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
            }
            if (lower.contains("transfer-encoding: chunked")) {
                relayChunked(clientInput, localOut);
            } else {
                long length = httpContentLength(headerText);
                relayBytes(clientInput, localOut, length);
            }
            localOut.flush();
            copyUntilEof(local.getInputStream(), client.getOutputStream());
            client.getOutputStream().flush();
            Log.i(TAG, "Proxied AirDrop " + path + " to Bada");
        } catch (java.net.ConnectException e) {
            byte[] response = ("HTTP/1.1 503 Service Unavailable\r\n"
                    + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
            client.getOutputStream().write(response);
            client.getOutputStream().flush();
            Log.w(TAG, "Bada AirDrop receiver is not running for " + path);
        }
    }

    private void wakeBadaReceiver() {
        try (Socket bridge = new Socket()) {
            bridge.connect(new InetSocketAddress("127.0.0.1", BRIDGE_PORT), 2000);
            bridge.setSoTimeout(10000);
            sendBridgeCommand(bridge, 5, new byte[0]);
        } catch (Throwable t) {
            Log.w(TAG, "Root Bada wake command failed", t);
        }
        for (String pkg : new String[] {"dev.bluehouse.bada", "dev.bluehouse.bada.debug"}) {
            try {
                sendBroadcast(new Intent(BADA_WAKE_ACTION).setPackage(pkg));
            } catch (Throwable t) {
                Log.d(TAG, "Bada wake broadcast failed for " + pkg + ": " + t);
            }
        }
    }

    private Socket connectBada() throws Exception {
        java.net.ConnectException last = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress("127.0.0.1", BADA_AIRDROP_PORT), 500);
                return socket;
            } catch (java.net.ConnectException e) {
                last = e;
                try { socket.close(); } catch (Throwable ignored) {}
                Thread.sleep(100);
            }
        }
        throw last == null ? new java.net.ConnectException("Bada AirDrop receiver unavailable") : last;
    }

    private static byte[] readHttpHeader(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] end = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        int matched = 0;
        while (out.size() < 65536) {
            int value = input.read();
            if (value < 0) throw new java.io.EOFException("EOF before HTTP headers");
            out.write(value);
            if (value == (end[matched] & 0xff)) matched++;
            else matched = value == '\r' ? 1 : 0;
            if (matched == end.length) return out.toByteArray();
        }
        throw new java.io.IOException("HTTP headers too large");
    }

    private static long httpContentLength(String headers) {
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && "content-length".equalsIgnoreCase(line.substring(0, colon).trim())) {
                try { return Long.parseLong(line.substring(colon + 1).trim()); }
                catch (NumberFormatException ignored) { return 0; }
            }
        }
        return 0;
    }

    private static void relayBytes(InputStream in, OutputStream out, long length) throws Exception {
        byte[] buffer = new byte[32768];
        long remaining = length;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new java.io.EOFException("EOF inside HTTP body");
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void relayChunked(InputStream in, OutputStream out) throws Exception {
        while (true) {
            byte[] line = readHttpLineBytes(in);
            out.write(line);
            String sizeText = new String(line, 0, line.length - 2, StandardCharsets.US_ASCII);
            int semicolon = sizeText.indexOf(';');
            if (semicolon >= 0) sizeText = sizeText.substring(0, semicolon);
            long size = Long.parseLong(sizeText.trim(), 16);
            relayBytes(in, out, size + 2);
            if (size == 0) return;
        }
    }

    private static byte[] readHttpLineBytes(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int previous = -1;
        while (out.size() < 8192) {
            int value = in.read();
            if (value < 0) throw new java.io.EOFException("EOF in HTTP chunk line");
            out.write(value);
            if (previous == '\r' && value == '\n') return out.toByteArray();
            previous = value;
        }
        throw new java.io.IOException("HTTP chunk line too large");
    }

    private static void copyUntilEof(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[32768];
        int read;
        while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
    }

    private String discoverPlist() {
        String caps = Base64.getEncoder().encodeToString(
                "Apple iOS\u0000\u0000\u0001".getBytes(StandardCharsets.UTF_8));
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
                + "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                + "<plist version=\"1.0\"><dict>"
                + "<key>ReceiverComputerName</key><string>" + xml(deviceName) + "</string>"
                + "<key>ReceiverMediaCapabilities</key><data>" + caps + "</data>"
                + "<key>Manufacturer</key><string>Google</string>"
                + "</dict></plist>\n";
    }

    private synchronized boolean startMoseyNetwork() {
        if (rawMdns != null && rawMdns.isRunning()) {
            return true;
        }
        if (!isMoseyInterfaceReady() || httpPort <= 0) {
            Log.i(TAG, "Waiting for mosey0 link-local address and HTTPS server");
            return false;
        }
        acquireMulticastLock("raw-mdns");
        rawMdns = new RawMdnsEngine(serviceId, httpPort, new RawMdnsEngine.Listener() {
            @Override public void onFound(String name, String host, int port) {
                String endpoint = name + "@" + host + ":" + port;
                synchronized (seenServices) {
                    if (!seenServices.add(endpoint)) return;
                }
                sendEvent("{\"type\":\"airdrop_found\",\"name\":\"" + json(name)
                        + "\",\"host\":\"" + json(host) + "\",\"port\":" + port
                        + ",\"service\":\"" + AIRDROP_SERVICE + "\"}");
            }

            @Override public void onLost(String name) {
                synchronized (seenServices) {
                    java.util.Iterator<String> iterator = seenServices.iterator();
                    while (iterator.hasNext()) {
                        if (iterator.next().startsWith(name + "@")) iterator.remove();
                    }
                }
                sendEvent("{\"type\":\"airdrop_lost\",\"name\":\"" + json(name) + "\"}");
            }
        });
        boolean started = rawMdns.start();
        if (!started) rawMdns = null;
        return started;
    }

    private boolean isMoseyInterfaceReady() {
        try {
            java.net.NetworkInterface iface = java.net.NetworkInterface.getByName("mosey0");
            return iface != null && iface.isUp() && !iface.getInterfaceAddresses().isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private synchronized void stopMoseyNetwork() {
        if (rawMdns != null) {
            rawMdns.stop();
            rawMdns = null;
        }
        synchronized (seenServices) {
            seenServices.clear();
        }
        stopHttpsServer();
    }

    private synchronized void stopHttpsServer() {
        if (httpSocket != null) {
            try {
                httpSocket.close();
            } catch (Throwable ignored) {
            }
        }
        httpSocket = null;
        httpPort = 0;
    }

    private InetAddress moseyLinkLocalAddress() {
        try {
            java.net.NetworkInterface iface = java.net.NetworkInterface.getByName("mosey0");
            if (iface == null || !iface.isUp()) return null;
            for (java.net.InterfaceAddress address : iface.getInterfaceAddresses()) {
                InetAddress value = address.getAddress();
                if (value instanceof java.net.Inet6Address && value.isLinkLocalAddress()) {
                    return value;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String createServiceId() {
        byte[] value = new byte[6];
        new SecureRandom().nextBytes(value);
        return hex(value);
    }

    private void announceAirDropService() {
        RawMdnsEngine engine = rawMdns;
        if (engine != null) engine.requestAnnouncement();
    }

    private void startBleWakeupAdvertising() {
        try {
            BluetoothAdapter adapter = bluetoothAdapter();
            if (adapter == null) {
                Log.w(TAG, "Bluetooth adapter unavailable; will retry");
                return;
            }
            bleAdvertiser = adapter.getBluetoothLeAdvertiser();
            if (bleAdvertiser == null) {
                Log.w(TAG, "BLE advertiser unavailable; will retry");
                return;
            }
            byte[] payload = new byte[20];
            payload[0] = 5;
            payload[1] = 18;
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(false)
                    .setDiscoverable(false)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .addManufacturerData(76, payload)
                    .addServiceUuid(ParcelUuid.fromString(AIRDROP_UUID))
                    .build();
            bleAdvertiser.startAdvertising(settings, data, advertiseCallback);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to start BLE wakeup advertising", t);
        }
    }

    private void startAppleBleScan() {
        try {
            BluetoothAdapter adapter = bluetoothAdapter();
            if (adapter == null) {
                Log.w(TAG, "Bluetooth adapter unavailable for scan; will retry");
                return;
            }
            bleScanner = adapter.getBluetoothLeScanner();
            if (bleScanner == null) {
                Log.w(TAG, "BLE scanner unavailable; will retry");
                return;
            }
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                    .build();
            bleScanner.startScan(java.util.Collections.emptyList(), settings,
                    appleScanCallback);
            bleScanStarted = true;
            Log.i(TAG, "Apple BLE scan started");
        } catch (Throwable t) {
            Log.w(TAG, "Unable to start Apple BLE scan", t);
        }
    }

    private BluetoothAdapter bluetoothAdapter() {
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (manager != null) {
            return manager.getAdapter();
        }
        return BluetoothAdapter.getDefaultAdapter();
    }

    private synchronized void resetBluetoothOperations() {
        if (bleScanner != null) {
            try {
                bleScanner.stopScan(appleScanCallback);
            } catch (Throwable ignored) {
            }
        }
        if (bleAdvertiser != null) {
            try {
                bleAdvertiser.stopAdvertising(advertiseCallback);
            } catch (Throwable ignored) {
            }
        }
        bleScanner = null;
        bleAdvertiser = null;
        bleScanStarted = false;
        bleAdvertisingStarted = false;
    }

    private void acquireMulticastLock(String reason) {
        if (multicastLock != null && multicastLock.isHeld()) {
            return;
        }
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifi == null) {
                Log.w(TAG, "WifiManager unavailable; NSD multicast lock not acquired");
                return;
            }
            multicastLock = wifi.createMulticastLock("mosey-" + reason);
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
            Log.i(TAG, "Acquired NSD multicast lock: " + reason);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to acquire NSD multicast lock", t);
        }
    }

    private void sendEvent(String json) {
        final String eventJson = json;
        Thread sender = new Thread(new Runnable() {
            @Override public void run() {
                sendEventBlocking(eventJson);
            }
        }, "mosey_shim_event");
        sender.setDaemon(true);
        sender.start();
    }

    private void sendEventBlocking(String json) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", EVENT_PORT), 1000);
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            OutputStream out = socket.getOutputStream();
            writeLe32(out, body.length);
            out.write(body);
            out.flush();
            Log.i(TAG, "Event sent: " + json);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to send shim event", t);
        }
    }

    private static void readFully(InputStream in, byte[] buf) throws Exception {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new IllegalStateException("short read");
            }
            off += n;
        }
    }

    private static void writeLe32(OutputStream out, int value) throws Exception {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
        }
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String json(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte value : data) {
            out.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            out.append(Character.forDigit(value & 0x0f, 16));
        }
        return out.toString();
    }
}
