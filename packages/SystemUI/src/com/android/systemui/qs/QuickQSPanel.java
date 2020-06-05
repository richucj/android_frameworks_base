/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.qs;

import static com.android.systemui.util.InjectionInflationController.VIEW_CONTEXT;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.media.MediaHierarchyManager;
import com.android.systemui.media.MediaHost;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTile.SignalState;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.qs.customize.QSCustomizer;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import java.util.ArrayList;
import java.util.Collection;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Version of QSPanel that only shows N Quick Tiles in the QS Header.
 */
public class QuickQSPanel extends QSPanel {

    public static final String NUM_QUICK_TILES = "sysui_qqs_count";
    private static final String TAG = "QuickQSPanel";
    // Start it at 6 so a non-zero value can be obtained statically.
    private static int sDefaultMaxTiles = 6;

    private boolean mDisabledByPolicy;
    private int mMaxTiles;
    protected QSPanel mFullPanel;
    /** Whether or not the QuickQSPanel currently contains a media player. */
    private boolean mShowHorizontalTileLayout;
    private LinearLayout mHorizontalLinearLayout;

    // Only used with media
    private QSTileLayout mHorizontalTileLayout;
    private QSTileLayout mRegularTileLayout;
    private int mLastOrientation = -1;
    private int mMediaBottomMargin;

    @Inject
    public QuickQSPanel(
            @Named(VIEW_CONTEXT) Context context,
            AttributeSet attrs,
            DumpManager dumpManager,
            BroadcastDispatcher broadcastDispatcher,
            QSLogger qsLogger,
            MediaHost mediaHost,
            UiEventLogger uiEventLogger
    ) {
        super(context, attrs, dumpManager, broadcastDispatcher, qsLogger, mediaHost, uiEventLogger);
        if (mFooter != null) {
            removeView(mFooter.getView());
        }
        if (mTileLayout != null) {
            for (int i = 0; i < mRecords.size(); i++) {
                mTileLayout.removeTile(mRecords.get(i));
            }
            removeView((View) mTileLayout);
        }
        mMediaBottomMargin = getResources().getDimensionPixelSize(
                R.dimen.quick_settings_media_extra_bottom_margin);
        if (mUsingMediaPlayer) {
            mHorizontalLinearLayout = new LinearLayout(mContext);
            mHorizontalLinearLayout.setOrientation(LinearLayout.HORIZONTAL);
            mHorizontalLinearLayout.setClipChildren(false);
            mHorizontalLinearLayout.setClipToPadding(false);

            DoubleLineTileLayout horizontalTileLayout = new DoubleLineTileLayout(context,
                    mUiEventLogger);
            horizontalTileLayout.setPaddingRelative(
                    horizontalTileLayout.getPaddingStart(),
                    horizontalTileLayout.getPaddingTop(),
                    horizontalTileLayout.getPaddingEnd(),
                    mContext.getResources().getDimensionPixelSize(
                            R.dimen.qqs_horizonal_tile_padding_bottom));
            mHorizontalTileLayout = horizontalTileLayout;
            mRegularTileLayout = new HeaderTileLayout(context, mUiEventLogger);
            LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
            int marginSize = (int) mContext.getResources().getDimension(R.dimen.qqs_media_spacing);
            lp.setMarginStart(0);
            lp.setMarginEnd(marginSize);
            lp.gravity = Gravity.CENTER_VERTICAL;
            mHorizontalLinearLayout.addView((View) mHorizontalTileLayout, lp);

            sDefaultMaxTiles = getResources().getInteger(R.integer.quick_qs_panel_max_columns);

            boolean useHorizontal = shouldUseHorizontalTileLayout();
            mTileLayout = useHorizontal ? mHorizontalTileLayout : mRegularTileLayout;
            mTileLayout.setListening(mListening);
            addView(mHorizontalLinearLayout, 0 /* Between brightness and footer */);
            ((View) mRegularTileLayout).setVisibility(!useHorizontal ? View.VISIBLE : View.GONE);
            mHorizontalLinearLayout.setVisibility(useHorizontal ? View.VISIBLE : View.GONE);
            addView((View) mRegularTileLayout, 0);
            super.setPadding(0, 0, 0, 0);
            applyBottomMargin((View) mRegularTileLayout);
        } else {
            sDefaultMaxTiles = getResources().getInteger(R.integer.quick_qs_panel_max_columns);
            mTileLayout = new HeaderTileLayout(context, mUiEventLogger);
            mTileLayout.setListening(mListening);
            addView((View) mTileLayout, 0 /* Between brightness and footer */);
            super.setPadding(0, 0, 0, 0);
            applyBottomMargin((View) mTileLayout);
        }
    }

