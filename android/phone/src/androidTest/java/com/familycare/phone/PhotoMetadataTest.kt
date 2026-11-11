package com.familycare.phone

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.familycare.ui.MemoryPhotos
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PhotoMetadataTest {
    @Test fun selectedPhotoUsesExifAndStripsUnrelatedMetadata(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"selected-photo-test.jpg")
        try{
            val bitmap=Bitmap.createBitmap(160,100,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLUE)}
            file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)};bitmap.recycle()
            ExifInterface(file.path).apply{
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,"2026:10:27 08:30:00")
                setAttribute(ExifInterface.TAG_MAKE,"PRIVATE-CAMERA")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE,"37/1,29/1,76092/10000")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF,"N")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE,"127/1,2/1,109212/10000")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF,"E");saveAttributes()
            }
            val draft=MemoryPhotos.read(context,Uri.fromFile(file))
            assertEquals("2026-10-27 08:30",draft.captured)
            assertEquals(37.485447,draft.latitude!!,0.00001)
            assertEquals(127.036367,draft.longitude!!,0.00001)
            assertNotNull(MemoryPhotos.bitmap(draft.thumbnail))
            val cleaned=android.util.Base64.decode(draft.image,android.util.Base64.NO_WRAP)
            assertNull(ExifInterface(cleaned.inputStream()).getAttribute(ExifInterface.TAG_MAKE))
            val raw=File(context.cacheDir,"no-exif-test.jpg")
            try{raw.writeBytes(cleaned);val missing=MemoryPhotos.read(context,Uri.fromFile(raw));assertNull(missing.latitude);assertEquals("",missing.captured)}finally{raw.delete()}
        }finally{file.delete()}
    }
}
