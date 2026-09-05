# reflex-arc Rover Bridge

Pi-side code that lets Unity's RL policy drive the physical rover, and lets the LLM instruction pipeline execute missions end-to-end.

## How the full pipeline works

```
LLM (Ollama/Gemini)
  → generates instructions.txt
       GOTO 10 20
       WAIT 2
       GOTO 0 0
       STOP

Unity (InstructionExecutor.cs)
  → reads the file (or polls /instructions/next from this server)
  → moves GoalMarker to each GOTO target
  → RoverAgent (RL policy) navigates there
  → OnActionReceived throttle/turn → RoverBridgeClient.SendDrive()

Pi (server.py + motor_control.py)
  → POST /drive {"throttle": 0.5, "turn": -0.2}
  → GPIO PWM → real wheels move
  → GET /status, dashboard at http://<pi-ip>:5000

Unity reports DONE/BLOCKED back
  → POST /instructions/result → server logs it, advances queue
```

## Pi setup

```bash
# 1. Install dependencies
pip3 install flask RPi.GPIO

# 2. Wire your L298N motor driver (see motor_control.py for pin map)
#    ENA=GPIO12, IN1=GPIO20, IN2=GPIO21
#    ENB=GPIO13, IN3=GPIO16, IN4=GPIO26
#    Adjust pin numbers in motor_control.py if your wiring differs.

# 3. Smoke-test the motors (confirm no reversed wheels before running policies)
python3 motor_control.py

# 4. Start the server
python3 server.py
# → Dashboard: http://<pi-ip>:5000
```

## Unity setup

1. Add `InstructionExecutor.cs` to the same GameObject as `RoverAgent`.
2. Set `roverAgent` and `bridgeClient` references (or leave empty — auto-found).
3. Set `roverIP` in `RoverBridgeClient` to your Pi's IP.
4. To run from a file: set `instructionFilePath` to the .txt path.
5. To run from the web dashboard: leave `pollServer = true` (default).

## Instruction file format

```
# Comments and blank lines ignored
GOTO x z        # navigate to world position (x, z) — grid cell or world units
WAIT seconds    # pause N seconds
STOP            # halt execution
```

Generate this file with your LLM (Ollama, Gemini, etc.) and either:
- Write it to disk and point `InstructionExecutor.instructionFilePath` at it
- Upload it via the web dashboard at `http://<pi-ip>:5000`
- POST it directly: `curl -X POST http://<pi-ip>:5000/instructions --data-binary @mission.txt`

## API reference

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/drive` | `{"throttle": float, "turn": float}` both in [-1,1] |
| POST | `/stop` | Emergency stop |
| GET | `/status` | Current throttle, turn, instruction, queue |
| POST | `/instructions` | Upload instruction file (plain text body) |
| GET | `/instructions/next` | Get current instruction (Unity polls this) |
| POST | `/instructions/result` | Unity reports DONE/BLOCKED after each instruction |
| GET | `/instructions/status` | Full queue + result log |
| POST | `/instructions/clear` | Reset queue |

## Watchdog

If `/drive` isn't called for 1.5 seconds, the server auto-stops the rover.
This prevents runaway if Unity crashes or the network drops.
