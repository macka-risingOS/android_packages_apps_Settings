package com.android.settings.display;

import android.annotation.Nullable;
import android.app.settings.SettingsEnums;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.os.ServiceManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.IWindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.VisibleForTesting;
import androidx.preference.PreferenceScreen;

import com.android.settings.R;
import com.android.settings.core.instrumentation.SettingsStatsLog;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.widget.RadioButtonPickerFragment;
import com.android.settingslib.display.DisplayDensityUtils;
import com.android.settingslib.search.SearchIndexable;
import com.android.settingslib.widget.CandidateInfo;
import com.android.settingslib.widget.FooterPreference;
import com.android.settingslib.widget.IllustrationPreference;
import com.android.settingslib.widget.SelectorWithWidgetPreference;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Preference fragment used for switch screen resolution */
@SearchIndexable
public class ScreenResolutionFragment extends RadioButtonPickerFragment {
    private static final String TAG = "ScreenResolution";

    private Resources mResources;
    private static final String SCREEN_RESOLUTION = "user_selected_resolution";
    private static final String SCREEN_RESOLUTION_KEY = "screen_resolution";
    private Display mDefaultDisplay;
    private String[] mScreenResolutionOptions;
    private Set<Point> mResolutions;
    private String[] mScreenResolutionSummaries;

    private IllustrationPreference mImagePreference;
    private DisplayObserver mDisplayObserver;
    private AccessibilityManager mAccessibilityManager;

    private int mHighWidth;
    private int mFullWidth;
    private int mHighHeight;
    private int mFullHeight;
    
    private IWindowManager mWm;
    private float baseDensity;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        mWm = IWindowManager.Stub.asInterface(ServiceManager.checkService(
                Context.WINDOW_SERVICE));

        mDefaultDisplay =
                context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        mAccessibilityManager = context.getSystemService(AccessibilityManager.class);
        mResources = context.getResources();
        mScreenResolutionOptions =
                mResources.getStringArray(R.array.config_screen_resolution_options_strings);
        mImagePreference = new IllustrationPreference(context);
        mDisplayObserver = new DisplayObserver(context);
        ScreenResolutionController controller =
                new ScreenResolutionController(context, SCREEN_RESOLUTION_KEY);
        mResolutions = controller.getAllSupportedResolutions();
        mHighWidth = controller.getHighWidth();
        mFullWidth = controller.getFullWidth();
        mHighHeight = controller.getHighHeight();
        mFullHeight = controller.getFullHeight();
        Log.i(TAG, "mHighWidth: " + mHighWidth + "mFullWidth: " + mFullWidth);
        mScreenResolutionSummaries =
                new String[] {
                    mHighWidth + " x " + mHighHeight,
                    mFullWidth + " x " + mFullHeight
                };
                
