const net = require("net");
const http = require("http");

// ONE PUBLIC PORT: control page + unchanged raw TCP relay
const PORT = 3000;
const TARGET_PORT = 8090;

const CONTROL_KEY = "Vnptdng@9";
let TARGET_IPV6 = "Nhập IP IPv6 Android A";
let connectionCount = 0;

// =========================
// CONTROL PAGE
// Control UI: http://YOUR_SERVER:3000/control
// /set and /status are reserved for the control page.
// All other traffic, including modem HTTP/HTTPS, is relayed as raw TCP.
// =========================let TARGET_IPV6 = "2001:ee0:29c:11cd:ebc7:c6e7:20b1:ca5a";
const control = http.createServer((req, res) => {
    res.setHeader("Access-Control-Allow-Origin", "*");
    res.setHeader("Cache-Control", "no-store");

    if (req.method === "GET" && req.url === "/control") {
        res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
        res.end(`<!DOCTYPE html>
<html lang="vi">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="theme-color" content="#075bb8">
<title>TCP Tunnel Control</title>
<style>
:root{font-family:Arial,Helvetica,sans-serif;color:#172033;background:#eaf3ff}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;padding:22px 14px;background:radial-gradient(circle at 50% 0,#d8eaff 0,#edf5ff 48%,#e7f0fb 100%)}
.shell{width:100%;max-width:760px;margin:24px auto;background:#fff;border-radius:24px;overflow:hidden;box-shadow:0 16px 38px rgba(18,77,143,.18);border:1px solid #d7e6f8}
.hero{padding:25px 30px;background:linear-gradient(115deg,#064eaa,#0879d4);color:#fff;display:flex;align-items:center;gap:20px}
.brand{width:84px;flex:0 0 84px;text-align:center;font-weight:800;font-size:22px;letter-spacing:4px;border-right:1px solid rgba(255,255,255,.3);padding-right:18px}
.brand-mark{font-size:34px;line-height:1.1;display:block;letter-spacing:0}
.hero-copy{min-width:0;flex:1}
.hero h1{font-size:clamp(21px,4vw,31px);margin:0 0 7px;letter-spacing:.3px}
.hero p{margin:0;color:#dceeff;font-size:15px;line-height:1.4}
.router{font-size:43px;line-height:1;flex:0 0 auto}
.content{padding:28px 30px 24px}
.field{margin-bottom:20px}
.label{display:flex;align-items:center;gap:10px;font-size:18px;font-weight:700;margin:0 0 9px}
.label .ico{color:#0869c7;font-size:22px;width:26px;text-align:center}
.input-wrap{position:relative}
input{width:100%;height:54px;border:2px solid #b9c9df;border-radius:12px;padding:0 48px 0 15px;font-size:16px;color:#172033;background:#fbfdff;outline:none;transition:border .15s,box-shadow .15s}
input:focus{border-color:#1683e5;box-shadow:0 0 0 3px rgba(22,131,229,.13)}
.input-action{position:absolute;right:9px;top:8px;width:38px;height:38px;border:0;background:transparent;color:#294565;font-size:22px;cursor:pointer;border-radius:8px}
.actions{display:grid;grid-template-columns:1fr 1fr;gap:14px;margin:25px 0 22px}
button.action{min-height:58px;border:0;border-radius:12px;color:white;font-size:16px;font-weight:800;letter-spacing:.2px;cursor:pointer;box-shadow:0 5px 12px rgba(0,0,0,.1);transition:transform .12s,filter .12s}
button.action:active{transform:translateY(1px);filter:brightness(.96)}
.save{background:linear-gradient(110deg,#1596ed,#075bbb)}
.remote{background:linear-gradient(110deg,#19c65b,#07833b)}
.action .ico{font-size:21px;margin-right:8px;vertical-align:-1px}
.status-card{border:1px solid #b8ecd1;background:linear-gradient(120deg,#effdf5,#e4f7ee);border-radius:15px;padding:17px 18px;display:grid;grid-template-columns:48px 1fr;gap:10px;align-items:center}
.check{width:40px;height:40px;border-radius:50%;background:#12b94f;color:#fff;display:grid;place-items:center;font-size:25px;font-weight:bold}
.status-lines{min-width:0;display:grid;gap:9px;font-size:15px;line-height:1.4}
.status-line{overflow-wrap:anywhere}
.status-line strong{color:#15456d}
.note{margin-top:7px;color:#52677d;font-size:12px;min-height:16px}
.footer{margin:0 18px 18px;padding:16px 18px;border-radius:14px;background:#f0f4fb;display:flex;align-items:center;justify-content:space-between;gap:12px;color:#233b60}
.tcp{display:flex;align-items:center;gap:12px;font-size:14px}
.tcp-badge{padding:7px 10px;border-radius:8px;background:#258ce4;color:#fff;font-weight:800;box-shadow:inset 0 0 0 1px #75baff}
.running{display:flex;align-items:center;gap:8px;font-weight:700;font-size:14px;white-space:nowrap}
.dot{width:10px;height:10px;border-radius:50%;background:#12bd50;box-shadow:0 0 0 4px #d8f7e4}
@media(max-width:560px){body{padding:10px 8px}.shell{margin:8px auto;border-radius:17px}.hero{padding:20px 17px;gap:12px}.brand{width:60px;flex-basis:60px;font-size:17px;letter-spacing:2px;padding-right:10px}.brand-mark{font-size:27px}.hero p{font-size:12px}.router{font-size:32px}.content{padding:22px 17px 18px}.label{font-size:16px}.actions{grid-template-columns:1fr;gap:10px;margin:20px 0}.status-card{padding:14px 12px;grid-template-columns:38px 1fr}.check{width:34px;height:34px;font-size:21px}.status-lines{font-size:13px}.footer{margin:0 12px 12px;padding:13px;align-items:flex-start;flex-direction:column}.running{font-size:13px}}
</style>
</head>
<body>
<div class="shell">
  <header class="hero">
    <div class="brand"><span class="brand-mark">◉</span>VNPT</div>
    <div class="hero-copy"><h1>TCP TUNNEL CONTROL</h1><p>Kết nối IPv6 Android A → Remote Modem</p></div>
    <div class="router" aria-hidden="true">⌁</div>
  </header>
  <main class="content">
    <div class="field">
      <label class="label" for="ipv6"><span class="ico">↗</span>IPv6 Android A</label>
      <div class="input-wrap"><input id="ipv6" type="text" autocomplete="off" spellcheck="false" placeholder="2001:xxxx:xxxx::xxxx"><button class="input-action" type="button" title="Xóa IPv6" onclick="document.getElementById('ipv6').value=''">×</button></div>
    </div>
    <div class="field">
      <label class="label" for="key"><span class="ico">▣</span>Control Key</label>
      <div class="input-wrap"><input id="key" type="text" autocomplete="current-password" value="Vnptdng@9" "><button class="input-action" type="button" title="Hiện/ẩn mật khẩu" onclick="toggleKey()">◉</button></div>
    </div>
    <div class="actions">
      <button class="action save" type="button" onclick="updateIP()"><span class="ico">⟳</span>CẬP NHẬT IPv6</button>
      <button class="action remote" type="button" onclick="openRemote()"><span class="ico">↗</span>MỞ REMOTE MODEM</button>
    </div>
    <section class="status-card" aria-live="polite">
      <div class="check">✓</div>
      <div class="status-lines">
        <div class="status-line"><strong>IPv6 hiện tại:</strong> <span id="currentIP">Đang tải...</span></div>
        <div class="status-line"><strong>Target Port:</strong> 8090</div>
        <div class="status-line"><strong>Remote modem:</strong> cùng cổng 3000</div>
      </div>
    </section>
    <div class="note" id="message"></div>
  </main>
  <footer class="footer">
    <div class="tcp"><span class="tcp-badge">TCP</span><span>TCP Relay (net.createServer + net.connect)</span></div>
    <div class="running"><span class="dot"></span>Đang chạy · Cổng 3000</div>
  </footer>
</div>
<script>
const ipv6Input=document.getElementById("ipv6");
const keyInput=document.getElementById("key");
const currentIP=document.getElementById("currentIP");
const message=document.getElementById("message");
function toggleKey(){keyInput.type=keyInput.type==="password"?"text":"password";}
async function loadStatus(){
  try{
    const r=await fetch("/status",{cache:"no-store"});
    const d=await r.json();
    if(!r.ok)throw new Error(d.error||"Không đọc được trạng thái");
    ipv6Input.value=d.ipv6||"";
    currentIP.textContent=d.ipv6||"Chưa cập nhật";
    message.textContent="Đã kết nối máy chủ điều khiển.";
  }catch(e){currentIP.textContent="Không lấy được trạng thái";message.textContent="Lỗi: "+e.message;}
}
async function updateIP(){
  const ipv6=ipv6Input.value.trim();
  const key=keyInput.value;
  message.textContent="Đang cập nhật IPv6...";
  try{
    const r=await fetch("/set",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({ipv6,key})});
    const d=await r.json();
    if(!r.ok)throw new Error(d.error||"Cập nhật thất bại");
    currentIP.textContent=d.newIPv6||ipv6;
    message.textContent="Cập nhật IPv6 thành công.";
  }catch(e){message.textContent="Lỗi: "+e.message;}
}
function openRemote(){window.open("https://"+window.location.host+"/","_blank");}
loadStatus();
</script>
</body>
</html>`);
        return;
    }

    if (req.method === "GET" && req.url === "/status") {
        res.writeHead(200, { "Content-Type": "application/json; charset=utf-8" });
        res.end(JSON.stringify({
            status: "running",
            ipv6: TARGET_IPV6,
            port: TARGET_PORT,
            relayPort: PORT
        }));
        return;
    }

    if (req.method === "POST" && req.url === "/set") {
        let body = "";
        req.on("data", chunk => {
            body += chunk;
            if (body.length > 4096) req.destroy();
        });
        req.on("end", () => {
            try {
                const data = JSON.parse(body);
                if (data.key !== CONTROL_KEY) {
                    res.writeHead(401, { "Content-Type": "application/json; charset=utf-8" });
                    res.end(JSON.stringify({ error: "UNAUTHORIZED" }));
                    return;
                }
                const ipv6 = String(data.ipv6 || "").trim();
                if (net.isIP(ipv6) !== 6) {
                    res.writeHead(400, { "Content-Type": "application/json; charset=utf-8" });
                    res.end(JSON.stringify({ error: "INVALID_IPV6" }));
                    return;
                }
                const oldIP = TARGET_IPV6;
                TARGET_IPV6 = ipv6;
                console.log("[IP UPDATED]");
                console.log("OLD:", oldIP);
                console.log("NEW:", TARGET_IPV6);
                res.writeHead(200, { "Content-Type": "application/json; charset=utf-8" });
                res.end(JSON.stringify({
                    status: "OK",
                    message: "TARGET UPDATED",
                    oldIPv6: oldIP,
                    newIPv6: TARGET_IPV6,
                    port: TARGET_PORT
                }));
            } catch (err) {
                res.writeHead(400, { "Content-Type": "application/json; charset=utf-8" });
                res.end(JSON.stringify({ error: "INVALID_REQUEST" }));
            }
        });
        return;
    }

    if (req.method === "GET" && req.url === "/favicon.ico") {
        res.writeHead(204);
        res.end();
        return;
    }

    res.writeHead(404);
    res.end("NOT_FOUND");
});

