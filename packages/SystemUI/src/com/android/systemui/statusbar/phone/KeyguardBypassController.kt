/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.systemui.statusbar.phone

import android.content.Context
import android.hardware.biometrics.BiometricSourceType
import android.hardware.face.FaceManager
import android.provider.Settings
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.tuner.TunerService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeyguardBypassController {

    private val unlockMethodCache: UnlockMethodCache
    private val statusBarStateController: StatusBarStateController

    /**
     * The pending unlock type which is set if the bypass was blocked when it happened.
     */
    private var pendingUnlockType: BiometricSourceType? = null

    lateinit var unlockController: BiometricUnlockController
    var isPulseExpanding = false

    /**
     * If face unlock dismisses the lock screen or keeps user on keyguard for the current user.
     */
    var bypassEnabled: Boolean = false
        get() = field && unlockMethodCache.isUnlockingWithFacePossible
        private set

    var bouncerShowing: Boolean = false
    var qSExpanded = false
        set(value) {
            val changed = field != value
            field = value
            if (changed && !value) {
                maybePerformPendingUnlock()
            }
        }

    @Inject
    constructor(context: Context, tunerService: TunerService,
                statusBarStateController: StatusBarStateController) {
        unlockMethodCache = UnlockMethodCache.getInstance(context)
        this.statusBarStateController = statusBarStateController
        statusBarStateController.addCallback(object : StatusBarStateController.StateListener {
            override fun onStateChanged(newState: Int) {
                if (newState != StatusBarState.KEYGUARD) {
                    pendingUnlockType = null;
                }
            }
        })
        val faceManager = context.getSystemService(FaceManager::class.java)
        if (faceManager?.isHardwareDetected != true) {
            return
        }

        val dismissByDefault = if (context.resources.getBoolean(
                com.android.internal.R.bool.config_faceAuthDismissesKeyguard)) 1 else 0
        tunerService.addTunable(
                object : TunerService.Tunable {
                        override fun onTuningChanged(key: String?, newValue: String?) {
                                bypassEnabled = Settings.Secure.getIntForUser(
                                        context.contentResolver,
                                        Settings.Secure.FACE_UNLOCK_DISMISSES_KEYGUARD,
                                        dismissByDefault,
                                        KeyguardUpdateMonitor.getCurrentUser()) != 0
            }
        }, Settings.Secure.FACE_UNLOCK_DISMISSES_KEYGUARD)
    }

    /**
     * Notify that the biometric unlock has happened.
     *
     * @return false if we can not wake and unlock right now
     */
    fun onBiometricAuthenticated(biometricSourceType: BiometricSourceType): Boolean {
        if (bypassEnabled) {
            if (bouncerShowing) {
                // Whenever the bouncer is showing, we want to unlock. Otherwise we can get stuck
                // in the shade locked where the bouncer wouldn't unlock
                return true
            }
            if (statusBarStateController.state != StatusBarState.KEYGUARD) {
                // We're bypassing but not actually on the lockscreen, the user should decide when
                // to unlock
                return false
            }
            if (isPulseExpanding || qSExpanded) {
                pendingUnlockType = biometricSourceType
                return false
            }
        }
        return true
    }

    fun maybePerformPendingUnlock() {
        if (pendingUnlockType != null) {
            if (onBiometricAuthenticated(pendingUnlockType!!)) {
                unlockController.startWakeAndUnlock(pendingUnlockType)
                pendingUnlockType = null
            }
        }
    }

    fun onStartedGoingToSleep() {
        pendingUnlockType = null
    }
}
