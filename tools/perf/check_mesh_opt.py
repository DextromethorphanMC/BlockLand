"""Checks the chunk geometry optimizations (rendering spec §20) without starting the game.

python -I tools/perf/check_mesh_opt.py

Asks Gradle for the mod's compile classpath (configuration only), compiles MeshOptCheck.java with the mod's sources it
needs into a temp directory and runs it: FACE_GROUPS' classification against vanilla's own face vertex order (FaceInfo:
outward faces wind counter-clockwise, the side GL keeps), its stable grouping and visibility, and FAR_HIDDEN_GEOMETRY's
flood (sealed pockets hidden, caves open to the side kept, the border never hidden). Exit code 0 = all pass."""
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
    work = os.path.join(tempfile.gettempdir(), "blockland-meshopt")
    os.makedirs(work, exist_ok=True)
    cp = check_ballistics.classpath(work)
    classes = os.path.join(work, "classes")
    os.makedirs(classes, exist_ok=True)
    env = dict(os.environ, JAVA_TOOL_OPTIONS="")
    if subprocess.call([javac, "-nowarn", "-proc:none", "-encoding", "UTF-8", "-d", classes, "-cp", cp, "-sourcepath", SOURCES,
                        os.path.join(HERE, "MeshOptCheck.java")], env=env) != 0:
        sys.exit("compile failed")
    run_cp = os.pathsep.join([classes, cp] + check_shaders.lwjgl())
    sys.exit(subprocess.call([java, "-cp", run_cp, "com.blockland.client.graphics.far.MeshOptCheck"], env=env))


if __name__ == "__main__":
    main()
