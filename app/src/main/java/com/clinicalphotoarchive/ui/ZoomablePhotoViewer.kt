package com.clinicalphotoarchive.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.clinicalphotoarchive.data.PhotoEntity
import com.clinicalphotoarchive.util.ImageFiles
import kotlinx.coroutines.*
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

private sealed interface PhotoLoad {
    data object Loading: PhotoLoad
    data object Failed: PhotoLoad
    data class Ready(val bitmap: ImageBitmap): PhotoLoad
}

@Composable
internal fun ZoomablePhotoViewer(photo: PhotoEntity,onDismiss: ()->Unit) {
    val load by produceState<PhotoLoad>(PhotoLoad.Loading,photo.localPath) {
        value=try {
            val image=withContext(Dispatchers.IO) {ImageFiles.loadBitmap(photo.localPath,2048)?.asImageBitmap()}
            if(image==null) PhotoLoad.Failed else PhotoLoad.Ready(image)
        } catch(c: CancellationException) {throw c} catch(_: Throwable) {PhotoLoad.Failed}
    }
    var zoom by remember(photo.id) {mutableStateOf(ZoomState())}
    var viewport by remember(photo.id) {mutableStateOf(IntSize.Zero)}
    val image=(load as? PhotoLoad.Ready)?.bitmap
    val fit=if(image==null) 0f else min(viewport.width.toFloat()/image.width,viewport.height.toFloat()/image.height)
    val bounds=ZoomBounds(viewport.width.toFloat(),viewport.height.toFloat(),(image?.width ?: 0)*fit,(image?.height ?: 0)*fit)
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(dismissOnBackPress=true,dismissOnClickOutside=false,usePlatformDefaultWidth=false,securePolicy=SecureFlagPolicy.SecureOn)) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.End) {
                IconButton(onClick=onDismiss) {Icon(Icons.Default.Close,"Закрыть",tint=Color.White)}
            }
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged {viewport=it;zoom=ZoomState()}
                .semantics {stateDescription="Масштаб ${(zoom.scale*100).roundToInt()}%"}
                .pointerInput(photo.id,bounds) {
                    detectTransformGestures {centroid,pan,scale,_ ->
                        zoom=ZoomGeometry.transform(zoom,scale,pan.x,pan.y,centroid.x,centroid.y,bounds)
                    }
                }.pointerInput(photo.id,bounds) {detectTapGestures(onDoubleTap={zoom=ZoomGeometry.doubleTap(zoom,it.x,it.y,bounds)})},contentAlignment=Alignment.Center) {
                if(image!=null) Image(image,photo.description.ifBlank {"Снимок пациента"},Modifier.fillMaxSize().graphicsLayer {
                    scaleX=zoom.scale;scaleY=zoom.scale;translationX=zoom.offsetX;translationY=zoom.offsetY
                },contentScale=ContentScale.Fit)
                else if(load==PhotoLoad.Loading) CircularProgressIndicator(color=Color.White)
                else Text("Не удалось открыть снимок",color=Color.White,modifier=Modifier.padding(16.dp))
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
                IconButton(enabled=image!=null,onClick={zoom=ZoomGeometry.transform(zoom,0.8f,0f,0f,bounds.viewportW/2,bounds.viewportH/2,bounds)}) {Icon(Icons.Default.ZoomOut,"Уменьшить",tint=Color.White)}
                Text(String.format(Locale.ROOT,"%.1f×",zoom.scale),color=Color.White)
                IconButton(enabled=image!=null,onClick={zoom=ZoomGeometry.transform(zoom,1.25f,0f,0f,bounds.viewportW/2,bounds.viewportH/2,bounds)}) {Icon(Icons.Default.ZoomIn,"Увеличить",tint=Color.White)}
                TextButton(onClick={zoom=ZoomState()}) {Text("Сбросить",color=Color.White)}
            }
            if(photo.description.isNotBlank()) Text(photo.description,color=Color.White,modifier=Modifier.fillMaxWidth().heightIn(max=120.dp).verticalScroll(rememberScrollState()).padding(12.dp))
        }
    }
}