    private void applyBottomMargin(View view) {
        int margin = getResources().getDimensionPixelSize(R.dimen.qs_header_tile_margin_bottom);
        MarginLayoutParams layoutParams = (MarginLayoutParams) view.getLayoutParams();
        layoutParams.bottomMargin = margin;
        view.setLayoutParams(layoutParams);
    }

    private void reAttachMediaHost() {
        if (mMediaHost == null) {
            return;
        }
        boolean horizontal = shouldUseHorizontalTileLayout();
        ViewGroup host = mMediaHost.getHostView();
        ViewGroup newParent = horizontal ? mHorizontalLinearLayout : this;
        ViewGroup currentParent = (ViewGroup) host.getParent();
        if (currentParent != newParent) {
            if (currentParent != null) {
                currentParent.removeView(host);
            }
            newParent.addView(host);
            LinearLayout.LayoutParams layoutParams = (LayoutParams) host.getLayoutParams();
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            layoutParams.width = horizontal ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
            layoutParams.weight = horizontal ? 1.5f : 0;
            layoutParams.bottomMargin = mMediaBottomMargin;
        }
    }

    @Override
    protected void addMediaHostView() {
        mMediaHost.setVisibleChangedListener((visible) -> {
            switchTileLayout();
            return null;
        });
        mMediaHost.setExpansion(0.0f);
        mMediaHost.setShowsOnlyActiveMedia(true);
        mMediaHost.init(MediaHierarchyManager.LOCATION_QQS);
        reAttachMediaHost();
        updateMediaHostContentMargins();
    }

    @Override
    protected void updateTileLayoutMargins(int visualMarginStart, int visualMarginEnd) {
        if (mUsingMediaPlayer) {
            updateMargins((View) mRegularTileLayout, visualMarginStart, visualMarginEnd);
            updateMargins((View) mHorizontalTileLayout, visualMarginStart, 0);
        } else {
            updateMargins((View) mTileLayout, visualMarginStart, visualMarginEnd);
        }
    }

    @Override
    protected void updatePadding() {
        // QS Panel is setting a top padding by default, which we don't need.
    }

    @Override
    protected void addDivider() {
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Dependency.get(TunerService.class).addTunable(mNumTiles, NUM_QUICK_TILES);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Dependency.get(TunerService.class).removeTunable(mNumTiles);
    }

    @Override
    protected String getDumpableTag() {
        return TAG;
    }

    public void setQSPanelAndHeader(QSPanel fullPanel, View header) {
        mFullPanel = fullPanel;
    }

    @Override
    protected boolean shouldShowDetail() {
        return !mExpanded;
    }

    @Override
    protected void drawTile(TileRecord r, State state) {
        if (state instanceof SignalState) {
            SignalState copy = new SignalState();
            state.copyTo(copy);
            // No activity shown in the quick panel.
            copy.activityIn = false;
            copy.activityOut = false;
            state = copy;
        }
        super.drawTile(r, state);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (newConfig.orientation != mLastOrientation) {
            mLastOrientation = newConfig.orientation;
            switchTileLayout();
        }
    }

