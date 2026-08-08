package com.dynamicisland

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager

class DynamicIslandPackage : ReactPackage {
    @Deprecated("Migrate to [BaseReactPackage] and implement [getModule] instead.")
    override fun createNativeModules(
        reactContext: ReactApplicationContext
    ): List<NativeModule> {
        return listOf(DynamicIslandModule(reactContext))
    }

    @Suppress("DEPRECATION")
    override fun createViewManagers(
        reactContext: ReactApplicationContext
    ): List<ViewManager<*, *>> = emptyList()
}
