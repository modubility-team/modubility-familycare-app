package com.familycare.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext

/** Original AI-generated illustrative vehicle. It does not identify the registered model. */
@Composable fun VehicleArtwork(modifier:Modifier=Modifier){
    val context=LocalContext.current
    val bitmap=remember(context){context.assets.open("vehicle_showcase.png").use{BitmapFactory.decodeStream(it)}.asImageBitmap()}
    Image(bitmap,"차량 예시 이미지",modifier,contentScale=ContentScale.Fit)
}
