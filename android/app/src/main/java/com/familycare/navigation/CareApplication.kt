package com.familycare.navigation

import android.app.Application

/** Application-scoped state; never retains an Activity or its window. */
class CareApplication : Application() {
    val care: CareRuntime by lazy { CareRuntime(this) }
}
