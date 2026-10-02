# Park Builder 🎢

**Park Builder** is an immersive, feature-rich Android theme park management and simulation game built natively with **Jetpack Compose** and **Kotlin**. Design, build, and manage your ultimate amusement park with custom isometric rendering, procedural pixel art, dynamic visitor simulation, and deep economic management!

---

## ✨ Key Features

- **Isometric Rendering Engine**: Custom canvas-based renderer supporting smooth zooming, panning, and detailed isometric grid projections.
- **Procedural Pixel Art & Textures**: Built-in procedural sprite generator and texture atlas creating rich visual representations of rides, paths, trees, water, and visitors.
- **Dynamic Visitor Simulation**:
  - Pathfinding and movement AI across park pathways and ride queues.
  - Individual visitor stats including happiness, hunger, thirst, energy, and cash.
  - Satisfied (or frustrated!) visitor thoughts and feedback bubbles.
- **Rides & Attractions**:
  - Thrill rides (Roller Coasters, Dodgems, Carousels, etc.).
  - Food stalls, drink stands, and restrooms to keep guests happy.
  - Maintenance and breakdown mechanics requiring repair mechanics.
- **Park Infrastructure & Scenery**:
  - Paths, queues, lights, benches, and trash cans.
  - Trees, bushes, rocks, and water features for landscaping.
- **Economy & Management**:
  - Real-time cash flow, ticket sales, concession profits, and maintenance costs.
  - Adjustable admission and ride pricing.
  - Staff management (mechanics, handymen).
- **Modern Android Architecture**:
  - 100% Jetpack Compose UI with Material 3 design principles.
  - Reactive state management using `ViewModel`, `StateFlow`, and Coroutines.
  - Comprehensive unit test and UI test suite.

---

## 🛠️ Tech Stack & Requirements

- **Language**: Kotlin 1.9+ / 2.0+
- **UI Framework**: Jetpack Compose & Material 3
- **Architecture**: MVVM (Model-View-ViewModel) + Unidirectional Data Flow
- **Minimum SDK**: Android 8.0 (API Level 26)
- **Target SDK**: Android 35 (Compile SDK 37)
- **Testing**: JUnit 4, AndroidX Test, Robolectric / Instrumented Tests

---

## 📂 Project Structure

```text
app/
├── src/
│   ├── main/java/com/example/parkbuilder/
│   │   ├── MainActivity.kt           # Entry point
│   │   ├── game/
│   │   │   ├── GameEngine.kt         # Core simulation loop & game state
│   │   │   ├── GameViewModel.kt      # ViewModel bridging simulation & UI
│   │   │   ├── ParkGenerator.kt      # Procedural map generator
│   │   │   └── model/                # Game data classes, Iso math, ParkMap
│   │   └── ui/
│   │       ├── GameScreen.kt         # Main gameplay layout
│   │       ├── ParkHud.kt            # Overlay UI (HUD, controls, stats)
│   │       ├── ParkRenderer.kt       # Isometric canvas renderer
│   │       ├── PixelArt.kt           # Procedural sprite & pixel art generator
│   │       └── theme/                # Material 3 colors, typography & theme
│   ├── test/                         # Unit tests (Game engine, simulation, layout)
│   └── androidTest/                  # Instrumented UI tests
```

---

## 🚀 Getting Started

1. **Clone the Repository**:
   ```bash
   git clone https://github.com/your-username/park-builder.git
   ```
2. **Open in Android Studio**:
   - Open Android Studio (Ladybug or newer).
   - Select **Open** and choose the `ParkBuilder` directory.
3. **Build and Run**:
   - Connect an Android device or start an emulator (API 26+).
   - Click **Run** (`Shift + F10`) or execute via Gradle:
     ```bash
     ./gradlew assembleDebug
     ```

---

## 🧪 Running Tests

To run the unit test suite:
```bash
./gradlew test
```

To run instrumented tests on an emulator/device:
```bash
./gradlew connectedAndroidTest
```

---

## 📄 License

This project is open-source and available for educational and personal exploration.
