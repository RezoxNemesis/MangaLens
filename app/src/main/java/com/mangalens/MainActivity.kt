package com.mangalens

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.mangalens.core.verification.VerificationDialog
import com.mangalens.core.verification.VerificationState
import com.mangalens.ui.MangaLensNavGraph
import com.mangalens.ui.MangaLensViewModel
import com.mangalens.ui.theme.MangaLensTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                7401
            )
        }

        setContent {
            val viewModel: MangaLensViewModel = viewModel()
            val state by viewModel.state.collectAsState()
            MangaLensTheme(themeMode = state.themeMode) {
                val navController = rememberNavController()
                val verification by viewModel.captchaBridge.state.collectAsState()
                MangaLensNavGraph(
                    navController = navController,
                    state = state,
                    onUrlChanged = viewModel::setUrl,
                    onPaste = {
                        runCatching {
                            val clipboard =
                                getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clip = clipboard?.primaryClip
                            if (
                                clip != null &&
                                clip.itemCount > 0 &&
                                (
                                    clip.description?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true ||
                                    clip.description?.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) == true
                                )
                            ) {
                                val text = clip.getItemAt(0).coerceToText(this).toString().trim()
                                if (text.isNotBlank()) viewModel.setUrl(text)
                            }
                        }
                    },
                    onModeSelected = viewModel::setMode,
                    onIngest = viewModel::ingest,
                    onIngestAndTranslate = viewModel::ingestAndTranslate,
                    onTranslatePage = viewModel::translatePage,
                    onTranslateChapter = viewModel::translateChapter,
                    onTargetLanguageChanged = viewModel::setTargetLanguage,
                    onTranslationStyleChanged = viewModel::setTranslationStyle,
                    onCustomTranslationStyleChanged = viewModel::setCustomTranslationStyle,
                    onDownloadChapter = viewModel::downloadChapter,
                    onThemeModeChanged = viewModel::setThemeMode,
                    onMangaTranslationChanged = viewModel::setMangaTranslationEnabled,
                    onVideoTranslationChanged = viewModel::setVideoTranslationEnabled,
                    onWebTranslationChanged = viewModel::setWebTranslationEnabled,
                    onResetAdBlockStats = viewModel::resetAdBlockStats,
                    onImportImages = viewModel::importLocalImages
                )
                val verificationRequest = verification.request
                if (
                    verification.state == VerificationState.VERIFICATION_REQUIRED &&
                    verificationRequest != null
                ) {
                    VerificationDialog(
                        request = verificationRequest,
                        onVerified = { cookie, userAgent -> viewModel.ingestWithCookie(cookie, userAgent) },
                        onDismiss = { viewModel.captchaBridge.reset() }
                    )
                }
            }
        }
    }
}
