"""
motor_control.py — Raspberry Pi GPIO motor driver for the reflex-arc rover.

Wiring (L298N dual H-bridge, adjust pin numbers to match your board):
  ENA  -> GPIO 12  (left motors PWM enable)
  IN1  -> GPIO 20  (left forward)
  IN2  -> GPIO 21  (left backward)
  ENB  -> GPIO 13  (right motors PWM enable)
  IN3  -> GPIO 16  (right forward)
  IN4  -> GPIO 26  (right backward)

If wheels spin the wrong way, swap IN1↔IN2 or IN3↔IN4 for that side.
If left and right are swapped, swap the entire L and R pin groups.

throttle ∈ [-1, 1]  positive = forward
turn     ∈ [-1, 1]  positive = right

Differential mixing:
  left_speed  = throttle + turn   (clipped to [-1, 1])
  right_speed = throttle - turn
"""

import RPi.GPIO as GPIO
import time

# ── Pin assignments ──────────────────────────────────────────────────────────
ENA = 12   # left  PWM
IN1 = 20   # left  forward
IN2 = 21   # left  backward
ENB = 13   # right PWM
IN3 = 16   # right forward
IN4 = 26   # right backward

PWM_FREQ = 1000   # Hz — 1 kHz is typical for DC motor PWM

# ── Module-level state ───────────────────────────────────────────────────────
_pwm_left  = None
_pwm_right = None
_initialized = False


def init():
    """Call once at startup. Safe to call multiple times."""
    global _pwm_left, _pwm_right, _initialized
    if _initialized:
        return

    GPIO.setmode(GPIO.BCM)
    GPIO.setwarnings(False)
    GPIO.setup([ENA, IN1, IN2, ENB, IN3, IN4], GPIO.OUT, initial=GPIO.LOW)

    _pwm_left  = GPIO.PWM(ENA, PWM_FREQ)
    _pwm_right = GPIO.PWM(ENB, PWM_FREQ)
    _pwm_left.start(0)
    _pwm_right.start(0)
    _initialized = True
    print("[motors] initialized")


def _set_side(in_a, in_b, pwm, speed: float):
    """Drive one motor side. speed ∈ [-1, 1]."""
    speed = max(-1.0, min(1.0, speed))
    duty  = abs(speed) * 100.0          # convert to 0–100 duty cycle

    if speed > 0:
        GPIO.output(in_a, GPIO.HIGH)
        GPIO.output(in_b, GPIO.LOW)
    elif speed < 0:
        GPIO.output(in_a, GPIO.LOW)
        GPIO.output(in_b, GPIO.HIGH)
    else:
        GPIO.output(in_a, GPIO.LOW)
        GPIO.output(in_b, GPIO.LOW)

    pwm.ChangeDutyCycle(duty)


def drive(throttle: float, turn: float):
    """
    Set motor speeds from throttle + turn inputs (both ∈ [-1, 1]).
    Differential drive: left = throttle + turn, right = throttle - turn.
    """
    if not _initialized:
        init()

    left  = throttle + turn
    right = throttle - turn

    _set_side(IN1, IN2, _pwm_left,  left)
    _set_side(IN3, IN4, _pwm_right, right)


def stop():
    """Cut all motor outputs immediately."""
    if not _initialized:
        return
    GPIO.output([IN1, IN2, IN3, IN4], GPIO.LOW)
    _pwm_left.ChangeDutyCycle(0)
    _pwm_right.ChangeDutyCycle(0)


def cleanup():
    """Release GPIO on shutdown."""
    global _initialized
    if not _initialized:
        return
    stop()
    _pwm_left.stop()
    _pwm_right.stop()
    GPIO.cleanup()
    _initialized = False
    print("[motors] cleaned up")


# ── Quick smoke test ─────────────────────────────────────────────────────────
if __name__ == "__main__":
    print("Motor smoke test — forward 2s, turn right 1s, backward 1s, stop")
    init()
    try:
        drive(0.5, 0.0);  time.sleep(2)
        drive(0.0, 0.5);  time.sleep(1)
        drive(-0.4, 0.0); time.sleep(1)
        stop()
        print("Done. Check wheel directions and fix IN pins if needed.")
    finally:
        cleanup()