control.on("error", err => console.log("[CONTROL ERROR]", err.message));
control.on("clientError", (err, socket) => {
    console.log("[CONTROL CLIENT ERROR]", err.message);
    if (socket.writable) socket.end("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n");
});

// =========================
// ORIGINAL TCP RELAY LOGIC
// Same net.connect() + bidirectional pipe as _tunnel.js.
// No HTTP proxy, no HTTP/HTML rewriting of modem traffic.
// =========================
function relayTCP(client, firstData) {
    // Pause immediately so no bytes are lost while Android A is connecting.
    client.pause();
    if (firstData && firstData.length) client.unshift(firstData);

    const target = TARGET_IPV6;
    const id = ++connectionCount;

    console.log(`[CLIENT ${id}] ${client.remoteAddress}`);
    console.log(`[TARGET ${id}] [${target}]:${TARGET_PORT}`);

    const upstream = net.connect({
        host: target,
        port: TARGET_PORT,
        family: 6
    });

    upstream.on("connect", () => {
        console.log(`[CONNECTED ${id}] Android A`);
        client.pipe(upstream);
        upstream.pipe(client);
    });

    upstream.on("error", err => {
        console.log(`[UPSTREAM ERROR ${id}]`, err.message);
        client.destroy();
    });

    client.on("error", err => {
        console.log(`[CLIENT ERROR ${id}]`, err.message);
        upstream.destroy();
    });

    client.on("close", () => upstream.destroy());
    upstream.on("close", () => client.destroy());
}

