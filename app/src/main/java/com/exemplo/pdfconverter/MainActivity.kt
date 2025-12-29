package com.exemplo.pdfconverter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF3F4F6)) {
                    PdfConverterApp()
                }
            }
        }
    }
}

@Composable
fun PdfConverterApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Estados da Aplicação
    var pdfUri by remember { mutableStateOf<Uri?>(null) }
    var pdfFileName by remember { mutableStateOf("Documento") }
    var pageCount by remember { mutableStateOf(0) }
    var selectedPages by remember { mutableStateOf(setOf<Int>()) }
    var thumbnails by remember { mutableStateOf(mapOf<Int, Bitmap>()) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf("") }
    var zoomPage by remember { mutableStateOf<Int?>(null) }
    
    // Renderizador PDF
    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var fileDescriptor by remember { mutableStateOf<ParcelFileDescriptor?>(null) }

    // Selecionar Arquivo
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                pdfUri = uri
                selectedPages = emptySet()
                thumbnails = emptyMap()
                
                // Pegar nome do arquivo
                try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (cursor.moveToFirst()) {
                            pdfFileName = cursor.getString(nameIndex).replace(".pdf", "", true)
                        }
                    }
                } catch (e: Exception) { pdfFileName = "Documento" }

                // Carregar PDF
                scope.launch(Dispatchers.IO) {
                    isLoading = true
                    loadingMessage = "Processando PDF..."
                    try {
                        fileDescriptor?.close()
                        fileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
                        fileDescriptor?.let { fd ->
                            pdfRenderer = PdfRenderer(fd)
                            pageCount = pdfRenderer!!.pageCount
                            
                            // Gerar Miniaturas
                            val newThumbs = mutableMapOf<Int, Bitmap>()
                            for (i in 0 until pageCount) {
                                val page = pdfRenderer!!.openPage(i)
                                // Qualidade média para grade (Rápido)
                                val w = (page.width / 3).coerceAtLeast(100)
                                val h = (page.height / 3).coerceAtLeast(100)
                                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                val canvas = android.graphics.Canvas(bitmap)
                                canvas.drawColor(android.graphics.Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                page.close()
                                newThumbs[i] = bitmap
                            }
                            thumbnails = newThumbs
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                    isLoading = false
                }
            }
        }
    )

    // Função de Download
    fun downloadSelected() {
        if (selectedPages.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            isLoading = true
            try {
                val sortedPages = selectedPages.sorted()
                
                // Modo 1: Única Imagem (JPG Direto)
                if (sortedPages.size == 1) {
                    loadingMessage = "Salvando Imagem..."
                    val pageIndex = sortedPages[0]
                    val bitmap = renderPageHighRes(pdfRenderer!!, pageIndex)
                    saveBitmapToPublic(context, bitmap, "${pdfFileName}-pag${pageIndex + 1}.jpg")
                    withContext(Dispatchers.Main) { 
                        Toast.makeText(context, "Imagem salva em Downloads!", Toast.LENGTH_LONG).show() 
                    }
                } 
                // Modo 2: Múltiplas (ZIP)
                else {
                    loadingMessage = "Gerando ZIP..."
                    val zipFile = File(context.cacheDir, "${pdfFileName}-Imagens.zip")
                    ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                        for ((idx, pageIndex) in sortedPages.withIndex()) {
                            withContext(Dispatchers.Main) { loadingMessage = "Processando ${idx + 1}/${sortedPages.size}" }
                            val bitmap = renderPageHighRes(pdfRenderer!!, pageIndex)
                            val entry = ZipEntry("${pdfFileName}-pag${pageIndex + 1}.jpg")
                            zos.putNextEntry(entry)
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, zos)
                            zos.closeEntry()
                        }
                    }
                    saveFileToPublic(context, zipFile, "${pdfFileName}-Imagens.zip")
                    withContext(Dispatchers.Main) { 
                        Toast.makeText(context, "ZIP salvo em Downloads!", Toast.LENGTH_LONG).show() 
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    Toast.makeText(context, "Erro: ${e.message}", Toast.LENGTH_SHORT).show() 
                }
            }
            isLoading = false
        }
    }

    // --- Interface do Usuário (UI) ---
    Box(modifier = Modifier.fillMaxSize()) {
        if (pdfUri == null) {
            // Tela Inicial
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.PictureAsPdf, null, tint = Color(0xFF2563EB), modifier = Modifier.size(64.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text("Conversor PDF", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = { filePickerLauncher.launch(arrayOf("application/pdf")) }) {
                    Text("Selecionar Arquivo PDF")
                }
            }
        } else {
            // Tela de Grade
            Column(modifier = Modifier.fillMaxSize()) {
                // Barra Superior
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color.White).padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { pdfUri = null }) { Icon(Icons.Default.ArrowBack, null) }
                    Column {
                        Text(pdfFileName, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("${selectedPages.size} selecionadas", fontSize = 12.sp, color = Color.Gray)
                    }
                    TextButton(onClick = { 
                        selectedPages = if(selectedPages.size == pageCount) emptySet() else (0 until pageCount).toSet() 
                    }) {
                        Text(if(selectedPages.size == pageCount) "Nada" else "Tudo")
                    }
                }

                // Grade de Páginas
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(100.dp),
                    contentPadding = PaddingValues(10.dp, 10.dp, 10.dp, 100.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(pageCount) { index ->
                        val isSelected = selectedPages.contains(index)
                        Box(
                            modifier = Modifier
                                .aspectRatio(0.7f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if(isSelected) Color(0xFFDCFCE7) else Color.White)
                                .border(2.dp, if(isSelected) Color(0xFF16A34A) else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable { 
                                    selectedPages = if(isSelected) selectedPages - index else selectedPages + index 
                                }
                        ) {
                            thumbnails[index]?.let { bmp ->
                                Image(
                                    bitmap = bmp.asImageBitmap(), 
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize().padding(4.dp)
                                )
                            }
                            // Botão Zoom
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp)
                                    .background(Color.Black.copy(0.6f), CircleShape)
                                    .clickable { zoomPage = index }
                                    .padding(4.dp)
                            ) {
                                Icon(Icons.Default.ZoomIn, null, tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                            // Check
                            if(isSelected) {
                                Icon(
                                    Icons.Default.CheckCircle, 
                                    null, 
                                    tint = Color(0xFF16A34A),
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                                )
                            }
                        }
                    }
                }
            }
            
            // Botão Flutuante
            Box(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Button(
                    onClick = { downloadSelected() },
                    enabled = selectedPages.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))
                ) {
                    Icon(Icons.Default.Download, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if(selectedPages.size <= 1) "Baixar Imagem" else "Baixar ZIP")
                }
            }
        }

        // Zoom Modal
        zoomPage?.let { idx ->
            ZoomDialog(idx, pdfRenderer, selectedPages.contains(idx), 
                onClose = { zoomPage = null },
                onToggle = { selectedPages = if(selectedPages.contains(idx)) selectedPages - idx else selectedPages + idx }
            )
        }

        // Loading
        if(isLoading) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.7f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Text(loadingMessage, color = Color.White, modifier = Modifier.padding(top=8.dp))
                }
            }
        }
    }
}

