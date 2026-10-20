# I'm Squishin Your Head! 🤏

An Android camera game where you pinch heads with two fingers to squish them like balloons. Uses ML Kit face detection to overlay comic balloons on real faces — pinch to squish, release to boing back, close far enough and **POP**!

## Features

- **Real-time face detection** via Google ML Kit
- **Two-finger pinch to squish** — the balloon compresses with your pinch
- **Face textures** — balloons are textured with the actual detected face
- **Combo system** — pop heads in quick succession for combo multipliers
- **Haptic feedback** — vibration on pop
- **Sound effects** — procedurally generated squish and pop sounds
- **Front/back camera** — flip between cameras with one tap
- **Comic animations** — X-X eyes when popped, bulging eyes when squishing, particle explosions, score popups

## Screenshots

The game renders colorful balloons over detected faces. Pinch with two fingers to squish:

```
   🎈     🎈     🎈
  /   \   /   \   /   \
 | 😐  | | 😬  | | 😱  |
  \   /   \   /   \   /
   🎈     🎈     🎈
```

## Building

### Prerequisites

- Android Studio (Hedgehog or newer)
- Android SDK 34
- Kotlin 1.9.24+

### Setup

```bash
git clone https://github.com/puddintaim1975-dev/headpop.git
cd headpop
```

Open in Android Studio and build, or from command line:

```bash
./gradlew assembleRelease
```

### Signing (for release builds)

Create a `keystore.properties` file in the project root (gitignored):

```properties
storeFile=../keystore/your-release.jks
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

Generate a keystore:

```bash
keytool -genkey -v -keystore keystore/your-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias your-key-alias
```

Debug builds work without any keystore setup.

## Tech

- **Language:** Kotlin
- **Camera:** AndroidX CameraX
- **Face detection:** Google ML Kit Face Detection (fast mode with tracking)
- **Audio:** SoundPool with procedurally generated WAV effects
- **Min SDK:** 24 (Android 7.0)
- **Target SDK:** 34 (Android 14)

## How to Play

1. Point the camera at faces (yours, friends', anyone)
2. Pinch a head balloon with two fingers
3. Squeeze until it POPs!
4. Pop quickly for combo bonuses
5. Flip the camera to switch between front/back

## License

WTFPL — Do What The F*** You Want To. See [LICENSE](LICENSE).