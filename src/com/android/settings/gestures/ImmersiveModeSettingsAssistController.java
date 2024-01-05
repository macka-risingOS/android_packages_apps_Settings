/*
 * Copyright (C) 2021 The Android Open Source Project
 * Copyright (C) 2023 The RisingOS Android Project
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
 * limitations under the License.
 */

package com.android.settings.gestures;

import android.content.Context;
import android.provider.Settings;

import com.android.settings.R;
import com.android.settings.core.TogglePreferenceController;

/**
 * Configures behaviour of immersive ui mode
 */
public class ImmersiveModeSettingsAssistController extends TogglePreferenceController {

    public ImmersiveModeSettingsAssistController(Context context, String key) {
        super(context, key);
    }

    @Override
    public boolean isChecked() {;
        return Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.LOCK_IMMERSIVE_SYSUI, 0)
                == 1;
    }

    @Override
    public boolean setChecked(boolean isChecked) {
        return Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.LOCK_IMMERSIVE_SYSUI, isChecked ? 1 : 0);
    }

    @Override
    public int getAvailabilityStatus() {
        return SystemNavigationPreferenceController.isGestureAvailable(mContext) ? AVAILABLE
                : UNSUPPORTED_ON_DEVICE;
    }

    @Override
    public int getSliceHighlightMenuRes() {
        return R.string.menu_key_system;
    }
}
