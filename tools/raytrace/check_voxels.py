"""Checks BlockLand's voxel ray tracer against a CPU reference (hidden window, no focus).

python -I tools/raytrace/check_voxels.py

Builds a voxel atlas the way client/graphics/raytrace/VoxelWorld does (section tiles, toroidal slots, a box corner at
negative section coordinates), marches a few thousand random rays with rt_march from rt_common.glsl on this machine's GPU
and compares every hit with a fine CPU march. Exit code 0 = the tracer and the atlas layout agree."""
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
COMMON = os.path.join(REPO, "mod", "src", "main", "resources", "assets", "blockland", "shaders", "include", "rt_common.glsl")
sys.path.insert(0, os.path.join(REPO, "tools", "shaders"))

import check_shaders                              # noqa: E402  (LWJGL and JDK lookup)


def main():
    java = check_shaders.java()
    javac = os.path.join(os.path.dirname(java), "javac" + (".exe" if java.endswith(".exe") else ""))
    classes = os.path.join(tempfile.gettempdir(), "blockland-voxelcheck")
    os.makedirs(classes, exist_ok=True)
    classpath = os.pathsep.join(check_shaders.lwjgl())
    env = dict(os.environ, JAVA_TOOL_OPTIONS="")
    if subprocess.call([javac, "-nowarn", "-d", classes, "-cp", classpath, os.path.join(HERE, "VoxelTraceCheck.java")], env=env) != 0:
        sys.exit("compile failed")
    sys.exit(subprocess.call([java, "-cp", os.pathsep.join([classes, classpath]), "VoxelTraceCheck", COMMON], env=env))


if __name__ == "__main__":
    main()
