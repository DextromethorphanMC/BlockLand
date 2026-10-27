"""Runs DLSS through BlockLand's bridge on this machine's GPU, outside the game (hidden window, no focus).

python -I tools/dlss/check_dlss.py <Streamline runtime folder>
python -I tools/dlss/check_dlss.py --framegen <Streamline runtime folder> [visible]
python -I tools/dlss/check_dlss.py --neural <Streamline runtime folder with nvngx_dlssnr.dll>
python -I tools/dlss/check_dlss.py --rr <Streamline runtime folder with sl.dlss_d.dll and nvngx_dlssd.dll>

The runtime folder holds NVIDIA's Streamline binaries (sl.interposer.dll, sl.common.dll, sl.dlss.dll, nvngx_dlss.dll);
see tools/dlss/README.md. Compiles the bridge (mod/.../graphics/upscale/dlss, LWJGL only) and DlssCheck.java into a temp
folder and runs it. Exit code 0 = DLSS ran; the printed errors show which jitter / motion-vector conventions are right.
--framegen runs FrameGenCheck instead: DLSS Frame Generation through the presenter's swapchain (the window never takes
the focus; "visible" shows it, for systems that do not present into hidden windows).
--neural runs NeuralCheck: DLSS 5 neural rendering through NGX on the same device (driver support, parameter block layout,
Super Resolution alongside; the model itself when the driver and GPU support it).
--rr runs RayReconstructionCheck: DLSS Ray Reconstruction with its guides on a noisy input (it must come out smoother),
then Super Resolution again on the same bridge."""
import glob
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
BRIDGE = os.path.join(REPO, "mod", "src", "main", "java", "com", "blockland", "client", "graphics", "upscale", "dlss")
sys.path.insert(0, os.path.join(REPO, "tools", "shaders"))

import check_shaders                              # noqa: E402  (LWJGL and JDK lookup)


def jdk_tool(name):
    java = check_shaders.java()
    return os.path.join(os.path.dirname(java), name + (".exe" if java.endswith(".exe") else ""))


def main():
    args = sys.argv[1:]
    framegen = "--framegen" in args
    neural = "--neural" in args
    rr = "--rr" in args
    args = [a for a in args if a not in ("--framegen", "--neural", "--rr")]
    if not args:
        sys.exit(__doc__)
    runtime = os.path.abspath(args[0])
    work = os.path.join(tempfile.gettempdir(), "blockland-dlsscheck")
    classes = os.path.join(work, "classes")
    logs = os.path.join(work, "logs")
    os.makedirs(classes, exist_ok=True)
    os.makedirs(logs, exist_ok=True)
    classpath = os.pathsep.join(check_shaders.lwjgl())
    sources = glob.glob(os.path.join(BRIDGE, "*.java")) + [os.path.join(HERE, name) for name in ("DlssCheck.java", "FrameGenCheck.java", "NeuralCheck.java", "RayReconstructionCheck.java")]
    # what the bridge calls outside itself that needs Minecraft: check-only stand-ins (stubs/)
    sources += glob.glob(os.path.join(HERE, "stubs", "**", "*.java"), recursive=True)
    env = dict(os.environ, JAVA_TOOL_OPTIONS="")
    if subprocess.call([jdk_tool("javac"), "-nowarn", "-d", classes, "-cp", classpath] + sources, env=env) != 0:
        sys.exit("compile failed")
    main_class = "RayReconstructionCheck" if rr else "NeuralCheck" if neural else "FrameGenCheck" if framegen else "DlssCheck"
    sys.exit(subprocess.call([check_shaders.java(), "-cp", os.pathsep.join([classes, classpath]), main_class, runtime, logs] + args[1:], env=env))


if __name__ == "__main__":
    main()
