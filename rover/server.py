"""
server.py — Flask bridge server for the reflex-arc rover (Raspberry Pi 5).

Run on the Pi:
    python3 server.py

Then from any browser on the same network:
    http://<pi-ip>:5000          → web dashboard

Unity talks to this via RoverBridgeClient.cs:
    POST /drive   {"throttle": 0.5, "turn": -0.2}
    POST /stop
    GET  /status

The instruction pipeline:
    POST /instructions  (raw text body — the LLM output file)
    GET  /instructions/status   → current instruction + result log
    POST /instructions/clear

Watchdog: if no /drive command arrives within WATCHDOG_TIMEOUT seconds the
rover stops automatically (so a disconnected Unity doesn't leave it running).
"""

import threading
import time
import os
from datetime import datetime
from flask import Flask, request, jsonify, render_template_string

# On a real Pi, import the real motor driver.
# On a dev machine without GPIO, the mock keeps the server runnable.
try:
    import motor_control as motors
    motors.init()
    HARDWARE = True
except (ImportError, RuntimeError):
    HARDWARE = False
    print("[server] No GPIO found — running in mock mode (no real motors)")

    class _MockMotors:
        _throttle = 0.0
        _turn = 0.0

        def drive(self, throttle, turn):
            self._throttle = throttle
            self._turn = turn

        def stop(self):
            self._throttle = 0.0
            self._turn = 0.0

        def cleanup(self):
            pass

    motors = _MockMotors()

# ── Config ───────────────────────────────────────────────────────────────────
WATCHDOG_TIMEOUT = 1.5   # seconds of silence → auto-stop
INSTRUCTIONS_DIR = os.path.join(os.path.dirname(__file__), "instructions")
os.makedirs(INSTRUCTIONS_DIR, exist_ok=True)

# ── Shared state (protected by a lock) ───────────────────────────────────────
_lock            = threading.Lock()
_throttle        = 0.0
_turn            = 0.0
_last_drive_time = 0.0
_status_log      = []          # list of {"time", "event"} dicts
_instruction_queue: list[str] = []
_current_instruction: str | None = None
_instruction_results: list[str] = []

app = Flask(__name__)


def _log(event: str):
    ts = datetime.now().strftime("%H:%M:%S")
    entry = {"time": ts, "event": event}
    _status_log.append(entry)
    if len(_status_log) > 200:
        _status_log.pop(0)
    print(f"[{ts}] {event}")


# ── Watchdog thread ──────────────────────────────────────────────────────────
def _watchdog():
    while True:
        time.sleep(0.25)
        with _lock:
            if _throttle != 0.0 or _turn != 0.0:
                if time.time() - _last_drive_time > WATCHDOG_TIMEOUT:
                    motors.stop()
                    _throttle_local = 0.0
                    _turn_local = 0.0
                    globals()['_throttle'] = 0.0
                    globals()['_turn'] = 0.0
                    _log("WATCHDOG: no heartbeat — stopped")

threading.Thread(target=_watchdog, daemon=True).start()


# ── Routes ───────────────────────────────────────────────────────────────────

@app.route("/drive", methods=["POST"])
def route_drive():
    data = request.get_json(force=True, silent=True) or {}
    throttle = float(data.get("throttle", 0.0))
    turn     = float(data.get("turn",     0.0))
    throttle = max(-1.0, min(1.0, throttle))
    turn     = max(-1.0, min(1.0, turn))

    with _lock:
        globals()['_throttle'] = throttle
        globals()['_turn']     = turn
        globals()['_last_drive_time'] = time.time()

    motors.drive(throttle, turn)
    return jsonify({"ok": True, "throttle": throttle, "turn": turn})


@app.route("/stop", methods=["POST"])
def route_stop():
    with _lock:
        globals()['_throttle'] = 0.0
        globals()['_turn']     = 0.0
    motors.stop()
    _log("STOP command received")
    return jsonify({"ok": True})


@app.route("/status", methods=["GET"])
def route_status():
    with _lock:
        return jsonify({
            "hardware": HARDWARE,
            "throttle": _throttle,
            "turn":     _turn,
            "current_instruction": _current_instruction,
            "queue_length": len(_instruction_queue),
            "log": _status_log[-20:],
        })


