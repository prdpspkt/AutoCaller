package com.example.aicallresponder

import android.app.admin.DeviceAdminReceiver

/**
 * Device admin component. When the app is provisioned as **Device Owner**, this receiver is the
 * admin that DevicePolicyManager acts through (e.g. to silently grant runtime permissions).
 *
 * Provision it from a factory-reset device with:
 *   adb shell dpm set-device-owner com.example.aicallresponder/.AiDeviceAdminReceiver
 */
class AiDeviceAdminReceiver : DeviceAdminReceiver()