    boolean switchTileLayout() {
        if (!mUsingMediaPlayer) return false;
        mShowHorizontalTileLayout = shouldUseHorizontalTileLayout();
        if (mShowHorizontalTileLayout && mHorizontalLinearLayout.getVisibility() == View.GONE) {
            mHorizontalLinearLayout.setVisibility(View.VISIBLE);
            ((View) mRegularTileLayout).setVisibility(View.GONE);
            mTileLayout.setListening(false);
            for (TileRecord record : mRecords) {
                mTileLayout.removeTile(record);
                record.tile.removeCallback(record.callback);
            }
            mTileLayout = mHorizontalTileLayout;
            if (mHost != null) setTiles(mHost.getTiles());
            mTileLayout.setListening(mListening);
            reAttachMediaHost();
            return true;
        } else if (!mShowHorizontalTileLayout
                && mHorizontalLinearLayout.getVisibility() == View.VISIBLE) {
            mHorizontalLinearLayout.setVisibility(View.GONE);
            ((View) mRegularTileLayout).setVisibility(View.VISIBLE);
            mTileLayout.setListening(false);
            for (TileRecord record : mRecords) {
                mTileLayout.removeTile(record);
                record.tile.removeCallback(record.callback);
            }
            mTileLayout = mRegularTileLayout;
            if (mHost != null) setTiles(mHost.getTiles());
            mTileLayout.setListening(mListening);
            reAttachMediaHost();
            return true;
        }
        return false;
    }

    private boolean shouldUseHorizontalTileLayout() {
        return mMediaHost.getVisible()
                && getResources().getConfiguration().orientation
                        == Configuration.ORIENTATION_LANDSCAPE;
    }

    /** Returns true if this panel currently uses a horizontal tile layout. */
    public boolean usesHorizontalLayout() {
        return mShowHorizontalTileLayout;
    }

    @Override
    public void setHost(QSTileHost host, QSCustomizer customizer) {
        super.setHost(host, customizer);
        setTiles(mHost.getTiles());
    }

    public void setMaxTiles(int maxTiles) {
        mMaxTiles = maxTiles;
        if (mHost != null) {
            setTiles(mHost.getTiles());
        }
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (QS_SHOW_BRIGHTNESS.equals(key)) {
            // No Brightness or Tooltip for you!
            super.onTuningChanged(key, "0");
        }
    }

    @Override
    public void setTiles(Collection<QSTile> tiles) {
        ArrayList<QSTile> quickTiles = new ArrayList<>();
        for (QSTile tile : tiles) {
            quickTiles.add(tile);
            if (quickTiles.size() == mMaxTiles) {
                break;
            }
        }
        super.setTiles(quickTiles, true);
    }

    private final Tunable mNumTiles = new Tunable() {
        @Override
        public void onTuningChanged(String key, String newValue) {
            setMaxTiles(parseNumTiles(newValue));
        }
    };

    public int getNumQuickTiles() {
        return mMaxTiles;
    }

    /**
     * Parses the String setting into the number of tiles. Defaults to {@code mDefaultMaxTiles}
     *
     * @param numTilesValue value of the setting to parse
     * @return parsed value of numTilesValue OR {@code mDefaultMaxTiles} on error
     */
    public static int parseNumTiles(String numTilesValue) {
        try {
            return Integer.parseInt(numTilesValue);
        } catch (NumberFormatException e) {
            // Couldn't read an int from the new setting value. Use default.
            return sDefaultMaxTiles;
        }
    }

    public static int getDefaultMaxTiles() {
        return sDefaultMaxTiles;
    }