@Composable
fun ZoomDialog(index: Int, renderer: PdfRenderer?, isSelected: Boolean, onClose: () -> Unit, onToggle: () -> Unit) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    LaunchedEffect(index) {
        withContext(Dispatchers.IO) {
            renderer?.let { bitmap = renderPageHighRes(it, index, 2.0f) }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // Imagem Zoomável
            Box(
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 4f)
                        offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
                    }
                },
                contentAlignment = Alignment.Center
            ) {
                bitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().graphicsLayer(
                            scaleX = scale, scaleY = scale,
                            translationX = offset.x, translationY = offset.y
                        )
                    )
                }
            }
            
            // Botões de Controle
            Row(
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, null, tint = Color.White)
                }
                Button(
                    onClick = onToggle,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if(isSelected) Color.Red else Color.White,
                        contentColor = if(isSelected) Color.White else Color.Black
                    )
                ) {
                    Text(if(isSelected) "Desmarcar" else "Selecionar")
                }
            }
        }
    }
}

// Funções Auxiliares (Não precisa mudar nada aqui)
fun renderPageHighRes(renderer: PdfRenderer, index: Int, scale: Float = 2.0f): Bitmap {
    val page = renderer.openPage(index)
    val w = (page.width * scale).toInt()
    val h = (page.height * scale).toInt()
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)
    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    page.close()
    return bitmap
}

fun saveBitmapToPublic(ctx: Context, bmp: Bitmap, name: String) {
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
    }
    val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    uri?.let { ctx.contentResolver.openOutputStream(it)?.use { s -> bmp.compress(Bitmap.CompressFormat.JPEG, 90, s) } }
}

fun saveFileToPublic(ctx: Context, file: File, name: String) {
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip")
        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
    }
    val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    uri?.let { ctx.contentResolver.openOutputStream(it)?.use { out -> java.io.FileInputStream(file).use { inp -> inp.copyTo(out) } } }
}


