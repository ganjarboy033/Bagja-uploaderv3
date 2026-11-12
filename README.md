# kruzenlab-uploader ⚡
> Cloud Repository Manager & Atomic Batch Uploader for GitHub
> Developed by **DEV BAGJA / KruzenLab**

`kruzenlab-uploader` is a powerful, production-grade native Android application designed for streamlined cloud repository management, atomic Git tree commits, batch file/ZIP processing, and multi-account GitHub workflows.

---

## 🌟 Key Features

### 1. OAuth & Session Customization (With Built-in Fallback)
- **Custom OAuth Credentials**: Configure your own GitHub Client ID, Client Secret, and Session Password (`bagja_uploader_secret_session_key_32char_min`) directly in-app or via `.env`.
- **Automatic Fallback**: If fields are left empty, the application seamlessly falls back to pre-configured source defaults without errors.
- **Thin Highlight Indicators**: Styled with muted, light-weight typography (`font-light`, accent opacity) providing crystal-clear feedback on fallback states, technical indicators, and rate-limit quotas.

### 2. Universal Confirmation Dialogs & Real-Time Toast Notifications
- **Confirmation Modals**: Every critical or destructive action (delete file/folder, rename, overwrite, commit changes, create/delete repository, switch accounts) requires explicit confirmation.
- **Safe Repository Deletion**: Repo deletion requires re-typing the exact repository name to prevent accidental data loss.
- **Dynamic Real-Time Toasts**: Color-coded feedback (Green: Success, Red: Error, Yellow: Rate-Limit/Warning, Blue: In-Progress) for every cloud operation.

### 3. Complete Cloud File & Folder Management (CRUD via Git Data API)
- **Create Files & Folders**: Create new files or folder structures directly in the cloud (automatically injects `.gitkeep` for empty folders).
- **Recursive Rename**: Rename files or entire folder trees atomically via Git tree operations.
- **Delete Files & Folders**: Single-file deletion, multi-select deletion, and recursive folder deletion.
- **In-App Cloud Code Editor**: Inspect and edit code/text files directly on Android with fast syntax-aware editing and atomic commit on save.

### 4. Atomic Batch Uploader & ZIP Auto-Extractor
- **Single Atomic Tree Commit**: Follows the strict GitHub Git Data API sequence (`GET ref` → `POST blob` → `POST tree` → `POST commit` → `PATCH ref`) to push all files in a single clean commit.
- **In-App ZIP Extraction**: Unpacks archives locally, filters out noisy artifacts (`node_modules`, `.git`, `.next`, cache), and uploads preserving the relative directory structure.
- **Empty Repository Auto-Initializer**: Detects 0-commit repositories (which return 404 on branch refs) and initializes them seamlessly without 404 or 409 conflicts.

### 5. Advanced Repo & Workflow Management
- **Multi-Account Manager**: Store multiple GitHub accounts and PAT/OAuth tokens locally with instant 1-tap switching.
- **Live Rate Limit Monitor**: Real-time quota indicator (`X/5000 requests remaining`).
- **Branch Management**: Switch between branches or create new branches on the fly.
- **Releases Manager**: Create GitHub Releases with title, tag, release notes, and pre-release flags.
- **GitHub Actions Monitor**: View latest workflow runs, status badges, and trigger workflow re-runs.
- **Direct CDN / Raw Links**: One-tap copy for GitHub Raw URLs and jsDelivr CDN links.
- **AI README & Smart Commit Generator**: Generate comprehensive README structures and conventional commit messages.

---

## 🛠️ Architecture & Tech Stack

- **Platform**: Android 14+ (minSdk 26, targetSdk 34)
- **Language**: Kotlin 2.0
- **UI Framework**: Jetpack Compose (Material 3) with mobile-first responsive layout
- **Networking**: Retrofit 2 + OkHttp 4 (GitHub REST & Git Data API v3)
- **Local Persistence**: SQLite database (`AppDatabaseHelper`) for accounts, configs, commit history, and debug telemetry
- **Archive Processing**: Streaming Java Zip / `ZipExtractor`

---

## 📜 Attribution & Credits

Based on open-source architecture by AiraeXiyu (https://github.com/AiraeXiyu/XIYU-GITHUB-UPLOADER)

---
© 2026 KruzenLab · DEV BAGJA. All rights reserved.
