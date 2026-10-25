"""Checks the anti-cheat's movement envelope (mod: com.blockland.server.anticheat.predict) without starting the game.

python -I tools/anticheat/check_movement.py

The predictor is plain Java (no Minecraft classes), so this compiles predict/*.java with MovementCheck.java into a temp
directory and runs it: an independent copy of vanilla's player physics plays legal sequences (walking, sprint jumping,
ice, effects, falls, knockback, explosions, standing still, slime and bed bounces, honey, snow layers, boats) that must
stay inside the envelope, and cheats (speed, hover, glide, high jump, step, blink, over-bounce) that must leave it. Touches nothing in mod/build. Exit code 0 = all pass."""
import glob
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
PREDICT = os.path.join(REPO, "mod", "src", "main", "java", "com", "blockland", "server", "anticheat", "predict")
sys.path.insert(0, os.path.join(REPO, "tools", "shaders"))

import check_shaders                              # noqa: E402  (JDK lookup)


def main():
    java = check_shaders.java()
    javac = os.path.join(os.path.dirname(java), "javac" + (".exe" if java.endswith(".exe") else ""))
    work = os.path.join(tempfile.gettempdir(), "blockland-anticheat")
    classes = os.path.join(work, "classes")
    os.makedirs(classes, exist_ok=True)
    env = dict(os.environ, JAVA_TOOL_OPTIONS="")
    sources = sorted(glob.glob(os.path.join(PREDICT, "*.java"))) + [os.path.join(HERE, "MovementCheck.java")]
    if subprocess.call([javac, "-nowarn", "-encoding", "UTF-8", "-d", classes] + sources, env=env) != 0:
        sys.exit("compile failed")
    sys.exit(subprocess.call([java, "-cp", classes, "MovementCheck"], env=env))


if __name__ == "__main__":
    main()