# ── Instruction pipeline ─────────────────────────────────────────────────────

@app.route("/instructions", methods=["POST"])
def route_instructions_upload():
    """
    Receive a plain-text instruction file from Unity (or manually).
    Each line is one instruction. Comments (#) and blank lines are skipped.
    Saves to disk and loads into the execution queue.
    """
    raw = request.get_data(as_text=True)
    lines = [l.strip() for l in raw.splitlines()]
    instructions = [l for l in lines if l and not l.startswith("#")]

    # Save to disk for reference
    fname = datetime.now().strftime("mission_%Y%m%d_%H%M%S.txt")
    with open(os.path.join(INSTRUCTIONS_DIR, fname), "w") as f:
        f.write(raw)

    with _lock:
        _instruction_queue.clear()
        _instruction_queue.extend(instructions)
        globals()['_current_instruction'] = None
        _instruction_results.clear()

    _log(f"Instructions loaded: {len(instructions)} commands from '{fname}'")
    return jsonify({"ok": True, "count": len(instructions), "file": fname})


@app.route("/instructions/status", methods=["GET"])
def route_instructions_status():
    with _lock:
        return jsonify({
            "current": _current_instruction,
            "queue":   list(_instruction_queue),
            "results": list(_instruction_results),
        })


@app.route("/instructions/clear", methods=["POST"])
def route_instructions_clear():
    with _lock:
        _instruction_queue.clear()
        globals()['_current_instruction'] = None
        _instruction_results.clear()
    motors.stop()
    _log("Instructions cleared")
    return jsonify({"ok": True})


@app.route("/instructions/result", methods=["POST"])
def route_instructions_result():
    """
    Unity calls this when an instruction finishes execution.
    Body: {"result": "DONE", "instruction": "GOTO 10 20", "steps": 42}
    Advances the queue to the next instruction.
    """
    data = request.get_json(force=True, silent=True) or {}
    result      = data.get("result", "DONE")
    instruction = data.get("instruction", "")
    steps       = data.get("steps", 0)
    entry       = f"{instruction} → {result} (steps={steps})"

    with _lock:
        _instruction_results.append(entry)
        # Advance queue
        if _instruction_queue:
            nxt = _instruction_queue.pop(0)
            globals()['_current_instruction'] = nxt
        else:
            globals()['_current_instruction'] = None

    _log(f"Result: {entry}")
    if _current_instruction:
        _log(f"Next instruction: {_current_instruction}")
    else:
        _log("All instructions complete")

    return jsonify({
        "ok":   True,
        "next": _current_instruction,
        "done": _current_instruction is None,
    })


@app.route("/instructions/next", methods=["GET"])
def route_instructions_next():
    """Unity polls this to get the currently active instruction."""
    with _lock:
        # Auto-advance on first poll if queue is loaded and nothing active yet
        if _current_instruction is None and _instruction_queue:
            nxt = _instruction_queue.pop(0)
            globals()['_current_instruction'] = nxt
            _log(f"Starting first instruction: {nxt}")
        return jsonify({
            "instruction": _current_instruction,
            "queue_remaining": len(_instruction_queue),
        })


# ── Web dashboard (served at /) ───────────────────────────────────────────────

