import math, random, struct, wave

SR = 44100
DUR = 0.30
N = int(SR * DUR)
frames = bytearray()
for i in range(N):
    t = i / SR
    p = t / DUR
    # rising rubbery base: 120 -> 240 Hz
    f = 120 + 120 * (p ** 1.6)
    # vibrato (stick-slip wobble) deepening through the squish
    vib = math.sin(2 * math.pi * 11 * t) * (4 + 22 * p)
    ph = 2 * math.pi * f * t + 2 * math.pi * vib * t
    body = (math.sin(ph) * 0.62 + math.sin(2 * ph) * 0.22)
    # wet noise squelch, strongest early
    noise = (random.random() * 2 - 1) * (0.35 * math.exp(-t * 9))
    env = min(1.0, t / 0.006) * math.exp(-t * 7)
    s = max(-0.95, min(0.95, (body + noise) * env))
    frames += struct.pack("<h", int(s * 32767))

with wave.open("app/src/main/res/raw/squish.wav", "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes(bytes(frames))
print("squish.wav written:", len(frames), "bytes")