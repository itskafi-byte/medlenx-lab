"""
MedLenX VL - Pure Vision + Enhanced NER for Bangladeshi prescriptions
Implements new design.pdf requirements:
- Extract Doctor Name, BMDC Registration No, Medicine Trade Name, Generic Name, Dosage/Frequency, Pharma Company
- Handle Bengali/English mixed: ১+০+১, ১ চামচ
- PWA-ready with offline support
"""

import base64
import json
import os
import re
import requests
import mimetypes
from typing import List, Dict
from .config import settings

class MedLenXVLClient:
    def __init__(self, api_key=None):
        self.api_key = api_key or settings.OPENROUTER_API_KEY
        self.base_url = settings.OPENROUTER_BASE_URL
        self.primary_model = settings.MEDLENX_VL_MODEL_PRIMARY
        self.display_name = settings.MEDLENX_VL_DISPLAY_NAME
        if not self.api_key:
            print(f"⚠️  OPENROUTER_API_KEY not set - {self.display_name} demo mode")

    def _image_to_base64(self, image_path: str) -> str:
        if not os.path.exists(image_path):
            raise FileNotFoundError(f"Image not found: {image_path}")
        mime_type, _ = mimetypes.guess_type(image_path)
        if not mime_type:
            mime_type = "image/jpeg"
        with open(image_path, "rb") as f:
            b64 = base64.b64encode(f.read()).decode('utf-8')
        return f"data:{mime_type};base64,{b64}"

    def _build_messages(self, prompt_text: str, image_paths: List[str] = None, system_prompt: str = None):
        content = []
        if image_paths:
            for img_path in image_paths:
                try:
                    data_uri = self._image_to_base64(img_path)
                    content.append({"type": "image_url", "image_url": {"url": data_uri}})
                except Exception as e:
                    print(f"Failed image {img_path}: {e}")
        content.append({"type": "text", "text": prompt_text})
        messages = []
        if system_prompt:
            messages.append({"role": "system", "content": system_prompt})
        messages.append({"role": "user", "content": content})
        return messages

    def call_medlenx(self, prompt: str, image_paths: List[str] = None, system_prompt: str = None,
                     max_tokens=4000, temperature=0.2) -> Dict:
        if not self.api_key:
            return self._mock_response(prompt, image_paths)
        messages = self._build_messages(prompt, image_paths, system_prompt)
        payload = {"model": self.primary_model, "messages": messages, "max_tokens": max_tokens, "temperature": temperature}
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
            "HTTP-Referer": "https://medlenx-lab.local",
            "X-Title": "MedLenX Lab"
        }
        try:
            resp = requests.post(self.base_url, headers=headers, json=payload, timeout=120)
            resp.raise_for_status()
            data = resp.json()
            content = data["choices"][0]["message"]["content"]
            return {"success": True, "content": content, "model_used": self.display_name, "raw": data}
        except Exception as e:
            print(f"{self.display_name} failed: {e}")
            return {"success": False, "error": str(e), "content": ""}

    def _mock_response(self, prompt, image_paths):
        print(f"🔧 {self.display_name} Mock")
        lowered = prompt.lower()
        if "bangladeshi prescription" in lowered or "prescription image" in lowered:
            mock_content = json.dumps({
                "doctor": {
                    "name": "Dr. A. K. M. Rahman",
                    "bmdc_no": "A-12345",
                    "qualifications": "MBBS, FCPS (Medicine), MD (Cardiology)",
                    "hospital": "Dhaka Medical College Hospital",
                    "department": "Medicine & Cardiology",
                    "specialty": "Medicine",
                    "chamber": "Popular Diagnostic Center, Dhanmondi",
                    "district": "Dhaka",
                    "upazila": "Dhanmondi",
                    "territory": "Dhaka South"
                },
                "medicines": [
                    {
                        "brand_name": "Seclo",
                        "generic_name": "Omeprazole",
                        "raw_text": "Cap. Seclo 20mg - ১+০+১ - 1 month before meal",
                        "form": "Cap",
                        "type": "Capsule",
                        "strength": "20mg",
                        "dosage": "1+0+1",
                        "dosage_bengali": "১+০+১",
                        "dosage_normalized": "1+0+1",
                        "frequency": "Before meal",
                        "company": "Square Pharmaceuticals Ltd.",
                        "confidence": 0.92
                    },
                    {
                        "brand_name": "Napa",
                        "generic_name": "Paracetamol",
                        "raw_text": "Tab. Napa 500mg - ১+১+১ - 5 days",
                        "form": "Tab",
                        "type": "Tablet",
                        "strength": "500mg",
                        "dosage": "1+1+1",
                        "dosage_bengali": "১+১+১",
                        "dosage_normalized": "1+1+1",
                        "frequency": "After meal",
                        "company": "Beximco Pharmaceuticals Ltd.",
                        "confidence": 0.94
                    },
                    {
                        "brand_name": "Histacin",
                        "generic_name": "Chlorpheniramine",
                        "raw_text": "Tab. Histacin 4mg - ০+০+১",
                        "form": "Tab",
                        "type": "Tablet",
                        "strength": "4mg",
                        "dosage": "0+0+1",
                        "dosage_bengali": "০+০+১",
                        "dosage_normalized": "0+0+1",
                        "frequency": "At night",
                        "company": "Square Pharmaceuticals Ltd.",
                        "confidence": 0.89
                    },
                    {
                        "brand_name": "Zithrin",
                        "generic_name": "Azithromycin",
                        "raw_text": "Cap. Zithrin 500mg - 1 chamoch",
                        "form": "Cap",
                        "type": "Capsule",
                        "strength": "500mg",
                        "dosage": "0+0+1",
                        "dosage_bengali": "১ চামচ",
                        "dosage_normalized": "1 spoon",
                        "frequency": "After meal",
                        "company": "Healthcare Pharmaceuticals Ltd.",
                        "confidence": 0.88
                    }
                ],
                "patient_info": {
                    "note": "Masked per BMDC compliance - patient name, age, phone removed",
                    "masked": True
                },
                "meta": {
                    "total_medicines": 4,
                    "legibility": 0.78,
                    "language_mix": "English brand + Bengali dosage ১+০+১"
                }
            })
            return {"success": True, "content": mock_content, "model_used": f"{self.display_name} Mock"}
        
        mock_content = json.dumps({
            "doctor": {"name": "Dr. Mock", "bmdc_no": "A-00000", "qualifications": "MBBS"},
            "medicines": [{"brand_name": "Napa", "raw_text": "Napa 500mg", "form": "Tablet", "type": "Tablet", "strength": "500mg", "confidence": 0.85}]
        })
        return {"success": True, "content": mock_content, "model_used": f"{self.display_name} Mock"}

    def scan_prescription_pure(self, image_path: str) -> Dict:
        """
        Implements new design.pdf extraction:
        Doctor Name, BMDC Registration No, Medicine Trade Name, Generic Name, Dosage/Frequency, Pharma Company
        Handles Bengali ১+০+১, ১ চামচ
        """
        system_prompt = """You are MedLenX VL, expert Bangladeshi prescription reader for MR field work.
You handle Bengali/English mixed prescriptions, extract BMDC numbers, and mask patient PII per compliance.
Output valid JSON only."""

        prompt = """
Analyze this Bangladeshi prescription image - mobile capture may have rotation/contrast issues.

Extract per new design spec:

1. DOCTOR BLOCK (top, printed):
   - name, qualifications, hospital, department, specialty (Cardiology, Medicine, Orthopedics etc)
   - bmdc_no / BMDC Registration No (e.g., A-12345, format letter + numbers)
   - chamber (clinic name), district, upazila, territory

2. MEDICINES (Rx onwards, handwritten cursive, English brand + Bengali dosage):
   - brand_name: Trade name exact (e.g., Napa, Seclo, Sergel, Ace)
   - generic_name: Chemical generic (e.g., Paracetamol, Omeprazole)
   - raw_text: Full line as seen (include Bengali if present like ১+০+১)
   - form: Tab/Cap/Syr/Inj etc
   - type: Tablet/Capsule/Syrup/Injection/Cream/Drop/Inhaler/Suppository/Powder etc
   - strength: 20mg, 500mg, 10ml
   - dosage: Extract dosage pattern - can be English 1+0+1 or Bengali ১+০+১ or ১ চামচ করে - keep original in dosage_bengali, then normalize to English in dosage_normalized (০-৯ → 0-9)
   - dosage_bengali: Original Bengali if present
   - dosage_normalized: Converted to English 1+0+1
   - frequency: Before meal, After meal, At night etc
   - company: ONLY if the manufacturer is literally printed/written on the
     prescription next to that medicine. DO NOT guess or infer the company from
     the brand name - the server resolves the manufacturer from the official
     MedEx catalogue. If it is not written on the paper, return "" (empty string).
   - confidence: 0-1

3. Patient PII: DO NOT extract patient name, age, phone - mask per BMDC compliance, note "masked"

4. Bengali handling: ১=1, ২=2, ৩=3, ৪=4, ৫=5, ৬=6, ৭=7, ৮=8, ৯=9, ০=0, চামচ=spoon

Return ONLY JSON:
{
  "doctor": {
    "name": "Dr. A. K. M. Rahman",
    "bmdc_no": "A-12345",
    "qualifications": "MBBS, FCPS...",
    "hospital": "Dhaka Medical College Hospital",
    "department": "Medicine",
    "specialty": "Medicine",
    "chamber": "Popular Diagnostic, Dhanmondi",
    "district": "Dhaka",
    "upazila": "Dhanmondi",
    "territory": "Dhaka South"
  },
  "medicines": [
    {
      "brand_name": "Seclo",
      "generic_name": "Omeprazole",
      "raw_text": "Cap. Seclo 20mg - ১+০+১ - 1 month",
      "form": "Cap",
      "type": "Capsule",
      "strength": "20mg",
      "dosage": "1+0+1",
      "dosage_bengali": "১+০+১",
      "dosage_normalized": "1+0+1",
      "frequency": "Before meal",
      "company": "Square Pharmaceuticals Ltd.",
      "confidence": 0.92
    }
  ],
  "patient_info": {"masked": true, "note": "Patient PII masked per BMDC compliance"},
  "meta": {"total_medicines": 1, "legibility": 0.8, "language_mix": "English+Bengali ১+০+১"}
}

IMPORTANT: never invent a pharmaceutical company. Leave "company" empty unless it
is actually printed on the prescription. An empty company is correct and useful;
a guessed company is a data error.

Output JSON only, no markdown.
"""

        return self.call_medlenx(prompt=prompt, image_paths=[image_path], system_prompt=system_prompt, max_tokens=4000, temperature=0.1)

    def normalize_bengali_dosage(self, text):
        """Convert Bengali numerals to English: ১+₀+₁ → 1+0+1"""
        if not text:
            return ""
        bn_to_en = str.maketrans("০১২৩৪৫৬৭৮৯", "0123456789")
        # Also handle "চামচ" -> "spoon"
        normalized = text.translate(bn_to_en)
        normalized = normalized.replace("চামচ", "spoon").replace("চা", "tea")
        return normalized.strip()
