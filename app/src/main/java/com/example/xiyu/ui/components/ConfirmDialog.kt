package com.example.xiyu.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Universal confirmation modal for critical or destructive actions
 * (e.g., delete file/folder, rename, overwrite, commit, switch accounts).
 */
@Composable
fun UniversalConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "Confirm",
    cancelText: String = "Cancel",
    isDestructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Warning",
                tint = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = message,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ThinHighlightText(
                    text = if (isDestructive) "Tindakan ini permanen dan akan langsung di-commit ke remote GitHub."
                    else "Tindakan ini akan memicu panggilan API ke GitHub."
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    contentColor = if (isDestructive) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(cancelText)
            }
        },
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface
    )
}

/**
 * Safe repository deletion dialog: Requires the user to retype the repository name
 * before the destructive Delete button is enabled.
 */
@Composable
fun DeleteRepoConfirmDialog(
    repoName: String,
    onConfirmDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var typedName by remember { mutableStateOf("") }
    val isMatch = typedName.trim() == repoName.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Delete Repo",
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                text = "Hapus Repositori GitHub",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Anda akan menghapus repositori \"$repoName\" secara permanen di GitHub.",
                    fontSize = 14.sp
                )
                Text(
                    text = "Ketik ulang nama repositori untuk konfirmasi:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                OutlinedTextField(
                    value = typedName,
                    onValueChange = { typedName = it },
                    placeholder = { Text(repoName, fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                ThinHighlightText(
                    text = if (isMatch) "✓ Nama cocok. Tombol hapus aktif."
                    else "Ketik persis \"$repoName\" untuk membuka tombol."
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isMatch) {
                        onConfirmDelete()
                        onDismiss()
                    }
                },
                enabled = isMatch,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    disabledContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Hapus Repositori")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Batal")
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}
