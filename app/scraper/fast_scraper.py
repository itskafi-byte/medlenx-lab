import requests, re, json, time, os
from bs4 import BeautifulSoup
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

BASE_URL = "https://medex.com.bd"
HEADERS = {"User-Agent": "Mozilla/5.0 (MedLenX Lab Full Scraper)"}

def get_brand_links_for_alpha(alpha, max_pages=100):
    links = []
    for page in range(1, max_pages+1):
        try:
            url = f"{BASE_URL}/brands?alpha={alpha}&page={page}" if page>1 else f"{BASE_URL}/brands?alpha={alpha}"
            r = requests.get(url, headers=HEADERS, timeout=15)
            if r.status_code != 200:
                break
            # Find brand links via regex
            found = re.findall(r'/brands/\d+/[a-z0-9\-]+', r.text, re.I)
            if not found:
                break
            # Deduplicate and make full URLs
            for href in found:
                full = BASE_URL + href if href.startswith('/') else href
                if full not in links:
                    # Filter out non-brand (ensure it's brand detail, not company/generic)
                    if '/brands/' in full and '/companies/' not in full:
                        links.append(full)
            # Check if next page exists by looking for pagination
            if f'page={page+1}' not in r.text and len(found) < 20:
                break
        except Exception as e:
            print(f"Error alpha {alpha} page {page}: {e}")
            break
    return links

def parse_brand_detail(url):
    try:
        r = requests.get(url, headers=HEADERS, timeout=15)
        if r.status_code != 200:
            return None
        text = r.text
        # Pack image
        img_matches = re.findall(r'https://medex\.com\.bd/storage/images/packaging/[^\s"\'\\>]+', text)
        pack_image = img_matches[0].split('"')[0].split("'")[0] if img_matches else ""
        # Title parts
        m_title = re.search(r'<title>(.*?)</title>', text, re.I|re.S)
        title = m_title.group(1) if m_title else ""
        parts = [p.strip() for p in title.split('|')]
        brand_name = parts[0] if len(parts)>0 else ""
        strength = parts[1] if len(parts)>1 else ""
        form_raw = parts[2] if len(parts)>2 else ""
        company = parts[4] if len(parts)>4 else (parts[3] if len(parts)>3 else "")
        # Generic
        generic = ""
        mg = re.search(r'/generics/\d+/[^/]+[^>]*>([^<]+)</a>', text)
        if mg:
            generic = mg.group(1).strip()
        # Company more reliable
        mc = re.search(r'/companies/\d+/[^/]+/brands[^>]*>([^<]+)</a>', text)
        if mc:
            company = mc.group(1).strip()
        # Strength value
        strength_value = 0
        msv = re.search(r'(\d+(?:\.\d+)?)\s*(mg|ml|gm|mcg|%)', strength, re.I)
        if msv:
            try:
                strength_value = float(msv.group(1))
            except:
                pass
        brand_id = re.search(r'/brands/(\d+)/', url)
        bid = brand_id.group(1) if brand_id else ""
        return {
            "id": bid,
            "brand_name": brand_name,
            "generic": generic,
            "strength": strength,
            "strength_value": strength_value,
            "form": form_raw[:30],
            "type": form_raw[:30],
            "company": company,
            "ingredient": f"{generic} {strength}".strip(),
            "category": generic.split()[0] if generic else "General",
            "image_url": pack_image or f"https://placehold.co/300x300/0ea5e9/white?text={brand_name.replace(' ','+')}",
            "pack_image": pack_image,
            "url": url,
            "scraped_at": time.strftime("%Y-%m-%d %H:%M:%S")
        }
    except Exception as e:
        print(f"Detail error {url}: {e}")
        return None

def scrape_all_fast(output="data/medex_full.json", max_workers=15, alphas=None):
    if alphas is None:
        alphas = [chr(ord('a')+i) for i in range(26)]
    
    # Phase 1: Collect all brand URLs
    all_links = []
    print(f"Phase 1: Collecting brand URLs for {alphas}")
    for alpha in alphas:
        links = get_brand_links_for_alpha(alpha, max_pages=80)
        print(f"  Alpha {alpha}: {len(links)} links")
        all_links.extend(links)
        time.sleep(0.2)
    
    all_links = list(dict.fromkeys(all_links))
    print(f"\nTotal unique brand URLs: {len(all_links)} (expected ~17k)")

    # Phase 2: Fetch details concurrently
    results = []
    seen = set()
    # Load existing if exists to resume
    existing_path = Path(output)
    if existing_path.exists():
        try:
            with open(existing_path, 'r', encoding='utf-8') as f:
                existing = json.load(f)
                for e in existing:
                    key = (e.get('brand_name','').lower(), e.get('strength','').lower(), e.get('form',''))
                    if key not in seen and e.get('brand_name'):
                        seen.add(key)
                        results.append(e)
            print(f"Loaded existing {len(results)} from {output}")
        except Exception as e:
            print(f"Failed load existing: {e}")

    remaining = [url for url in all_links if url not in [r.get('url') for r in results]]
    print(f"Remaining to fetch: {len(remaining)}")

    def fetch_and_parse(url):
        return parse_brand_detail(url)

    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        future_to_url = {executor.submit(fetch_and_parse, url): url for url in remaining}
        count = 0
        for future in as_completed(future_to_url):
            count += 1
            data = future.result()
            if data and data.get('brand_name'):
                key = (data.get('brand_name','').lower(), data.get('strength','').lower(), data.get('form',''))
                if key not in seen:
                    seen.add(key)
                    results.append(data)
            if count % 100 == 0:
                print(f"  Fetched {count}/{len(remaining)} -> total {len(results)}")
                # Save incrementally
                with open(output, 'w', encoding='utf-8') as f:
                    json.dump(results, f, indent=2, ensure_ascii=False)

    # Final save
    with open(output, 'w', encoding='utf-8') as f:
        json.dump(results, f, indent=2, ensure_ascii=False)
    print(f"\n✅ DONE: {len(results)} medicines saved to {output}")
    return results

if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="data/medex_full.json")
    parser.add_argument("--workers", type=int, default=20)
    parser.add_argument("--alphas", default="")  # e.g., a,b,c
    args = parser.parse_args()
    alphas = [a.strip().lower() for a in args.alphas.split(',') if a.strip()] if args.alphas else None
    scrape_all_fast(output=args.output, max_workers=args.workers, alphas=alphas)
