import subprocess, pathlib, shutil, time, os
proj = pathlib.Path(r"C:\Users\PLP23-00167\Desktop\Capstone App\git\app\TindaGo_APP")
print(f"PROJ={proj}", flush=True)
# stop daemons
print("STOP DAEMONS", flush=True)
try:
    r = subprocess.run([str(proj/"gradlew.bat"), "--stop"], cwd=str(proj), capture_output=True, text=True, timeout=60)
    print(r.stdout[:2000], flush=True)
except Exception as e:
    print(f"stop err {e}", flush=True)
# clean
for p in [proj/"build", proj/"app/build", proj/".gradle"]:
    if p.exists():
        print(f"rm {p}", flush=True)
        shutil.rmtree(p, ignore_errors=True)
for f in proj.glob("_*.log"):
    try: f.unlink()
    except: pass
for f in proj.glob("_*.py"):
    if f.name == "_verify_final.py": continue
    try: f.unlink()
    except: pass
# compile
log = proj/"_verify_final.log"
cmd = [str(proj/"gradlew.bat"), ":app:compileDebugKotlin", "--rerun-tasks"]
print(f"RUN {' '.join(cmd)}", flush=True)
try:
    r = subprocess.run(cmd, cwd=str(proj), capture_output=True, text=True, timeout=500)
    txt = (r.stdout or "") + "\n====STDERR====\n" + (r.stderr or "") + f"\nEXIT={r.returncode}\n"
    log.write_text(txt, encoding="utf-8", errors="ignore")
    print(f"EXIT={r.returncode} LOG={log} BYTES={len(txt)}", flush=True)
    for line in txt.splitlines():
        if "e:" in line or "FAILED" in line or "SUCCESSFUL" in line or "Unresolved" in line or "Returns are prohibited" in line or "Compilation error" in line:
            print(line[:900], flush=True)
    # also try help to confirm jlink fixed
    print("=== HELP CHECK ===", flush=True)
    r2 = subprocess.run([str(proj/"gradlew.bat"), "help"], cwd=str(proj), capture_output=True, text=True, timeout=120)
    print(r2.stdout[-1500:], flush=True)
    print(f"HELP EXIT={r2.returncode}", flush=True)
except Exception as ex:
    import traceback
    print(f"EX {ex}", flush=True)
    traceback.print_exc()
    log.write_text(f"EX {ex}\n{traceback.format_exc()}", encoding="utf-8", errors="ignore")
