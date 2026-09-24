import subprocess, time, re, os

FP = re.compile(r"phys_footprint[^:]*:\s+([\d.]+)\s*([KMG]?)B")

def footprint(pid):
    try:
        out = subprocess.run(["footprint", "-p", str(pid), "-s"], capture_output=True, text=True, timeout=5).stdout
    except Exception:
        return None
    m = FP.search(out)
    if not m:
        return None
    return float(m.group(1)) * {"": 1/1048576, "K": 1/1024, "M": 1, "G": 1024}[m.group(2)]

def run(app_path, proc_match, label, total=20.0, win=2.0):
    name = os.path.basename(app_path)[:-4]
    subprocess.run(["osascript", "-e", f'tell application "{name}" to quit'], capture_output=True)
    time.sleep(2)
    subprocess.run(["pkill", "-f", proc_match], capture_output=True)
    time.sleep(2)
    t0 = time.time()
    subprocess.run(["open", "-a", app_path], capture_output=True)
    pid = None
    dl = time.time() + 10
    while pid is None and time.time() < dl:
        r = subprocess.run(["pgrep", "-f", proc_match], capture_output=True, text=True)
        p = [x for x in r.stdout.split() if x]
        if p:
            pid = p[0]
        time.sleep(0.01)
    if pid is None:
        print(f"{label}: 未启动")
        return
    samples = []
    end = time.time() + total
    while time.time() < end:
        f = footprint(pid)
        if f:
            samples.append((time.time() - t0, f))
        time.sleep(0.15)
    n = max(1, int(win / 0.15))
    plateau_t = None
    for i in range(len(samples) - n):
        a = samples[i][1]
        b = samples[i + n][1]
        if abs(b - a) / max(a, 1) < 0.02:
            plateau_t = samples[i][0]
            break
    peak = max(s[1] for s in samples)
    print(f"{label}: 内存进入 ±2% 平台期 @ {plateau_t}s | 峰值 {peak:.0f}MB | 末值 {samples[-1][1]:.0f}MB")

if __name__ == "__main__":
    run("/Applications/PixivShaft.app", "/Applications/PixivShaft.app/Contents/MacOS/PixivShaft", "Compose 现状")
    run("/Applications/Kazumi.app", "/Applications/Kazumi.app/Contents/MacOS/Kazumi", "Flutter Kazumi")
    run("/Applications/RustDesk.app", "/Applications/RustDesk.app/Contents/MacOS/RustDesk", "Flutter+Rust RustDesk")
