package vn.gateway.ipv6test;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.LinkProperties;
import android.net.LinkAddress;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.util.concurrent.LinkedBlockingQueue;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

public class MainActivity extends Activity {
    private static final int UI_INK = 0xFF222222;
    private volatile int gatewayPort = 8080;
    private volatile int modemPort = 443;
    private volatile String modemProtocol = "https";
    private static final String PREFS_NAME = "gateway_settings";
    private static final String PREF_MODEM_IP = "modem_ip";
    private static final String PREF_MODEM_PORT = "modem_port";
    private static final String PREF_MODEM_PROTOCOL = "modem_protocol";
    private static final String PREF_GATEWAY_PORT = "gateway_port";
    private static final String PREF_PROXY_IP = "proxy_ip";
    private volatile String modemHost = "192.168.1.1";
    private android.widget.EditText modemIpInput;
    private android.widget.EditText modemPortInput;
    private android.widget.RadioGroup modemProtocolGroup;
    private android.widget.EditText cellularIpv6Input;
    private android.widget.EditText gatewayPortInput;
    private android.widget.EditText proxyIpInput;

    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback cellularCallback;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private Network cellularNetwork;
    private Network wifiNetwork;
    private Inet6Address cellularIpv6;
    private ServerSocket serverSocket;
    // Tất cả socket của các phiên TCP Tunnel đang hoạt động; đóng khi dừng Gateway.
    private final Set<Socket> activeTunnelSockets = Collections.synchronizedSet(new HashSet<>());
    private ExecutorService executor;
    private TextView status;
    private TextView details;
    private TextView logView;
    private TextView accessLinkView;
    private TextView proxyLinkView;
    private ScrollView logScroll;
    private ScrollView pageScroll;
    private File logFile;
    private final Object logLock = new Object();
    private static final String LOG_CLEAR_MARKER = "\u0000__CLEAR_LOG__\u0000";
    private final LinkedBlockingQueue<String> diskLogQueue = new LinkedBlockingQueue<>(16384);
    private volatile boolean logWriterRunning = true;
    private Thread logWriterThread;
    // UI chỉ giữ một cửa sổ log gần nhất; file log vẫn lưu đầy đủ không giới hạn.
    private static final int MAX_VISIBLE_LOG_LINES = 300;
    private static final long LOG_UI_REFRESH_MS = 500L;
    private final Object displayLogLock = new Object();
    private final ArrayDeque<String> displayLogLines = new ArrayDeque<>();
    private final Handler logUiHandler = new Handler(Looper.getMainLooper());
    private boolean logUiRefreshPending = false;
    private final AtomicLong requestSequence = new AtomicLong(0);
    private Button startButton;
    private Button stopButton;
    private volatile boolean running = false;
    private volatile boolean serverStarting = false;
    private volatile long serverGeneration = 0L;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initLogging();
        startLogWriter();
        log("APP", "onCreate version=0.6.0-TCP-TUNNEL");
        final Thread.UncaughtExceptionHandler oldHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            logException("UNCAUGHT", throwable);
            if (oldHandler != null) oldHandler.uncaughtException(thread, throwable);
        });
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        modemHost = prefs.getString(PREF_MODEM_IP, "192.168.1.1");
        modemPort = prefs.getInt(PREF_MODEM_PORT, 443);
        modemProtocol = prefs.getString(PREF_MODEM_PROTOCOL, "https");
        gatewayPort = prefs.getInt(PREF_GATEWAY_PORT, 8080);
        // Bounded worker pool: prevent unbounded thread creation from inbound clients.
        // CallerRunsPolicy applies back-pressure to the accept loop when the queue is full.
        executor = new ThreadPoolExecutor(
                2, 16, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                r -> {
                    Thread t = new Thread(r, "Gateway-Worker");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());

        buildUi();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView makeText(String value, float size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, size);
        t.setTextColor(color);
        return t;
    }

    private void buildUi() {
        final int ink = 0xFF171717;
        final int muted = 0xFF555555;
        final int line = 0xFF777777;
        final int panel = 0xFFEAEAEA;
        final int green = 0xFF187A32;

        ScrollView page = new ScrollView(this);
        pageScroll = page;
        page.setFillViewport(true);
        page.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        page.setFocusableInTouchMode(true);
        page.setBackgroundColor(0xFFF0F0F0);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(12));
        page.addView(root);

        // Header: classic grayscale, compact and clearly bordered.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER);
        header.setPadding(dp(12), dp(9), dp(12), dp(9));
        header.setBackground(makeBorderBackground(0xFFF7F7F7, ink, 1, 5));
        TextView title = makeText("IPv6 MODEM GATEWAY", 18, ink);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        header.addView(title);
        TextView subtitle = makeText("TCP TUNNEL  •  TRUY CẬP MODEM QUA IPv6", 10, muted);
        subtitle.setGravity(Gravity.CENTER);
        header.addView(subtitle);
        root.addView(header);

        // Gateway status and actions.
        LinearLayout statusCard = panelCard(root, "TRẠNG THÁI GATEWAY", panel, ink);
        status = makeText("●  ĐANG DỪNG", 14, green);
        status.setTypeface(null, android.graphics.Typeface.BOLD);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(8), dp(5), dp(8), dp(5));
        statusCard.addView(status);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        startButton = new Button(this);
        startButton.setText("▶  KHỞI ĐỘNG GATEWAY");
        startButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 12);
        startButton.setTextColor(ink);
        startButton.setOnClickListener(v -> startNetworks());
        actions.addView(startButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        stopButton = new Button(this);
        stopButton.setText("■  DỪNG");
        stopButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 12);
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(v -> stopAll());
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(dp(112), dp(48));
        stopLp.leftMargin = dp(7);
        actions.addView(stopButton, stopLp);
        statusCard.addView(actions);

        // Connection settings: modem IP, protocol, modem port, Cellular IPv6 and Gateway port.
        LinearLayout configCard = panelCard(root, "CẤU HÌNH KẾT NỐI", panel, ink);
        modemIpInput = createConfigInput(modemHost, "Ví dụ: 192.168.1.1", android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        modemProtocolGroup = new android.widget.RadioGroup(this);
        modemProtocolGroup.setOrientation(android.widget.RadioGroup.HORIZONTAL);
        android.widget.RadioButton httpsRadio = new android.widget.RadioButton(this);
        httpsRadio.setId(View.generateViewId());
        httpsRadio.setText("HTTPS");
        httpsRadio.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
        android.widget.RadioButton telnetRadio = new android.widget.RadioButton(this);
        telnetRadio.setId(View.generateViewId());
        telnetRadio.setText("TELNET");
        telnetRadio.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
        modemProtocolGroup.addView(httpsRadio);
        modemProtocolGroup.addView(telnetRadio);
        httpsRadio.setChecked(!"telnet".equalsIgnoreCase(modemProtocol));
        telnetRadio.setChecked("telnet".equalsIgnoreCase(modemProtocol));
        modemPortInput = createConfigInput(String.valueOf(modemPort), "443", android.text.InputType.TYPE_CLASS_NUMBER);
        modemProtocolGroup.setOnCheckedChangeListener((group, checkedId) -> {
            boolean telnet = checkedId == telnetRadio.getId();
            modemProtocol = telnet ? "telnet" : "https";
            if (modemPortInput != null) {
                String current = modemPortInput.getText().toString().trim();
                if ("443".equals(current) || "23".equals(current) || current.isEmpty()) {
                    modemPortInput.setText(telnet ? "23" : "443");
                }
                modemPortInput.setHint(telnet ? "23" : "443");
            }
            refreshAccessLinkText();
            refreshDetails();
        });
        cellularIpv6Input = createConfigInput("Chưa có IPv6", "IPv6 Cellular tự động hiển thị", android.text.InputType.TYPE_CLASS_TEXT);
        cellularIpv6Input.setFocusable(false);
        cellularIpv6Input.setFocusableInTouchMode(false);
        cellularIpv6Input.setClickable(false);
        cellularIpv6Input.setLongClickable(true);
        cellularIpv6Input.setTextIsSelectable(true);
        cellularIpv6Input.setBackground(makeBorderBackground(0xFFEAEAEA, line, 1, 4));
        gatewayPortInput = createConfigInput(String.valueOf(gatewayPort), "8080", android.text.InputType.TYPE_CLASS_NUMBER);
        addConfigRow(configCard, "1. IP MODEM", modemIpInput);
        TextView protocolLabel = makeText("2. PHƯƠNG THỨC QUẢN TRỊ MODEM", 12, UI_INK);
        protocolLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams protocolLabelLp = new LinearLayout.LayoutParams(-1, -2);
        protocolLabelLp.topMargin = dp(7);
        configCard.addView(protocolLabel, protocolLabelLp);
        configCard.addView(modemProtocolGroup, new LinearLayout.LayoutParams(-1, dp(42)));
        addConfigRow(configCard, "3. PORT MODEM", modemPortInput);
        addConfigRow(configCard, "4. IPv6 ANDROID A", cellularIpv6Input);
        addConfigRow(configCard, "5. PORT TCP TUNNEL IPv6", gatewayPortInput);
        Button saveButton = new Button(this);
        saveButton.setText("▣  LƯU CẤU HÌNH");
        saveButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 12);
        saveButton.setOnClickListener(v -> {
            if (applyConnectionSettingsFromInputs())
                android.widget.Toast.makeText(this, "Đã lưu cấu hình kết nối", android.widget.Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(-1, dp(46));
        saveLp.topMargin = dp(8);
        configCard.addView(saveButton, saveLp);

        // Requested full-width stacked panels: connection info ABOVE access link.
        // This makes both sections wider and easier to read on a phone screen.
        LinearLayout infoCard = panelCard(root, "THÔNG TIN KẾT NỐI", panel, ink);
        details = makeText("Đang đọc thông tin mạng...", 12, ink);
        details.setPadding(dp(12), dp(12), dp(12), dp(12));
        details.setMinHeight(dp(112));
        details.setBackground(makeBorderBackground(0xFFFFFFFF, line, 1, 4));
        details.setTextIsSelectable(true);
        infoCard.addView(details, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout accessCard = panelCard(root, "ĐỊA CHỈ TRUY CẬP MODEM TỪ ANDROID B", panel, ink);
        accessLinkView = makeText("", 14, ink);
        accessLinkView.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        accessLinkView.setGravity(Gravity.CENTER);
        accessLinkView.setPadding(dp(8), dp(14), dp(8), dp(14));
        accessLinkView.setBackground(makeBorderBackground(0xFFFFFFFF, line, 1, 4));
        LinearLayout directLinkRow = new LinearLayout(this);
        directLinkRow.setOrientation(LinearLayout.HORIZONTAL);
        directLinkRow.setGravity(Gravity.CENTER_VERTICAL);
        directLinkRow.addView(accessLinkView, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView copyDirectIcon = makeCopyIconButton("Sao chép link truy cập trực tiếp", () -> copyGatewayLink(false));
        LinearLayout.LayoutParams directIconLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        directIconLp.leftMargin = dp(7);
        directLinkRow.addView(copyDirectIcon, directIconLp);
        accessCard.addView(directLinkRow, new LinearLayout.LayoutParams(-1, -2));

        // Proxy group: IP input auto-saves; generated URL updates as the IP changes.
        LinearLayout proxyCard = new LinearLayout(this);
        proxyCard.setOrientation(LinearLayout.VERTICAL);
        proxyCard.setPadding(dp(12), dp(12), dp(12), dp(12));
        proxyCard.setBackground(makeBorderBackground(0xFFF0FAF0, 0xFF62B96B, 2, 7));
        LinearLayout.LayoutParams proxyCardLp = new LinearLayout.LayoutParams(-1, -2);
        proxyCardLp.topMargin = dp(10);
        root.addView(proxyCard, proxyCardLp);

        TextView proxyHeading = makeText("▤  PROXY (IP Proxy hoặc Tên miền)", 14, ink);
        proxyHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        proxyCard.addView(proxyHeading, new LinearLayout.LayoutParams(-1, -2));

        TextView proxyLabel = makeText("IP PROXY", 13, ink);
        proxyLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams proxyLabelLp = new LinearLayout.LayoutParams(-1, -2);
        proxyLabelLp.topMargin = dp(10);
        proxyCard.addView(proxyLabel, proxyLabelLp);
        proxyIpInput = createConfigInput(getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_PROXY_IP, ""), "Ví dụ: 4.254.128.3", android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        proxyCard.addView(proxyIpInput, new LinearLayout.LayoutParams(-1, dp(46)));

        TextView proxyLinkLabel = makeText("LINK TRUY CẬP QUA PROXY", 13, ink);
        proxyLinkLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams proxyLinkLabelLp = new LinearLayout.LayoutParams(-1, -2);
        proxyLinkLabelLp.topMargin = dp(9);
        proxyCard.addView(proxyLinkLabel, proxyLinkLabelLp);
        proxyLinkView = makeText("Nhập IP Proxy để tạo link", 12, ink);
        proxyLinkView.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        proxyLinkView.setGravity(Gravity.CENTER);
        proxyLinkView.setPadding(dp(8), dp(12), dp(8), dp(12));
        proxyLinkView.setBackground(makeBorderBackground(0xFFFFFFFF, line, 1, 4));
        LinearLayout proxyLinkRow = new LinearLayout(this);
        proxyLinkRow.setOrientation(LinearLayout.HORIZONTAL);
        proxyLinkRow.setGravity(Gravity.CENTER_VERTICAL);
        proxyLinkRow.addView(proxyLinkView, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView copyProxyIcon = makeCopyIconButton("Sao chép link Proxy", this::copyProxyLink);
        LinearLayout.LayoutParams proxyIconLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        proxyIconLp.leftMargin = dp(7);
        proxyLinkRow.addView(copyProxyIcon, proxyIconLp);
        LinearLayout.LayoutParams proxyLinkRowLp = new LinearLayout.LayoutParams(-1, -2);
        proxyLinkRowLp.topMargin = dp(5);
        proxyCard.addView(proxyLinkRow, proxyLinkRowLp);

        proxyIpInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_PROXY_IP, s.toString().trim()).apply();
                refreshProxyLink();
            }
            @Override public void afterTextChanged(android.text.Editable e) { }
        });

        // Large bordered activity log; retains the 200-line rolling history.
        LinearLayout logCard = panelCard(root, "NHẬT KÝ HOẠT ĐỘNG", panel, ink);
        logScroll = new ScrollView(this);
        logScroll.setFillViewport(false);
        logScroll.setVerticalScrollBarEnabled(true);
        logScroll.setHorizontalScrollBarEnabled(true);
        logScroll.setScrollbarFadingEnabled(false);
        // Keep gestures inside the log panel from being stolen by the outer page ScrollView.
        logScroll.setNestedScrollingEnabled(false);
        logScroll.setOnTouchListener((v, event) -> {
            if (pageScroll != null) {
                int action = event.getActionMasked();
                if (action == android.view.MotionEvent.ACTION_DOWN ||
                        action == android.view.MotionEvent.ACTION_MOVE) {
                    pageScroll.requestDisallowInterceptTouchEvent(true);
                } else if (action == android.view.MotionEvent.ACTION_UP ||
                        action == android.view.MotionEvent.ACTION_CANCEL) {
                    pageScroll.requestDisallowInterceptTouchEvent(false);
                }
            }
            return false;
        });
        logScroll.setBackground(makeBorderBackground(0xFFFFFFFF, ink, 1, 4));
        logView = makeText("Log sẽ xuất hiện tại đây.", 10, ink);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        // Log is read-only; copy is handled by the dedicated button. Avoid focus jumps.
        logView.setFocusable(false);
        logView.setFocusableInTouchMode(false);
        logView.setTextIsSelectable(false);
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        logView.setBackgroundColor(0xFFFFFFFF);
        logScroll.addView(logView, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(-1, dp(400));
        logLp.topMargin = dp(4);
        logCard.addView(logScroll, logLp);

        LinearLayout logActions = new LinearLayout(this);
        logActions.setOrientation(LinearLayout.HORIZONTAL);
        Button copyLogButton = new Button(this);
        copyLogButton.setText("▢  SAO CHÉP 200 DÒNG GẦN NHẤT");
        copyLogButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 10);
        copyLogButton.setOnClickListener(v -> copyRecentLog());
        logActions.addView(copyLogButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        Button shareLogButton = new Button(this);
        shareLogButton.setText("SHARE FILE LOG");
        shareLogButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 10);
        shareLogButton.setOnClickListener(v -> shareLogFile());
        LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(0, dp(46), 1);
        shareLp.leftMargin = dp(6);
        logActions.addView(shareLogButton, shareLp);
        Button clearLogButton = new Button(this);
        clearLogButton.setText("XÓA LOG");
        clearLogButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 10);
        clearLogButton.setOnClickListener(v -> confirmClearLogs());
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(0, dp(46), 0.7f);
        clearLp.leftMargin = dp(6);
        logActions.addView(clearLogButton, clearLp);
        LinearLayout.LayoutParams logActionsLp = new LinearLayout.LayoutParams(-1, -2);
        logActionsLp.topMargin = dp(8);
        logCard.addView(logActions, logActionsLp);

        TextView warning = makeText("LƯU Ý: Android A chuyển tiếp TCP nguyên bản. HTTPS dùng trình duyệt; Telnet dùng ứng dụng Telnet client trên Android B.", 11, muted);
        warning.setPadding(dp(10), dp(8), dp(10), dp(8));
        warning.setBackground(makeBorderBackground(0xFFF7F7F7, line, 1, 4));
        LinearLayout.LayoutParams warningLp = new LinearLayout.LayoutParams(-1, -2);
        warningLp.topMargin = dp(8);
        root.addView(warning, warningLp);

        setContentView(page);
        refreshLogView();
        refreshAccessLinkText();
        refreshDetails();
    }

    private TextView makeCopyIconButton(String description, Runnable action) {
        TextView button = makeText("⧉", 25, 0xFFFFFFFF);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setFocusable(true);
        button.setClickable(true);
        button.setBackground(makeBorderBackground(0xFF087BD9, 0xFF087BD9, 1, 8));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private LinearLayout panelCard(LinearLayout parent, String heading, int fill, int ink) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8), dp(7), dp(8), dp(8));
        card.setBackground(makeBorderBackground(0xFFFFFFFF, 0xFF555555, 1, 5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        parent.addView(card, lp);
        TextView title = makeText(heading, 14, ink);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(dp(7), dp(5), dp(7), dp(8));
        title.setBackground(makeBorderBackground(fill, 0xFF888888, 1, 3));
        card.addView(title);
        return card;
    }

    private void addConfigRow(LinearLayout parent, String label, android.widget.EditText input) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = makeText(label, 12, UI_INK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1.05f));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, dp(42), 1.35f);
        inputLp.leftMargin = dp(6);
        row.addView(input, inputLp);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = dp(4);
        parent.addView(row, rowLp);
        View divider = new View(this);
        divider.setBackgroundColor(0xFFCCCCCC);
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(-1, dp(1));
        dividerLp.topMargin = dp(3);
        parent.addView(divider, dividerLp);
    }

    private android.graphics.drawable.GradientDrawable makeBorderBackground(int fillColor, int strokeColor, int strokeDp, int radiusDp) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    private android.widget.EditText createConfigInput(String value, String hint, int inputType) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setInputType(inputType);
        input.setHint(hint);
        input.setText(value);
        input.setSelectAllOnFocus(true);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
        input.setPadding(dp(9), dp(5), dp(9), dp(5));
        input.setBackground(makeBorderBackground(0xFFFFFFFF, 0xFF7B8794, 1, 5));
        return input;
    }

    private void addConfigField(LinearLayout parent, String label, android.widget.EditText input) {
        TextView title = makeText(label, 11, UI_INK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = dp(9);
        parent.addView(title, titleLp);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(-1, dp(42));
        inputLp.topMargin = dp(3);
        parent.addView(input, inputLp);
    }

    private boolean applyConnectionSettingsFromInputs() {
        String ip = modemIpInput == null ? modemHost : modemIpInput.getText().toString().trim();
        int mp, gp;
        try {
            mp = Integer.parseInt(modemPortInput == null ? String.valueOf(modemPort) : modemPortInput.getText().toString().trim());
            gp = Integer.parseInt(gatewayPortInput == null ? String.valueOf(gatewayPort) : gatewayPortInput.getText().toString().trim());
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Cổng phải là số từ 1 đến 65535.", android.widget.Toast.LENGTH_LONG).show();
            return false;
        }
        if (!isPrivateIpv4(ip)) {
            if (modemIpInput != null) modemIpInput.setError("Nhập IPv4 nội bộ hợp lệ");
            return false;
        }
        if (mp < 1 || mp > 65535 || gp < 1 || gp > 65535) {
            android.widget.Toast.makeText(this, "Cổng phải nằm trong khoảng 1–65535.", android.widget.Toast.LENGTH_LONG).show();
            return false;
        }
        modemHost = ip;
        modemPort = mp;
        gatewayPort = gp;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(PREF_MODEM_IP, modemHost)
                .putInt(PREF_MODEM_PORT, modemPort)
                .putString(PREF_MODEM_PROTOCOL, modemProtocol)
                .putInt(PREF_GATEWAY_PORT, gatewayPort).apply();
        if (modemIpInput != null) modemIpInput.setError(null);
        refreshDetails();
        log("CONFIG", "Đã lưu modem " + modemProtocol.toUpperCase(Locale.ROOT) + " " + modemHost + ":" + modemPort + " ; Gateway IPv6 :" + gatewayPort);
        return true;
    }

    private boolean applyModemIpFromInput() {
        if (modemPortInput != null && gatewayPortInput != null) return applyConnectionSettingsFromInputs();
        String value = modemIpInput == null ? modemHost : modemIpInput.getText().toString().trim();
        if (!isPrivateIpv4(value)) {
            if (modemIpInput != null) modemIpInput.setError("Nhập IPv4 nội bộ hợp lệ, ví dụ 192.168.1.1");
            android.widget.Toast.makeText(this,
                    "IP modem không hợp lệ. Chỉ chấp nhận IPv4 mạng nội bộ (10.x, 172.16-31.x, 192.168.x hoặc 100.64-127.x).",
                    android.widget.Toast.LENGTH_LONG).show();
            return false;
        }
        modemHost = value;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_MODEM_IP, modemHost).apply();
        if (modemIpInput != null) modemIpInput.setError(null);
        refreshDetails();
        log("MODEM_CONFIG", "Đã lưu IP modem " + modemHost + ":" + modemPort + " (" + modemProtocol.toUpperCase(Locale.ROOT) + ")");
        return true;
    }

    private boolean isPrivateIpv4(String value) {
        if (value == null || !value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        String[] parts = value.split("\\.");
        int[] octets = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                octets[i] = Integer.parseInt(parts[i]);
                if (octets[i] < 0 || octets[i] > 255) return false;
            }
        } catch (NumberFormatException e) { return false; }
        if (octets[0] == 10) return true;
        if (octets[0] == 192 && octets[1] == 168) return true;
        if (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31) return true;
        if (octets[0] == 100 && octets[1] >= 64 && octets[1] <= 127) return true;
        return false;
    }

    private void startNetworks() {
        if (!applyModemIpFromInput()) return;
        log("START", "Người dùng nhấn KHỞI ĐỘNG GATEWAY; modem=" + modemProtocol.toUpperCase(Locale.ROOT) + " " + modemHost + ":" + modemPort);

        try {
            Intent serviceIntent = new Intent(this, GatewayService.class);
            androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent);
        } catch (Exception e) {
            logException("FOREGROUND_SERVICE", e);
            postStatus("Không thể bật dịch vụ chạy nền: " + e.getMessage());
            startButton.setEnabled(true);
            return;
        }
        if (cellularCallback != null || wifiCallback != null) {
            // Đã có phiên Gateway: đóng listener cũ của chính ứng dụng rồi bind lại
            // đúng cổng đang nhập. Không thể đóng socket thuộc ứng dụng khác trên Android.
            if (serverSocket != null && !serverSocket.isClosed()) {
                log("PORT", "Cổng TCP Tunnel IPv6 :" + gatewayPort + " đang được Gateway giữ; đóng listener cũ để mở lại.");
                postStatus("Đang đóng listener cũ và mở lại cổng " + gatewayPort + "...");
                stopServerOnly();
                maybeStartServer();
            } else if (cellularNetwork != null && cellularIpv6 != null) {
                log("PORT", "Listener chưa mở; thử bind cổng TCP Tunnel IPv6 :" + gatewayPort + " ngay.");
                maybeStartServer();
            } else {
                log("PORT", "Chưa có IPv6 Cellular để mở cổng :" + gatewayPort + "; tiếp tục chờ mạng.");
                postStatus("Đang chờ IPv6 Cellular để mở cổng " + gatewayPort + "...");
            }
            return;
        }
        startButton.setEnabled(false);
        status.setText("Đang yêu cầu 4G và theo dõi Wi-Fi...");
        NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        NetworkRequest wifiRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        cellularCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                log("CELLULAR", "onAvailable: " + network);
                cellularNetwork = network;
                updateCellularAddress();
                maybeStartServer();
            }
            @Override public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                log("CELLULAR", "onLinkPropertiesChanged");
                if (network.equals(cellularNetwork)) {
                    updateCellularAddress();
                    maybeStartServer();
                }
            }
            @Override public void onLost(Network network) {
                log("CELLULAR", "onLost: " + network);
                if (network.equals(cellularNetwork)) {
                    cellularNetwork = null;
                    cellularIpv6 = null;
                    stopServerOnly();
                    postStatus("Mạng 4G bị mất.");
                    refreshDetails();
                }
            }
        };
        wifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                log("WIFI", "onAvailable: " + network);
                wifiNetwork = network;
                postStatus("Đã phát hiện Wi-Fi. Đang kiểm tra hai mạng...");
                refreshDetails();
            }
            @Override public void onLost(Network network) {
                log("WIFI", "onLost: " + network);
                if (network.equals(wifiNetwork)) {
                    wifiNetwork = null;
                    postStatus("Wi-Fi bị mất.");
                    refreshDetails();
                }
            }
        };

        try {
            log("NETWORK", "Đăng ký callback Cellular");
            cm.requestNetwork(cellRequest, cellularCallback);
            log("NETWORK", "Đăng ký callback Wi-Fi");

            cm.requestNetwork(wifiRequest, wifiCallback);
        } catch (Exception e) {
            logException("REQUEST_NETWORK", e);
            postStatus("Không thể yêu cầu mạng: " + e.getMessage());
            startButton.setEnabled(true);
        }
    }

    private synchronized void updateCellularAddress() {
        log("IPV6", "Đang đọc LinkProperties Cellular");
        if (cellularNetwork == null) return;
        LinkProperties lp = cm.getLinkProperties(cellularNetwork);
        Inet6Address found = null;
        if (lp != null) {
            for (LinkAddress la : lp.getLinkAddresses()) {
                InetAddress addr = la.getAddress();
                if (addr instanceof Inet6Address && isGlobal((Inet6Address) addr)) {
                    found = (Inet6Address) addr;
                    break;
                }
            }
        }
        Inet6Address previous = cellularIpv6;
        boolean changed = previous == null ? found != null : !previous.equals(found);
        if (changed) {
            log("IPV6", "Địa chỉ Cellular thay đổi: " +
                    (previous == null ? "(none)" : previous.getHostAddress()) + " -> " +
                    (found == null ? "(none)" : found.getHostAddress()));
            // Close the old listener so the next callback can bind the new global IPv6.
            stopServerOnly();
        }
        cellularIpv6 = found;
        if (found != null) log("IPV6", "IPv6 global hiện tại: " + found.getHostAddress());
        refreshDetails();
    }

    private synchronized void maybeStartServer() {
        log("SERVER", "maybeStartServer running=" + running + ", starting=" + serverStarting +
                ", cellular=" + (cellularNetwork != null) + ", ipv6=" + (cellularIpv6 != null));
        if (running || serverStarting || cellularNetwork == null || cellularIpv6 == null) return;

        final Inet6Address bindAddress = cellularIpv6;
        final int bindPort = gatewayPort;
        final long generation = ++serverGeneration;
        serverStarting = true;
        executor.execute(() -> {
            ServerSocket ss = null;
            try {
                log("PORT", "Kiểm tra và chuẩn bị mở cổng TCP Tunnel IPv6 :" + bindPort);
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(bindAddress, bindPort), 20);

                synchronized (MainActivity.this) {
                    // Nếu người dùng đã dừng/khởi động lại trong lúc bind, không giữ socket cũ.
                    if (generation != serverGeneration) {
                        try { ss.close(); } catch (Exception ignored) {}
                        log("PORT", "Listener thuộc lần khởi động cũ; đã đóng socket vừa tạo.");
                        return;
                    }
                    serverSocket = ss;
                    running = true;
                    serverStarting = false;
                }

                log("SERVER", "Listener đã mở thành công cổng " + bindPort);
                postStatus("TCP Tunnel đang nghe trên IPv6 Cellular cổng " + bindPort);
                runOnUiThread(() -> {
                    stopButton.setEnabled(true);
                    refreshDetails();
                });
                while (running && generation == serverGeneration) {
                    Socket client = ss.accept();
                    log("TUNNEL", "Client inbound: " + client.getInetAddress());
                    activeTunnelSockets.add(client);
                    executor.execute(() -> handleTcpTunnel(client));
                }
            } catch (Exception e) {
                boolean current;
                synchronized (MainActivity.this) {
                    current = generation == serverGeneration;
                    if (current) {
                        serverStarting = false;
                        if (serverSocket == ss) {
                            serverSocket = null;
                            running = false;
                        }
                    }
                }
                if (current) {
                    logException("SERVER_BIND_OR_ACCEPT", e);
                    String message = (e instanceof java.net.BindException)
                            ? "Cổng " + bindPort + " vẫn đang được sử dụng. Gateway đã thử mở lại nhưng không thể chiếm cổng."
                            : "Listener IPv6 gặp lỗi: " + e.getClass().getSimpleName() + " - " + e.getMessage();
                    postStatus(message);
                    if (ss != null) try { ss.close(); } catch (Exception ignored) {}
                    runOnUiThread(() -> {
                        startButton.setEnabled(true);
                        stopButton.setEnabled(false);
                    });
                } else {
                    log("SERVER", "Socket của lần chạy cũ đã được đóng; bỏ qua lỗi: " + e.getMessage());
                }
            }
        });
    }

    private void copyRecentLog() {
        try {
            String all = readLog();
            String[] lines = all.split("\n");
            StringBuilder out = new StringBuilder();
            int start = Math.max(0, lines.length - 200);
            out.append("===== GATEWAY TRACE: 200 DÒNG GẦN NHẤT =====\n");
            for (int i = start; i < lines.length; i++) out.append(lines[i]).append('\n');
            android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cb.setPrimaryClip(android.content.ClipData.newPlainText("Gateway trace gần nhất", out.toString()));
            android.widget.Toast.makeText(this, "Đã sao chép tối đa 200 dòng log gần nhất.", android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Không sao chép được log: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /**
     * TCP Tunnel hai chiều: không đọc hoặc sửa HTTP/TLS.
     * Client đi vào qua listener IPv6 Cellular; socket đích được tạo từ
     * wifiNetwork để modem luôn được truy cập qua Wi-Fi.
     */
    private void handleTcpTunnel(Socket client) {
        final long connectionId = requestSequence.incrementAndGet();
        Socket modemSocket = null;
        try {
            if (!running) throw new java.io.IOException("Gateway đã dừng");
            final Network wifi = wifiNetwork;
            if (wifi == null) throw new java.io.IOException("Chưa phát hiện Wi-Fi modem");

            final String targetHost = modemHost;
            final int targetPort = modemPort;
            client.setSoTimeout(0);
            client.setTcpNoDelay(true);
            client.setKeepAlive(true);
            log("TUNNEL", "[TCP-" + connectionId + "] Client " + client.getInetAddress() +
                    " → " + targetHost + ":" + targetPort);

            // SocketFactory của Network bảo đảm kết nối đích đi qua Wi-Fi,
            // không phụ thuộc mạng mặc định Android đang chọn.
            modemSocket = wifi.getSocketFactory().createSocket();
            activeTunnelSockets.add(modemSocket);
            modemSocket.connect(new InetSocketAddress(targetHost, targetPort), 8000);
            modemSocket.setSoTimeout(0);
            modemSocket.setTcpNoDelay(true);
            modemSocket.setKeepAlive(true);

            if (!running || client.isClosed()) {
                closeTunnelSockets(client, modemSocket);
                return;
            }

            log("TUNNEL", "[TCP-" + connectionId + "] Đã kết nối modem qua Wi-Fi; bắt đầu relay hai chiều");
            AtomicInteger finishedDirections = new AtomicInteger(0);
            AtomicBoolean terminated = new AtomicBoolean(false);
            startTunnelPipe(client, modemSocket, client, modemSocket,
                    "ANDROID_B_TO_MODEM", connectionId, finishedDirections, terminated);
            startTunnelPipe(modemSocket, client, client, modemSocket,
                    "MODEM_TO_ANDROID_B", connectionId, finishedDirections, terminated);
        } catch (Exception e) {
            if (running) logException("TCP_TUNNEL_" + connectionId, e);
            closeTunnelSockets(client, modemSocket);
        }
    }

    /** Sao chép byte nguyên bản theo một chiều; EOF chỉ half-close chiều gửi. */
    private void startTunnelPipe(Socket source, Socket destination,
                                 Socket client, Socket modemSocket, String direction,
                                 long connectionId, AtomicInteger finishedDirections,
                                 AtomicBoolean terminated) {
        Thread thread = new Thread(() -> {
            try {
                InputStream in = source.getInputStream();
                OutputStream out = destination.getOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                    out.flush();
                }
                try { destination.shutdownOutput(); } catch (Exception ignored) { }
                log("TUNNEL", "[TCP-" + connectionId + "] " + direction + " kết thúc luồng dữ liệu");
                if (finishedDirections.incrementAndGet() == 2 && terminated.compareAndSet(false, true)) {
                    closeTunnelSockets(client, modemSocket);
                    log("TUNNEL", "[TCP-" + connectionId + "] Đóng phiên TCP Tunnel hoàn tất");
                }
            } catch (Exception e) {
                if (terminated.compareAndSet(false, true)) {
                    if (running) logException("TUNNEL_" + connectionId + "_" + direction, e);
                    closeTunnelSockets(client, modemSocket);
                }
            }
        }, "TCP-Tunnel-" + connectionId + "-" + direction);
        thread.setDaemon(true);
        thread.start();
    }

    private void closeTunnelSockets(Socket client, Socket modemSocket) {
        if (client != null) {
            activeTunnelSockets.remove(client);
            try { client.close(); } catch (Exception ignored) { }
        }
        if (modemSocket != null) {
            activeTunnelSockets.remove(modemSocket);
            try { modemSocket.close(); } catch (Exception ignored) { }
        }
    }

    private String testModemOverWifi() {
        log("MODEM", "Bắt đầu thử kết nối HTTPS tới " + modemHost + ":" + modemPort);
        Network wifi = wifiNetwork;
        if (wifi == null) return "ERROR: Chưa phát hiện Wi-Fi. Hãy kết nối Wi-Fi modem.\n";
        Socket raw = null;
        SSLSocket tls = null;
        try {
            // SocketFactory from this Network ensures this outbound socket uses Wi-Fi,
            // independently of the phone's default network.
            raw = wifi.getSocketFactory().createSocket();
            raw.connect(new InetSocketAddress(modemHost, modemPort), 5000);
            // Chỉ dùng SSL bỏ qua chứng chỉ cho kết nối modem nội bộ này.
            // Socket TCP ban đầu vẫn được tạo từ wifi.getSocketFactory(), vì vậy
            // lưu lượng đi qua Wi-Fi; không thay đổi cấu hình mạng toàn ứng dụng.
            tls = (SSLSocket) createModemOnlySslSocketFactory()
                    .createSocket(raw, modemHost, modemPort, true);
            tls.setSoTimeout(5000);
            log("MODEM", "TCP tới modem thành công; bắt đầu TLS handshake (bỏ qua chứng chỉ riêng modem)");
            tls.startHandshake();
            log("MODEM", "TLS handshake thành công với modem (certificate verification skipped for modem only)");
            OutputStream out = tls.getOutputStream();
            out.write(("GET / HTTP/1.1\r\nHost: " + modemHost +
                    "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    tls.getInputStream(), StandardCharsets.UTF_8));
            String first = reader.readLine();
            return "OK: Wi-Fi đã kết nối được HTTPS modem.\n" +
                    "Modem response: " + first + "\n";
        } catch (javax.net.ssl.SSLHandshakeException e) {
            logException("MODEM_TLS", e);
            return "ERROR: Không hoàn tất được TLS với modem: " + e.getMessage() + "\n";
        } catch (Exception e) {
            logException("MODEM_TEST", e);
            return "ERROR: Không kết nối được modem qua Wi-Fi: " +
                    e.getClass().getSimpleName() + " - " + e.getMessage() + "\n";
        } finally {
            try { if (tls != null) tls.close(); else if (raw != null) raw.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Trust manager riêng cho HTTPS modem nội bộ.
     * Không dùng factory này cho API Internet hoặc host khác.
     */
    private SSLSocketFactory createModemOnlySslSocketFactory() throws Exception {
        if (!isPrivateIpv4(modemHost) || modemPort < 1 || modemPort > 65535) {
            throw new IllegalStateException("Chỉ cho phép bỏ qua chứng chỉ với IP modem IPv4 nội bộ và cổng hợp lệ");
        }
        X509TrustManager modemOnlyTrustManager = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{modemOnlyTrustManager}, new SecureRandom());
        return context.getSocketFactory();
    }

    private String buildProxyLink() {
        String proxy = proxyIpInput == null
                ? getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_PROXY_IP, "").trim()
                : proxyIpInput.getText().toString().trim();
        if (proxy.isEmpty() || cellularIpv6 == null) return "";
        String host = cellularIpv6.getHostAddress();
        int zone = host.indexOf('%');
        if (zone >= 0) host = host.substring(0, zone);
        return "http://" + proxy + ":3000/control?ipv6=" + Uri.encode(host) + "&port=" + gatewayPort;
    }

    private void refreshProxyLink() {
        if (proxyLinkView == null) return;
        String link = buildProxyLink();
        proxyLinkView.setText(link.isEmpty() ? "Nhập IP Proxy và khởi động Gateway để tạo link" : link);
    }

    private void copyProxyLink() {
        String link = buildProxyLink();
        if (link.isEmpty()) {
            android.widget.Toast.makeText(this, "Nhập IP Proxy và bảo đảm Gateway đã có IPv6.", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cb.setPrimaryClip(android.content.ClipData.newPlainText("Proxy URL", link));
        log("COPY_PROXY", "Đã sao chép link truy cập qua Proxy");
        android.widget.Toast.makeText(this, "Đã sao chép link Proxy", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void copyGatewayLink(boolean modemTest) {
        if (modemTest && !applyModemIpFromInput()) return;
        Inet6Address ip = cellularIpv6;
        if (!running || ip == null) {
            android.widget.Toast.makeText(this, "Chưa có IPv6 4G hoặc Gateway chưa chạy.", android.widget.Toast.LENGTH_LONG).show();
            log("COPY_LINK", "Không thể copy: listener hoặc IPv6 chưa sẵn sàng");
            return;
        }
        String host = ip.getHostAddress();
        int zone = host.indexOf('%');
        if (zone >= 0) host = host.substring(0, zone);
        String url = "telnet".equalsIgnoreCase(modemProtocol)
                ? "telnet [" + host + "] " + gatewayPort
                : "https://[" + host + "]:" + gatewayPort + "/";
        android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cb.setPrimaryClip(android.content.ClipData.newPlainText("Gateway access", url));
        log("COPY_LINK", "Đã sao chép lệnh truy cập TCP Tunnel (" + modemProtocol.toUpperCase(Locale.ROOT) + ")");
        android.widget.Toast.makeText(this, "Đã sao chép toàn bộ link", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void refreshAccessLinkText() {
        if (accessLinkView == null) return;
        String host = cellularIpv6 == null ? "IPv6 ANDROID A" : cellularIpv6.getHostAddress();
        String value = "telnet".equalsIgnoreCase(modemProtocol)
                ? "telnet [" + host + "] " + gatewayPort
                : "https://[" + host + "]:" + gatewayPort + "/";
        accessLinkView.setText(value);
    }

    private void refreshDetails() {
        runOnUiThread(() -> {
            if (details == null) return;
            StringBuilder b = new StringBuilder();
            b.append("4G Cellular: ").append(cellularNetwork == null ? "Chưa phát hiện" : "Đã phát hiện").append("\n");
            String ipv6Text = cellularIpv6 == null ? "Chưa tìm thấy IPv6 global" : cellularIpv6.getHostAddress();
            b.append("IPv6 Cellular: ").append(ipv6Text).append("\n");
            if (cellularIpv6Input != null) cellularIpv6Input.setText(ipv6Text);
            refreshAccessLinkText();
            refreshProxyLink();
            b.append("Wi-Fi: ").append(wifiNetwork == null ? "Chưa phát hiện" : "Đã phát hiện").append("\n");
            b.append("Phương thức: ").append(modemProtocol.toUpperCase(Locale.ROOT)).append("\n");
            b.append("Modem đích: ").append(modemHost).append(":").append(modemPort).append(" (qua Wi-Fi)\n");
            b.append("TCP Tunnel: ").append(running ? "Đang chạy" : "Chưa chạy").append(" :").append(gatewayPort).append("\n");
            b.append("Xác thực: Tắt Token (truy cập trực tiếp)");
            details.setText(b.toString());
        });
    }

    private boolean isGlobal(Inet6Address a) {
        return !a.isAnyLocalAddress() && !a.isLoopbackAddress() &&
                !a.isLinkLocalAddress() && !a.isSiteLocalAddress() && !a.isMulticastAddress();
    }

    private void postStatus(String msg) {
        runOnUiThread(() -> {
            status.setText("Trạng thái: " + msg);
            refreshDetails();
        });
    }

    private synchronized void stopServerOnly() {
        // Vô hiệu hóa cả tác vụ bind đang chạy để nó không thể mở lại socket sau khi dừng.
        serverGeneration++;
        running = false;
        serverStarting = false;
        ServerSocket oldSocket = serverSocket;
        serverSocket = null;
        if (oldSocket != null) {
            try { oldSocket.close(); } catch (Exception e) {
                log("PORT", "Lỗi khi đóng listener cũ: " + e.getMessage());
            }
            log("PORT", "Đã đóng listener Gateway trên cổng " + gatewayPort);
        }
        int closedTunnels = 0;
        synchronized (activeTunnelSockets) {
            for (Socket socket : new ArrayList<>(activeTunnelSockets)) {
                try { socket.close(); closedTunnels++; } catch (Exception ignored) { }
            }
            activeTunnelSockets.clear();
        }
        if (closedTunnels > 0) log("TUNNEL", "Đã đóng " + closedTunnels + " socket thuộc các phiên TCP Tunnel");
        runOnUiThread(() -> stopButton.setEnabled(false));
    }

    private void stopAll() {
        log("STOP", "Dừng Gateway và hủy callback mạng");
        stopServerOnly();
        if (cellularCallback != null) {
            try { cm.unregisterNetworkCallback(cellularCallback); } catch (Exception ignored) {}
            cellularCallback = null;
        }
        if (wifiCallback != null) {
            try { cm.unregisterNetworkCallback(wifiCallback); } catch (Exception ignored) {}
            wifiCallback = null;
        }
        cellularNetwork = null;
        wifiNetwork = null;
        cellularIpv6 = null;
        stopService(new Intent(this, GatewayService.class));
        startButton.setEnabled(true);
        status.setText("Đã dừng.");
        refreshDetails();
    }

    @Override protected void onDestroy() {
        // Khi Activity thực sự bị hủy, luôn đóng listener và giải phóng callback/service.
        // Android có thể hủy tiến trình mà không gọi onDestroy; khi đó hệ điều hành
        // sẽ tự giải phóng socket cùng tiến trình.
        log("LIFECYCLE", "MainActivity onDestroy: đóng Gateway và giải phóng tài nguyên.");
        stopAll();
        super.onDestroy();
    }

    private void initLogging() {
        try {
            File dir = new File(getFilesDir(), "logs");
            if (!dir.exists() && !dir.mkdirs()) Log.e("IPv6Gateway", "Cannot create log dir");
            logFile = new File(dir, "gateway-log.txt");
            log("LOG", "Log file: " + logFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e("IPv6Gateway", "initLogging failed", e);
        }
    }

    private void startLogWriter() {
        logWriterThread = new Thread(() -> {
            ArrayList<String> batch = new ArrayList<>(64);
            while (logWriterRunning || !diskLogQueue.isEmpty()) {
                try {
                    String item = diskLogQueue.poll(500, TimeUnit.MILLISECONDS);
                    if (item != null && LOG_CLEAR_MARKER.equals(item)) {
                        appendDiskBatch(batch);
                        batch.clear();
                        truncateDiskLog();
                    } else if (item != null) {
                        batch.add(item);
                    }
                    // Ghi theo lô, không mở/đóng file cho từng dòng log.
                    if (batch.size() >= 32 || (item == null && !batch.isEmpty())) {
                        appendDiskBatch(batch);
                        batch.clear();
                    }
                } catch (InterruptedException ignored) {
                    if (!logWriterRunning) break;
                }
            }
            appendDiskBatch(batch);
        }, "Gateway-LogWriter");
        logWriterThread.setDaemon(true);
        logWriterThread.start();
    }

    private void appendDiskBatch(ArrayList<String> batch) {
        if (batch.isEmpty() || logFile == null) return;
        synchronized (logLock) {
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(logFile, true), StandardCharsets.UTF_8), 16 * 1024)) {
                for (String line : batch) writer.write(line + "\n");
                writer.flush();
            } catch (Exception e) {
                Log.e("IPv6Gateway", "Cannot append log batch", e);
            }
        }
    }

    private void truncateDiskLog() {
        synchronized (logLock) {
            try (FileOutputStream ignored = new FileOutputStream(logFile, false)) {
                // Opening in non-append mode truncates the log after all earlier queued lines were flushed.
            } catch (Exception e) {
                Log.e("IPv6Gateway", "Cannot clear log file", e);
            }
        }
    }

    private void log(String tag, String message) {
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date()) + " [" + tag + "] " + message;
        Log.i("IPv6Gateway", line);
        synchronized (displayLogLock) {
            displayLogLines.addLast(line);
            while (displayLogLines.size() > MAX_VISIBLE_LOG_LINES) displayLogLines.removeFirst();
        }
        synchronized (logLock) {
            if (logWriterRunning && !diskLogQueue.offer(line)) {
                Log.e("IPv6Gateway", "Log queue full; one log line could not be queued");
            }
        }
        refreshLogView();
    }

    private void logException(String tag, Throwable throwable) {
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        log(tag, sw.toString());
    }

    private void confirmClearLogs() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Xóa nhật ký hoạt động?")
                .setMessage("Toàn bộ log đang lưu sẽ bị xóa. Nên dùng trước khi kiểm tra một modem mới để log chỉ chứa lần kiểm tra tiếp theo.")
                .setNegativeButton("HỦY", (dialog, which) -> dialog.dismiss())
                .setPositiveButton("XÓA LOG", (dialog, which) -> clearLogs())
                .show();
    }

    private void clearLogs() {
        synchronized (logLock) {
            diskLogQueue.clear();
            if (!diskLogQueue.offer(LOG_CLEAR_MARKER)) {
                android.widget.Toast.makeText(this, "Hàng đợi log đang bận, vui lòng thử lại.",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
        }
        synchronized (displayLogLock) { displayLogLines.clear(); }
        if (logView != null) logView.setText("Log đã được xóa. Bắt đầu ghi nhận phiên kiểm tra mới.");
        log("LOG", "========== BẮT ĐẦU PHIÊN LOG MỚI ==========");
        android.widget.Toast.makeText(this, "Đã xóa log cũ. Log mới sẽ được ghi từ bây giờ.",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private String readLog() {
        synchronized (logLock) {
            try {
                if (logFile == null || !logFile.exists()) return "Chưa có log.";
                byte[] bytes = java.nio.file.Files.readAllBytes(logFile.toPath());
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "Không đọc được log: " + e;
            }
        }
    }

    private void refreshLogView() {
        if (logView == null) return;
        synchronized (displayLogLock) {
            if (logUiRefreshPending) return;
            logUiRefreshPending = true;
        }
        // Gộp nhiều log phát sinh liên tiếp thành một lần cập nhật UI mỗi 500ms.
        logUiHandler.postDelayed(() -> {
            StringBuilder snapshot = new StringBuilder();
            synchronized (displayLogLock) {
                for (String line : displayLogLines) snapshot.append(line).append('\n');
                logUiRefreshPending = false;
            }
            if (logView == null) return;
            String visible = snapshot.length() == 0 ? "Chưa có log." : snapshot.toString();
            android.text.SpannableString styled = new android.text.SpannableString(visible);
            int lineStart = 0;
            while (lineStart < visible.length()) {
                int lineEnd = visible.indexOf('\n', lineStart);
                if (lineEnd < 0) lineEnd = visible.length();
                int stampEnd = Math.min(lineStart + 23, lineEnd);
                if (stampEnd - lineStart == 23 && isLogTimestamp(visible, lineStart)) {
                    styled.setSpan(new android.text.style.ForegroundColorSpan(0xFFD92D20),
                            lineStart, stampEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    styled.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                            lineStart, stampEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                lineStart = lineEnd + 1;
            }
            // Preserve the user's position. Auto-follow only when the user was
            // already at the bottom; never force-scroll while they are reading older logs.
            final int previousLogScrollY = logScroll != null ? logScroll.getScrollY() : 0;
            final int previousPageScrollY = pageScroll != null ? pageScroll.getScrollY() : 0;
            boolean wasAtBottom = false;
            if (logScroll != null && logScroll.getChildCount() > 0) {
                View child = logScroll.getChildAt(0);
                int distanceToBottom = child.getBottom() - (logScroll.getScrollY() + logScroll.getHeight());
                wasAtBottom = distanceToBottom <= dp(24);
            }
            logView.setText(styled);
            if (logScroll != null) {
                final boolean keepFollowing = wasAtBottom;
                logScroll.post(() -> {
                    if (keepFollowing) {
                        int maxY = Math.max(0, logView.getHeight() - logScroll.getHeight());
                        logScroll.scrollTo(0, maxY);
                    } else {
                        logScroll.scrollTo(0, previousLogScrollY);
                    }
                    // Updating text must never move the whole screen/page.
                    if (pageScroll != null && pageScroll.getScrollY() != previousPageScrollY) {
                        pageScroll.scrollTo(0, previousPageScrollY);
                    }
                });
            }
        }, LOG_UI_REFRESH_MS);
    }

    private boolean isLogTimestamp(String value, int start) {
        if (start + 23 > value.length()) return false;
        return value.charAt(start + 4) == '-' && value.charAt(start + 7) == '-' &&
                value.charAt(start + 10) == ' ' && value.charAt(start + 13) == ':' &&
                value.charAt(start + 16) == ':' && value.charAt(start + 19) == '.';
    }

    private void shareLogFile() {
        try {
            String contents = readLog();
            File export = new File(getCacheDir(), "gateway-log.txt");
            FileOutputStream fos = new FileOutputStream(export, false);
            fos.write(contents.getBytes(StandardCharsets.UTF_8));
            fos.close();
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", export);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "IPv6 Modem Gateway - log lỗi");
            send.putExtra(Intent.EXTRA_TEXT, "File log lỗi Gateway được đính kèm.");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Gửi file log"));
            log("LOG", "Người dùng mở chức năng chia sẻ log");
        } catch (Exception e) {
            logException("SHARE_LOG", e);
            android.widget.Toast.makeText(this, "Không thể chia sẻ log: " + e.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

}
