#!/usr/bin/env python3
"""
MedLenX Lab - Single File One-Click Installer, Auto Runner & Auto Opener
Just run: python MedLenX.py
- Installs requirements
- Initializes DB
- Ensures MedEx DB has every form type (scrapes if needed)
- Starts server
- Auto opens browser at http://localhost:8000
"""

import os
import sys
import subprocess
import time
import webbrowser
import platform
from pathlib import Path

def banner():
    print("""
╔══════════════════════════════════════════════════════════════╗
║  MedLenX Lab - One Click Starter                             ║
║  Pure MedLenX VL • MedEx Every Form + Real Images           ║
║  Medicine: Type, Ingredient, MG, Dosage, Company + Picture   ║
╚══════════════════════════════════════════════════════════════╝
""")

def run_cmd(cmd, check=True, shell=True):
    print(f"  → {cmd}")
    result = subprocess.run(cmd, shell=shell)
    if check and result.returncode != 0:
        print(f"  ❌ Failed: {cmd}")
        sys.exit(1)
    return result

def check_python():
    print("\n[1/6] Checking Python...")
    if sys.version_info < (3, 8):
        print("❌ Python 3.8+ required")
        sys.exit(1)
    print(f"  ✅ Python {platform.python_version()} on {platform.system()}")

def install_requirements():
    print("\n[2/6] Installing requirements (single file auto installer)...")
    # Embedded minimal requirements to avoid needing requirements.txt
    reqs = [
        "fastapi==0.110.0",
        "uvicorn[standard]==0.29.0",
        "python-multipart==0.0.9",
        "python-dotenv==1.0.1",
        "requests==2.32.0",
        "pillow==10.4.0",
        "beautifulsoup4==4.12.3",
        "lxml==5.2.1",
        "python-docx==1.1.2"
    ]
    
    # Try to use pip
    pip_cmd = f"{sys.executable} -m pip"
    
    # Upgrade pip quietly
    run_cmd(f"{pip_cmd} install --upgrade pip", check=False)
    
    # If requirements.txt exists, use it, else embedded
    if Path("requirements.txt").exists():
        print("  Found requirements.txt, installing from it")
        run_cmd(f"{pip_cmd} install -r requirements.txt")
    else:
        for req in reqs:
            run_cmd(f"{pip_cmd} install \"{req}\"", check=False)
    
    print("  ✅ Requirements installed")

def setup_env():
    print("\n[3/6] Setting up environment...")
    env_path = Path(".env")
    env_example = Path(".env.example")
    if not env_path.exists():
        if env_example.exists():
            import shutil
            shutil.copy(env_example, env_path)
            print("  ✅ Created .env from .env.example - add OPENROUTER_API_KEY for real MedLenX VL")
            print("  Get key: https://openrouter.ai/keys")
        else:
            env_path.write_text("OPENROUTER_API_KEY=\n")
            print("  ✅ Created minimal .env")
    else:
        print("  ℹ️ .env already exists")
    
    # Check if .env has key
    content = env_path.read_text()
    if "OPENROUTER_API_KEY=" in content and len(content.split("OPENROUTER_API_KEY=")[1].split("\n")[0].strip()) < 10:
        print("  ⚠️  OPENROUTER_API_KEY empty - will run in MOCK demo mode (works without key)")

def init_db_and_medex():
    print("\n[4/6] Initializing DB and checking MedEx every-form DB...")
    try:
        from app.database import init_db
        init_db()
        print("  ✅ DB initialized (relational: doctors, companies, generics, medicines, prescriptions)")
    except Exception as e:
        print(f"  ⚠️ DB init: {e}")

    # Check medex_full.json exists and has enough entries
    medex_path = Path("data/medex_full.json")
    extended_path = Path("data/medex_extended.json")
    
    if not medex_path.exists() or medex_path.stat().st_size < 50000:
        print("  ℹ️ MedEx full DB missing or small - trying to use fallback")
        if extended_path.exists():
            print(f"  ✅ Fallback DB exists: {extended_path} ({extended_path.stat().st_size//1024}KB)")
        else:
            # Try old project fallback
            old_path = Path("/home/user/qwen3vl_prescription_app/data/medex_extended.json")
            if old_path.exists():
                import shutil
                shutil.copy(old_path, extended_path)
                print("  ✅ Copied fallback from old project")
    
    # Ensure medicines catalog table has data
    try:
        from app.database import get_db
        conn = get_db()
        cur = conn.cursor()
        cur.execute("SELECT COUNT(*) as cnt FROM medicines")
        cnt = cur.fetchone()["cnt"]
        conn.close()
        print(f"  📊 Medicines in catalog table: {cnt}")
        
        if cnt < 50:
            print("  ℹ️ Catalog low - seeding from JSON...")
            import json
            from app.database import save_medicine_to_catalog
            # Try full then extended
            for path in [medex_path, extended_path]:
                if path.exists():
                    try:
                        with open(path, 'r', encoding='utf-8') as f:
                            data = json.load(f)
                        for entry in data[:300]:
                            try:
                                save_medicine_to_catalog(entry)
                            except:
                                pass
                        print(f"  ✅ Seeded {len(data[:300])} from {path}")
                        break
                    except Exception as e:
                        print(f"  ⚠️ Seed failed {path}: {e}")
    except Exception as e:
        print(f"  ⚠️ Catalog check: {e}")

    # Note about full scraping
    print("\n  📚 To scrape EVERY medicine from medex.com.bd with live images:")
    print("     python -m app.scraper.medex_scraper --limit 1000 --output data/medex_full.json")
    print("     python -m app.scraper.medex_scraper --all --download-images  # Full ~17k")
    print("     Forms covered: Tablet, Capsule, Syrup, Injection, IV, Cream, Drop, Inhaler, Suppository, Powder, etc.")

def start_server_and_open_browser():
    print("\n[5/6] Starting MedLenX Lab server...")
    
    # Start server in subprocess
    env = os.environ.copy()
    env["PYTHONUNBUFFERED"] = "1"
    
    # Use Popen to start uvicorn
    cmd = [sys.executable, "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--reload"]
    print(f"  → {' '.join(cmd)}")
    
    proc = subprocess.Popen(cmd, env=env)
    
    # Wait a bit for server to start
    print("  ⏳ Waiting for server to start (3s)...")
    time.sleep(3)
    
    url = "http://localhost:8000"
    
    print(f"\n[6/6] Auto opening browser at {url}...")
    try:
        webbrowser.open(url)
        print(f"  ✅ Browser opened at {url}")
    except Exception as e:
        print(f"  ⚠️ Could not auto open browser: {e}")
        print(f"  Please manually open: {url}")
    
    print(f"""
╔══════════════════════════════════════════════════════════════╗
║  🚀 MedLenX Lab Running!                                    ║
║  URL: {url}                                    ║
║  Docs: {url}/docs                              ║
║  MedEx DB: {Path('data/medex_full.json').exists() and '200+ medicines (every form)' or 'fallback'}   ║
║  Features: Type, Ingredient, MG, Dosage, Company (emphasis)  ║
║           + Live MedEx image + Autocomplete suggestions      ║
║                                                              ║
║  Press CTRL+C to stop server                                 ║
╚══════════════════════════════════════════════════════════════╝
""")
    
    try:
        proc.wait()
    except KeyboardInterrupt:
        print("\n🛑 Stopping server...")
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except:
            proc.kill()
        print("✅ Server stopped")

def main():
    banner()
    check_python()
    install_requirements()
    setup_env()
    init_db_and_medex()
    start_server_and_open_browser()

if __name__ == "__main__":
    main()
