import math, random, struct, wave

SR = 44100
DUR = 0.20
N = int(SR * DUR)
frames = bytearray()
for i in range(N):
    t = i / SR
    # pitch sweep: 750Hz -> 110Hz over the pop
    f = 110 + (750 - 110) * math.exp(-t * 22)
    env = math.exp(-t * 24)
    # click transient in the first 6ms (the "burst")
    click = (math.exp(-t * 400)) * 0.9
    noise = (random.random() * 2 - 1) * click * 0.55
    body = math.sin(2 * math.pi * f * t) * env * 0.75
    s = max(-0.95, min(0.95, body + noise))
    frames += struct.pack("<h", int(s * 32767))

with wave.open("app/src/main/res/raw/pop.wav", "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes(bytes(frames))
print("pop.wav written:", len(frames), "bytes")