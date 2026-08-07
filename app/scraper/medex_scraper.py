"""
MedLens MedEx Scraper - Scrapes every medicine from medex.com.bd
Including: Tablet, Capsule, Syrup, Injection, IV, Cream, Drop, Inhaler, Suppository, etc.
Also scrapes pack images.

Usage:
  python -m app.scraper.medex_scraper --limit 1000 --output data/medex_full.json
  python -m app.scraper.medex_scraper --all --download-images
"""

import requests
from bs4 import BeautifulSoup
import re
import json
import os
import time
import argparse
from urllib.parse import urljoin
from pathlib import Path

BASE_URL = "https://medex.com.bd"
BRANDS_URL = f"{BASE_URL}/brands"
HEADERS = {"User-Agent": "Mozilla/5.0 (MedLenX Lab - Educational Research) AppleWebKit/537.36"}

# Dosage form mapping from icon filename to readable type
FORM_MAP = {
    "tablet.png": "Tablet",
    "capsule.png": "Capsule",
    "syrup-2.png": "Syrup",
    "syrup.png": "Syrup",
    "iv-infusion.png": "Injection",
    "injection.png": "Injection",
    "cream.png": "Cream",
    "drop.png": "Drop",
    "inhaler.png": "Inhaler",
    "suppository.png": "Suppository",
    "powder.png": "Powder",
    "suspension.png": "Suspension",
    "ointment.png": "Ointment",
    "gel.png": "Gel",
    "lotion.png": "Lotion",
    "sachet.png": "Sachet",
    "vial.png": "Vial",
    "ampoule.png": "Ampoule",
    "spray.png": "Spray",
    "nebuliser.png": "Nebuliser",
    "eye-drop.png": "Eye Drop",
    "nasal.png": "Nasal Drop",
    "ear-drop.png": "Ear Drop",
}

def get_soup(url, retries=3):
    for attempt in range(retries):
        try:
            r = requests.get(url, headers=HEADERS, timeout=15)
            if r.status_code == 200:
                return BeautifulSoup(r.text, 'lxml')
            else:
                print(f"  ⚠️ Status {r.status_code} for {url}")
                time.sleep(1)
        except Exception as e:
            print(f"  ❌ Error fetching {url}: {e} (attempt {attempt+1})")
            time.sleep(2)
    return None

def parse_brand_list_page(alpha, page=1):
    """Parse brands list for given alpha and page"""
    url = f"{BRANDS_URL}?alpha={alpha}&page={page}"
    if page == 1:
        url = f"{BRANDS_URL}?alpha={alpha}"
    soup = get_soup(url)
    if not soup:
        return [], False
    
    brand_links = []
    # Find all brand detail links
    for a in soup.select('a[href]'):
        href = a.get('href','')
        if re.match(r'.*/brands/\d+/.*', href):
            full = href if href.startswith('http') else urljoin(BASE_URL, href)
            # Avoid duplicates and generic/company links
            if '/brands/' in full and '/companies/' not in full and '/generics/' not in full:
                brand_links.append(full)
    
    # Deduplicate preserving order
    brand_links = list(dict.fromkeys(brand_links))
    
    # Check if has next page - look for pagination
    has_next = False
    pagination = soup.select('.pagination a')
    for p in pagination:
        if f"page={page+1}" in p.get('href',''):
            has_next = True
            break
    # Also check if we got 30 items (typical per page) then assume more
    if len(brand_links) >= 25:
        has_next = True
    
    return brand_links, has_next

