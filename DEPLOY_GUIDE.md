# MedLenX Lab - GitHub + Render.com Deployment Guide for Supervisor

This guide shows step-by-step how to push current project to GitHub and host on Render.com so supervisor can test.

## Prerequisites

- Git installed: https://git-scm.com/downloads
- GitHub account: https://github.com
- Render.com account: https://render.com (sign up with GitHub for easy connect)
- Python 3.8+ locally (for testing)

---

## Part 1: Push to GitHub

### Step 1: Prepare project (already done, .gitignore created)

Current clean project has only essential files:
- `app/` - FastAPI + MedLenX VL + scraper
- `data/medex_full.json` - 25,105 medicines (16MB) with 100% medex.com.bd images
- `templates/index.html` - Premium UI with split-screen, dark mode, company logos, popular medicines live
- `static/` - Icons, company logos (122 logos), PWA sw.js
- `MedLenX.py` - Single file one-click installer + auto browser opener
- `requirements.txt`, `run.py`, `.env.example`, `render.yaml`

`.gitignore` already excludes `venv/`, `__pycache__/`, `.env`, `data/medlenx.db`, user uploads.

### Step 2: Initialize Git in project folder

Open terminal / command prompt in `medlenx_lab` folder:

```bash
cd /path/to/medlenx_lab

# Initialize git
git init

# Add all files
git add .

# First commit
git commit -m "MedLenX Lab - Full 25k MedEx DB, PWA, Analytics Dashboard, Company Logos, Dark Mode, Premium Enterprise UI"
```

### Step 3: Create GitHub Repository

1. Go to https://github.com/new
2. Repository name: `medlenx-lab`
3. Description: `MedLenX Lab - Bangladeshi Prescription Intelligence - MedEx 25k DB - PWA - Analytics Dashboard`
4. Choose **Public** (for free Render) or Private
5. **DO NOT** initialize with README, .gitignore, license (we already have)
6. Click **Create repository**

### Step 4: Link local repo to GitHub and push

GitHub will show commands after creating repo. Use:

```bash
# Replace USERNAME with your GitHub username
git remote add origin https://github.com/USERNAME/medlenx-lab.git

# Rename branch to main
git branch -M main

# Push
git push -u origin main
```

If asked for credentials, use Personal Access Token (GitHub > Settings > Developer settings > Personal access tokens > Tokens classic > Generate, with repo scope).

**Alternative with GitHub CLI (easier):**
```bash
# Install gh CLI: https://cli.github.com/
gh auth login
gh repo create medlenx-lab --public --source=. --remote=origin --push
```

After push, verify at `https://github.com/USERNAME/medlenx-lab`

---

## Part 2: Host on Render.com

### Step 1: Create Render Account

1. Go to https://render.com
2. Click **Get Started** → **Sign up with GitHub** (important for auto-connect)
3. Authorize Render to access your repositories

### Step 2: Create Web Service

1. In Render Dashboard, click **New +** → **Web Service**
2. You will see list of your GitHub repos. Find `medlenx-lab` and click **Connect**
   - If not visible, click **Configure account** and give Render access to repo
3. Configure service:

| Field | Value |
|-------|-------|
| **Name** | `medlenx-lab` |
| **Region** | `Singapore` (closest to BD, lower latency) |
| **Branch** | `main` |
| **Root Directory** | *(leave empty)* |
| **Runtime** | `Python 3` |
| **Build Command** | `pip install -r requirements.txt` |
| **Start Command** | `uvicorn app.main:app --host 0.0.0.0 --port $PORT` |
| **Plan** | `Free` |

4. Click **Advanced** → **Add Environment Variable**:
   - Key: `PYTHON_VERSION` Value: `3.11.0`
   - Key: `OPENROUTER_API_KEY` Value: `sk-or-v1-xxxx` (your key from https://openrouter.ai/keys) → **Optional**: Leave empty to run in MOCK demo mode (works without key, realistic fake responses)

5. Click **Create Web Service**

### Step 3: Wait for Deploy

- Render will clone repo, run `pip install -r requirements.txt`, then start with `uvicorn...`
- First deploy takes 2-4 minutes (16MB JSON + dependencies)
- Watch logs in Render dashboard - should see:
  ```
  ✅ Loaded MedEx DB: 25105 from data/medex_full.json
  ✅ MedLenX Relational DB initialized
  Uvicorn running on http://0.0.0.0:10000
  ```

### Step 4: Get Public URL

Once **Live** (green), Render gives URL like:
```
https://medlenx-lab.onrender.com
```

Click it → Your MedLenX Lab is live! Test:
- Upload sample prescription `uploads/prescriptions/sample_bd_prescription.jpg`
- Check Dashboard KPIs, Bar Chart drawer, Donut cross-filter, Doctor modal, Recent prescriptions modal
- Dark/Light toggle top of left sidebar
- Medicine Database → Popular from Top Pharma live
- Global search top bar

**Share this URL with supervisor:** `https://medlenx-lab.onrender.com`

### Step 5: Notes for Supervisor Demo

- **Free Tier:** Render free plan spins down after 15 min inactivity → first request after sleep takes ~30s cold start (normal). Show supervisor to wait.
- **Uploads:** Render filesystem is ephemeral - uploaded images will be lost on restart. For demo it's okay. For production, need Render Disk or S3.
- **Mock Mode:** If no `OPENROUTER_API_KEY`, app runs in mock mode with realistic fake MedLenX VL responses (still shows all features, images, company logos). For real VL, add key in Render env vars and redeploy.
- **Custom Domain (Optional):** In Render dashboard → Settings → Custom Domain → add your own domain

### Step 6: Future Updates (After Supervisor Feedback)

When you make changes locally:

```bash
git add .
git commit -m "Fix: dark mode toggle + company logos + popular medicines live"
git push origin main
```

Render auto-deploys on every push to `main` (if Auto-Deploy is On).

---

## Quick One-Liner for Local Test Before Pushing

```bash
# Test locally first
python run.py
# or one-click:
python MedLenX.py  # auto installs, runs, opens browser at http://localhost:8000
```

Then push to GitHub.

---

## Files for Deployment (Already in Repo)

- `requirements.txt` - All dependencies (fastapi, uvicorn, python-multipart, requests, pillow, bs4, etc.)
- `render.yaml` - Infrastructure as code for Render (optional, auto-detected)
- `.gitignore` - Excludes venv, __pycache__, .env, medlenx.db
- `app/main.py` - Includes fixes for /sw.js and /static/icons 404s
- `data/medex_full.json` - 25k medicines (16MB, okay for GitHub <100MB limit)

---

## Supervisor Checklist

Share with supervisor:

- **Live URL:** https://medlenx-lab.onrender.com
- **GitHub Repo:** https://github.com/USERNAME/medlenx-lab
- **Features:** PWA Camera, Split-screen Verification with zoom/drag, BMDC No, Bengali ১+০+১ handling, 25k MedEx DB every form, 100% images from medex.com.bd (pack + dosage icons), Company logos emphasis, Dark/Light theme, Popular medicines live from top pharma, Interactive dashboard (KPI filters, bar drawer, donut cross-filter, doctor modal, recent modal with zoom lens, CountUp, shimmer)
- **Docs:** README.md, DOCUMENTATION.md (in docs/ if you kept), DEPLOY_GUIDE.md

Good luck!
