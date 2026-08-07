import os
from dotenv import load_dotenv

load_dotenv()

class Settings:
    OPENROUTER_API_KEY = os.getenv("OPENROUTER_API_KEY", "")
    OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1/chat/completions"
    
    # MedLenX VL - Powered by state-of-the-art vision-language model
    # Using Qwen3-VL-235B behind the scenes but branded as MedLenX VL
    MEDLENX_VL_MODEL_PRIMARY = "qwen/qwen3-vl-235b-a22b-instruct"
    MEDLENX_VL_MODEL_FALLBACK = "qwen/qwen3-vl-30b-a3b-instruct"
    MEDLENX_VL_DISPLAY_NAME = "MedLenX VL"
    MEDLENX_VL_VERSION = "1.0-Pro"
    
    BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    DATA_DIR = os.path.join(BASE_DIR, "data")
    UPLOAD_DIR = os.path.join(BASE_DIR, "uploads", "prescriptions")
    STATIC_DIR = os.path.join(BASE_DIR, "static")
    IMAGES_DIR = os.path.join(STATIC_DIR, "images", "medicines")
    
    # MedEx DB
    MEDEX_DB_PATH = os.path.join(DATA_DIR, "medex_full.json")
    MEDEX_FALLBACK_PATH = os.path.join(DATA_DIR, "medex_extended.json")

settings = Settings()
os.makedirs(settings.UPLOAD_DIR, exist_ok=True)
os.makedirs(settings.DATA_DIR, exist_ok=True)
os.makedirs(settings.IMAGES_DIR, exist_ok=True)
