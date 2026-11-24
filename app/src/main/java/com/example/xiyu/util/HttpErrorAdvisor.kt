package com.example.xiyu.util

import com.example.xiyu.data.database.NetworkLogRecord
import com.example.xiyu.data.model.HttpErrorAdvice

object HttpErrorAdvisor {

    fun explain(log: NetworkLogRecord): HttpErrorAdvice {
        return when (log.statusCode) {
            0 -> HttpErrorAdvice(
                statusCode = 0,
                title = "Koneksi Terputus / Timeout",
                meaning = "Permintaan jaringan gagal mencapai server GitHub.",
                cause = "Koneksi internet seluler/Wi-Fi tidak stabil, DNS bermasalah, atau timeout saat upload payload besar.",
                solution = "Periksa jaringan internet Anda lalu ulangi proses. Jika upload ribuan file, pastikan Rate Limit Pacer aktif di Setelan.",
                quickActionLabel = "Buka Setelan",
                quickActionRoute = "debug_panel"
            )
            in 200..299 -> HttpErrorAdvice(
                statusCode = log.statusCode,
                title = "Berhasil (HTTP ${log.statusCode})",
                meaning = "Server GitHub menerima dan memproses permintaan ${log.method} dengan sukses.",
                cause = "Tidak ada kesalahan. Respons diterima dalam ${log.durationMs} ms.",
                solution = "Tidak perlu tindakan lanjutan."
            )
            302, 304 -> HttpErrorAdvice(
                statusCode = log.statusCode,
                title = "Redirect / Cache (HTTP ${log.statusCode})",
                meaning = "Server mengarahkan ke URL penyimpanan eksternal atau menggunakan cache.",
                cause = "Wajar untuk unduhan artifact/zipball GitHub yang di-redirect ke CDN Azure/S3.",
                solution = "Aplikasi otomatis mengikuti redirect tanpa header Authorization agar CDN menerima unduhan."
            )
            401 -> HttpErrorAdvice(
                statusCode = 401,
                title = "Sesi / Token Ditolak (401 Unauthorized)",
                meaning = "GitHub menolak token otorisasi karena kedaluwarsa, dicabut, atau tidak valid.",
                cause = "Fine-grained PAT sering memiliki masa berlaku atau izin habis saat mem-push ribuan file, atau sesi OAuth kedaluwarsa.",
                solution = "Login ulang atau gunakan Classic Personal Access Token (No Expiration) dengan scope 'repo' & 'workflow'.",
                quickActionLabel = "Ke Halaman Login",
                quickActionRoute = "login"
            )
            403 -> HttpErrorAdvice(
                statusCode = 403,
                title = "Akses Dibatasi / Rate Limit (403 Forbidden)",
                meaning = "Anda tidak memiliki izin pada resource ini atau terkena GitHub Secondary Rate Limit.",
                cause = "Mengirim request terlalu cepat tanpa jeda saat upload massal, atau PAT kekurangan scope 'workflow'/'delete_repo'.",
                solution = "Aktifkan 'Rate Limit Pacer' di Setelan, tunggu 1-2 menit, lalu pastikan akun/token memiliki akses tulis ke repositori.",
                quickActionLabel = "Cek Setelan Pacer",
                quickActionRoute = "debug_panel"
            )
            404 -> HttpErrorAdvice(
                statusCode = 404,
                title = "Target Tidak Ditemukan (404 Not Found)",
                meaning = "Branch, path berkas, atau repositori privat tidak ditemukan oleh token saat ini.",
                cause = "Repositori masih kosong (belum ada branch), nama branch salah ketik, atau token kurang scope 'repo' untuk repo privat.",
                solution = "Pastikan nama repositori & branch target benar, atau pilih 'Create New Repository' di Uploader.",
                quickActionLabel = "Buka Uploader",
                quickActionRoute = "upload"
            )
            409 -> HttpErrorAdvice(
                statusCode = 409,
                title = "Konflik SHA / State Git (409 Conflict)",
                meaning = "Berkas atau ref branch di GitHub telah berubah oleh commit lain yang lebih baru.",
                cause = "SHA berkas lama tidak cocok dengan versi terbaru di remote, atau repo baru sedang diinisialisasi.",
                solution = "Muat ulang daftar file di Cloud File Manager (Refresh) agar SHA terbaru terambil sebelum commit.",
                quickActionLabel = "Buka Files",
                quickActionRoute = "repo_explorer"
            )
            422 -> HttpErrorAdvice(
                statusCode = 422,
                title = "Data Tidak Valid / Branch Bentrok (422 Unprocessable Entity)",
                meaning = "GitHub menolak payload karena referensi branch atau parameter tree tidak valid.",
                cause = "Mencoba membuat branch/tag yang sudah ada, SHA parent hilang, atau karakter path berkas tidak valid.",
                solution = "Refresh repositori di Files, periksa nama branch target, atau ganti nama file/tag.",
                quickActionLabel = "Buka Files",
                quickActionRoute = "repo_explorer"
            )
            429 -> HttpErrorAdvice(
                statusCode = 429,
                title = "Terlalu Banyak Permintaan (429 Too Many Requests)",
                meaning = "Batas request per detik/menit API GitHub terlampaui.",
                cause = "Upload massal tanpa jeda atau polling berulang terlalu cepat.",
                solution = "Aktifkan Rate Limit Pacer di Setelan dan tunggu beberapa saat sebelum mencoba ulang.",
                quickActionLabel = "Buka Setelan",
                quickActionRoute = "debug_panel"
            )
            in 500..599 -> HttpErrorAdvice(
                statusCode = log.statusCode,
                title = "Gangguan Server GitHub (HTTP ${log.statusCode})",
                meaning = "Server internal GitHub sedang mengalami gangguan sementara.",
                cause = "Beban server tinggi atau pemeliharaan di sisi GitHub.",
                solution = "Tunggu beberapa menit lalu tekan ulang aksi Anda. WorkManager akan mencoba ulang otomatis jika sedang upload."
            )
            else -> HttpErrorAdvice(
                statusCode = log.statusCode,
                title = "Respons HTTP ${log.statusCode}",
                meaning = "Endpoint ${log.url} mengembalikan status ${log.statusCode}.",
                cause = log.summary.ifBlank { "Kesalahan tidak spesifik dari server." },
                solution = "Periksa ringkasan respons, pastikan token aktif dan parameter repositori valid.",
                quickActionLabel = "Buka Setelan",
                quickActionRoute = "debug_panel"
            )
        }
    }
}