def parse_brand_detail(url):
    """Parse single brand detail page"""
    soup = get_soup(url)
    if not soup:
        return None
    
    text = soup.decode() if isinstance(soup, str) else str(soup)
    # Use regex for packaging images (more reliable)
    pack_images = re.findall(r'https://medex\.com\.bd/storage/images/packaging/[^\s"\'\\>]+', text)
    pack_image = pack_images[0] if pack_images else ""
    # Clean url (remove duplicates, keep first)
    if pack_image:
        # Remove extra quote artifacts
        pack_image = pack_image.split('"')[0].split("'")[0].split(">")[0]
        # Ensure webp/jpg/png
        if not pack_image.lower().endswith(('.webp','.jpg','.jpeg','.png')):
            # try to extract up to extension
            m = re.search(r'(https://medex\.com\.bd/storage/images/packaging/.*?\.(?:webp|jpg|jpeg|png))', pack_image)
            if m:
                pack_image = m.group(1)
    
    # Title parsing: e.g. "3 Bion | 100 mg+200 mg+200 mcg | Tablet | ... | Jenphar..."
    title_tag = soup.title.text if soup.title else ""
    title_parts = [p.strip() for p in title_tag.split('|')]
    
    brand_name = title_parts[0] if len(title_parts) > 0 else ""
    strength = title_parts[1] if len(title_parts) > 1 else ""
    dosage_form_raw = title_parts[2] if len(title_parts) > 2 else ""
    company = title_parts[4] if len(title_parts) > 4 else ""
    if not company and len(title_parts) > 3:
        company = title_parts[3]  # fallback
    
    # Generic - from first generic link
    generic = ""
    generic_links = soup.select('a[href*="/generics/"]')
    for gl in generic_links:
        href = gl.get('href','')
        # Skip brand-names subpage
        if '/brand-names' not in href and '/generics/' in href:
            generic = gl.text.strip()
            break
    
    # Company - more reliable from company link
    if not company:
        company_links = soup.select('a[href*="/companies/"]')
        for cl in company_links:
            if '/companies/' in cl.get('href','') and '/brands' not in cl.get('href',''):
                company = cl.text.strip()
                break
    
    # Dosage form from icon
    form_type = dosage_form_raw
    # Try to infer from img
    form_imgs = soup.select('img[src*="dosage-forms"]')
    if form_imgs:
        src = form_imgs[0].get('src','')
        fname = src.split('/')[-1]
        form_type = FORM_MAP.get(fname, dosage_form_raw or fname.replace('.png','').title())
    
    # Strength value parsing
    strength_value = 0
    strength_unit = ""
    m_strength = re.search(r'(\d+(?:\.\d+)?)\s*(mg|ml|gm|mcg|%)', strength, re.I)
    if m_strength:
        try:
            strength_value = float(m_strength.group(1))
            strength_unit = m_strength.group(2)
        except:
            pass

    # Composition / Ingredient - try to find Composition section
    composition = ""
    # Look for h3 Composition then next p
    try:
        comp_header = soup.find(lambda tag: tag.name in ['h3','h2'] and 'Composition' in tag.text)
        if comp_header:
            next_p = comp_header.find_next_sibling('p')
            if next_p:
                composition = next_p.text[:500]
            else:
                # find next div
                next_div = comp_header.find_next('div')
                if next_div:
                    composition = next_div.text[:500]
    except:
        pass

    # Brand ID from URL
    brand_id_match = re.search(r'/brands/(\d+)/', url)
    brand_id = brand_id_match.group(1) if brand_id_match else ""

    # Type mapping for UI icons
    type_mapping = {
        "Tablet": "Tablet",
        "Capsule": "Capsule",
        "Syrup": "Syrup",
        "Suspension": "Syrup",
        "Injection": "Injection",
        "IV": "Injection",
        "Cream": "Cream",
        "Drop": "Drop",
        "Eye Drop": "Eye Drop",
        "Inhaler": "Inhaler",
        "Suppository": "Suppository",
        "Powder": "Powder",
        "Ointment": "Ointment",
        "Gel": "Gel",
    }
    mapped_type = type_mapping.get(form_type, form_type or "Tablet")

    return {
        "id": brand_id or url,
        "brand_name": brand_name,
        "generic": generic,
        "strength": strength,
        "strength_value": strength_value,
        "strength_unit": strength_unit,
        "form": mapped_type[:20],  # short form like Tab, Cap
        "type": mapped_type,
        "dosage_form": form_type,
        "company": company,
        "ingredient": composition or f"{generic} {strength}".strip(),
        "category": generic.split()[0] if generic else "General",
        "image_url": pack_image or f"https://placehold.co/300x300/0ea5e9/white?text={brand_name.replace(' ','+')}",
        "pack_image": pack_image,
        "url": url,
        "scraped_at": time.strftime("%Y-%m-%d %H:%M:%S")
    }