    void setDisabledByPolicy(boolean disabled) {
        if (disabled != mDisabledByPolicy) {
            mDisabledByPolicy = disabled;
            setVisibility(disabled ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * Sets the visibility of this {@link QuickQSPanel}. This method has no effect when this panel
     * is disabled by policy through {@link #setDisabledByPolicy(boolean)}, and in this case the
     * visibility will always be {@link View#GONE}. This method is called externally by
     * {@link QSAnimator} only.
     */
    @Override
    public void setVisibility(int visibility) {
        if (mDisabledByPolicy) {
            if (getVisibility() == View.GONE) {
                return;
            }
            visibility = View.GONE;
        }
        super.setVisibility(visibility);
    }

    @Override
    protected QSEvent openPanelEvent() {
        return QSEvent.QQS_PANEL_EXPANDED;
    }

    @Override
    protected QSEvent closePanelEvent() {
        return QSEvent.QQS_PANEL_COLLAPSED;
    }

    @Override
    protected QSEvent tileVisibleEvent() {
        return QSEvent.QQS_TILE_VISIBLE;
    }

    private static class HeaderTileLayout extends TileLayout {

        private final UiEventLogger mUiEventLogger;

        private Rect mClippingBounds = new Rect();

        public HeaderTileLayout(Context context, UiEventLogger uiEventLogger) {
            super(context);
            mUiEventLogger = uiEventLogger;
            setClipChildren(false);
            setClipToPadding(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            setLayoutParams(lp);
        }

        @Override
        protected void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);
            updateResources();
        }

        @Override
        public void onFinishInflate(){
            super.onFinishInflate();
            updateResources();
        }

        private LayoutParams generateTileLayoutParams() {
            LayoutParams lp = new LayoutParams(mCellWidth, mCellHeight);
            return lp;
        }

        @Override
        protected void addTileView(TileRecord tile) {
            addView(tile.tileView, getChildCount(), generateTileLayoutParams());
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            // We only care about clipping on the right side
            mClippingBounds.set(0, 0, r - l, 10000);
            setClipBounds(mClippingBounds);

            calculateColumns();

            for (int i = 0; i < mRecords.size(); i++) {
                mRecords.get(i).tileView.setVisibility( i < mColumns ? View.VISIBLE : View.GONE);
            }

            setAccessibilityOrder();
            layoutTileRecords(mColumns);
        }

        @Override
        public boolean updateResources() {
            mCellWidth = mContext.getResources().getDimensionPixelSize(R.dimen.qs_quick_tile_size);
            mCellHeight = mCellWidth;

            return false;
        }

        private boolean calculateColumns() {
            int prevNumColumns = mColumns;
            int maxTiles = mRecords.size();

            if (maxTiles == 0){ // Early return during setup
                mColumns = 0;
                return true;
            }

            final int availableWidth = getMeasuredWidth() - getPaddingStart() - getPaddingEnd();
            final int leftoverWhitespace = availableWidth - maxTiles * mCellWidth;
            final int smallestHorizontalMarginNeeded;
            smallestHorizontalMarginNeeded = leftoverWhitespace / Math.max(1, maxTiles - 1);

            if (smallestHorizontalMarginNeeded > 0){
                mCellMarginHorizontal = smallestHorizontalMarginNeeded;
                mColumns = maxTiles;
            } else{
                mColumns = mCellWidth == 0 ? 1 :
                        Math.min(maxTiles, availableWidth / mCellWidth );
                // If we can only fit one column, use mCellMarginHorizontal to center it.
                if (mColumns == 1) {
                    mCellMarginHorizontal = (availableWidth - mCellWidth) / 2;
                } else {
                    mCellMarginHorizontal =
                            (availableWidth - mColumns * mCellWidth) / (mColumns - 1);
                }

            }
            return mColumns != prevNumColumns;
        }

        private void setAccessibilityOrder() {
            if (mRecords != null && mRecords.size() > 0) {
                View previousView = this;
                for (TileRecord record : mRecords) {
                    if (record.tileView.getVisibility() == GONE) continue;
                    previousView = record.tileView.updateAccessibilityOrder(previousView);
                }
                mRecords.get(mRecords.size() - 1).tileView.setAccessibilityTraversalBefore(
                        R.id.expand_indicator);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // Measure each QS tile.
            for (TileRecord record : mRecords) {
                if (record.tileView.getVisibility() == GONE) continue;
                record.tileView.measure(exactly(mCellWidth), exactly(mCellHeight));
            }

            int height = mCellHeight;
            if (height < 0) height = 0;

            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height);
        }

        @Override
        public int getNumVisibleTiles() {
            return mColumns;
        }

        @Override
        protected int getColumnStart(int column) {
            if (mColumns == 1) {
                // Only one column/tile. Use the margin to center the tile.
                return getPaddingStart() + mCellMarginHorizontal;
            }
            return getPaddingStart() + column *  (mCellWidth + mCellMarginHorizontal);
        }

        @Override
        public void setListening(boolean listening) {
            boolean startedListening = !mListening && listening;
            super.setListening(listening);
            if (startedListening) {
                for (int i = 0; i < getNumVisibleTiles(); i++) {
                    QSTile tile = mRecords.get(i).tile;
                    mUiEventLogger.logWithInstanceId(QSEvent.QQS_TILE_VISIBLE, 0,
                            tile.getMetricsSpec(), tile.getInstanceId());
                }
            }
        }
    }
}
