import uvicorn
import os
from dotenv import load_dotenv

load_dotenv()

if __name__ == "__main__":
    print("""
╔════════════════════════════════════════════════════════╗
║  MedLenX Lab - Pure MedLenX VL Prescription Scanner   ║
║  • Pure Vision Only - No Approaches                   ║
║  • MedEx Full DB Scraped - Every Form + Images        ║
╚════════════════════════════════════════════════════════╝

[*] Checking env...
""")
    api_key = os.getenv("OPENROUTER_API_KEY")
    if not api_key:
        print("⚠️  OPENROUTER_API_KEY not set - running in MOCK demo mode")
        print("   Set key in .env to use real MedLenX VL")
    else:
        print(f"✅ API key configured: {api_key[:20]}...")

    print("\n[*] Starting MedLenX Lab at http://localhost:8000")
    print("[*] Docs at http://localhost:8000/docs\n")

    uvicorn.run("app.main:app", host="0.0.0.0", port=8000, reload=True)