// =========================
// ONE LISTENER / ONE PUBLIC PORT
// /control, /set, /status -> local control server
// EVERYTHING ELSE        -> unchanged raw TCP relay to Android A:8090
// =========================
const listener = net.createServer(client => {
    let firstData = Buffer.alloc(0);
    let decided = false;

    client.on("error", err => console.log("[INCOMING CLIENT ERROR]", err.message));

    function onData(chunk) {
        if (decided) return;
        firstData = Buffer.concat([firstData, chunk]);
        // HTTPS starts with a TLS handshake record (0x16 0x03).
        // Relay it immediately as raw TCP; do not wait for an HTTP request line.
        const isTLS = firstData.length >= 3 &&
            firstData[0] === 0x16 && firstData[1] === 0x03;

        if (isTLS) {
            decided = true;
            client.removeListener("data", onData);
            relayTCP(client, firstData);
            return;
        }

        const text = firstData.toString("latin1");
        const lineEnd = text.indexOf("\r\n");

        // Wait for a complete HTTP request line before classifying.
        if (lineEnd === -1 && firstData.length < 8192) return;

        const requestLine = lineEnd >= 0 ? text.slice(0, lineEnd) : "";
        const match = requestLine.match(/^[A-Z]+\s+(\S+)\s+HTTP\/\d(?:\.\d)?$/i);
        let pathname = "";
        if (match) {
            try { pathname = new URL(match[1], "http://tunnel.local").pathname; }
            catch (_) { pathname = match[1].split("?")[0]; }
        }

        const isControlRequest = !!match && (
            pathname === "/control" ||
            pathname === "/set" ||
            pathname === "/status" ||
            pathname === "/favicon.ico"
        );

        decided = true;
        client.removeListener("data", onData);

        if (isControlRequest) {
            client.pause();
            client.unshift(firstData);
            control.emit("connection", client);
            client.resume();
        } else {
            relayTCP(client, firstData);
        }
    }

    client.on("data", onData);
});

listener.on("error", err => console.log("[LISTENER ERROR]", err.message));
listener.listen(PORT, "0.0.0.0", () => {
    console.log("--------------------------------");
    console.log("VNPT TCP TUNNEL STARTED");
    console.log("PUBLIC PORT :", PORT);
    console.log("CONTROL URL : http://localhost:" + PORT + "/control");
    console.log("REMOTE URL  : https://localhost:" + PORT + "/");
    console.log("TARGET      : [" + TARGET_IPV6 + "]:" + TARGET_PORT);
    console.log("TCP RELAY   : net.createServer + net.connect");
    console.log("HTTP PROXY  : DISABLED");
    console.log("--------------------------------");
});
