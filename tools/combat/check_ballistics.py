"""Checks the shared round-in-fluids ballistics (mod: com.blockland.combat.Ballistics) without starting the game.

python -I tools/combat/check_ballistics.py

Asks Gradle for the mod's compile classpath (configuration only: compiles nothing into mod/build, safe while dev
clients run), compiles Ballistics.java with BallisticsCheck.java into a temp directory and runs the check: hand-worked
cases (spent in water and lava, energy on the way, skipping off the surface, entries, under water, shallow water) and
the voxel walk against a fine march. Exit code 0 = all pass."""
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
MOD = os.path.join(REPO, "mod")
SOURCE = os.path.join(MOD, "src", "main", "java", "com", "blockland", "combat", "Ballistics.java")
sys.path.insert(0, os.path.join(REPO, "tools", "shaders"))

import check_shaders                              # noqa: E402  (JDK lookup)


def classpath(work):
    out = os.path.join(work, "cp.txt")
    command = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", os.path.join(MOD, "build.ps1"),
               "blPrintClasspath", "--init-script", os.path.join(HERE, "printcp.gradle"), "--no-configuration-cache",
               "-Dbl.cpout=" + out, "-q"]
    if subprocess.call(command, cwd=MOD) != 0 or not os.path.exists(out):
        sys.exit("could not get the compile classpath from Gradle")
    with open(out, encoding="utf-8") as f:
        return f.readline().strip()


def main():
    java = check_shaders.java()
    javac = os.path.join(os.path.dirname(java), "javac" + (".exe" if java.endswith(".exe") else ""))
    work = os.path.join(tempfile.gettempdir(), "blockland-ballistics")
    os.makedirs(work, exist_ok=True)
    cp = classpath(work)
    classes = os.path.join(work, "classes")
    os.makedirs(classes, exist_ok=True)
    env = dict(os.environ, JAVA_TOOL_OPTIONS="")
    if subprocess.call([javac, "-nowarn", "-encoding", "UTF-8", "-d", classes, "-cp", cp, SOURCE,
                        os.path.join(HERE, "BallisticsCheck.java")], env=env) != 0:
        sys.exit("compile failed")
    sys.exit(subprocess.call([java, "-cp", os.pathsep.join([classes, cp]), "BallisticsCheck"], env=env))


if __name__ == "__main__":
    main()
