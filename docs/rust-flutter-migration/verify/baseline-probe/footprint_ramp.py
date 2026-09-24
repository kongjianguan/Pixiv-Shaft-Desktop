import subprocess, time, sys, re, os

def footprint(pid):
    try:
        out = subprocess.run(["footprint", "-p", str(pid), "-s"], capture_output=True, text=True, timeout=5).stdout
    except Exception:
        return None
    m = re.search(r"phys_footprint[^:]*:\s+([\d.]+)\s*([KMG]?)B", out)
    if not m:
        return None
    v = float(m.group(1))
    u = m.group(2)
    return v * {"": 1/1024/1024, "K": 1/1024, "M": 1, "G": 1024}[u]

def measure(app_path, proc_match, label, total=25.0):
    subprocess.run(["osascript", "-e", f'tell application "{os.path.basename(app_path)[:-4]}" to quit'],
                   capture_output=True)
    time.sleep(2)
    subprocess.run(["pkill", "-f", proc_match], capture_output=True)
    time.sleep(2)
    t0 = time.time()
    subprocess.run(["open", "-a", app_path], capture_output=True)
    pid = None
    deadline = time.time() + 10
    while pid is None and time.time() < deadline:
        r = subprocess.run(["pgrep", "-f", proc_match], capture_output=True, text=True)
        pids = [p for p in r.stdout.split() if p]
        if pids:
            pid = pids[0]
        time.sleep(0.01)
    if pid is None:
        print(f"{label}: 进程未出现")
        return
    t_proc = time.time() - t0
    samples = []
    end = time.time() + total
    while time.time() < end:
        f = footprint(pid)
        if f:
            samples.append((time.time() - t0, f))
        time.sleep(0.15)
    if not samples:
        print(f"{label}: footprint 采样失败")
        return
    final = sum(s[1] for s in samples[-10:]) / min(10, len(samples[-10:]))
    # 首次达到 final*0.9 的时间 = 界面基本就绪的代理指标
    t90 = None
    for t, f in samples:
        if f >= final * 0.9:
            t90 = t
            break
    print(f"{label}: 进程出现 {t_proc*1000:.0f}ms | 内存达稳态90% {t90:.2f}s | 稳态内存 {final:.0f}MB | 采样 {len(samples)} 点")

if __name__ == "__main__":
    measure("/Applications/PixivShaft.app", "/Applications/PixivShaft.app/Contents/MacOS/PixivShaft", "Compose 现状")
