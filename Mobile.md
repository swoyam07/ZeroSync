Java JDK	OpenJDK 21.0.12.1 (LTS)
Kotlin Compiler	2.4.21
Gradle	9.8.1
C/C++ (GCC)	16.2.1

Target Android phones.
Device:
    - Primary test device: Xiaomi Redmi Turbo 4.
    - Operating system target: Android 16 with Xiaomi HyperOS 3.
    - Device memory: 16 GB RAM.
    - Storage: 256 GB
    - Display: 6.67-inch AMOLED.
    - Display resolution: 1220 x 2712 pixels.
    - Display refresh rate: up to 120 Hz.
    - Processor: MediaTek Dimensity 8400 Ultra.
    - Battery: 6550 mAh.

    Android compatibility
        - Set `targetSdk` to the latest stable Android SDK version supported by the installed Android Gradle Plugin.
        - Get a pat
        - Test ZeroSync on Android 16 / API level 36.
        - Keep the layout adaptive; do not use a fixed 1220 x 2712 pixel layout.
        - Test with 16 GB RAM available, but do not assume every future user has high RAM.
        - The app must safely handle backgrounding, process recreation, configuration changes, and low-memory conditions.

UI:
    APP THEME ARCHITECTURE
    ----------------------
    Theme Constraint: Strict AMOLED Dark / Pure Black Theme (#000000 background base). Not designed for light or white themes.


    COLOR SYSTEM MAPPING

    Role: Primary Color
    (Monochrome only since 2026-10-21: black, white and grays. No other colors.)
    HEX Code: #FFFFFF
    Tone: White
    Visual & Technical Purpose: High legibility on dark surfaces without visual glare. Used for primary text links, active navigation icons, and brand touches.

    Role: Primary Container
    HEX Code: #3A3A3A
    Tone: Dark Gray
    Visual & Technical Purpose: Muted background fill for active tabs, toggles, or selected cards against pure black backgrounds.

    Role: Secondary Color
    HEX Code: #8A8A8A
    Tone: Mid Gray
    Visual & Technical Purpose: Used for secondary buttons, filter chips, outlined borders, and inactive/supporting controls.

    Role: Tertiary (Accent)
    HEX Code: #D0D0D0
    Tone: Light Gray
    Visual & Technical Purpose: Highest luminance in your dark palette. Reserved for focal points: Floating Action Buttons (FABs), notification badges, and progress indicators.

    APP UI Layout
    ----------------------
    Floating Bottom Navigation Bar(Pill/Capsule Format) Main
        Icons
            - Gallery (Defult Open) (on hold feature)
            - Contact (on hold feature)
            - Music
            - File
            - Camera Document Format


Security :
1) Create a private database that other ordinary apps cannot in any way or form access.
2) App should not able to connect with wifi or intercept with other app connect (Like for sim call only other wise it should not allow in and out communtion with other apps stand alose dont let in any data sont let out data)

Feature :
1) Phone
    Make and receive normal SIM calls from any phone number.
    Use your own calling interface instead of the built-in Phone app interface.
    Display incoming calls using your app's design.
    Bluetootn compataple
    Change ringtone if needed
    when phone comes then show the user name if registered in in comming dile box take full screen defult call pick up and end call

2) Contact
    Save contact name , image ,description, multitple number max 3. call button to call that user and dile box call button in main menu
    Need another switch tab in top buttom  Bar(Pill/Capsule Format) (contact and dile(can chnage the name or to icons)) on click switch mode,  for this section
    Anohter method of switching when i hold for long time or hold the gallery icon in navbar and swipe right and left switch mode type dile numner or see contact
    defult on click contact hold on navbar section dile number part.

3) Gallery
    Concept:
        Have data (video,images) in folder related format.
        Need scrolling like tiktok for video
    UI :
        Need another switch tab in top buttom  Bar(Pill/Capsule Format) (Whole and Specific(can chnage the name or to icons)) on click switch mode,  for this section
            Whole (Full mobile storage (video/image access))
            Specific (There will folder specific items like eg sport folder (sport related topics) and music (music related topics only ti view)) (only pull videos here)
        Anohter method of switching when i hold for long time or hold the gallery icon in navbar and swipe right and left switch mode type
            Specific:
                first show all the folders then select the folder and show videos inside that folder content only

    Section auto scroll feature on/off
    Random scroll on/off (dont pull in same format while scrolling)

4) Music
    scroll able playlist circle button

5) File
    Documtn pdf,word,pdf view detail

6) Camera

Cloude settings to operating flow:

    ZeroSync must prioritize a fast, simple UI and low resource use.

     Resource goals

    - Prefer a responsive UI over decorative animations.
    - Do not add animations by default. Add them only when they improve user understanding, feedback, or an important action.
    - Do not run background work, continuous updates, polling, location tracking, network calls, or active services unless I that feature explicitly needs/approved them.
    - Keep the app functional with no internet connection(main theme).
    - Prefer simple Android and Jetpack tools before adding third-party packages.
    - Use low-memory-friendly lists and UI patterns for large data.
    - Test performance in release builds before claiming a feature is optimized.

     Ask before adding packages

    Before adding any package, library, SDK, Gradle plugin, or major framework, stop and ask for my choice.

    First explain:
    1. What problem we are solving.
    2. Whether Kotlin  or current project code already provides a built-in solution.
    3. The available options.
    4. Your recommendation for ZeroSync.

    Use this exact format:

     Decision: [feature name]

    Option A — Write local/raw code
    - Value: [what it gives us]
    - Resource use: [battery, CPU, RAM, app size, storage, internet]
    - Sacrifice: [what we lose, such as animations, advanced features, time, or maintenance]
    - Best for: [when this is the right choice]

    Option B — Use Android/Jetpack built-in tool
    - Tool: [exact official tool name]
    - Value: [what it gives us]
    - Resource use: [battery, CPU, RAM, app size, storage, internet]
    - Sacrifice: [what we give up]
    - Best for: [when this is the right choice]

    Option C — Use a third-party package
    - Package: [exact package name and official source]
    - Value: [what it gives us]
    - Resource use: [battery, CPU, RAM, app size, storage, internet]
    - Sacrifice: [dependency size, updates, configuration, or complexity]
    - Best for: [when this is the right choice]

    Recommendation
    - Recommend one option in simple language.
    - Explain why it best fits ZeroSync.
    - Wait for my approval before adding anything.

    Ask feature choices
    Before building a feature that has meaningful trade-offs, ask:
    What should this feature prioritize: lowest resource use, fastest user experience, simplest code, or more polished UI?
    Then give 2 or 3 realistic choices using the same Value, Resource use, and Sacrifice format.
    Do not create unnecessary alternatives.
    Do not add dependencies or write complex code until I choose an option.
