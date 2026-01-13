package io.homeassistant.companion.android.glasses

import android.app.Activity
import android.os.Bundle
import timber.log.Timber

class GlassesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Timber.d("ZZZ: GlassesActivity.onCreate: savedInstanceState=$savedInstanceState")
    }
}