def scrape_medex(limit=1000, alpha_range=None, output_path="data/medex_full.json", download_images=False, images_dir="static/images/medicines"):
    """
    Main scraper - scrapes every medicine
    limit: max number of brands to scrape (0 = all)
    alpha_range: list of letters to scrape, e.g. ['a','b','c'] or None for all a-z
    """
    if alpha_range is None:
        alpha_range = [chr(ord('a')+i) for i in range(26)]  # a-z
    
    all_brands = []
    seen_urls = set()
    scraped_count = 0

    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    if download_images:
        os.makedirs(images_dir, exist_ok=True)

    for alpha in alpha_range:
        print(f"\n🔤 Scraping alpha='{alpha}'...")
        page = 1
        while True:
            if limit and scraped_count >= limit:
                break
            
            brand_links, has_next = parse_brand_list_page(alpha, page)
            if not brand_links:
                print(f"  No brands found for alpha={alpha} page={page}, stopping")
                break
            
            print(f"  Page {page}: {len(brand_links)} brands found")
            
            for burl in brand_links:
                if limit and scraped_count >= limit:
                    break
                if burl in seen_urls:
                    continue
                seen_urls.add(burl)
                
                print(f"    [{scraped_count+1}] Scraping {burl.split('/')[-1]}...", end=" ")
                detail = parse_brand_detail(burl)
                if detail:
                    # Filter to ensure has brand name
                    if detail["brand_name"] and detail["brand_name"].lower() != "medex":
                        all_brands.append(detail)
                        scraped_count += 1
                        print(f"✅ {detail['brand_name']} | {detail['type']} | {detail['strength']} | {detail['company'][:20]} | Img: {'YES' if detail['pack_image'] else 'NO'}")
                        
                        # Download image if requested
                        if download_images and detail["pack_image"]:
                            try:
                                img_name = f"{detail['id']}_{re.sub(r'[^a-zA-Z0-9]', '_', detail['brand_name'])[:30]}.webp"
                                img_path = os.path.join(images_dir, img_name)
                                if not os.path.exists(img_path):
                                    r = requests.get(detail["pack_image"], headers=HEADERS, timeout=15)
                                    if r.status_code == 200:
                                        with open(img_path, 'wb') as f:
                                            f.write(r.content)
                                        detail["local_image"] = f"/static/images/medicines/{img_name}"
                            except Exception as e:
                                print(f"  Image download failed: {e}")
                        
                        # Save incrementally every 50
                        if scraped_count % 50 == 0:
                            with open(output_path, 'w', encoding='utf-8') as f:
                                json.dump(all_brands, f, indent=2, ensure_ascii=False)
                            print(f"  💾 Saved {scraped_count} to {output_path}")
                    else:
                        print(f"❌ No brand name")
                else:
                    print(f"❌ Failed to parse")
                
                time.sleep(0.5)  # polite delay
            
            if not has_next:
                print(f"  No more pages for alpha={alpha}")
                break
            page += 1
            if page > 100:  # safety
                print(f"  Reached max pages for alpha={alpha}")
                break
            time.sleep(1)
        
        if limit and scraped_count >= limit:
            break

    # Final save
    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(all_brands, f, indent=2, ensure_ascii=False)
    
    print(f"\n✅ DONE: Scraped {len(all_brands)} medicines")
    print(f"📁 Saved to {output_path}")
    
    # Stats by form
    from collections import Counter
    form_counter = Counter([b["type"] for b in all_brands])
    print("\n📊 By Form:")
    for form, cnt in form_counter.most_common():
        print(f"  {form}: {cnt}")

    return all_brands

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="MedEx Full Scraper for MedLenX Lab")
    parser.add_argument("--limit", type=int, default=500, help="Max brands to scrape (0 for all ~17k)")
    parser.add_argument("--alpha", type=str, default="", help="Specific alpha like 'a' or 'a,b,c' (default all a-z)")
    parser.add_argument("--output", type=str, default="data/medex_full.json", help="Output JSON path")
    parser.add_argument("--download-images", action="store_true", help="Download pack images")
    parser.add_argument("--all", action="store_true", help="Scrape all ~17k (ignores limit)")
    args = parser.parse_args()

    limit = 0 if args.all else args.limit
    alpha_range = None
    if args.alpha:
        alpha_range = [a.strip().lower() for a in args.alpha.split(',') if a.strip()]

    scrape_medex(limit=limit, alpha_range=alpha_range, output_path=args.output, download_images=args.download_images)