        String selectedResolution = Settings.System.getString(
                getContext().getContentResolver(), SCREEN_RESOLUTION);
        String defaultKey = getKeyForResolution(selectedResolution);
        setDefaultKey(defaultKey);
    }

    @Override
    protected int getPreferenceScreenResId() {
        return R.xml.screen_resolution_settings;
    }

    @Override
    protected void addStaticPreferences(PreferenceScreen screen) {
        updateIllustrationImage(mImagePreference);
        screen.addPreference(mImagePreference);

        final FooterPreference footerPreference = new FooterPreference(screen.getContext());
        footerPreference.setTitle(R.string.screen_resolution_footer);
        footerPreference.setSelectable(false);
        footerPreference.setLayoutResource(R.layout.preference_footer);
        screen.addPreference(footerPreference);
    }

    @Override
    public void bindPreferenceExtra(
            SelectorWithWidgetPreference pref,
            String key,
            CandidateInfo info,
            String defaultKey,
            String systemDefaultKey) {
        final ScreenResolutionCandidateInfo candidateInfo = (ScreenResolutionCandidateInfo) info;
        final CharSequence summary = candidateInfo.loadSummary();
        if (summary != null) pref.setSummary(summary);
    }

    @Override
    protected List<? extends CandidateInfo> getCandidates() {
        final List<ScreenResolutionCandidateInfo> candidates = new ArrayList<>();

        for (int i = 0; i < mScreenResolutionOptions.length; i++) {
            candidates.add(
                    new ScreenResolutionCandidateInfo(
                            mScreenResolutionOptions[i],
                            mScreenResolutionSummaries[i],
                            mScreenResolutionOptions[i],
                            true /* enabled */));
        }

        return candidates;
    }

    /** Get current display mode. */
    @VisibleForTesting
    public Display.Mode getDisplayMode() {
        return mDefaultDisplay.getMode();
    }

    /** Using display manager to set the display mode. */
    @VisibleForTesting
    public void setDisplayMode(final String resolution) {
        Point resolutionPoint = parseResolution(resolution);
        if (resolutionPoint == null) {
            return;
        }

        int width = resolutionPoint.x;
        int height = resolutionPoint.y;

        Display.Mode switchMode = new Display.Mode(width, height, getDisplayMode().getRefreshRate());
        Display.Mode fullMode = new Display.Mode(mFullWidth, mFullHeight, getDisplayMode().getRefreshRate());

        mDisplayObserver.startObserve();

        // Store settings globally
        Settings.System.putString(
                getContext().getContentResolver(),
                SCREEN_RESOLUTION,
                switchMode.getPhysicalWidth() + "x" + switchMode.getPhysicalHeight());

        try {
            baseDensity = (float) mWm.getBaseDisplayDensity(Display.DEFAULT_DISPLAY);
            if (resolution == mFullWidth + " x " + mFullHeight) {
                mDefaultDisplay.setUserPreferredDisplayMode(fullMode);
                mWm.setForcedDisplaySize(Display.DEFAULT_DISPLAY, (int) fullMode.getPhysicalWidth(), (int) fullMode.getPhysicalHeight());
            } else {
                mDefaultDisplay.setUserPreferredDisplayMode(switchMode);
                mWm.setForcedDisplaySize(Display.DEFAULT_DISPLAY, (int) switchMode.getPhysicalWidth(), (int) switchMode.getPhysicalHeight());
            }
            DisplayDensityUtils density = new DisplayDensityUtils(getContext());
            int[] densityValues = density.getDefaultDisplayDensityValues();
            double newDensity = baseDensity * mFullWidth / width;
            int minDistance = Math.abs(densityValues[0] - (int) newDensity);
            int idx = 0;

            for (int i = 1; i < densityValues.length; i++) {
                int dist = Math.abs(densityValues[i] - (int) newDensity);
                if (dist < minDistance) {
                    minDistance = dist;
                    idx = i;
                }
            }
            density.setForcedDisplayDensity(idx);
        } catch (Exception e) {
            Log.e(TAG, "setUserPreferredDisplayMode() failed", e);
            return;
        }

        // Send the atom after resolution changed successfully
        SettingsStatsLog.write(
                SettingsStatsLog.USER_SELECTED_RESOLUTION,
                mDefaultDisplay.getUniqueId().hashCode(),
                switchMode.getPhysicalWidth(),
                switchMode.getPhysicalHeight());
    }

    /** Get the key corresponding to the resolution. */
    @VisibleForTesting
    String getKeyForResolution(String resolution) {
        if (resolution != null) {
            Point point = parseResolution(resolution);
            if (point != null) {
                int width = point.x;
                return width == mHighWidth
                        ? mScreenResolutionOptions[ScreenResolutionController.HIGHRESOLUTION_IDX]
                        : width == mFullWidth
                                ? mScreenResolutionOptions[ScreenResolutionController.FULLRESOLUTION_IDX]
                                : null;
            }
        }
        return null;
    }

    /** Parse resolution string to a Point object. */
    @Nullable
    private Point parseResolution(String resolution) {
        if (resolution != null) {
            String[] parts = resolution.split("x");
            if (parts.length == 2) {
                try {
                    int width = Integer.parseInt(parts[0].trim());
                    int height = Integer.parseInt(parts[1].trim());
                    return new Point(width, height);
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Error parsing resolution string", e);
                }
            }
        }
        return null;
    }

    /** Get the width corresponding to the resolution key. */
    int getWidthForResoluitonKey(String key) {
        return mScreenResolutionOptions[ScreenResolutionController.HIGHRESOLUTION_IDX].equals(key)
                ? mHighWidth
                : mScreenResolutionOptions[ScreenResolutionController.FULLRESOLUTION_IDX].equals(
                    key)
                ? mFullWidth : -1;
    }

    @Override
    protected String getDefaultKey() {
        String selectedResolution = Settings.System.getString(
                getContext().getContentResolver(), SCREEN_RESOLUTION);
        if (selectedResolution == null) {
            int physicalWidth = getDisplayMode().getPhysicalWidth();
            return getKeyForResolution(Integer.toString(physicalWidth));
        }
        return getKeyForResolution(selectedResolution);
    }

    @Override
    protected boolean setDefaultKey(final String key) {
        String resolution = getResolutionForResolutionKey(key);
        if (resolution == null) {
            return false;
        }

        setDisplayMode(resolution);
        updateIllustrationImage(mImagePreference);

        return true;
    }

    private String getResolutionForResolutionKey(String key) {
        int width = getWidthForResoluitonKey(key);
        if (width < 0) {
            return null;
        }

        return width == mHighWidth
                ? mHighWidth + "x" + mHighHeight
                : width == mFullWidth
                        ? mFullWidth + "x" + mFullHeight
                        : null;
    }

    @Override
    public void onRadioButtonClicked(SelectorWithWidgetPreference selected) {
        String selectedKey = selected.getKey();
        String resolution = getResolutionForResolutionKey(selectedKey);
        
        setDisplayMode(resolution);
        Log.d("onRadioButtonClicked: setDisplayMode: ", "resolution: " + resolution);

        if (mAccessibilityManager.isEnabled()) {
            AccessibilityEvent event = AccessibilityEvent.obtain();
            event.setEventType(AccessibilityEvent.TYPE_ANNOUNCEMENT);
            event.getText().add(mResources.getString(R.string.screen_resolution_selected_a11y));
            mAccessibilityManager.sendAccessibilityEvent(event);
        }

        super.onRadioButtonClicked(selected);
    }

    /** Update the resolution image according display mode. */
    private void updateIllustrationImage(IllustrationPreference preference) {
        String key = getDefaultKey();

        if (TextUtils.equals(
                mScreenResolutionOptions[ScreenResolutionController.HIGHRESOLUTION_IDX], key)) {
            preference.setLottieAnimationResId(R.drawable.screen_resolution_high);
        } else if (TextUtils.equals(
                mScreenResolutionOptions[ScreenResolutionController.FULLRESOLUTION_IDX], key)) {
            preference.setLottieAnimationResId(R.drawable.screen_resolution_full);
        }
    }

    @Override
    public int getMetricsCategory() {
        return SettingsEnums.SCREEN_RESOLUTION;
    }

    /** This is an extension of the CandidateInfo class, which adds summary information. */
    public static class ScreenResolutionCandidateInfo extends CandidateInfo {
        private final CharSequence mLabel;
        private final CharSequence mSummary;
        private final String mKey;

        ScreenResolutionCandidateInfo(
                CharSequence label, CharSequence summary, String key, boolean enabled) {
            super(enabled);
            mLabel = label;
            mSummary = summary;
            mKey = key;
        }

        @Override
        public CharSequence loadLabel() {
            return mLabel;
        }

        /** It is the summary for radio options. */
        public CharSequence loadSummary() {
            return mSummary;
        }

        @Override
        public Drawable loadIcon() {
            return null;
        }

        @Override
        public String getKey() {
            return mKey;
        }
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.screen_resolution_settings) {
                @Override
                protected boolean isPageSearchEnabled(Context context) {
                    ScreenResolutionController mController =
                            new ScreenResolutionController(context, SCREEN_RESOLUTION_KEY);
                    return mController.checkSupportedResolutions();
                }
            };

    private static final class DisplayObserver implements DisplayManager.DisplayListener {
        private final @Nullable Context mContext;
        private int mDefaultDensity;
        private int mCurrentIndex;
        private AtomicInteger mPreviousWidth = new AtomicInteger(-1);

        DisplayObserver(Context context) {
            mContext = context;
        }

        public void startObserve() {
            if (mContext == null) {
                return;
            }

            final DisplayDensityUtils density = new DisplayDensityUtils(mContext);
            final int currentIndex = density.getCurrentIndexForDefaultDisplay();
            final int defaultDensity = density.getDefaultDensityForDefaultDisplay();

            if (density.getDefaultDisplayDensityValues()[mCurrentIndex]
                    == density.getDefaultDensityForDefaultDisplay()) {
                return;
            }

            mDefaultDensity = defaultDensity;
            mCurrentIndex = currentIndex;
            final DisplayManager dm = mContext.getSystemService(DisplayManager.class);
            dm.registerDisplayListener(this, null);
        }

        public void stopObserve() {
            if (mContext == null) {
                return;
            }

            final DisplayManager dm = mContext.getSystemService(DisplayManager.class);
            dm.unregisterDisplayListener(this);
        }

        @Override
        public void onDisplayAdded(int displayId) {}

        @Override
        public void onDisplayRemoved(int displayId) {}

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId != Display.DEFAULT_DISPLAY) {
                return;
            }

            if (!isDensityChanged()) {
                return;
            }

            stopObserve();
        }

        private void restoreDensity() {
            final DisplayDensityUtils density = new DisplayDensityUtils(mContext);
            /* If current density is the same as a default density of other resolutions,
             * then mCurrentIndex may be out of boundary.
             */
            if (density.getDefaultDisplayDensityValues().length <= mCurrentIndex) {
                mCurrentIndex = density.getCurrentIndexForDefaultDisplay();
            }
            if (density.getDefaultDisplayDensityValues()[mCurrentIndex]
                    != density.getDefaultDensityForDefaultDisplay()) {
                density.setForcedDisplayDensity(mCurrentIndex);
            }

            mDefaultDensity = density.getDefaultDensityForDefaultDisplay();
        }

        private boolean isDensityChanged() {
            final DisplayDensityUtils density = new DisplayDensityUtils(mContext);
            if (density.getDefaultDensityForDefaultDisplay() == mDefaultDensity) {
                return false;
            }

            return true;
        }
    }
}
