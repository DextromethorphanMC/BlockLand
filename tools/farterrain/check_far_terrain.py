"""Checks the far terrain's client logic (rendering spec §22) without starting the game.

python -I tools/farterrain/check_far_terrain.py

Asks Gradle for the mod's compile classpath (configuration only: compiles nothing into mod/build, safe while dev clients
or a dev server run), compiles FarTerrainCheck.java with the mod's sources it needs (-sourcepath) into a temp directory
and runs it: the tile codec and keys, the mesher's quads (range, outward winding, merging) and the quadtree selection
(no holes, no overlaps, levels by distance). Exit code 0 = all pass."""
import os
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True  # importing the other checks must not leave __pycache__ folders in tools/

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
MOD = os.path.join(REPO, "mod")
SOURCES = os.path.join(MOD, "src", "main", "java")
sys.path.insert(0, os.path.join(REPO, "tools", "shaders"))
sys.path.insert(0, os.path.join(REPO, "tools", "combat"))

import check_shaders                              # noqa: E402  (JDK and LWJGL lookup)
import check_ballistics                           # noqa: E402  (the Gradle classpath)


def main():
    java = check_shaders.java()
    javac = os.path.join(os.path.dirname(java), "javac" + (".exe" if java.endswith(".exe") else ""))
    work = os.path.join(tempfile.gettempdir(), "blockland-farterrain")
    os.makedirs(work, exist_ok=True)
    cp = check_ballistics.classpath(work)
    classes = os.path.join(work, "classes")
    os.makedirs(classes, exist_ok=True)
    env = dict(os.environ, JAVA_TOOL_OPTIONS="")
    if subprocess.call([javac, "-nowarn", "-proc:none", "-encoding", "UTF-8", "-d", classes, "-cp", cp, "-sourcepath", SOURCES,
                        os.path.join(HERE, "FarTerrainCheck.java")], env=env) != 0:
        sys.exit("compile failed")
    run_cp = os.pathsep.join([classes, cp] + check_shaders.lwjgl())
    sys.exit(subprocess.call([java, "-cp", run_cp, "com.blockland.client.graphics.far.FarTerrainCheck"], env=env))


if __name__ == "__main__":
    main()
