# ImageFrames

ImageFrames is a Hytale mod that facilitates the display of custom images on blocks within the game world. It allows players to define image sources (URLs), dimensions, and orientation for immersive decoration.

## Features

*   **Custom Images**: Load images via URL.
*   **Flexible Resizing**: Supports `stretch`, `crop`, and `contain` fit modes.
*   **Multi-Block Frames**: Create large displays spanning multiple blocks (e.g., 2x2, 3x2).
*   **Intuitive Orientation**:
    *   **Smart Face Detection**: Automatically determines the frame's facing direction based on the block face you interact with.
*   **Owner Locking**: Prevents other players from modifying your frames (configurable).
*   **Interactive UI**: Easy-to-use configuration menu when interacting with frame blocks.
*   **Panels**: Flat panels with optional hidden frame and togglable collision.
*   **Banners**: Placeable banners with adjustable scale and display mode.

## Usage

1.  **Place Frame Blocks**: Arrange the frame blocks in a rectangular shape (e.g., 1x1, 2x1, 2x2).
2.  **Interact**: Right-click the frame to open the configuration menu.
3.  **Configure**:
    *   **URL**: Enter the direct link to your image.
    *   **Fit**: Choose how the image fits the frame.
    *   **Rotation**: Rotate the image if needed.
    *   **Flip**: Flip horizontally or vertically.
    *   **Panels**: Toggle **Hide Frame** and **Collision** (panels only).
    *   **Banners**: Set **Banner Scale** (0.25 - 4.0) and **Banner Mode** (**Texture** or **Area**).
4.  **Apply**: Click "Apply" to render the image.

### Resetting Frames
 To reset an ImageFrame, simply break one of the frame blocks and rebuild the structure. This will clear the current configuration.

### Panel Notes
*   Panels can hide the frame and toggle collision from the UI.
*   When collision is disabled, only the **top-right** panel tile can be broken to remove the whole panel.
    This tile is prone to physics updates, so build with care.

## Crafting

You can craft Image Frames and Panels at a **Furniture Bench**.

**Recipe:**
*   **Category**: Furniture Misc
*   30x **Life Essence**
*   9x **Wood Trunk** (Any type)

**Output:** 9x Image Frames

**Panel Output:** 18x Image Panels

**Banner Recipe:**
*   **Category**: Furniture Misc
*   10x **Life Essence**
*   3x **Wood Trunk** (Any type)
*   7x **Rubble**

**Banner Output:** 1x Image Banner

## Screenshots

<div align="center">
  <img src="Screenshots/Main.png" alt="Main Showcase" width="600"/>
  <br>
  <em>Showcase</em>
</div>

<div align="center">
  <img src="Screenshots/UI.png" alt="UI Configuration" width="600"/>
  <br>
  <em>Configuration Interface</em>
</div>

<div align="center">
  <img src="Screenshots/BannerUI.png" alt="Banner UI Configuration" width="600"/>
  <br>
  <em>Banner Configuration Interface</em>
</div>

<div align="center">
  <img src="Screenshots/Panel UI.png" alt="Panel UI Configuration" width="600"/>
  <br>
  <em>Panel Configuration Interface</em>
</div>

<div align="center">
  <img src="Screenshots/Block.png" alt="Block View" width="600"/>
  <br>
  <em>Frame Block</em>
</div>

<div align="center">
  <img src="Screenshots/Panels.png" alt="Panels" width="600"/>
  <br>
  <em>Panels</em>
</div>

<div align="center">
  <img src="Screenshots/Banner.png" alt="Banner" width="600"/>
  <br>
  <em>Banner</em>
</div>

<div align="center">
  <img src="Screenshots/BannerHeld.png" alt="Banner Held" width="600"/>
  <br>
  <em>Banner Held</em>
</div>

<div align="center">
  <img src="Screenshots/With Frame.png" alt="Panel With Frame" width="600"/>
  <br>
  <em>Panel With Frame</em>
</div>

<div align="center">
  <img src="Screenshots/Hidden Frame.png" alt="Panel With Hidden Frame" width="600"/>
  <br>
  <em>Panel With Hidden Frame</em>
</div>

## Installation

1.  Install JDK 25 and build the project using `./gradlew build` (`gradlew.bat build` on Windows).
2.  Place the generated JAR file into your Hytale's `mods` directory.

## Hytale 0.7.3 compatibility

This version targets **Hytale 0.7.3**. Gradle downloads the pinned server API from
Hytale's official release Maven repository, so building and running the unit tests
does not require a local game installation. A game installation is still required
for the IDE server launch configurations; use `-Phytale_home=...` for a custom path.

Block reads, writes and rotations use the section APIs, including negative heights
and heights above 320 in cubic worlds. Runtime textures and models are registered
during asset loading, before saved world sections load. The integrity check starts
with the plugin, and world operations run on the world thread.

Saved frame groups using the original `image_frames:frame`, `image_frames:panel`
or `image_frames:banner` item IDs are mapped to the underscore IDs on load. Their
stored tile IDs and positions are preserved.

Image processing is bounded to 16 MiB per HTTP download, 16 megapixels per source
or rendered image, 256 blocks per frame, and a tile size between 16 and 1024 pixels.
The decoded image cache uses at most 64 MiB of estimated pixel storage and retains
its disk cache when evicting entries. Oversized saved layouts are rejected before
world loading; their saved data is retained.

Run `./gradlew build` to compile, package and execute the regression tests. See
[the 0.7.3 validation notes](docs/hytale-0.7.3.md) for server checks and remaining
manual verification.
