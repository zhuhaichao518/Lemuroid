# Optional CloudPlayPlus left stick

The touch editor offers Original (the default), CloudPlayPlus touch-down center,
and CloudPlayPlus fixed center. Existing saved configurations retain Original.
Settings are stored per controller layout and orientation alongside the other
touch settings.

Behavior reference: [CloudPlayPlus #687](https://github.com/CloudPlayPlus/cloudplayplus/pull/687)
and [onscreen_gamepad #5](https://github.com/CloudPlayPlus/onscreen_gamepad/pull/5),
reviewed at component commit `479586e9d0f0b63db733054e80217c6f37ea3310`.
The abandoned floating-follow prototype (#684 / component #4) is not used.
This is a native Kotlin implementation of the interaction rules.

- Touch-down mode uses the first contact as zero; fixed mode uses the layout center
  and responds immediately. Neither moves the origin during the gesture.
- The radial dead zone is 1 dp. Force increases linearly to full at 40 dp.
  Digital controllers quantize the direction to eight sectors (four if their
  original control disallows diagonals); analog controllers retain radial force.
- Optional left-half activation includes the original joystick box even when it
  crosses the middle of the screen. Existing button and other control boxes win.
- While dragging, the base disappears and a fixed-size shaded half-moon rotates
  toward the input. Fixed-center mode can display it above the stick with a dot
  showing the full relative touch displacement, including travel beyond full force.
- Optional automatic upward movement uses the reference's upward 30-degree cone,
  target at feedback radius + 100 dp, and 30 dp release radius at full force.
  Releasing on the target holds up; the next joystick touch stops it. No sprint
  button is sent. The UI calls this upward movement because retro games may use
  up for ladders, aiming or menus rather than running.
- Opening a menu, editing settings, hiding controls, losing focus, changing the
  layout or resizing releases movement. Cancellation never starts automatic movement.

`CloudStickController` splits touch pointers at the mobile activity before PadKit.
Only fresh down events can select the joystick; captured fingers remain excluded
from the button stream until up, even after moving onto a button or changing modes.
Filtered events retain pointer IDs, adjusted down/up actions, and a consistent
down time. The original control path continues to use PadKit.

On N64, PSP, DualShock and 3DS layouts this replaces the left analog stick;
on digital layouts it replaces the left directional pad. Right controls are
unchanged. Half-screen activation can be disabled for games using the touchscreen.

Validation commands:

```sh
./gradlew :lemuroid-touchinput:connectedDebugAndroidTest
./gradlew :lemuroid-app:assembleFreeBundleRelease
```

Device tests cover center/force rules, diagonal input, original behavior, button
priority, both orders of two-finger input, pointer ownership beyond the control
area, mode changes, screen resizing, automatic movement and cancellation.