DASHBOARD_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Rover Control</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: system-ui, sans-serif; background: #0d1117; color: #e6edf3; padding: 20px; }
  h1 { color: #58a6ff; margin-bottom: 16px; }
  h2 { color: #8b949e; font-size: 14px; text-transform: uppercase; letter-spacing: 1px; margin: 20px 0 8px; }
  .card { background: #161b22; border: 1px solid #30363d; border-radius: 8px; padding: 16px; margin-bottom: 16px; }
  .status-row { display: flex; gap: 24px; flex-wrap: wrap; }
  .stat { flex: 1; min-width: 120px; }
  .stat-label { font-size: 11px; color: #8b949e; }
  .stat-value { font-size: 24px; font-weight: bold; color: #58a6ff; }
  .btn { padding: 10px 20px; border: none; border-radius: 6px; cursor: pointer; font-size: 14px; font-weight: 600; }
  .btn-danger { background: #da3633; color: white; }
  .btn-primary { background: #238636; color: white; }
  .btn-secondary { background: #21262d; color: #e6edf3; border: 1px solid #30363d; }
  .btn:hover { opacity: 0.85; }
  .btn-row { display: flex; gap: 8px; flex-wrap: wrap; }
  .dpad { display: grid; grid-template-columns: 60px 60px 60px; grid-template-rows: 60px 60px 60px; gap: 4px; }
  .dpad button { width: 60px; height: 60px; border-radius: 8px; background: #21262d; border: 1px solid #30363d; color: #e6edf3; font-size: 20px; cursor: pointer; }
  .dpad button:active { background: #58a6ff; }
  .dpad .center { background: #da3633; }
  textarea { width: 100%; background: #0d1117; border: 1px solid #30363d; color: #e6edf3; padding: 10px; border-radius: 6px; font-family: monospace; font-size: 13px; resize: vertical; }
  .log { font-family: monospace; font-size: 12px; max-height: 200px; overflow-y: auto; }
  .log-entry { padding: 2px 0; border-bottom: 1px solid #21262d; }
  .log-time { color: #8b949e; margin-right: 8px; }
  .pill { display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 11px; font-weight: 600; }
  .pill-green { background: #1f4f2a; color: #56d364; }
  .pill-red { background: #4d1616; color: #f85149; }
  .pill-blue { background: #1a3a5c; color: #58a6ff; }
  input[type=range] { width: 100%; }
</style>
</head>
<body>
<h1>🛸 Rover Control</h1>

<div class="card">
  <h2>Status</h2>
  <div class="status-row">
    <div class="stat"><div class="stat-label">Hardware</div><div class="stat-value" id="hw">—</div></div>
    <div class="stat"><div class="stat-label">Throttle</div><div class="stat-value" id="thr">—</div></div>
    <div class="stat"><div class="stat-label">Turn</div><div class="stat-value" id="trn">—</div></div>
    <div class="stat"><div class="stat-label">Current Instruction</div><div class="stat-value" id="instr" style="font-size:14px">—</div></div>
    <div class="stat"><div class="stat-label">Queue</div><div class="stat-value" id="qlen">—</div></div>
  </div>
</div>

<div class="card">
  <h2>Manual Drive</h2>
  <p style="color:#8b949e;font-size:12px;margin-bottom:12px">Hold buttons to drive. Release = stop. Speed: <span id="spd-label">50%</span></p>
  <input type="range" min="5" max="100" value="50" id="speed-slider" style="margin-bottom:14px" oninput="document.getElementById('spd-label').textContent=this.value+'%'">
  <div class="dpad">
    <div></div>
    <button onmousedown="startDrive(1,0)" onmouseup="stopDrive()" ontouchstart="startDrive(1,0)" ontouchend="stopDrive()">▲</button>
    <div></div>
    <button onmousedown="startDrive(0,-1)" onmouseup="stopDrive()" ontouchstart="startDrive(0,-1)" ontouchend="stopDrive()">◄</button>
    <button class="center" onmousedown="stopDrive()" onclick="doStop()">■</button>
    <button onmousedown="startDrive(0,1)" onmouseup="stopDrive()" ontouchstart="startDrive(0,1)" ontouchend="stopDrive()">►</button>
    <div></div>
    <button onmousedown="startDrive(-1,0)" onmouseup="stopDrive()" ontouchstart="startDrive(-1,0)" ontouchend="stopDrive()">▼</button>
    <div></div>
  </div>
</div>

<div class="card">
  <h2>Mission Instructions</h2>
  <p style="color:#8b949e;font-size:12px;margin-bottom:8px">Paste LLM-generated instruction file below or upload one. Format: one instruction per line.<br>
  Supported: <code>GOTO x z</code>, <code>WAIT seconds</code>, <code>STOP</code></p>
  <textarea id="instr-box" rows="8" placeholder="# LLM Mission Instructions&#10;GOTO 10 20&#10;GOTO -5 15&#10;WAIT 2&#10;GOTO 0 0&#10;STOP"></textarea>
  <div class="btn-row" style="margin-top:8px">
    <button class="btn btn-primary" onclick="uploadInstructions()">▶ Upload & Run</button>
    <button class="btn btn-secondary" onclick="clearInstructions()">✕ Clear</button>
    <input type="file" id="file-input" accept=".txt" style="display:none" onchange="loadFile(event)">
    <button class="btn btn-secondary" onclick="document.getElementById('file-input').click()">📂 Open File</button>
  </div>
  <div id="instr-results" style="margin-top:12px;font-family:monospace;font-size:12px;color:#56d364"></div>
</div>

<div class="card">
  <h2>Event Log</h2>
  <div class="log" id="log"></div>
</div>

<script>
let driveInterval = null;

function speed() {
  return parseInt(document.getElementById('speed-slider').value) / 100;
}

function startDrive(thr, trn) {
  if (driveInterval) clearInterval(driveInterval);
  const s = speed();
  sendDrive(thr * s, trn * s);
  driveInterval = setInterval(() => sendDrive(thr * s, trn * s), 150);
}

function stopDrive() {
  if (driveInterval) { clearInterval(driveInterval); driveInterval = null; }
  sendDrive(0, 0);
}

function doStop() {
  fetch('/stop', {method:'POST'});
}

function sendDrive(thr, trn) {
  fetch('/drive', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({throttle: thr, turn: trn})
  });
}

function uploadInstructions() {
  const text = document.getElementById('instr-box').value;
  fetch('/instructions', {
    method: 'POST',
    headers: {'Content-Type': 'text/plain'},
    body: text
  }).then(r => r.json()).then(d => {
    alert(`Loaded ${d.count} instructions: ${d.file}`);
    // Poke Unity's InstructionExecutor by requesting next instruction
    fetch('/instructions/next');
  });
}

function clearInstructions() {
  fetch('/instructions/clear', {method:'POST'});
  document.getElementById('instr-results').textContent = '';
}

function loadFile(evt) {
  const f = evt.target.files[0];
  if (!f) return;
  const reader = new FileReader();
  reader.onload = e => {
    document.getElementById('instr-box').value = e.target.result;
  };
  reader.readAsText(f);
}

// ── Poll status every second ──────────────────────────────────────────────
async function poll() {
  try {
    const s = await fetch('/status').then(r => r.json());
    document.getElementById('hw').innerHTML = s.hardware
      ? '<span class="pill pill-green">REAL</span>'
      : '<span class="pill pill-red">MOCK</span>';
    document.getElementById('thr').textContent = s.throttle.toFixed(2);
    document.getElementById('trn').textContent = s.turn.toFixed(2);
    document.getElementById('instr').textContent = s.current_instruction || '(none)';
    document.getElementById('qlen').textContent = s.queue_length;

    const log = document.getElementById('log');
    log.innerHTML = (s.log || []).slice().reverse().map(e =>
      `<div class="log-entry"><span class="log-time">${e.time}</span>${e.event}</div>`
    ).join('');

    // Poll instruction results
    const ir = await fetch('/instructions/status').then(r => r.json());
    if (ir.results && ir.results.length > 0) {
      document.getElementById('instr-results').textContent = ir.results.join('\\n');
    }
  } catch(e) { /* server offline */ }
}

setInterval(poll, 1000);
poll();
</script>
</body>
</html>"""


@app.route("/")
def route_dashboard():
    return DASHBOARD_HTML


# ── Shutdown ──────────────────────────────────────────────────────────────────
import atexit
@atexit.register
def _shutdown():
    motors.stop()
    motors.cleanup()


if __name__ == "__main__":
    print("=" * 60)
    print("  Rover Bridge Server")
    print(f"  Hardware: {'YES (GPIO active)' if HARDWARE else 'NO (mock mode)'}")
    print("  Dashboard: http://<pi-ip>:5000")
    print("  API: POST /drive, POST /stop, GET /status")
    print("  Instructions: POST /instructions, GET /instructions/next")
    print("=" * 60)
    app.run(host="0.0.0.0", port=5000, threaded=True)
